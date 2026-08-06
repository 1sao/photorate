"""Which ops does graphsurgeon fail to infer, and does onnx shape inference see 0 unknowns?"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import onnx
import onnx_graphsurgeon as gs
import convert_clip as cc

# Rebuild the staged model in-process and save it (the pipeline aborted before save).
src = Path('ml/original_models/vision_model_fp16/vision_model_fp16.onnx')
model = onnx.load(src)
g = model.graph
cc._cast_fp16_inits_and_constants(g)
dt = cc._tensor_dtypes(g)
for sub in cc._subgraphs(g):
    dt.update(cc._tensor_dtypes(sub))
cc._strip_fp16_casts(g, dt)
for sub in cc._subgraphs(g):
    cc._strip_fp16_casts(sub, dt)
cc._drop_value_info(g)
cc.resolve_if_nodes(model, take_then=True)
cc.fix_dynamic_shapes(model, {'pixel_values': [1, 3, 224, 224]})
onnx.save(model, '/tmp/clip_staged.onnx')
print('saved /tmp/clip_staged.onnx')

# 1) ONNX shape inference unknown-dim count.
mi = onnx.shape_inference.infer_shapes(model, strict_mode=False)
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
            if len(unknown_tensors) < 10:
                unknown_tensors.append(vi.name)
print(f'onnx shape inference: total dims={total} unknown={unk}')
print('  unknown tensors:', unknown_tensors)

# 2) Which ops does graphsurgeon fail to infer (opset 12)?
gg = gs.import_onnx(mi)
gg.opset = 13  # try forcing newer opset handling
try:
    gg.infer_shapes()
    print('gs.infer_shapes OK with opset 13')
except Exception as exc:
    print('gs.infer_shapes FAILED (opset 13):', str(exc)[:200])

# Fall back: report shapes gs DOES know along the qkv path.
gg2 = gs.import_onnx(mi)
try:
    gg2.infer_shapes()
except Exception:
    pass
node = next((n for n in gg2.nodes if 'qkv_proj/MatMul' in n.name), None)
if node is not None:
    print('qkv MatMul input shape in gs view:', node.inputs[0].shape)
    print('qkv MatMul output shape in gs view:', node.outputs[0].shape)
