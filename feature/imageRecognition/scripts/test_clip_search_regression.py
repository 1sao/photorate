#!/usr/bin/env python3
"""Host MobileCLIP-S1 search regression — Python counterpart of LiteRtClipSearchDatasetTest.

Kotlin counterpart (KDoc links both ways):
- app/src/androidTest/kotlin/isao/photorate/android/LiteRtClipSearchDatasetTest.kt

Runs the combined MobileCLIP-S1 tflite (vision + text in one graph) on host
via ai-edge-litert and verifies:
1. Every sample image is the TOP-1 match for its own description
   (the app's search behavior when a user types a query).
2. The scaled-down decode (640 + JPEG q95, mirroring the app's decode path)
   embeds almost identically to a full-resolution decode
   (cosine similarity > 0.99).

Preprocessing is /255-only (no ImageNet mean/std), 256x256 center crop —
the recipe that measured 5/6 on the S1 graph (ml/litert/converted/CLIP_S1_README.md).

Usage:
    python3 test_clip_search_regression.py
    python3 test_clip_search_regression.py --model clip_s1_combined_i8.tflite
"""
from __future__ import annotations

import argparse
import io
import sys
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPTS_DIR.parents[2]

import numpy as np
from PIL import Image
from tokenizers import Tokenizer

MODEL_PATH = PROJECT_ROOT / "ml" / "litert" / "converted" / "clip_s1_combined_f16.tflite"
TOKENIZER_PATH = PROJECT_ROOT / "ml" / "original_models" / "tokenizer.json"
SEARCH_SAMPLES_DIR = PROJECT_ROOT / "plans" / "samples" / "search"

# Search image name -> its description (mirrors LiteRtClipSearchDatasetTest.samples)
SAMPLES = {
    "turtle": "Turtle",
    "cat_and_lemons": "cat and lemons",
}

INPUT_SIZE = 256
CONTEXT_LENGTH = 77
SCALED_EQUIVALENCE_THRESHOLD = 0.99
DECODE_MIN_DIM = 640
JPEG_QUALITY = 95


def cosine_similarity(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def preprocess_image(path_or_pil):
    """App-identical preprocessing: resize shortest edge -> center crop -> /255.

    Mirrors LiteRtAppClipSearch.preprocess and the Kotlin test's decode path.
    """
    if isinstance(path_or_pil, (str, Path)):
        img = Image.open(path_or_pil).convert("RGB")
    else:
        img = path_or_pil.convert("RGB")
    w, h = img.size
    scale = INPUT_SIZE / min(w, h)
    new_w, new_h = round(w * scale), round(h * scale)
    img = img.resize((new_w, new_h), Image.BILINEAR)
    left = (new_w - INPUT_SIZE) // 2
    top = (new_h - INPUT_SIZE) // 2
    img = img.crop((left, top, left + INPUT_SIZE, top + INPUT_SIZE))
    arr = np.asarray(img, dtype=np.float32) / 255.0
    return np.transpose(arr, (2, 0, 1))[np.newaxis, ...]


def tokenize(tokenizer, text):
    """CLIP tokenizer with padding to context length (mirrors ClipTokenizer.kt)."""
    ids = tokenizer.encode(text).ids
    if len(ids) > CONTEXT_LENGTH:
        ids = ids[: CONTEXT_LENGTH - 1] + [ids[-1]]
    ids = ids + [0] * (CONTEXT_LENGTH - len(ids))
    return np.array([ids], dtype=np.int64)


def run_model(interp, pixels, input_ids):
    in_idx = [i["index"] for i in interp.get_input_details()]
    out_idx = [o["index"] for o in interp.get_output_details()]
    interp.set_tensor(in_idx[0], pixels)
    interp.set_tensor(in_idx[1], input_ids)
    interp.invoke()
    return [interp.get_tensor(i) for i in out_idx]


def image_embed(interp, pixels):
    blank_ids = np.zeros((1, CONTEXT_LENGTH), dtype=np.int64)
    return run_model(interp, pixels, blank_ids)[1][0]  # out 1 = image embedding


def text_embed(interp, tokenizer, text):
    blank_pixels = np.zeros((1, 3, INPUT_SIZE, INPUT_SIZE), dtype=np.float32)
    return run_model(interp, blank_pixels, tokenize(tokenizer, text))[0][
        0]  # out 0 = text embedding


def decode_scaled_jpeg(image_path):
    """Mirror the app's scaled decode: sample down to min-dim 640, JPEG q95 re-encode.

    Mirrors LiteRtClipSearchDatasetTest.decodeScaledJpeg.
    """
    img = Image.open(image_path).convert("RGB")
    w, h = img.size
    sample_size = max(min(w, h) // DECODE_MIN_DIM, 1)
    if sample_size > 1:
        img = img.resize((w // sample_size, h // sample_size), Image.BILINEAR)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=JPEG_QUALITY)
    return Image.open(io.BytesIO(buf.getvalue()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=MODEL_PATH)
    args = parser.parse_args()

    import ai_edge_litert.interpreter as litert

    print(f"Model: {args.model}")
    interp = litert.Interpreter(model_path=str(args.model), num_threads=4)
    interp.allocate_tensors()

    tokenizer = Tokenizer.from_file(str(TOKENIZER_PATH))

    all_pass = True

    # --- Test 1: top-1 description ranking (mirrors searchFindsImagesByTheirDescription) ---
    print("\n=== Test 1: top-1 description ranking ===")
    image_embeddings = {}
    for name in SAMPLES:
        img_path = SEARCH_SAMPLES_DIR / f"{name}.jpg"
        if not img_path.exists():
            print(f"  MISSING sample: {img_path}")
            all_pass = False
            continue
        pixels = preprocess_image(img_path)
        image_embeddings[name] = image_embed(interp, pixels)

    for name, description in SAMPLES.items():
        if name not in image_embeddings:
            continue
        query = text_embed(interp, tokenizer, description)
        ranked = sorted(
            image_embeddings.keys(),
            key=lambda n: cosine_similarity(query, image_embeddings[n]),
            reverse=True,
        )
        sims = ", ".join(
            f"{n}={cosine_similarity(query, image_embeddings[n]):.4f}" for n in ranked
        )
        ok = ranked[0] == name
        all_pass = all_pass and ok
        mark = "✓" if ok else "✗"
        print(f"  {mark} query={description!r} ranked={ranked}  [{sims}]")

    # --- Test 2: scaled decode equivalence (mirrors scaledDownDecodeMatchesFullDecode) ---
    print("\n=== Test 2: scaled decode equivalence ===")
    for name in SAMPLES:
        img_path = SEARCH_SAMPLES_DIR / f"{name}.jpg"
        if not img_path.exists():
            continue
        full_pixels = preprocess_image(img_path)
        scaled_pixels = preprocess_image(decode_scaled_jpeg(img_path))
        full_emb = image_embed(interp, full_pixels)
        scaled_emb = image_embed(interp, scaled_pixels)
        sim = cosine_similarity(full_emb, scaled_emb)
        ok = sim > SCALED_EQUIVALENCE_THRESHOLD
        all_pass = all_pass and ok
        mark = "✓" if ok else "✗"
        print(f"  {mark} {name}: full-vs-scaled similarity={sim:.4f}")

    print()
    if all_pass:
        print("PASS: all CLIP search regression checks passed.")
        return 0
    print("FAIL: at least one CLIP search regression check failed.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
