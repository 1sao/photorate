"""Print how many dims onnx shape inference still sees as unknown."""
import sys
import onnx

path = sys.argv[1]
m = onnx.load(path)
mi = onnx.shape_inference.infer_shapes(m, strict_mode=False)
unk = total = 0
unknown_tensors = []
for vi in list(mi.graph.value_info) + list(mi.graph.input) + list(mi.graph.output):
    t = vi.type.tensor_type
    if not t.HasField('shape'):
        continue
    for d in t.shape.dim:
        total += 1
        if not d.HasField('dim_value'):
            unk += 1
            if len(unknown_tensors) < 8:
                unknown_tensors.append(vi.name)
print(f'{path}: total dims={total} unknown={unk}')
print('  unknown tensors:', unknown_tensors)
print('  nodes:', len(m.graph.node), 'inits:', len(m.graph.initializer))
