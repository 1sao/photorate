#!/usr/bin/env python3
"""Embedding parity + latency comparison for the MobileCLIP-S1 tflite variants.

Runs the same real inputs (sample images + candidate texts) through the fp32
original and every quantized variant and reports:
  - file sizes
  - embedding cosine similarity vs fp32 (image emb and text emb, per sample)
  - image/text task ranking stability (which top-1 flips vs fp32)
  - host CPU latency median (same inputs)

Usage:
  ml/litert/.venv/bin/python ml/litert/scripts/clip_s1_parity.py
"""

import time
from pathlib import Path

import numpy as np
import tensorflow as tf
from PIL import Image
from tokenizers import Tokenizer

import clip_s1_verify as v

ROOT = Path(__file__).resolve().parents[3]
VARIANTS = {
    "f32": ROOT / "ml/original_models/mobileclip2_tflite/mobileclip_s1_datacompdr_last.tflite",
    "f16": ROOT / "ml/litert/converted/clip_s1_combined_f16.tflite",
    "i8": ROOT / "ml/litert/converted/clip_s1_combined_i8.tflite",
}


def main() -> None:
    tokenizer = Tokenizer.from_file(str(v.TOKENIZER_PATH))
    candidates = list(dict.fromkeys(list(v.SAMPLES_MAP.values()) + ["Cat"]))
    text_ids = {c: v.tokenize(tokenizer, c) for c in candidates}

    # Precompute every input once, reuse across variants.
    image_inputs = {path: v.preprocess(path, False) for path in v.SAMPLES_MAP}
    blank = np.zeros((1, 3, v.INPUT_SIZE, v.INPUT_SIZE), np.float32)

    print(f"{'variant':<6} {'size MB':>8} {'task':>6}  {'cos img vs f32':>16} {'cos txt vs f32':>16} {'lat med ms':>11}")
    ref = None
    for name, path in VARIANTS.items():
        interp = tf.lite.Interpreter(model_path=str(path))
        interp.allocate_tensors()
        img_embs = {p: v.image_embed(interp, px, text_ids["Cat"]) for p, px in image_inputs.items()}
        txt_embs = {c: v.text_embed(interp, blank, text_ids[c]) for c in candidates}

        # task ranking
        passes = 0
        flips = []
        for p, desc in v.SAMPLES_MAP.items():
            ranked = sorted(candidates, key=lambda c: v.cosine_similarity(txt_embs[c], img_embs[p]), reverse=True)
            ok = ranked[0] == desc
            passes += ok
            if not ok:
                flips.append(f"{p.stem}->{ranked[0]!r}")
        # parity vs f32
        if ref is not None:
            cos_img = np.mean([v.cosine_similarity(img_embs[p], ref["img"][p]) for p in v.SAMPLES_MAP])
            cos_txt = np.mean([v.cosine_similarity(txt_embs[c], ref["txt"][c]) for c in candidates])
        else:
            cos_img = cos_txt = 1.0
        # latency
        n = 15
        px = image_inputs[list(image_inputs)[0]]
        ids = text_ids["Turtle"]
        v.run_model(interp, px, ids)
        times = []
        for _ in range(n):
            t0 = time.perf_counter()
            v.run_model(interp, px, ids)
            times.append((time.perf_counter() - t0) * 1e3)
        med = float(np.median(times))
        print(f"{name:<6} {path.stat().st_size / 1e6:>8.1f} {passes}/6{'':<3} {cos_img:>16.4f} {cos_txt:>16.4f} {med:>11.1f}")
        if flips:
            print(f"       flips: {', '.join(flips)}")
        ref = dict(img=img_embs, txt=txt_embs)


if __name__ == "__main__":
    main()
