import numpy as np
import onnx

m = onnx.load("ml/original_models/rtmdet_nano_8xb32-300e_hand-267f9c8f/end2end.onnx")

print("=== NMS / decode-related nodes in baked model ===")
for n in m.graph.node:
    if n.op_type in ("NonMaxSuppression", "TopK", "Range"):
        attrs = {a.name: (
            a.i if a.type == onnx.AttributeProto.INT else a.f if a.type == onnx.AttributeProto.FLOAT else None)
                 for a in n.attribute}
        print(f"  {n.op_type} {list(n.input)} -> {list(n.output)} attrs={attrs}")
