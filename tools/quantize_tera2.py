#!/usr/bin/env python3
"""
quantize_tera2.py — 8-bit TeraTTS for the phone, with graph surgery.

For each model: unroll the sampler's Loop, rewrite 1×1 Conv1d as MatMul
(tera_graph_tools.py), quantize MatMul to int8, then check the result against
the original on real inputs, measure speed and write q2_fp32.wav / q2_int8.wav.

Run (from ~/teratts-books, next to tera_graph_tools.py):  .venv/bin/python quantize_tera2.py
Output: tera_int8/ (models for the phone)
"""
import shutil
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

import ref_infer as r
import tera_graph_tools as tg

ort.set_default_logger_severity(3)
HERE = Path(__file__).resolve().parent
PREP = HERE / "tera_prep"
OUT = HERE / "tera_int8"
MODELS = ["text_encoder.onnx", "duration_predictor.onnx", "sampler_distilled_cfg3_8step.onnx", "vocoder.onnx"]
TEXT = "<ru>Старинный замок стоял на высокой горе, и ветер гулял по его пустым коридорам.</ru>"


def session(path, threads=4):
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def bench(path, feeds, runs=5):
    s = session(path)
    s.run(None, feeds)
    t = time.time()
    for _ in range(runs):
        s.run(None, feeds)
    return (time.time() - t) / runs


def main():
    root = r.release_dir()
    src = root / "models"
    PREP.mkdir(exist_ok=True)
    OUT.mkdir(exist_ok=True)

    print("1. Развёртка цикла, свёртки 1×1 → MatMul, квантизация:")
    for m in MODELS:
        t = time.time()
        if m in (MODELS[0], MODELS[1], MODELS[3]):   # keep full precision (vocoder: int8 gave a metallic hum)
            shutil.copy(src / m, PREP / m); shutil.copy(src / m, OUT / m)
            print(f"   {m:<38} оставлена 32-битной")
            continue
        st = tg.prepare(str(src / m), str(PREP / m))
        tg.quantize(str(PREP / m), str(OUT / m))
        print(f"   {m:<38} циклов {st['loops']}, свёрток→MatMul {st['conv1x1']:3d}, MatMul {st['matmul']:4d}"
              f"   {(src / m).stat().st_size / 1e6:4.0f} МБ → {(OUT / m).stat().st_size / 1e6:4.0f} МБ"
              f"   ({time.time() - t:.0f} с)")
    shutil.copy(root / "unicode_indexer.json", OUT / "unicode_indexer.json")

    core = r.Core(root)
    mt = r.frontend(TEXT, core.ix, r.DictStress(root))
    ttl, dp = core.style("ru_f1")
    ids = core.ix.ids(mt)
    dids = core.ix.ids(mt.replace("+", ""))
    mask = np.ones((1, 1, ids.shape[1]), np.float32)
    dmask = np.ones((1, 1, dids.shape[1]), np.float32)
    emb = core.enc.run(None, {"text_ids": ids, "style_ttl": ttl, "text_mask": mask})[0]
    n = 90
    g = np.random.default_rng(0)
    feeds = {
        MODELS[0]: {"text_ids": ids, "style_ttl": ttl, "text_mask": mask},
        MODELS[1]: {"text_ids": dids, "style_dp": dp, "text_mask": dmask},
        MODELS[2]: {"initial_latent": g.standard_normal((1, 144, n)).astype(np.float32), "text_emb": emb,
                    "style_ttl": ttl, "latent_mask": np.ones((1, 1, n), np.float32), "text_mask": mask,
                    "guidance": np.asarray([3.0], np.float32)},
        MODELS[3]: {"latent": g.standard_normal((1, 144, n)).astype(np.float32)},
    }

    print("\n2. Точность (развёрнутая fp32 и int8 против исходной, относительная ошибка):")
    for m in MODELS:
        a = session(src / m).run(None, feeds[m])[0]
        b = session(PREP / m).run(None, feeds[m])[0]
        c = session(OUT / m).run(None, feeds[m])[0]
        scale = float(np.abs(a).max()) or 1.0
        print(f"   {m:<38} развёрнутая {np.abs(a - b).max() / scale:.1e}   int8 {np.abs(a - c).max() / scale:.3f}")

    print("\n3. Скорость на Маке (4 потока; на телефоне с i8mm выигрыш int8 должен быть больше):")
    t32 = tp = t8 = 0.0
    for m in MODELS:
        a, b, c = bench(src / m, feeds[m]), bench(PREP / m, feeds[m]), bench(OUT / m, feeds[m])
        t32, tp, t8 = t32 + a, tp + b, t8 + c
        print(f"   {m:<38} {a * 1000:6.0f} мс   развёрнутая {b * 1000:6.0f} мс   int8 {c * 1000:6.0f} мс")
    print(f"   {'всего':<38} {t32 * 1000:6.0f} мс   развёрнутая {tp * 1000:6.0f} мс   int8 {t8 * 1000:6.0f} мс")

    print("\n4. Звук:")
    for tag, folder in [("fp32", src), ("int8", OUT)]:
        c = r.Core(root)
        if tag == "int8":
            c.enc, c.dp, c.sampler, c.voc = (session(folder / m) for m in MODELS)
        rg = np.random.default_rng(1)
        w = c.synth(mt, "ru_f1", 1.0, lambda s: rg.standard_normal(s).astype(np.float32))
        r.write_wav(HERE / f"q2_{tag}.wav", w)
        print(f"   q2_{tag}.wav")
    print("\nПослушайте: afplay q2_fp32.wav; afplay q2_int8.wav")


if __name__ == "__main__":
    main()
