#!/usr/bin/env python3
"""
MobileCLIP model verification script.

Tests the MobileCLIP ONNX models in ./models against the sample data in ./samples:

  1. Tokenizes a text description (e.g. "Turtle") using the CLIP tokenizer.
  2. Runs the tokenized ids through the *text* encoder ONNX model to get a
     text embedding (512 floats).
  3. Loads a sample image (turtle.jpg), preprocesses it exactly like the
     HuggingFace CLIP feature extractor (resize shortest edge -> center crop
     -> rescale by 1/255, no ImageNet normalization per preprocessor_config.json),
     then runs it through the *vision* encoder ONNX model to get an image
     embedding (512 floats).
  4. Computes the cosine similarity between the text and image embeddings.

Verification criterion: the correct description ("Turtle") must yield a clearly
higher similarity than the wrong one ("Cat"). This mirrors what the app will do
on-device: search photos by comparing a query text embedding against stored
image embeddings.

Usage:
    python3 test_mobileclip.py
(uses the venv at onnx/.venv, which has onnxruntime/numpy/pillow/tokenizers)

Models (fp16 internally, but ONNX I/O are float32/int64):
    text_model_fp16.onnx    input: input_ids [batch, seq] int64
                            output: text_embeds [batch, 512] float32
    vision_model_fp16.onnx  input: pixel_values [batch, 3, 224, 224] float32
                            output: image_embeds [batch, 512] float32

NOTE: the vision model was exported with a 224x224 input, even though
preprocessor_config.json mentions 256x256; we follow the actual model shape.
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
TURTLE_IMG = SAMPLES / "turtle.jpg"
CORRECT_DESC = (SAMPLES / "turtle_img_description_correct").read_text().strip()
WRONG_DESC = (SAMPLES / "turtle_img_description_wrong").read_text().strip()

VISION_INPUT_SIZE = 224  # matches the exported vision model's input shape
CONTEXT_LENGTH = 77  # CLIP text encoder context length (model_max_length in tokenizer_config.json)


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    """Cosine similarity between two vectors: dot(a,b) / (|a| * |b|)."""
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def encode_text(session: ort.InferenceSession, text: str) -> np.ndarray:
    """Tokenize `text` and run the text encoder, returning the [512] embedding."""
    tokenizer = Tokenizer.from_file(str(TOKENIZER_PATH))
    # CLIPTokenizer adds <|startoftext|> / <|endoftext|> around the input.
    encoding = tokenizer.encode(text)
    ids = encoding.ids
    # The exported text model expects a FIXED-length sequence (its internal
    # attention-mask constant is [77, 77]), so pad with the pad token (id 0)
    # up to the CLIP context length of 77, same as HuggingFace CLIPProcessor.
    if len(ids) > CONTEXT_LENGTH:
        ids = ids[:CONTEXT_LENGTH - 1] + [ids[-1]]  # truncate but keep EOS
    ids = ids + [0] * (CONTEXT_LENGTH - len(ids))
    input_ids = np.array([ids], dtype=np.int64)  # [1, 77]
    out = session.run(None, {"input_ids": input_ids})
    return np.asarray(out[0])[0]  # text_embeds [512]


def preprocess_image(path: Path) -> np.ndarray:
    """
    Replicate CLIPFeatureExtractor preprocessing:
      - convert to RGB
      - resize so the shortest edge == size
      - center crop to size x size
      - rescale pixels by 1/255 (do_normalize is false in this model)
    Returns a float32 [3, size, size] CHW array.
    """
    img = Image.open(path).convert("RGB")
    w, h = img.size
    # Resize the shortest edge to `size`, keeping aspect ratio.
    scale = VISION_INPUT_SIZE / min(w, h)
    new_w, new_h = round(w * scale), round(h * scale)
    img = img.resize((new_w, new_h), Image.BILINEAR)
    # Center crop to size x size.
    left = (new_w - VISION_INPUT_SIZE) // 2
    top = (new_h - VISION_INPUT_SIZE) // 2
    img = img.crop((left, top, left + VISION_INPUT_SIZE, top + VISION_INPUT_SIZE))
    # (H, W, C) uint8 -> (C, H, W) float32 in [0, 1]
    arr = np.asarray(img, dtype=np.float32) / 255.0
    return np.transpose(arr, (2, 0, 1))


def encode_image(session: ort.InferenceSession) -> np.ndarray:
    """Preprocess turtle.jpg and run the vision encoder, returning the [512] embedding."""
    pixels = preprocess_image(TURTLE_IMG)
    batch = pixels[np.newaxis, ...]  # [1, 3, 224, 224]
    out = session.run(None, {"pixel_values": batch})
    return np.asarray(out[0])[0]  # image_embeds [512]


def main() -> None:
    # onnxruntime warns about fp16 weights on CPU; that is expected and fine.
    ort.set_default_logger_severity(3)
    text_session = ort.InferenceSession(str(TEXT_MODEL), providers=["CPUExecutionProvider"])
    vision_session = ort.InferenceSession(str(VISION_MODEL), providers=["CPUExecutionProvider"])

    print(f"Image : {TURTLE_IMG.name}")
    print(f"Correct description: {CORRECT_DESC!r}")
    print(f"Wrong   description: {WRONG_DESC!r}")

    image_embed = encode_image(vision_session)
    correct_embed = encode_text(text_session, CORRECT_DESC)
    wrong_embed = encode_text(text_session, WRONG_DESC)

    correct_sim = cosine_similarity(correct_embed, image_embed)
    wrong_sim = cosine_similarity(wrong_embed, image_embed)

    print(f"Similarity(image, {CORRECT_DESC!r}) = {correct_sim:.4f}")
    print(f"Similarity(image, {WRONG_DESC!r})   = {wrong_sim:.4f}")

    # Verification: the correct description must match better than the wrong one.
    if correct_sim > wrong_sim:
        print("PASS: image is found with the correct description, not the wrong one.")
    else:
        print("FAIL: similarity ordering is wrong!")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
