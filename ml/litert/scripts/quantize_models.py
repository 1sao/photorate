#!/usr/bin/env python3
"""Quantize the float32 GPU-clean RTM hand models with ai-edge-quantizer.

Part of the ml/litert/ model recipe — the accuracy-safe-quantization skill
runs *after* on-device GPU verification of the float32 conversion passed
(see converted/README.md for those numbers).

Two variants per model, per the skill's lane table:

  fp16  FLOAT_CASTING   weights cast to fp16, compute stays float  ~1/2 size
  int8  dynamic wi8 channelwise  int8 weights, fp32 activations    ~1/4 size

Dynamic-range int8 keeps activations in float, so the graph's op set
(STRIDED_SLICE/MAXIMUM/...) is unchanged and the output tensors stay fp32
with the same shapes — the CompiledModel verification harness applies
unchanged.  This is the "rides the GPU delegate" lane from the skill.

Usage:
  ml/litert/.venv/bin/python ml/litert/scripts/quantize_models.py

Outputs (all in ml/litert/converted/):
  rtmdet_hand_320_f16.tflite / rtmdet_hand_320_i8.tflite
  rtmpose_hand_256_f16.tflite / rtmpose_hand_256_i8.tflite
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]  # ml/litert
OUT = ROOT / "converted"

F32 = [
    (OUT / "rtmdet_hand_320_f32.tflite", "rtmdet_hand_320"),
    (OUT / "rtmpose_hand_256_f32.tflite", "rtmpose_hand_256"),
]

FP16_SUFFIX = "_f16.tflite"
INT8_SUFFIX = "_i8.tflite"


def quantize_fp16(src: Path, dst: Path) -> None:
    from ai_edge_quantizer import quantizer, recipe_manager
    from ai_edge_quantizer.recipe import AlgorithmName, qtyping

    rm = recipe_manager.RecipeManager()
    rm.add_quantization_config(
        regex=".*",
        operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
        op_config=qtyping.OpQuantizationConfig(
            weight_tensor_config=qtyping.TensorQuantizationConfig(
                num_bits=16,
                dtype=qtyping.TensorDataType.FLOAT,
            ),
            compute_precision=qtyping.ComputePrecision.FLOAT,
        ),
        algorithm_key=AlgorithmName.FLOAT_CASTING,
    )
    quantizer.Quantizer(str(src), rm.get_quantization_recipe()).quantize().export_model(str(dst))
    print(f"{src.name} -> {dst.name} ({dst.stat().st_size / 1e6:.1f} MB)")


def quantize_int8(src: Path, dst: Path) -> None:
    from ai_edge_quantizer import quantizer
    from ai_edge_quantizer.recipe import dynamic_wi8c_afp32

    # Channelwise dynamic-range int8 (weights), fp32 activations.
    quantizer.Quantizer(str(src), dynamic_wi8c_afp32()).quantize().export_model(str(dst))
    print(f"{src.name} -> {dst.name} ({dst.stat().st_size / 1e6:.1f} MB)")


def main() -> None:
    for src, stem in F32:
        if not src.exists():
            print(f"SKIP {src.name}: not found (run convert_models.py first)")
            continue
        f32_size = src.stat().st_size
        print(f"{src.name} f32={f32_size / 1e6:.1f} MB")
        quantize_fp16(src, OUT / (stem + FP16_SUFFIX))
        quantize_int8(src, OUT / (stem + INT8_SUFFIX))
    print("quantization done")


if __name__ == "__main__":
    main()
