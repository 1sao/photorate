#!/usr/bin/env python3
"""Simplify an ONNX model with a frozen input shape and verify numeric parity.

Freezing the batch dim (--input-shape) lets onnxsim constant-fold the dynamic
Shape/Slice/Concat/Reshape bookkeeping, which is what leaves int64 tensors in
the compute path (the thing that makes NNAPI reject the graph on-device).

Usage:
  onnx/.venv/bin/python plans/model_zoo_pinto/simplify_clean.py IN.onnx OUT.onnx --input-shape 1,3,256,256
"""
import argparse

import numpy as np
import onnx
import onnxruntime as ort
import onnxsim


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("in_path")
    ap.add_argument("out_path")
    ap.add_argument("--input-shape", default="1,3,256,256")
    args = ap.parse_args()

    shape = [int(x) for x in args.input_shape.split(",")]
    model = onnx.load(args.in_path)
    model, check = onnxsim.simplify(
        model,
        overwrite_input_shapes={model.graph.input[0].name: shape},
        check_n=1,
    )
    print(f"onnxsim check: {check}")
    onnx.save(model, args.out_path)
    print(f"saved {args.out_path}")

    # Parity: random + zero inputs, original vs simplified.
    a = ort.InferenceSession(args.in_path, providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(args.out_path, providers=["CPUExecutionProvider"])
    feeds = {a.get_inputs()[0].name: np.zeros(shape, dtype=np.float32)}
    oa, ob = a.run(None, feeds), b.run(None, feeds)
    diff = max(float(np.abs(oa[i] - ob[i]).max()) for i in range(len(oa)))
    print(f"zero-input parity max abs diff: {diff}")
    rng = np.random.default_rng(0)
    feeds = {a.get_inputs()[0].name: rng.standard_normal(shape).astype(np.float32)}
    oa, ob = a.run(None, feeds), b.run(None, feeds)
    diff = max(float(np.abs(oa[i] - ob[i]).max()) for i in range(len(oa)))
    print(f"random-input parity max abs diff: {diff}")


if __name__ == "__main__":
    main()
