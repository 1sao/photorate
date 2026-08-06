"""Which ops have non-initializer shape-parameter inputs that keep dims dynamic?"""
import sys
import onnx
from collections import Counter

m = onnx.load(sys.argv[1])
g = m.graph
inits = {i.name for i in g.initializer}
producers = {}
for n in g.node:
    for o in n.output:
        producers[o] = n

# op -> set of input slots that are shape/parameter inputs (int64, value-driven)
SHAPE_PARAM_SLOTS = {
    'Expand': {1},
    'Reshape': {1},
    'ConstantOfShape': {0},
    'Slice': {1, 2, 3, 4},
    'Range': {0, 1, 2},
    'Tile': {1},
    'Pad': {1},
    'OneHot': {1, 2},
    'TopK': {1},
    'MaxPool': set(),  # attrs
    'Upsample': {1},
    'Resize': {1, 2, 3},
    'CumSum': {1},
    'DepthToSpace': set(),
    'ScatterND': {1},
    'Gather': {1},
}

found = []
for n in g.node:
    slots = SHAPE_PARAM_SLOTS.get(n.op_type)
    if not slots:
        continue
    for idx in slots:
        if idx < len(n.input) and n.input[idx] and n.input[idx] not in inits:
            src = producers.get(n.input[idx])
            found.append((n.op_type, n.name, idx, n.input[idx], src.op_type if src else '?'))

print(f'total nodes: {len(g.node)}, inits: {len(inits)}')
print(f'non-constant shape-param inputs: {len(found)}')
cnt = Counter((op, src) for op, _, _, _, src in found)
for (op, src), c in cnt.most_common():
    print(f'  {op} <- {src}: {c}')
for row in found[:15]:
    print('   ', row[0], row[1][:55], 'slot', row[2], row[3][:50], 'from', row[4])
