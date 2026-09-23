import numpy as np
import onnx
import onnxruntime as ort
import sys
from onnx import helper, numpy_helper

SRC = sys.argv[1]
DST = sys.argv[2]

m = onnx.load(SRC)

# The score-sort tail is: ReduceMax(1415)->1446 -> Squeeze->1447 ->
# TopK(1447, k=2100)->[1459, 1460] -> Gather boxes(1442,1460)->boxes,
# Gather scores(1415,1460)->1464 -> Transpose->scores. k == total anchors,
# so it is a pure permutation; the caller sorts anyway.
# Rewire outputs to the raw tensors and drop the tail.
for o in m.graph.output:
    if o.name == "boxes":
        o.name = "1442"
        o.type.tensor_type.shape.dim[1].dim_value = 2100
        o.type.tensor_type.shape.dim[2].dim_value = 4
    elif o.name == "scores":
        o.name = "1415"
        o.type.tensor_type.shape.dim[1].dim_value = 2100
        o.type.tensor_type.shape.dim[2].dim_value = 1

onnx.save(m, DST)
print(f"saved raw-output model to {DST}")
