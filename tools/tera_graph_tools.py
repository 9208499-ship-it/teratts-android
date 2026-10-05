#!/usr/bin/env python3
"""
tera_graph_tools.py — graph surgery that makes TeraTTS models quantizable.

* unroll_loop(model): the distilled sampler is a single `Loop` node (8 Euler steps)
  with the whole network inside its body; quantizers don't look into subgraphs.
  With a constant trip count the loop is written out step by step; weights stay
  shared (moved to the main graph once).
* conv1x1_to_matmul(model): 1-D convolutions with kernel 1 and group 1 are the
  bulk of the compute, but ONNX Runtime has no 8-bit kernel for 1-D convolutions.
  They are rewritten as  Transpose → MatMul(W^T) → Add(bias) → Transpose,
  which quantizes to an int8 MatMul (sped up by i8mm/dotprod on ARM).
* quantize(path_in, path_out): both of the above + dynamic int8 for MatMul.
"""
from __future__ import annotations

import copy

import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto


# --------------------------------------------------------------------------- loop

def _const_value(graph: onnx.GraphProto, name: str):
    for init in graph.initializer:
        if init.name == name:
            return numpy_helper.to_array(init)
    for n in graph.node:
        if n.op_type == "Constant" and n.output and n.output[0] == name:
            for a in n.attribute:
                if a.name == "value":
                    return numpy_helper.to_array(a.t)
    return None


def unroll_loop(model: onnx.ModelProto) -> onnx.ModelProto:
    """Unroll every top-level Loop with a constant trip count and no scan outputs."""
    model = copy.deepcopy(model)
    g = model.graph
    new_nodes = []
    for node in g.node:
        if node.op_type != "Loop":
            new_nodes.append(node)
            continue
        body = next(a.g for a in node.attribute if a.name == "body")
        trip = _const_value(g, node.input[0]) if node.input[0] else None
        if trip is None:
            raise RuntimeError("Loop trip count is not a constant — cannot unroll")
        trip = int(np.asarray(trip).reshape(-1)[0])
        carried_in = list(node.input[2:])              # initial values of loop-carried vars
        n_carried = len(carried_in)
        scan_outputs = len(body.output) - 1 - n_carried
        if scan_outputs:
            raise RuntimeError("Loop has scan outputs — not supported")

        # body inputs: iter_num, cond_in, carried...
        iter_name, cond_name = body.input[0].name, body.input[1].name
        carried_names = [i.name for i in body.input[2:]]
        out_names = [o.name for o in body.output[1:1 + n_carried]]   # output[0] is cond_out

        # body initializers → main graph once (shared by all steps)
        body_inits = {i.name for i in body.initializer}
        for init in body.initializer:
            moved = copy.deepcopy(init)
            moved.name = f"loopw::{init.name}"
            g.initializer.append(moved)

        # large Constant nodes inside the body are weights too: share them as well
        shared_consts = {}
        for bnode in body.node:
            if bnode.op_type == "Constant" and bnode.output:
                val = next((a.t for a in bnode.attribute if a.name == "value"), None)
                if val is not None and int(np.prod(val.dims) if len(val.dims) else 1) > 1024:
                    t = copy.deepcopy(val)
                    t.name = f"loopw::const::{bnode.output[0]}"
                    g.initializer.append(t)
                    shared_consts[bnode.output[0]] = t.name

        current = carried_in
        for step in range(trip):
            sfx = f"__s{step}"
            rename = {}
            it = helper.make_tensor(f"loop_iter{sfx}", TensorProto.INT64, [], [step])
            new_nodes.append(helper.make_node("Constant", [], [f"loop_iter{sfx}"], value=it))
            rename[iter_name] = f"loop_iter{sfx}"
            ct = helper.make_tensor(f"loop_cond{sfx}", TensorProto.BOOL, [], [True])
            new_nodes.append(helper.make_node("Constant", [], [f"loop_cond{sfx}"], value=ct))
            rename[cond_name] = f"loop_cond{sfx}"
            for bn, cur in zip(carried_names, current):
                rename[bn] = cur
            for name in body_inits:
                rename[name] = f"loopw::{name}"
            rename.update(shared_consts)
            local_outputs = set()
            for bnode in body.node:
                local_outputs.update(o for o in bnode.output if o and o not in shared_consts)
            for idx, bnode in enumerate(body.node):
                if bnode.op_type == "Constant" and bnode.output and bnode.output[0] in shared_consts:
                    continue
                nn = copy.deepcopy(bnode)
                nn.name = f"{bnode.name or bnode.op_type}#{idx}{sfx}"
                for k, inp in enumerate(nn.input):
                    if inp in rename:
                        nn.input[k] = rename[inp]
                    elif inp in local_outputs:
                        nn.input[k] = inp + sfx
                    # else: outer-scope value — keep the name
                for k, out in enumerate(nn.output):
                    if out:
                        nn.output[k] = out + sfx
                new_nodes.append(nn)
            nxt = []
            for o in out_names:
                if o in rename:
                    nxt.append(rename[o])
                elif o in local_outputs:
                    nxt.append(o + sfx)
                else:
                    nxt.append(o)
            current = nxt
        # wire final values to the Loop's outputs
        for final, loop_out in zip(current, node.output[:n_carried]):
            new_nodes.append(helper.make_node("Identity", [final], [loop_out], name=f"{loop_out}__unrolled"))
    del g.node[:]
    g.node.extend(new_nodes)
    return model


# ---------------------------------------------------------------------- conv 1x1

def conv1x1_to_matmul(model: onnx.ModelProto) -> tuple[onnx.ModelProto, int]:
    """Rewrite 1-D Conv(kernel 1, group 1, stride 1, no pad) on [B, C, T] as MatMul."""
    model = copy.deepcopy(model)
    g = model.graph
    inits = {i.name: i for i in g.initializer}
    new_nodes, changed = [], 0
    for node in g.node:
        if node.op_type != "Conv" or node.input[1] not in inits:
            new_nodes.append(node)
            continue
        w = numpy_helper.to_array(inits[node.input[1]])
        attrs = {a.name: helper.get_attribute_value(a) for a in node.attribute}
        group = attrs.get("group", 1)
        pads = list(attrs.get("pads", [0, 0]))
        strides = list(attrs.get("strides", [1]))
        dil = list(attrs.get("dilations", [1]))
        if w.ndim != 3 or w.shape[2] != 1 or group != 1 or any(pads) or strides != [1] or dil != [1]:
            new_nodes.append(node)
            continue
        base = f"{node.name or node.output[0]}#c{changed}"
        wt_name = f"{node.input[1]}__mm_T"
        if wt_name not in inits:
            wt = numpy_helper.from_array(np.ascontiguousarray(w[:, :, 0].T), wt_name)  # [Cin, Cout]
            g.initializer.append(wt)
            inits[wt_name] = wt
        x, y = node.input[0], node.output[0]
        new_nodes.append(helper.make_node("Transpose", [x], [f"{base}__xT"], perm=[0, 2, 1], name=f"{base}__tin"))
        mm_out = f"{base}__mm"
        new_nodes.append(helper.make_node("MatMul", [f"{base}__xT", wt_name], [mm_out], name=f"{base}__matmul"))
        if len(node.input) > 2 and node.input[2]:
            new_nodes.append(helper.make_node("Add", [mm_out, node.input[2]], [f"{base}__b"], name=f"{base}__bias"))
            mm_out = f"{base}__b"
        new_nodes.append(helper.make_node("Transpose", [mm_out], [y], perm=[0, 2, 1], name=f"{base}__tout"))
        changed += 1
    del g.node[:]
    g.node.extend(new_nodes)
    return model, changed


def gemm_to_matmul(model: onnx.ModelProto) -> int:
    """Gemm -> (Transpose) MatMul (Mul alpha) (Add beta*C), done correctly before quantization."""
    g = model.graph
    inits = {i.name: i for i in g.initializer}
    new_nodes, changed = [], 0
    for node in g.node:
        if node.op_type != "Gemm":
            new_nodes.append(node)
            continue
        attrs = {a.name: helper.get_attribute_value(a) for a in node.attribute}
        alpha, beta = float(attrs.get("alpha", 1.0)), float(attrs.get("beta", 1.0))
        ta, tb = int(attrs.get("transA", 0)), int(attrs.get("transB", 0))
        base = f"{node.name or node.output[0]}#g{changed}"
        a_name, b_name = node.input[0], node.input[1]
        if ta:
            new_nodes.append(helper.make_node("Transpose", [a_name], [f"{base}__aT"], perm=[1, 0], name=f"{base}__ta"))
            a_name = f"{base}__aT"
        if tb:
            if b_name in inits:
                w = numpy_helper.to_array(inits[b_name]).T
                b_name = f"{b_name}__T"
                if b_name not in inits:
                    t = numpy_helper.from_array(np.ascontiguousarray(w), b_name)
                    g.initializer.append(t)
                    inits[b_name] = t
            else:
                new_nodes.append(helper.make_node("Transpose", [b_name], [f"{base}__bT"], perm=[1, 0], name=f"{base}__tb"))
                b_name = f"{base}__bT"
        out = f"{base}__mm"
        new_nodes.append(helper.make_node("MatMul", [a_name, b_name], [out], name=f"{base}__matmul"))
        if alpha != 1.0:
            g.initializer.append(numpy_helper.from_array(np.array(alpha, np.float32), f"{base}__alpha"))
            new_nodes.append(helper.make_node("Mul", [out, f"{base}__alpha"], [f"{base}__scaled"], name=f"{base}__mul"))
            out = f"{base}__scaled"
        if len(node.input) > 2 and node.input[2]:
            c_name = node.input[2]
            if beta != 1.0:
                g.initializer.append(numpy_helper.from_array(np.array(beta, np.float32), f"{base}__beta"))
                new_nodes.append(helper.make_node("Mul", [c_name, f"{base}__beta"], [f"{base}__c"], name=f"{base}__mulc"))
                c_name = f"{base}__c"
            new_nodes.append(helper.make_node("Add", [out, c_name], [node.output[0]], name=f"{base}__add"))
        else:
            new_nodes.append(helper.make_node("Identity", [out], [node.output[0]], name=f"{base}__id"))
        changed += 1
    del g.node[:]
    g.node.extend(new_nodes)
    return changed


def drop_unused_initializers(model: onnx.ModelProto) -> int:
    used = {i for n in model.graph.node for i in n.input} | {o.name for o in model.graph.output}
    keep = [i for i in model.graph.initializer if i.name in used]
    dropped = len(model.graph.initializer) - len(keep)
    del model.graph.initializer[:]
    model.graph.initializer.extend(keep)
    return dropped


# ------------------------------------------------------------------- top level

def prepare(path_in: str, path_out: str) -> dict:
    """Unroll loops + Conv1x1→MatMul; returns stats. The result is still fp32."""
    m = onnx.load(path_in)
    stats = {"loops": sum(1 for n in m.graph.node if n.op_type == "Loop")}
    if stats["loops"]:
        m = unroll_loop(m)
    m, stats["conv1x1"] = conv1x1_to_matmul(m)
    stats["gemm"] = gemm_to_matmul(m)
    stats["matmul"] = sum(1 for n in m.graph.node if n.op_type == "MatMul")
    drop_unused_initializers(m)
    try:
        m = onnx.shape_inference.infer_shapes(m)   # types for the new nodes (quantizer needs them)
    except Exception:
        pass
    onnx.save(m, path_out, save_as_external_data=False)
    return stats


def quantize(path_prepared: str, path_out: str):
    """int8 MatMul; nodes ONNX Runtime can't load in int8 are excluded automatically."""
    import re
    import onnxruntime as ort
    from onnxruntime.quantization import QuantType, quantize_dynamic
    g = onnx.load(path_prepared, load_external_data=False).graph
    # tiny parts with odd shapes: the quantizer mishandles Gemm and the step-time encoder
    # the quantizer replaces each Gemm by a new node "<name>_MatMul" before quantizing,
    # so both names must be excluded
    base = [n.name for n in g.node if n.op_type == "Gemm" or "time_encoder" in n.name]
    exclude = base + [x + "_MatMul" for x in base]
    for _ in range(40):
        quantize_dynamic(path_prepared, path_out, weight_type=QuantType.QInt8,
                         op_types_to_quantize=["MatMul"], nodes_to_exclude=exclude,
                         extra_options={"MatMulConstBOnly": True,
                                        "DefaultTensorType": onnx.TensorProto.FLOAT})
        try:
            ort.InferenceSession(path_out, providers=["CPUExecutionProvider"])
            return exclude
        except Exception as e:
            m = re.search(r"Node \((.+?)_MatMul_quant\)", str(e))
            if not m:
                raise
            exclude += [m.group(1), m.group(1) + "_MatMul"]
            print(f"      исключён из int8: {m.group(1)}")
    raise RuntimeError("слишком много исключений")
