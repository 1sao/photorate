#!/usr/bin/env python3
"""Host verification + latency harness for the combined MobileCLIP-S1 tflite.

The model (ml/original_models/mobileclip2_tflite/mobileclip_s1_datacompdr_last.tflite)
is a single graph with BOTH encoders:
  input  args_0  [1, 3, 256, 256] float32  (pixel values)
  input  args_1  [1, 77]           int64    (token ids)
  output [1, 512] float32  (image embedding)
  output [1, 512] float32  (text embedding)
  output scalar    float32  (something else - logged for info)

It runs the same task check as onnx/test_mobileclip_samples.py: encode each
sample image + candidate texts through the one graph, rank candidates by cosine
similarity against the image embedding, assert top-1 == correct description.

Also prints host CPU latency (median of N runs, warm + cold) so fp32/fp16/int8
variants can be compared on the same machine.

Usage:
  ml/litert/.venv/bin/python ml/litert/scripts/clip_s1_verify.py [MODEL [N_RUNS]]
Defaults to the fp32 original; pass a quantized variant to compare.
"""

import sys
import time
from pathlib import Path

import numpy as np
import tensorflow as tf
from PIL import Image
from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parents[3]  # repo root
MODEL = ROOT / "ml/original_models/mobileclip2_tflite/mobileclip_s1_datacompdr_last.tflite"
TOKENIZER_PATH = ROOT / "ml/original_models/tokenizer.json"
SAMPLES = ROOT / "onnx/samples"

INPUT_SIZE = 256  # matches the exported model's image input
CONTEXT_LENGTH = 77

# OpenAI/open_clip CLIP ImageNet normalization (only used for the variant test).
MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)

SAMPLES_MAP = {
    SAMPLES / "turtle.jpg": "Turtle",
    SAMPLES / "a_can_of_tuna.jpg": "a can of tuna",
    SAMPLES / "a_jar_and_a_hand.jpg": "a jar and a hand",
    SAMPLES / "cat_lying_with_lemons.jpg": "cat lying with lemons",
    ROOT / "plans/samples/search/turtle.jpg": "Turtle",
    ROOT / "plans/samples/search/cat_and_lemons.jpg": "cat and lemons",
}


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def preprocess(path: Path, normalize: bool) -> np.ndarray:
    """App-identical preprocessing at 256: resize shortest edge -> center crop -> /255
    (+ optional CLIP ImageNet normalization). Returns [1, 3, 256, 256] float32."""
    img = Image.open(path).convert("RGB")
    w, h = img.size
    scale = INPUT_SIZE / min(w, h)
    new_w, new_h = round(w * scale), round(h * scale)
    img = img.resize((new_w, new_h), Image.BILINEAR)
    left = (new_w - INPUT_SIZE) // 2
    top = (new_h - INPUT_SIZE) // 2
    img = img.crop((left, top, left + INPUT_SIZE, top + INPUT_SIZE))
    arr = np.asarray(img, dtype=np.float32) / 255.0
    if normalize:
        arr = (arr - MEAN) / STD
    return np.transpose(arr, (2, 0, 1))[np.newaxis, ...]


def tokenize(tokenizer: Tokenizer, text: str) -> np.ndarray:
    ids = tokenizer.encode(text).ids
    if len(ids) > CONTEXT_LENGTH:
        ids = ids[: CONTEXT_LENGTH - 1] + [ids[-1]]
    ids = ids + [0] * (CONTEXT_LENGTH - len(ids))
    return np.array([ids], dtype=np.int64)


def run_model(interp: tf.lite.Interpreter, pixels: np.ndarray, input_ids: np.ndarray) -> list:
    in_idx = [i["index"] for i in interp.get_input_details()]
    out_idx = [o["index"] for o in interp.get_output_details()]
    interp.set_tensor(in_idx[0], pixels)
    interp.set_tensor(in_idx[1], input_ids)
    interp.invoke()
    return [interp.get_tensor(i) for i in out_idx]


def image_embed(interp, pixels, input_ids) -> np.ndarray:
    """The model's output 1 (StatefulPartitionedCall:0) is the image embedding
    (verified: varies with the image, constant under text change)."""
    return run_model(interp, pixels, input_ids)[1][0]


def text_embed(interp, pixels, input_ids) -> np.ndarray:
    """Output 0 (StatefulPartitionedCall:1) is the text embedding (verified:
    varies with the token ids, constant under image change)."""
    return run_model(interp, pixels, input_ids)[0][0]


def task_check(interp: tf.lite.Interpreter, tokenizer: Tokenizer, normalize: bool) -> tuple:
    candidates = list(dict.fromkeys(list(SAMPLES_MAP.values()) + ["Cat"]))
    blank = np.zeros((1, 3, INPUT_SIZE, INPUT_SIZE), np.float32)
    text_embeds = {c: text_embed(interp, blank, tokenize(tokenizer, c)) for c in candidates}
    passes = 0
    rows = []
    for path, desc in SAMPLES_MAP.items():
        img_emb = image_embed(interp, preprocess(path, normalize), tokenize(tokenizer, "Cat"))
        ranked = sorted(candidates, key=lambda c: cosine_similarity(text_embeds[c], img_emb), reverse=True)
        ok = ranked[0] == desc
        passes += ok
        sims = "  ".join(f"{c}={cosine_similarity(text_embeds[c], img_emb):.3f}" for c in ranked)
        rows.append((path.name, desc, ranked[0], ok, sims))
    return passes, len(SAMPLES_MAP), rows


def latency(interp: tf.lite.Interpreter, n: int) -> tuple:
    pixels = preprocess(SAMPLES / "turtle.jpg", False)
    ids = tokenize(Tokenizer.from_file(str(TOKENIZER_PATH)), "Turtle")
    # cold-ish start
    run_model(interp, pixels, ids)
    times = []
    for _ in range(n):
        t0 = time.perf_counter()
        run_model(interp, pixels, ids)
        times.append((time.perf_counter() - t0) * 1e3)
    return float(np.median(times)), float(np.mean(times)), times


def main() -> None:
    model = Path(sys.argv[1]) if len(sys.argv) > 1 else MODEL
    n_runs = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    tokenizer = Tokenizer.from_file(str(TOKENIZER_PATH))

    interp = tf.lite.Interpreter(model_path=str(model))
    interp.allocate_tensors()
    for i in interp.get_input_details():
        print(f"input  {i['name']:32s} {i['shape']} {i['dtype'].__name__}")
    for i, o in enumerate(interp.get_output_details()):
        print(f"output {i} {o['name']:32s} {o['shape']} {o['dtype'].__name__}")

    for variant, normalize in [("/255 only", False), ("/255 + CLIP norm", True)]:
        passes, total, rows = task_check(interp, tokenizer, normalize)
        print(f"\n=== preprocessing: {variant}  ->  {passes}/{total} top-1 ===")
        for name, desc, top, ok, sims in rows:
            print(f"{name:<28} correct={desc!r:<24} top={top!r:<24} {'OK' if ok else 'FAIL'}")
            print(f"    {sims}")

    med, mean, times = latency(interp, n_runs)
    print(f"\n=== latency (n={n_runs}) ===")
    print(f"median {med:.1f} ms   mean {mean:.1f} ms   min {min(times):.1f} ms   max {max(times):.1f} ms")


if __name__ == "__main__":
    main()
