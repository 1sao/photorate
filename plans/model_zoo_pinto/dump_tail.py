#!/usr/bin/env python3
"""Dump graph outputs, the values of named initializers, and the tail nodes of
an ONNX model (for diagnosing reshape/dynamic-shape bookkeeping)."""
import numpy as np
import onnx
import sys
from onnx import numpy_helper


def main(path: str) -> None:
    m = onnx.load(path)
    g = m.graph
    print("graph outputs:",
          [(o.name, [d.dim_value or d.dim_param for d in o.type.tensor_type.shape.dim]) for o in
           g.output])
    print("initializers of interest:")
    for i in g.initializer:
        if i.name in ("625", "626", "627", "629", "630", "631", "632", "633"):
            print(f"  {i.name} = {numpy_helper.to_array(i).tolist()}")
    print("tail nodes:")
    for n in g.node[-15:]:
        print(f"  {n.op_type} {list(n.input)} -> {list(n.output)}")
        for a in n.attribute:
            if a.type in (onnx.AttributeProto.INTS, onnx.AttributeProto.INT):
                v = list(a.ints) if a.ints else a.i
                print(f"      {a.name} = {v}")


if __name__ == "__main__":
    main(sys.argv[1])
