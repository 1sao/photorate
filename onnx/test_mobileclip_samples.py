#!/usr/bin/env python3
"""
MobileCLIP sample-identification verification script.

The sample images in ./samples are named after their correct descriptions
(e.g. a_can_of_tuna.jpg -> "a can of tuna", turtle.jpg -> "turtle" via the
turtle_img_description_correct file). This script:

  1. Encodes every sample image with the vision encoder.
  2. Encodes a set of candidate text descriptions (all correct descriptions
     plus a decoy: the turtle "wrong" description, "Cat").
  3. For each image, ranks all candidates by cosine similarity and asserts
     that the correct description is the TOP-1 match.

This is the same pipeline the app will run on-device: a user types a query,
and photos are ranked by how similar their embeddings are to the query
embedding.

Usage:
    python3 test_mobileclip_samples.py
(uses the venv at onnx/.venv, which has onnxruntime/numpy/pillow/tokenizers)

Models (fp16 internally, but ONNX I/O are float32/int64):
    text_model_fp16.onnx    input: input_ids [batch, seq] int64
                            output: text_embeds [batch, 512] float32
    vision_model_fp16.onnx  input: pixel_values [batch, 3, 224, 224] float32
                            output: image_embeds [batch, 512] float32
"""

from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image
from tokenizers import Tokenizer

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
HERE = Path(__file__).resolve().parent
MODELS = HERE / "models"
SAMPLES = HERE / "samples"

TOKENIZER_PATH = MODELS / "tokenizer.json"
TEXT_MODEL = MODELS / "text_model_fp16.onnx"
VISION_MODEL = MODELS / "vision_model_fp16.onnx"

VISION_INPUT_SIZE = 224  # matches the exported vision model's input shape
CONTEXT_LENGTH = 77  # CLIP text encoder context length


def image_correct_description(image_path: Path) -> str:
    """Derive the correct description from the sample image's filename."""
    stem = image_path.stem
    # turtle.jpg has its description in the companion text files; the rest are
    # named after their descriptions with underscores instead of spaces.
    if stem == "turtle":
        return (SAMPLES / "turtle_img_description_correct").read_text().strip()
    return stem.replace("_", " ")


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    """Cosine similarity between two vectors: dot(a,b) / (|a| * |b|)."""
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def encode_text(session: ort.InferenceSession, text: str) -> np.ndarray:
    """Tokenize `text` and run the text encoder, returning the [512] embedding."""
    tokenizer = Tokenizer.from_file(str(TOKENIZER_PATH))
    encoding = tokenizer.encode(text)
    ids = encoding.ids
    # Pad with the pad token (id 0) up to the fixed CLIP context length of 77.
    if len(ids) > CONTEXT_LENGTH:
        ids = ids[:CONTEXT_LENGTH - 1] + [ids[-1]]  # truncate but keep EOS
    ids = ids + [0] * (CONTEXT_LENGTH - len(ids))
    input_ids = np.array([ids], dtype=np.int64)  # [1, 77]
    out = session.run(None, {"input_ids": input_ids})
    return np.asarray(out[0])[0]  # text_embeds [512]


def preprocess_image(path: Path) -> np.ndarray:
    """Replicate CLIPFeatureExtractor: resize shortest edge -> center crop ->
    rescale by 1/255. Returns a float32 [3, size, size] CHW array."""
    img = Image.open(path).convert("RGB")
    w, h = img.size
    scale = VISION_INPUT_SIZE / min(w, h)
    new_w, new_h = round(w * scale), round(h * scale)
    img = img.resize((new_w, new_h), Image.BILINEAR)
    left = (new_w - VISION_INPUT_SIZE) // 2
    top = (new_h - VISION_INPUT_SIZE) // 2
    img = img.crop((left, top, left + VISION_INPUT_SIZE, top + VISION_INPUT_SIZE))
    arr = np.asarray(img, dtype=np.float32) / 255.0
    return np.transpose(arr, (2, 0, 1))  # (H, W, C) -> (C, H, W)


def encode_image(session: ort.InferenceSession, path: Path) -> np.ndarray:
    """Preprocess `path` and run the vision encoder, returning the [512] embedding."""
    pixels = preprocess_image(path)
    batch = pixels[np.newaxis, ...]  # [1, 3, 224, 224]
    out = session.run(None, {"pixel_values": batch})
    return np.asarray(out[0])[0]  # image_embeds [512]


def main() -> None:
    # onnxruntime warns about fp16 weights on CPU; that is expected and fine.
    ort.set_default_logger_severity(3)
    text_session = ort.InferenceSession(str(TEXT_MODEL), providers=["CPUExecutionProvider"])
    vision_session = ort.InferenceSession(str(VISION_MODEL), providers=["CPUExecutionProvider"])

    images = sorted(SAMPLES.glob("*.jpg"))
    correct = {img: image_correct_description(img) for img in images}

    # Candidate descriptions: every correct description plus a decoy ("Cat",
    # the turtle 'wrong' description) so a trivial all-similar label can't pass.
    candidates = list(dict.fromkeys(list(correct.values()) + ["Cat"]))

    print(f"Images: {len(images)}, candidates: {candidates}\n")

    text_embeds = {c: encode_text(text_session, c) for c in candidates}

    all_pass = True
    for img in images:
        desc = correct[img]
        image_embed = encode_image(vision_session, img)
        ranked = sorted(
            candidates,
            key=lambda c: cosine_similarity(text_embeds[c], image_embed),
            reverse=True,
        )
        sims = ", ".join(
            f"{c}={cosine_similarity(text_embeds[c], image_embed):.3f}"
            for c in ranked
        )
        top = ranked[0]
        ok = top == desc
        all_pass = all_pass and ok
        print(f"{img.name:<28} correct={desc!r:<22} top={top!r:<22} {'OK' if ok else 'FAIL'}")
        print(f"    ranking: {sims}")

    print()
    if all_pass:
        print("PASS: every image is matched to its correct description (top-1).")
    else:
        print("FAIL: at least one image was not matched to its correct description.")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
