import numpy as np
import onnx

import sys

A = sys.argv[1]
B = sys.argv[2]

ma = onnx.load(A)
mb = onnx.load(B)

init_a = {i.name: i for i in ma.graph.initializer}
init_b = {i.name: i for i in mb.graph.initializer}

print(f"A: {len(init_a)} initializers, B: {len(init_b)} initializers")

# Map Conv weight tensors by op position (many names will differ after
# re-export). Collect per-node Conv inputs.
conv_a = [(n.op_type, list(n.input)) for n in ma.graph.node if n.op_type == "Conv"]
conv_b = [(n.op_type, list(n.input)) for n in mb.graph.node if n.op_type == "Conv"]
print(f"A Conv nodes: {len(conv_a)}, B Conv nodes: {len(conv_b)}")

if len(conv_a) != len(conv_b):
    print("Conv node counts differ -> models are structurally different")
    sys.exit(1)


def tensor(init, name):
    if name not in init:
        return None
    t = init[name]
    if t.raw_data:
        import onnx.numpy_helper

        return onnx.numpy_helper.to_array(t)
    return None


diffs = 0
max_diff = 0.0
for (_, ia), (_, ib) in zip(conv_a, conv_b):
    wa = tensor(init_a, ia[1])
    wb = tensor(init_b, ib[1])
    if wa is None or wb is None:
        print(f"  missing weight {ia[1]} / {ib[1]}")
        diffs += 1
        continue
    if wa.shape != wb.shape:
        print(f"  shape mismatch {wa.shape} vs {wb.shape}")
        diffs += 1
        continue
    d = float(np.abs(wa.astype(np.float64) - wb.astype(np.float64)).max())
    max_diff = max(max_diff, d)
    if d > 1e-6:
        diffs += 1
        if diffs <= 5:
            print(f"  Conv weight {ia[1]}: max diff {d:.6f}")

print(f"\nConv weights differing (>1e-6): {diffs}/{len(conv_a)}")
print(f"overall max weight diff: {max_diff:.8f}")
