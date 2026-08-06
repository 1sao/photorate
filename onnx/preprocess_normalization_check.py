#!/usr/bin/env python3
"""
Preprocessing verification for the vision search model (vision_model_fp16.onnx).

Question (from MODEL_SOURCES.md): the app feeds /255-only pixels (no ImageNet
normalization), but a vanilla CLIP/EVA-02-CLIP checkpoint expects normalization.
Does adding CLIP ImageNet normalization change search accuracy?

Answer (verified 2026-08-02 on the same models + sample images the app uses):
adding normalization BREAKS search. /255-only scores 5/6 top-1 matches;
/255 + CLIP ImageNet mean/std scores 2/6 (often matching a wrong description,
e.g. "Cat"). The image embeddings under normalization become nearly orthogonal
to the text embeddings (similarity to the /255-only embedding ~0.18), so this
model is empirically trained/exported for /255-only input — MobileCLIP-style
preprocessing, consistent with its preprocessor_config.json (do_normalize: false).

Decision: keep the /255-only decode in AndroidAppClipSearch.preprocess(). Do not
add normalization unless the model files are replaced.

Usage:
    python3 preprocess_normalization_check.py
(uses the venv at onnx/.venv, which has onnxruntime/numpy/pillow/tokenizers)
"""

from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image
from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parent
MODELS = ROOT / "models"

# image path -> correct description (mirrors onnx/test_mobileclip_samples.py +
# the androidTest dataset in plans/samples/search).
samples = {
    MODELS.parent / "samples" / "turtle.jpg": "Turtle",
    MODELS.parent / "samples" / "a_can_of_tuna.jpg": "a can of tuna",
    MODELS.parent / "samples" / "a_jar_and_a_hand.jpg": "a jar and a hand",
    MODELS.parent / "samples" / "cat_lying_with_lemons.jpg": "cat lying with lemons",
    ROOT.parent / "plans" / "samples" / "search" / "turtle.jpg": "Turtle",
    ROOT.parent / "plans" / "samples" / "search" / "cat_and_lemons.jpg": "cat and lemons",
}

VISION_INPUT_SIZE = 224  # matches the exported vision model's input shape
CONTEXT_LENGTH = 77  # CLIP text encoder context length

# OpenAI/open_clip CLIP ImageNet normalization (what a vanilla CLIP expects).
MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    """Cosine similarity between two vectors: dot(a,b) / (|a| * |b|)."""
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def encode_text(session: ort.InferenceSession, tokenizer: Tokenizer, text: str) -> np.ndarray:
    """Tokenize `text` and run the text encoder, returning the [512] embedding."""
    ids = tokenizer.encode(text).ids
    if len(ids) > CONTEXT_LENGTH:
        ids = ids[: CONTEXT_LENGTH - 1] + [ids[-1]]  # truncate but keep EOS
    ids = ids + [0] * (CONTEXT_LENGTH - len(ids))
    out = session.run(None, {"input_ids": np.array([ids], dtype=np.int64)})
    return np.asarray(out[0])[0]


def preprocess(path: Path, normalize: bool) -> np.ndarray:
    """App-identical preprocessing: resize shortest edge -> center crop -> /255
    (+ optional CLIP ImageNet normalization). Returns [1, 3, 224, 224] float32."""
    img = Image.open(path).convert("RGB")
    w, h = img.size
    scale = VISION_INPUT_SIZE / min(w, h)
    new_w, new_h = round(w * scale), round(h * scale)
    img = img.resize((new_w, new_h), Image.BILINEAR)
    left = (new_w - VISION_INPUT_SIZE) // 2
    top = (new_h - VISION_INPUT_SIZE) // 2
    img = img.crop((left, top, left + VISION_INPUT_SIZE, top + VISION_INPUT_SIZE))
    arr = np.asarray(img, dtype=np.float32) / 255.0  # [H, W, C] in [0, 1]
    if normalize:
        arr = (arr - MEAN) / STD
    return np.transpose(arr, (2, 0, 1))[np.newaxis, ...]


def main() -> None:
    ort.set_default_logger_severity(3)
    tokenizer = Tokenizer.from_file(str(MODELS / "tokenizer.json"))
    text_session = ort.InferenceSession(str(MODELS / "text_model_fp16.onnx"), providers=["CPUExecutionProvider"])
    vision_session = ort.InferenceSession(str(MODELS / "vision_model_fp16.onnx"), providers=["CPUExecutionProvider"])

    # Candidate descriptions: every correct description plus a decoy so a
    # trivial all-similar label can't pass.
    candidates = list(dict.fromkeys(list(samples.values()) + ["Cat"]))
    text_embeds = {c: encode_text(text_session, tokenizer, c) for c in candidates}

    for variant, normalize in [("/255 only", False), ("/255 + CLIP norm", True)]:
        print(f"\n=== preprocessing: {variant} ===")
        passes = 0
        for path, desc in samples.items():
            embed = np.asarray(vision_session.run(None, {"pixel_values": preprocess(path, normalize)})[0])[0]
            ranked = sorted(candidates, key=lambda c: cosine_similarity(text_embeds[c], embed), reverse=True)
            sims = "  ".join(f"{c}={cosine_similarity(text_embeds[c], embed):.3f}" for c in ranked)
            ok = ranked[0] == desc
            passes += ok
            print(f"{path.name:<28} correct={desc!r:<24} top={ranked[0]!r:<24} {'OK' if ok else 'FAIL'}")
            print(f"    {sims}")
        print(f"PASS {passes}/{len(samples)}")

    print("\nExpected: /255 only passes more than /255 + CLIP norm. If the model is")
    print("replaced with a normalization-expecting checkpoint, this check flips.")


if __name__ == "__main__":
    main()
