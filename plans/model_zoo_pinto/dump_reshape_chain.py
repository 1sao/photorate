#!/usr/bin/env python3
"""Dump the nodes around a named int64 chain (Shape -> Slice -> Concat ->
Reshape) so the dynamic-batch-dim bookkeeping can be seen and surgically
replaced with a static shape (which is what makes a graph float32-clean for
NNAPI/CoreML).

Usage:
  onnx/.venv/bin/python plans/model_zoo_pinto/dump_reshape_chain.py MODEL.onnx
"""
import onnx
import sys


def main(path: str) -> None:
    m = onnx.load(path)
    g = m.graph
    for node in g.node:
        if node.op_type in ("Shape", "Slice", "Concat", "Reshape"):
            print(f"{node.op_type:10s} {list(node.input)} -> {list(node.output)}")
            for att in node.attribute:
                if att.name in ("starts", "ends", "axes", "perm", "axes"):
                    print(f"           attr {att.name} = {list(att.ints)}")
    # Also print the graph inputs so the dynamic batch dim is visible.
    for inp in g.input:
        print(
            f"graph input {inp.name} shape {[d.dim_param or d.dim_value for d in inp.type.tensor_type.shape.dim]}")


if __name__ == "__main__":
    main(sys.argv[1])
