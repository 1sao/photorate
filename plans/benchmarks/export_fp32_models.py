#!/usr/bin/env python3
"""Re-export the shipped ONNX models toward NNAPI-friendliness (2026-08-04).

Background: the Tensor G4's NNAPI driver (darwinn) rejects any non-float32
operand ("RET_CHECK input.type != TENSOR_FLOAT32"). The shipped mmdeploy
exports bake int64-heavy post-processing into the graphs:

  - rtmpose_hand.onnx — an INT64 bboxes_width_height input + int64 Slice
    params + ArgMax indices in the baked simcc decode. The input alone is
    trivially fixable (below); the internal int64 ops still poison the graph
    for darwinn (verified on-device: 0 TPU subgraphs even with the fp32
    input). Fully float32-izing requires moving the decode to the caller.
  - rtmdet_n_hand.onnx — a baked NonMaxSuppression chain (TopK/Gather on
    int64 indices). The NMS strip below moves it to the caller.

This script produces NEW files in onnx/models_fp32/ (the originals in
onnx/models/ are never modified):

  - rtmpose_hand_fp32.onnx — int64 bbox input -> float32, redundant Cast
    removed. Python parity: BIT-IDENTICAL outputs (156 crops, max diff 0.0),
    identical app-search winners on all 39 boxes. On-device with NNAPI it
    still gets 0 TPU subgraphs (int64 decode internals), so NNAPI remains
    slower than CPU — the value here is the recipe, not the win.
  - rtmdet_n_hand_fp32.onnx — NMS stripped; outputs raw boxes [1,2100,4] and
    scores [1,1,2100]. The caller must re-implement NMS with the baked
    params: score_thr 0.05, iou_thr 0.6, max 200, top 100. Python parity:
    caller-NMS reproduces every real detection exactly (the only difference
    is the original model's zero-pad filler row, which the app drops).

Run:  onnx/.venv/bin/python plans/benchmarks/export_fp32_models.py
Then: onnx/.venv/bin/python plans/benchmarks/parity_fp32_models.py
"""
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, shape_inference
from pathlib import Path

SRC = Path("onnx/models")
DST = Path("onnx/models_fp32")
DST.mkdir(exist_ok=True)


def export_rtmpose():
    """fp32 bbox input; remove the INT64->FLOAT Cast after Unsqueeze."""
    m = onnx.load(str(SRC / "rtmpose_hand.onnx"))
    g = m.graph
    for i in g.input:
        if i.name == "bboxes_width_height":
            i.type.tensor_type.elem_type = TensorProto.FLOAT
    cast = next((n for n in g.node
                 if n.op_type == "Cast" and "post_Cast_1_output_0" in list(n.output)),
                None)
    assert cast is not None
    rewired = 0
    for n in g.node:
        if n is cast:
            continue
        for k, inp in enumerate(n.input):
            if inp == "post_Cast_1_output_0":
                n.input[k] = "post_Unsqueeze_output_0"
                rewired += 1
    g.node.remove(cast)
    # Refresh stale value_info (post_Unsqueeze_output_0 was declared INT64).
    keep = [vi for vi in g.value_info if vi.name != "post_Unsqueeze_output_0"]
    del g.value_info[:]
    g.value_info.extend(keep)
    m = shape_inference.infer_shapes(m)
    onnx.checker.check_model(m)
    out = DST / "rtmpose_hand_fp32.onnx"
    onnx.save(m, str(out))
    s = ort.InferenceSession(str(out), providers=["CPUExecutionProvider"])
    print(f"saved {out}; inputs={[(i.name, i.type) for i in s.get_inputs()]} "
          f"(rewired {rewired} consumers)")


def export_rtmdet():
    """Cut at NonMaxSuppression's inputs; expose raw boxes + scores."""
    m = onnx.load(str(SRC / "rtmdet_n_hand.onnx"))
    g = m.graph
    nms = next(n for n in g.node if n.op_type == "NonMaxSuppression")
    boxes_t, scores_t = nms.input[0], nms.input[1]
    consumers = {}
    for n in g.node:
        for i in n.input:
            consumers.setdefault(i, []).append(n)
    to_remove = set()
    queue = list(nms.output)
    while queue:
        t = queue.pop(0)
        for n in consumers.get(t, []):
            if id(n) not in to_remove:
                to_remove.add(id(n))
                queue.extend(n.output)
    kept = [n for n in g.node if id(n) not in to_remove]
    del g.node[:]
    g.node.extend(kept)
    g.node.append(helper.make_node("Identity", [boxes_t], ["boxes"]))
    g.node.append(helper.make_node("Identity", [scores_t], ["scores"]))
    del g.output[:]
    g.output.extend([
        helper.make_tensor_value_info("boxes", TensorProto.FLOAT, [1, 2100, 4]),
        helper.make_tensor_value_info("scores", TensorProto.FLOAT, [1, 1, 2100]),
    ])
    m = shape_inference.infer_shapes(m)
    onnx.checker.check_model(m)
    out = DST / "rtmdet_n_hand_fp32.onnx"
    onnx.save(m, str(out))
    s = ort.InferenceSession(str(out), providers=["CPUExecutionProvider"])
    print(f"saved {out}; outputs={[o.name for o in s.get_outputs()]}")


if __name__ == "__main__":
    export_rtmpose()
    export_rtmdet()
    print("done — originals in onnx/models/ are untouched")
