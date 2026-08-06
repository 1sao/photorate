import onnx
import sys

path = sys.argv[1]
m = onnx.load(path)

print("=== shapes of key tensors (value_info) ===")
want = {"1415", "1416", "1442", "1447", "1458", "1459", "1460", "1464"}
for vi in m.graph.value_info:
    if vi.name in want:
        dims = [d.dim_value or d.dim_param for d in vi.type.tensor_type.shape.dim]
        print(f"  {vi.name} {dims} elem_type={vi.type.tensor_type.elem_type}")

print("=== TopK input constants ===")
init = {i.name: i for i in m.graph.initializer}
for n in m.graph.node:
    if n.op_type == "TopK":
        for inp in n.input[1:]:
            if inp in init:
                import numpy as np

                a = np.frombuffer(init[inp].raw_data, dtype=np.int64) if init[
                    inp].raw_data else np.array(init[inp].int64_data)
                print(f"  TopK attr input {inp}: {a.tolist()}")
