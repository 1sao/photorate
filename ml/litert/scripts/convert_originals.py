#!/usr/bin/env python3
"""Convert the *original* end2end ONNX models to float32 LiteRT .tflite.

Companion to convert_models.py: these are the unmodified source graphs
(`end2end.onnx`, with the in-graph post-processing — detector NMS/TopK,
pose SimCC head) rather than the head-cut `*_f32clean.onnx` graphs that the
shipped `rtmdet_hand_320_f32.tflite` / `rtmpose_hand_256_f32.tflite` come
from.

Purpose: demonstrate what the original graphs do on GPU vs the cleaned ones.
The detector's NMS/TopK/data-dependent Gathers are not GPU ops, so the
converted model is expected to be CPU-only; the pose original is expected to
converge with the cleaned one after the same GPU-clean rewrites.

Usage: ml/litert/.venv/bin/python ml/litert/scripts/convert_originals.py
"""

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ORIG = ROOT.parent / "original_models"
OUT = ROOT / "converted"
VENV_BIN = ROOT / ".venv" / "bin"

MODELS = [
    (
        ORIG / "rtmdet_nano_8xb32-300e_hand-267f9c8f" / "end2end.onnx",
        OUT / "rtmdet_hand_320_end2end_f32.tflite",
    ),
    (
        ORIG / "rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320" / "end2end.onnx",
        OUT / "rtmpose_hand_256_end2end_f32.tflite",
    ),
]


def convert(src: Path, dst: Path) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        env = dict(os.environ)
        env["TF_CPP_MIN_LOG_LEVEL"] = "2"
        env["ONNX2TF_QUIET"] = "1"
        cmd = [
            str(VENV_BIN / "onnx2tf"),
            "-i", str(src),
            "-o", tmp,
            "-k", "input",
            "--copy_onnx_input_output_names_to_tflite",
        ]
        subprocess.run(cmd, env=env, check=True)
        produced = [p for p in Path(tmp).glob("*_float32.tflite")]
        if len(produced) != 1:
            raise SystemExit(f"expected exactly one float32 tflite, got {produced}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(produced[0]), dst)
        print(f"{src.name} -> {dst.name} ({dst.stat().st_size / 1e6:.1f} MB)")


def main() -> None:
    for src, dst in MODELS:
        convert(src, dst)
    print("original conversion done")


if __name__ == "__main__":
    main()
