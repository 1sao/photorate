#!/usr/bin/env python3
"""Probe intermediate tensors of an ONNX model via ORT (any node output can be
requested as a session output). Prints shapes and a preview of values.

Usage:
  onnx/.venv/bin/python plans/model_zoo_pinto/probe_intermediates.py MODEL.onnx TENSOR_NAME [MORE...]
"""
import numpy as np
import onnxruntime as ort
import sys


def main(path: str, names: list[str]) -> None:
    feeds = {"input": np.zeros((1, 3, 256, 256), dtype=np.float32)}
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    for name in names:
        try:
            out = sess.run([name], feeds)[0]
            flat = np.asarray(out).reshape(-1)
            print(
                f"{name:20s} shape={np.asarray(out).shape} dtype={np.asarray(out).dtype} first={flat[:8]}")
        except Exception as e:
            print(f"{name:20s} FAILED: {type(e).__name__}: {str(e)[:80]}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
