#!/usr/bin/env python3
"""Convert the RTM hand models (GPU-clean ONNX) to float32 LiteRT .tflite.

Part of the ml/litert/ model recipe (see README.md). Float32 only — no
quantization yet (accuracy-safe-quantization skill comes after on-device
verification passes).

The sources are the `end2end_f32clean_gpu.onnx` graphs produced by
rewrite_gpu_clean.py: numerically identical to the f32clean models, but with
constant-scalar GATHERs and constant-size SPLITs rewritten to SLICEs so the
litert ClGlAccelerator accepts every node (GATHER/SPLIT_V are the only two
unsupported builtins in these graphs — verified on-device by the op probe).

Uses onnx2tf with --keep_ncw_or_nchw so the tflite I/O stays NCHW and
matches the ONNX contract exactly (input "input" [1,3,320,320] / [1,3,256,256];
detector outputs boxes [1,2100,4] + scores [1,2100,1]; pose outputs
simcc_x/simcc_y [1,21,512]).

Usage: ml/litert/.venv/bin/python ml/litert/scripts/convert_models.py
"""

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]          # ml/litert
ORIG = ROOT.parent / "original_models"              # ml/original_models
OUT = ROOT / "converted"
VENV_BIN = ROOT / ".venv" / "bin"

MODELS = [
    (
        ORIG / "rtmdet_nano_8xb32-300e_hand-267f9c8f" / "end2end_f32clean_gpu.onnx",
        OUT / "rtmdet_hand_320_f32.tflite",
    ),
    (
        ORIG / "rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320" / "end2end_f32clean_gpu.onnx",
        OUT / "rtmpose_hand_256_f32.tflite",
    ),
]


def convert(src: Path, dst: Path) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        env = dict(os.environ)
        env["TF_CPP_MIN_LOG_LEVEL"] = "2"
        # Silence onnx2tf's per-op "DynamicRangeQuantize" warnings.
        env["ONNX2TF_QUIET"] = "1"
        cmd = [
            str(VENV_BIN / "onnx2tf"),
            "-i", str(src),
            "-o", tmp,
            "-k", "input",
            "--copy_onnx_input_output_names_to_tflite",
        ]
        subprocess.run(cmd, env=env, check=True)
        # onnx2tf emits *_float32.tflite and *_float16.tflite; we ship float32
        # (quantization comes later, per the accuracy-safe-quantization skill).
        produced = [p for p in Path(tmp).glob("*_float32.tflite")]
        if len(produced) != 1:
            raise SystemExit(f"expected exactly one float32 tflite, got {produced}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(produced[0]), dst)
        print(f"{src.name} -> {dst} ({dst.stat().st_size / 1e6:.1f} MB)")


def main() -> None:
    for src, dst in MODELS:
        convert(src, dst)
    print("conversion done")


if __name__ == "__main__":
    main()
