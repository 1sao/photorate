#!/usr/bin/env python3
"""Trace where int64 (or other non-float) value tensors appear in an ONNX
graph: which node produces them and which consume them. Used to judge whether
a graph's non-float32 tensors are trivially removable (constant shapes) or sit
in the real compute path (bad for NNAPI/CoreML on the app's target GPUs).

Usage:
  onnx/.venv/bin/python plans/model_zoo_pinto/inspect_int64.py MODEL.onnx
"""
import onnx
import sys
from collections import defaultdict


def main(path: str) -> None:
    m = onnx.load(path)
    g = m.graph
    init = {i.name for i in g.initializer}
    dtype_of = {}
    for vi in g.value_info:
        dtype_of[vi.name] = vi.type.tensor_type.elem_type
    for out in g.output:
        dtype_of[out.name] = out.type.tensor_type.elem_type

    non_float = sorted(n for n, t in dtype_of.items() if t not in (1, 10, 11, 16))
    if not non_float:
        print("No int32/int64/bool value tensors in the compute path — graph is float32-clean.")
        return

    producer = {}
    consumer = defaultdict(list)
    for node in g.node:
        for o in node.output:
            producer[o] = node.op_type
        for i in node.input:
            consumer[i].append(node.op_type)
    for name in non_float:
        print(
            f"{name:40s} i64?={dtype_of[name] == 7}  produced by {producer.get(name, 'INIT/INPUT'):12s} consumed by {consumer.get(name, ['?'])}")


if __name__ == "__main__":
    main(sys.argv[1])
