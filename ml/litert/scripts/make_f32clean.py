#!/usr/bin/env python3
"""Regenerate the *_f32clean.onnx models from the mmdeploy end2end.onnx exports.

Both f32clean models are numerically identical, GPU-safe reductions of the
original exports (weights untouched; only dead post-processing removed):

- Detector: rewires the graph outputs to the raw anchor tensors (1442 boxes
  [1,2100,4], 1415 scores [1,2100,1]) that sit before the in-graph score sort,
  prunes the now-unreachable NMS/sort tail, then onnxsim-folds the int64
  shape bookkeeping. Caller-side NMS (iou=0.6, score_thr=0.05, max 200)
  reproduces the original `dets` output bit-exactly.
- Pose: onnxsim constant-folds the int64 shape bookkeeping at frozen input
  [1,3,256,256]; raw SimCC outputs are already the graph outputs.

Verified end-to-end against the shipped files in ml/original_models (2026-09-22):
detector outputs 0.0 diff, pose outputs <= 2.7e-7 (float noise).

Needs a working onnxsim (run in the onnx env, not the litert env).

Usage:
  <python> ml/litert/scripts/make_f32clean.py [--orig-dir DIR] [--write]

Default --orig-dir is ml/original_models. Without --write, writes
end2end_f32clean_regen.onnx next to each reference and diffs against it.
"""
import argparse
import numpy as np
import onnx
import onnxruntime as ort
import onnxsim
from onnx import TensorProto, helper
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DEFAULT_ORIG = ROOT / "ml" / "original_models"

MODEL_DIRS = [
    ("rtmdet_nano_8xb32-300e_hand-267f9c8f", True, {"1442": (2100, 4), "1415": (2100, 1)}, None),
    (
        "rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320",
        False,
        {"simcc_x": (21, 512), "simcc_y": (21, 512)},
        [1, 3, 256, 256],
    ),
]

MAX_DIFF = 1e-5


def prune(model: onnx.ModelProto, keep_outputs: dict) -> None:
    prods = {}
    for n in model.graph.node:
        for o in n.output:
            prods[o] = n
    new_outputs = [
        helper.make_tensor_value_info(name, TensorProto.FLOAT, [1, d1, d2])
        for name, (d1, d2) in keep_outputs.items()
    ]
    del model.graph.output[:]
    model.graph.output.extend(new_outputs)
    used = set(keep_outputs)
    for n in reversed(model.graph.node):
        if set(n.output) & used:
            used.update(n.input)
    kept = [n for n in model.graph.node if set(n.output) & used]
    del model.graph.node[:]
    model.graph.node.extend(kept)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--orig-dir", type=Path, default=DEFAULT_ORIG)
    ap.add_argument("--write", action="store_true", help="overwrite the reference f32clean files")
    args = ap.parse_args()

    for dirname, prune_tail, outs, fixed_shape in MODEL_DIRS:
        src = args.orig_dir / dirname / "end2end.onnx"
        ref = args.orig_dir / dirname / "end2end_f32clean.onnx"
        m = onnx.load(src)
        if fixed_shape:
            m, check = onnxsim.simplify(
                m,
                overwrite_input_shapes={m.graph.input[0].name: fixed_shape},
                check_n=0,
            )
            assert check, f"onnxsim check failed for {src}"
        if prune_tail:
            prune(m, outs)
        m, check = onnxsim.simplify(m, check_n=0)
        assert check, f"onnxsim check failed after prune for {src}"
        onnx.checker.check_model(m)

        dst = ref if args.write else ref.with_name(ref.stem + "_regen.onnx")
        onnx.save(m, dst)

        h, w = (fixed_shape[2], fixed_shape[3]) if fixed_shape else (320, 320)
        x = np.random.default_rng(0).standard_normal((1, 3, h, w)).astype(np.float32)
        a = ort.InferenceSession(str(dst), providers=["CPUExecutionProvider"])
        b = ort.InferenceSession(str(ref), providers=["CPUExecutionProvider"])
        oa = a.run(None, {a.get_inputs()[0].name: x})
        ob = b.run(None, {b.get_inputs()[0].name: x})
        diffs = [float(np.abs(u - v).max()) for u, v in zip(oa, ob)]
        status = "OK" if max(diffs) <= MAX_DIFF else "FAIL"
        print(f"{src.name} [{dirname}] -> {dst.name}: diffs={diffs} {status}")
        if max(diffs) > MAX_DIFF:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
