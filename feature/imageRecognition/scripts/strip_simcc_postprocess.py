#!/usr/bin/env python3
"""Generates ml/original_models/onnx/rtmpose_hand_rawsimcc.onnx from
ml/original_models/onnx/rtmpose_hand.onnx.

The shipped PINTO end2end export bakes a SimCC post-processing whose
`bboxes_width_height` rescale convention differs from rtmlib's, which broke the
ONNX oracle in rtm_pipeline.py. This script strips everything from the first
ArgMax node onward (the baked decode tail) and re-exposes the raw SimCC outputs
`simcc_x` / `simcc_y` [1, 21, 512], so the oracle can decode with rtmlib's own
get_simcc_maximum + postprocess math (mirrored in rtm_pipeline.py).

Kotlin counterpart: feature/imageRecognition/imageRecognitionComponentLiteRt/src/
commonMain/kotlin/isao/photorate/imageRecognition/litert/LiteRtHandLandmarker.kt
(the tflite models keep the caller-side SimCC decode — this file documents why the
ONNX oracle needed the same treatment).

Usage:
    .venv/bin/python strip_simcc_postprocess.py
    (requires: .venv/bin/pip install onnx)
"""
from __future__ import annotations

import onnx
import sys
from onnx import helper, TensorProto
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPTS_DIR.parents[2]
ONNX_MODELS_DIR = PROJECT_ROOT / "ml" / "original_models" / "onnx"
SRC = ONNX_MODELS_DIR / "rtmpose_hand.onnx"
DST = ONNX_MODELS_DIR / "rtmpose_hand_rawsimcc.onnx"


def main() -> int:
    m = onnx.load(str(SRC))
    nodes = m.graph.node
    start = next(i for i, n in enumerate(nodes) if n.op_type == "ArgMax")
    new_nodes = nodes[:start]
    new_graph = helper.make_graph(
        new_nodes,
        "rtmpose_raw_simcc",
        list(m.graph.input),
        [
            helper.make_tensor_value_info("simcc_x", TensorProto.FLOAT, [None, 21, 512]),
            helper.make_tensor_value_info("simcc_y", TensorProto.FLOAT, [None, 21, 512]),
        ],
        initializer=list(m.graph.initializer),
    )
    model = helper.make_model(new_graph, opset_imports=m.opset_import)
    model.ir_version = m.ir_version
    onnx.checker.check_model(model)
    onnx.save(model, str(DST))
    print(f"saved {DST} (nodes: {len(new_nodes)}, dropped {len(nodes) - len(new_nodes)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
