#!/usr/bin/env python3
"""Quantize the combined MobileCLIP-S1 tflite with ai-edge-quantizer.

Follows the accuracy-safe-quantization skill (same recipes as
quantize_models.py for the RTM hand models):

  fp16  FLOAT_CASTING               weights cast to fp16, compute stays float
  int8  dynamic channelwise (wi8c)  int8 weights, fp32 activations

Both keep the graph's op set and fp32 outputs unchanged, so the host
verification harness (clip_s1_verify.py) applies to every variant as-is.

Usage:
  ml/litert/.venv/bin/python ml/litert/scripts/quantize_clip_s1.py
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]  # repo root
SRC = ROOT / "ml/original_models/mobileclip2_tflite/mobileclip_s1_datacompdr_last.tflite"
OUT_DIR = ROOT / "ml/litert/converted"


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

    quantizer.Quantizer(str(src), dynamic_wi8c_afp32()).quantize().export_model(str(dst))
    print(f"{src.name} -> {dst.name} ({dst.stat().st_size / 1e6:.1f} MB)")


def main() -> None:
    stem = "clip_s1_combined"
    if not SRC.exists():
        print(f"SKIP: {SRC} not found")
        return
    print(f"source f32 = {SRC.stat().st_size / 1e6:.1f} MB")
    quantize_fp16(SRC, OUT_DIR / f"{stem}_f16.tflite")
    quantize_int8(SRC, OUT_DIR / f"{stem}_i8.tflite")
    print("quantization done")


if __name__ == "__main__":
    main()
