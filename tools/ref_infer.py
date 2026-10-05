#!/usr/bin/env python3
"""
ref_infer.py — эталон конвейера TeraTTS для переноса на Android.

Делает ровно то, что потом будут делать Kotlin (подготовка текста, словарь
ударений) и Rust (четыре ONNX-модели), но без кода TeraTTS — только numpy,
onnxruntime и словари из папки модели. И сверяет себя с оригиналом:

  A. ядро синтеза: при одинаковом шуме звук должен совпасть с оригиналом;
  B. подготовка текста: итоговая строка для энкодера — совпасть символ в символ;
  C. словарь ударений: совпасть с RUAccent в режиме dictionary;
  D. «как на телефоне»: свой генератор шума — файлы для прослушивания.

Запуск:  .venv/bin/python ref_infer.py
"""
from __future__ import annotations

import gzip
import importlib
import json
import math
import re
import sys
import time
import types
import unicodedata
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

HERE = Path(__file__).resolve().parent
MODEL_ID = "TeraSpace/TeraTTSv2"

# ---- константы из teratts.py ------------------------------------------------
SAMPLE_RATE = 44_100
SAMPLES_PER_FRAME = 3_072          # SAMPLES_PER_COMPRESSED_FRAME
LATENT_CHANNELS = 144
SPEED = 1.05
GUIDANCE = 3.0
SEED = 1234


def release_dir() -> Path:
    from huggingface_hub import snapshot_download
    rev = (HERE / ".model_revision").read_text().strip()
    return Path(snapshot_download(MODEL_ID, revision=rev, local_files_only=True))


# =============================================================================
# 1. ПОДГОТОВКА ТЕКСТА (→ Kotlin)
# =============================================================================

class Indexer:
    def __init__(self, path: Path):
        self.table: list[int] = json.loads(path.read_text())
        assert len(self.table) == 65_536

    def known(self, ch: str) -> bool:
        dec = unicodedata.normalize("NFKD", ch)
        return bool(dec) and all(ord(c) < 65_536 and self.table[ord(c)] >= 0 for c in dec)

    def ids(self, text: str) -> np.ndarray:
        out = []
        for c in text:
            t = self.table[ord(c)] if ord(c) < 65_536 else -1
            if t < 0:
                raise ValueError(f"символ вне словаря: {c!r} U+{ord(c):04X}")
            out.append(t)
        return np.asarray(out, dtype=np.int64)[None, :]


PUNCT_NEEDS_SPACE = re.compile(r"[,.!?;:…](?=[^\s<])")
NUMBER_NEEDS_SPACE = re.compile(r"(?<=\d)(?=[A-Za-zА-Яа-яЁё])")
TAGGED_NUMBER = re.compile(r"(?<![\w.])[-−]?\d+(?:[.,]\d+)?(?![\w.])")
LANG_SPAN = re.compile(r"<(ru|en)>(.*?)</\1>", re.S)
RU_SPAN = re.compile(r"<ru>(.*?)</ru>", re.S)


def add_punct_spaces(text: str) -> str:
    def rep(m: re.Match) -> str:
        p, i = m.group(0), m.start()
        prev = text[i - 1] if i else ""
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if p in ".," and prev.isdigit() and nxt.isdigit():
            return p
        return p + " "
    return PUNCT_NEEDS_SPACE.sub(rep, text)


def skip_unknown(text: str, ix: Indexer, keep_digits: bool = False) -> str:
    return "".join(c for c in text if ix.known(c) or (keep_digits and c.isdigit()))


def expand_numbers(text: str) -> str:
    """Числа словами внутри <ru>/<en>. На Android — свой конвертер (см. отчёт B)."""
    from num2words import num2words

    def span(m: re.Match) -> str:
        lang, content = m.groups()

        def num(n: re.Match) -> str:
            lit = n.group(0).replace("−", "-")
            val = float(lit.replace(",", ".")) if ("." in lit or "," in lit) else int(lit)
            return str(num2words(val, lang=lang))
        return f"<{lang}>{TAGGED_NUMBER.sub(num, content)}</{lang}>"
    return LANG_SPAN.sub(span, text)


def frontend(tagged: str, ix: Indexer, stress) -> str:
    """Строка ровно в том виде, в каком её получит текстовый энкодер (с '+')."""
    t = unicodedata.normalize("NFC", tagged)
    t = add_punct_spaces(t)
    t = NUMBER_NEEDS_SPACE.sub(" ", t)
    t = skip_unknown(t, ix, keep_digits=True)
    t = expand_numbers(t)
    t = skip_unknown(t, ix)
    if stress is not None:
        def ru(m: re.Match) -> str:
            c = m.group(1)
            return m.group(0) if "+" in c else f"<ru>{stress(c)}</ru>"
        t = RU_SPAN.sub(ru, t)
    return unicodedata.normalize("NFKD", t)


# =============================================================================
# 2. СЛОВАРЬ УДАРЕНИЙ (→ Kotlin; повторяет RUAccent mode="dictionary")
# =============================================================================

class DictStress:
    CLEAN = re.compile(r"[^a-zA-Z0-9\sа-яА-ЯёЁ—.,!?:;'(){}\[\]«»„“”\-]")
    WORD = re.compile(r"[A-Za-zА-Яа-яЁё]+")

    def __init__(self, root: Path):
        d = root / "ruaccent" / "dictionary"
        self.accents: dict[str, str] = json.load(gzip.open(d / "accents.json.gz"))
        self.yo: dict[str, str] = json.load(gzip.open(d / "yo_words.json.gz"))
        self.accents.update({"о": "+о", "О": "+О"})

    @staticmethod
    def fix_case(src: str, dst: str) -> str:
        if len(src) != len(dst):
            return dst
        return "".join(b.upper() if a.isupper() else b.lower() for a, b in zip(src, dst))

    def word(self, m: re.Match) -> str:
        w = m.group(0)
        w = self.fix_case(w, self.yo.get(w.lower(), w))
        acc = self.accents.get(w.lower(), w.lower())
        if acc == w.lower():
            return w
        out, ins = list(w), 0
        for mk in re.finditer(r"\+", acc):
            out.insert(mk.start() + ins, "+")
            ins += 1
        return "".join(out)

    def __call__(self, text: str) -> str:
        return self.WORD.sub(self.word, self.CLEAN.sub("", text))


# =============================================================================
# 3. ЯДРО СИНТЕЗА (→ Rust)
# =============================================================================

class Core:
    def __init__(self, root: Path, threads: int = 4):
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        m = root / "models"
        mk = lambda n: ort.InferenceSession(str(m / n), so, providers=["CPUExecutionProvider"])
        self.enc = mk("text_encoder.onnx")
        self.dp = mk("duration_predictor.onnx")
        self.sampler = mk("sampler_distilled_cfg3_8step.onnx")
        self.voc = mk("vocoder.onnx")
        self.ix = Indexer(root / "unicode_indexer.json")
        self.root = root

    def style(self, voice: str):
        d = self.root / "styles" / voice
        return (np.load(d / "style_ttl.npy").astype(np.float32),
                np.load(d / "style_dp.npy").astype(np.float32))

    def synth(self, model_text: str, voice: str, scale: float, noise_fn) -> np.ndarray:
        ttl, dp = self.style(voice)
        ids = self.ix.ids(model_text)                       # с плюсами
        dids = self.ix.ids(model_text.replace("+", ""))     # без плюсов
        mask = np.ones((1, 1, ids.shape[1]), np.float32)
        dmask = np.ones((1, 1, dids.shape[1]), np.float32)
        emb = self.enc.run(None, {"text_ids": ids, "style_ttl": ttl, "text_mask": mask})[0]
        raw = self.dp.run(None, {"text_ids": dids, "style_dp": dp, "text_mask": dmask})[0]
        dur = float(raw[0]) * scale / SPEED
        n = max(1, math.ceil(dur * SAMPLE_RATE / SAMPLES_PER_FRAME))
        latent = self.sampler.run(None, {
            "initial_latent": noise_fn((1, LATENT_CHANNELS, n)),
            "text_emb": emb, "style_ttl": ttl,
            "latent_mask": np.ones((1, 1, n), np.float32),
            "text_mask": mask,
            "guidance": np.asarray([GUIDANCE], np.float32),
        })[0]
        wav = self.voc.run(None, {"latent": latent})[0]
        return wav[0, :round(dur * SAMPLE_RATE)]


def write_wav(path: Path, w: np.ndarray):
    pcm = np.rint(np.clip(w, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(SAMPLE_RATE)
        f.writeframes(pcm.tobytes())


# =============================================================================
# Сверка с оригиналом
# =============================================================================

def load_original(root: Path):
    sys.path.insert(0, str(root))
    pkg = types.ModuleType("teratts_hub"); pkg.__path__ = [str(root)]
    sys.modules["teratts_hub"] = pkg
    tt = importlib.import_module("teratts_hub.teratts")
    ra = importlib.import_module("teratts_hub.teratts_ruaccent")
    import dataclasses
    loaded = tt.load_model(root, model="distilled", threads=4, russian_stress=False)
    # оригинальный конвейер + оригинальный RUAccent в режиме «только словарь»
    return tt, dataclasses.replace(loaded, accentizer=ra.RUAccent(root / "ruaccent", mode="dictionary"))


TEXTS = [
    "<ru>Старинный з+амок на горе. Ржавый зам+ок на двери.</ru>",
    "<ru>В 1812 году, 3,5 километра. Цена:100 рублей!Он ушёл…</ru>",
    "<ru>Он открыл</ru> <en>iPhone 15</en> <ru>и прочитал 21 сообщение.</ru>",
    "<ru>Йод, ёлка и чайка. Ещё раз: «привет» — сказал он (тихо).</ru>",
    "<ru>Мороз и солнце; день чудесный! Ещё ты дремлешь, друг прелестный?</ru>",
]


def main():
    root = release_dir()
    print(f"📁 Модель: {root.name}")
    core = Core(root)
    stress = DictStress(root)
    tt, orig = load_original(root)
    ok = True

    print("\nB/C. Подготовка текста и словарь ударений")
    for t in TEXTS:
        ours = frontend(t, core.ix, stress)
        theirs = tt.normalize_text(orig, t)
        same = ours == theirs
        ok &= same
        print(f"  {'✅' if same else '❌'} {ours}")
        if not same:
            print(f"     оригинал: {theirs}")

    print("\nA. Ядро синтеза (одинаковый шум → одинаковый звук)")
    for t in TEXTS[:3]:
        mt = frontend(t, core.ix, stress)
        rng = np.random.default_rng(SEED)
        ours = core.synth(mt, "ru_f1", 1.0, lambda s: rng.standard_normal(s).astype(np.float32))
        theirs = tt.generate_speech(orig, t, "ru_f1", seed=SEED)
        n = min(len(ours), len(theirs))
        diff = float(np.max(np.abs(ours[:n] - theirs[:n]))) if n else 1.0
        same = len(ours) == len(theirs) and diff < 1e-3
        ok &= same
        print(f"  {'✅' if same else '❌'} длина {len(ours)} / {len(theirs)}, макс. разница {diff:.2e}")

    print("\nD. «Как на телефоне»: свой генератор шума")
    rng = np.random.default_rng()
    for i, t in enumerate(TEXTS, 1):
        mt = frontend(t, core.ix, stress)
        t0 = time.time()
        w = core.synth(mt, "ru_f1", 1.0, lambda s: rng.standard_normal(s).astype(np.float32))
        dt = time.time() - t0
        out = HERE / f"ref_{i}.wav"
        write_wav(out, w)
        print(f"  🔊 {out.name}: {len(w) / SAMPLE_RATE:.1f} с звука за {dt:.2f} с")

    print("\n" + ("✅ Всё совпало — спецификация верна." if ok else
                  "❌ Есть расхождения — пришлите этот вывод целиком."))


if __name__ == "__main__":
    main()
