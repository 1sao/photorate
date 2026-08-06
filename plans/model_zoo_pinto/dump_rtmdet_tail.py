import onnx
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "plans/model_zoo_pinto/rtmdet_320_f32clean.onnx"
m = onnx.load(path)

print("=== graph outputs ===")
for o in m.graph.output:
    dims = [d.dim_value or d.dim_param for d in o.type.tensor_type.shape.dim]
    print(f"  {o.name} {dims}")

print("=== last 14 nodes ===")
for n in m.graph.node[-14:]:
    print(f"  {n.op_type} {list(n.input)} -> {list(n.output)}")

print("=== int64-typed value infos ===")
init = {i.name for i in m.graph.initializer}
for vi in m.graph.value_info:
    if vi.type.tensor_type.elem_type == 7 and vi.name not in init:
        dims = [d.dim_value or d.dim_param for d in vi.type.tensor_type.shape.dim]
        print(f"  {vi.name} {dims}")

print("=== TopK / Gather / Squeeze / Transpose nodes ===")
for n in m.graph.node:
    if n.op_type in ("TopK", "Gather", "Squeeze", "Unsqueeze", "Transpose"):
        print(f"  {n.op_type} {list(n.input)} -> {list(n.output)}")
