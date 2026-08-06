import numpy as np
import onnx
from onnx import numpy_helper

m = onnx.load("ml/original_models/rtmdet_nano_8xb32-300e_hand-267f9c8f/end2end.onnx")
init = {i.name: i for i in m.graph.initializer}
for name in ("1443", "1444", "1445", "1462", "1465", "1458"):
    if name in init:
        a = numpy_helper.to_array(init[name])
        print(f"{name}: {a.tolist()}  dtype={a.dtype}")
    else:
        print(f"{name}: (not an initializer)")
