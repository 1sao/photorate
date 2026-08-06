#!/usr/bin/env python3
"""Audit an ONNX model for the hand-pipeline candidate comparison.

Prints, per model:
  - file size on disk
  - input/output names, dtypes and shapes
  - op-type histogram (compute graph)
  - NNAPI-friendliness verdict: the Tensor G4 darwinn driver rejects any
    non-float32 operand, so models with int64 compute tensors (baked NMS,
    int64 ArgMax/Slice/TopK chains, int64 bbox inputs) are CPU-only. This
    audit counts int64/int32 value tensors and the ops that typically poison
    a graph for NNAPI, matching the app's measured dead end (see
    photosOnnx/OnnxImageRecognizer.android.kt NNAPI comment).

Usage:
  onnx/.venv/bin/python plans/model_zoo_pinto/audit_model.py MODEL.onnx [MORE.onnx ...]
"""
import onnx
import sys
from onnx import numpy_helper
from pathlib import Path

# Ops whose outputs commonly poison a graph for NNAPI (int64 indices etc.).
NNAPI_POISON_OPS = {
    "NonMaxSuppression", "TopK", "ArgMax", "ArgMin", "GatherND", "Where",
    "NonZero", "Range", "OneHot",
}
# Ops that are fine in themselves but often sit in int64 chains.
INT64_INDEX_OPS = {"Slice", "Gather", "Expand", "Concat", "Cast", "Reshape", "ScatterND"}


def audit(path: str) -> None:
    p = Path(path)
    model = onnx.load(str(p))
    g = model.graph

    size_mb = p.stat().st_size / 1048576

    def fmt_dtype(t):
        return {1: "f32", 7: "i64", 6: "i32", 10: "f16", 3: "i8", 9: "bool"}.get(t, f"t{t}")

    def fmt_shape(dims):
        out = []
        for d in dims:
            if d.HasField("dim_value"):
                out.append(str(d.dim_value))
            else:
                out.append(d.dim_param or "?")
        return "x".join(out) or "scalar"

    print(f"\n===== {p.name}  ({size_mb:.2f} MB) =====")
    for inp in g.input:
        tt = inp.type.tensor_type
        print(f"  IN  {inp.name} [{fmt_dtype(tt.elem_type)}] ({fmt_shape(tt.shape.dim)})")
    for out in g.output:
        tt = out.type.tensor_type
        print(f"  OUT {out.name} [{fmt_dtype(tt.elem_type)}] ({fmt_shape(tt.shape.dim)})")

    # Op histogram + value-dtype histogram (non-initializer tensors only).
    op_counts = {}
    value_dtypes = {}
    poison = {}
    for node in g.node:
        op_counts[node.op_type] = op_counts.get(node.op_type, 0) + 1
        if node.op_type in NNAPI_POISON_OPS:
            poison[node.op_type] = poison.get(node.op_type, 0) + 1
    initializer_ids = {vi.name for vi in g.initializer}
    for vi in g.value_info:
        tt = vi.type.tensor_type
        if vi.name in initializer_ids:
            continue
        dt = tt.elem_type
        value_dtypes[dt] = value_dtypes.get(dt, 0) + 1

    total_ops = sum(op_counts.values())
    top_ops = ", ".join(f"{k}:{v}" for k, v in sorted(op_counts.items(), key=lambda x: -x[1])[:10])
    print(f"  ops: {total_ops} total | {top_ops}")
    dtype_str = " ".join(f"{fmt_dtype(k)}:{v}" for k, v in sorted(value_dtypes.items()))
    print(f"  value dtypes: {dtype_str}")

    int64_tensors = value_dtypes.get(7, 0)
    verdict = []
    if poison:
        verdict.append(f"NNAPI-POISON ops: {poison}")
    if int64_tensors:
        verdict.append(f"{int64_tensors} int64 value tensors in compute path")
    if not verdict:
        verdict.append("looks float32-clean (NNAPI candidate)")
    print(f"  VERDICT: {'; '.join(verdict)}")


if __name__ == "__main__":
    for model_path in sys.argv[1:]:
        audit(model_path)
