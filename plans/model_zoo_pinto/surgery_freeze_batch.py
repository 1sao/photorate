#!/usr/bin/env python3
"""Freeze the dynamic batch dim of a SimCC pose export.

The raw mmdeploy RTMPose export computes its output reshape shape at runtime:
  Shape(input) -> Slice -> Concat(..., [21,512]) -> Reshape(x, shape)
Those int64 tensors are pure bookkeeping, but any int64 tensor in the compute
path is enough to make NNAPI reject the whole graph on the app's target GPU
(Tensor G4 darwinn). Since the app always runs batch=1, we replace the dynamic
shape with a static constant and prune the dead nodes — the graph becomes
fully float32-clean with no output change.

Usage:
  onnx/.venv/bin/python plans/model_zoo_pinto/surgery_freeze_batch.py IN.onnx OUT.onnx [--static-batch 1]
"""
import argparse
import numpy as np
import onnx
import sys
from collections import deque
from onnx import TensorProto, helper, numpy_helper


def freeze_batch_dim(in_path: str, out_path: str, static_batch: int = 1) -> None:
    m = onnx.load(in_path)
    g = m.graph
    init = {i.name: numpy_helper.to_array(i) for i in g.initializer}

    # Find Concat nodes that build "([batch] , ...)" from one non-initializer
    # (the sliced batch dim) plus only initializer constants, and the Reshape
    # that consumes them.
    concat_out_to_static = {}
    for node in g.node:
        if node.op_type != "Concat":
            continue
        non_init = [i for i in node.input if i not in init]
        consts = [init[i] for i in node.input if i in init]
        if len(non_init) == 1 and consts and len(node.output) == 1:
            # The dynamic input is a 1-element int64 tensor (the batch dim).
            try:
                static = np.concatenate(
                    [np.asarray([static_batch], dtype=np.int64)] + [c.reshape(-1) for c in consts]
                )
            except Exception:
                continue
            concat_out_to_static[node.output[0]] = static

    if not concat_out_to_static:
        print("No dynamic-batch Concat/Reshape pattern found; nothing to do.")
        sys.exit(1)

    new_initializers = []
    patched = 0
    for node in g.node:
        if node.op_type == "Reshape" and len(node.input) == 2 and node.input[
            1] in concat_out_to_static:
            static = concat_out_to_static[node.input[1]]
            name = f"{node.output[0]}_static_shape"
            new_initializers.append(
                helper.make_tensor(name, TensorProto.INT64, list(static.shape), static.tolist())
            )
            node.input[1] = name
            print(f"patched Reshape {node.output[0]} -> static shape {static.tolist()}")
            patched += 1

    if not patched:
        print("Found concat pattern but no consuming Reshape; aborting.")
        sys.exit(1)

    g.initializer.extend(new_initializers)

    # Prune nodes not reachable from the graph outputs (the now-dead
    # Shape/Slice/Concat chain).
    produced = {o: n for n in g.node for o in n.output}
    keep_ids = set()
    queue = deque(out.name for out in g.output)
    while queue:
        name = queue.popleft()
        node = produced.get(name)
        if node is None or id(node) in keep_ids:
            continue
        keep_ids.add(id(node))
        for inp in node.input:
            if inp in produced:
                queue.append(inp)
    kept = [n for n in g.node if id(n) in keep_ids]
    removed = len(g.node) - len(kept)
    del g.node[:]
    g.node.extend(kept)
    print(f"removed {removed} dead nodes")

    onnx.checker.check_model(m)
    onnx.save(m, out_path)
    print(f"saved {out_path}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("in_path")
    ap.add_argument("out_path")
    ap.add_argument("--static-batch", type=int, default=1)
    args = ap.parse_args()
    freeze_batch_dim(args.in_path, args.out_path, args.static_batch)
