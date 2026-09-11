#!/usr/bin/env python3
"""Convert the MobileCLIP vision + text ONNX models to float32 LiteRT .tflite.

Part of the ml/litert/ model recipe — the gpu-clean-conversion skill applied to
the app's MobileCLIP search models. The originals in ml/original_models are
READ-ONLY inputs; nothing here modifies them.

Sources (confirmed-source MobileCLIP exports, fp16 weights / fp32 I/O):
  ml/original_models/vision_model_fp16/vision_model_fp16.onnx
      input:  pixel_values f32 [?, 3, 224, 224]   (NCHW)
      output: image_embeds  f32 [?, 512]
  ml/original_models/text_model_fp16/text_model_fp16.onnx
      input:  input_ids     i64 [?, ?]   (batch, seq-len — both dynamic)
      output: text_embeds   f32 [?, 512]

Why the fp32 staging step (ONNX -> ONNX surgery, then onnx2tf):
  MobileCLIP is a mixed-precision graph: fp16 initializers/constants, 76
  explicit Cast->fp16 ops, and an `If(Equal(shape[0]==1))` conditional that
  squeezes the singleton batch dim. onnx2tf's handling of these fp16 ops
  (`DT_HALF`) misfires on the pos_embed constant-folded Reshape (bogus
  wa/Squeeze) and on mixed-dtype Pow(x=f32, y=f16), and it cannot convert the
  `If` control-flow at all. The staging step casts every fp16 weight/constant
  to fp32, strips every fp16 Cast, resolves the static `If` to its taken
  branch (batch is pinned to 1), drops stale value_info, and freezes every
  dynamic dimension — yielding a pure float32, fully-static graph that
  converts cleanly. This matches the RTM recipe, which also converts from
  float32 ONNX.

Why -dsm: with a constant-only Reshape (learned pos_embed), onnx2tf's strict
accuracy-correction workaround tries to build a Keras Functional model whose
output is a constant, which crashes. -dsm disables exactly that validation.

Usage:
  ml/litert/.venv/bin/python ml/litert/scripts/convert_clip.py

Staged fp32 exports (written, never overwrite sources):
  ml/original_models/onnx/models_fp32/clip_vision_fp32.onnx
  ml/original_models/onnx/models_fp32/clip_text_fp32.onnx

Outputs (in ml/litert/converted/):
  clip_vision_f32.tflite
  clip_text_f32.tflite
"""

import numpy as np
import onnx
import onnxruntime as ort
import os
import shutil
import subprocess
import tempfile
from onnx import helper, numpy_helper
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]  # ml/litert
ORIG = ROOT.parent / "original_models"  # ml/original_models
FP32_DIR = ROOT.parent / "original_models" / "onnx" / "models_fp32"  # ml/original_models/onnx/models_fp32
OUT = ROOT / "converted"
VENV_BIN = ROOT / ".venv" / "bin"

MODELS = [
    (
        ORIG / "vision_model_fp16" / "vision_model_fp16.onnx",
        FP32_DIR / "clip_vision_fp32.onnx",
        OUT / "clip_vision_f32.tflite",
        "pixel_values",
        "1,3,224,224",
        ["-k", "pixel_values"],  # keep NCHW on the vision input
    ),
    (
        ORIG / "text_model_fp16" / "text_model_fp16.onnx",
        FP32_DIR / "clip_text_fp32.onnx",
        OUT / "clip_text_f32.tflite",
        "input_ids",
        "1,77",  # batch 1, seq-len 77 (the app's tokenizer contextLength)
        [],
    ),
]

FLOAT16 = onnx.TensorProto.FLOAT16
FLOAT = onnx.TensorProto.FLOAT
INT64 = onnx.TensorProto.INT64


def _cast_fp16_inits_and_constants(g: onnx.GraphProto) -> int:
    """Cast fp16 initializers and Constant values to fp32 (lossless). Returns count."""
    n = 0
    for init in g.initializer:
        arr = numpy_helper.to_array(init)
        if arr.dtype == np.float16:
            init.CopyFrom(numpy_helper.from_array(arr.astype(np.float32), name=init.name))
            n += 1
    for node in g.node:
        if node.op_type == "Constant":
            for attr in node.attribute:
                if attr.name == "value" and attr.t.data_type == FLOAT16:
                    arr = numpy_helper.to_array(attr.t)
                    attr.t.CopyFrom(numpy_helper.from_array(arr.astype(np.float32)))
                    n += 1
        elif node.op_type == "If":
            for attr in node.attribute:
                if attr.name in ("then_branch", "else_branch"):
                    n += _cast_fp16_inits_and_constants(attr.g)
    return n


def _subgraphs(g: onnx.GraphProto):
    for node in g.node:
        if node.op_type == "If":
            for attr in node.attribute:
                if attr.name in ("then_branch", "else_branch"):
                    yield attr.g


BOOL = onnx.TensorProto.BOOL


def _tensor_dtypes(g: onnx.GraphProto) -> dict:
    """Map tensor name -> elem_type within one graph.

    Handles dtype-changing ops (Shape->int64, Equal->bool, ConstantOfShape,
    Where, Cast, Constant) explicitly; all other ops preserve their input
    dtype (first known input wins).
    """
    dt = {}
    for i in g.input:
        t = i.type.tensor_type
        if t.HasField("elem_type"):
            dt[i.name] = t.elem_type
    for init in g.initializer:
        dt[init.name] = init.data_type
    for n in g.node:
        if n.op_type == "Constant":
            for a in n.attribute:
                if a.name == "value":
                    dt[n.output[0]] = a.t.data_type
        elif n.op_type == "Cast":
            for a in n.attribute:
                if a.name == "to":
                    dt[n.output[0]] = a.i
        elif n.op_type == "Shape":
            dt[n.output[0]] = INT64
        elif n.op_type == "Equal":
            dt[n.output[0]] = BOOL
        elif n.op_type == "ConstantOfShape":
            val = next((a.t for a in n.attribute if a.name == "value"), None)
            dt[n.output[0]] = val.data_type if val is not None else FLOAT
        elif n.op_type == "Where":
            dt[n.output[0]] = dt.get(n.input[1], FLOAT)
    changed = True
    while changed:
        changed = False
        for n in g.node:
            if n.op_type in ("Constant", "Cast", "Shape", "Equal", "ConstantOfShape", "Where"):
                continue
            for o in n.output:
                if o in dt:
                    continue
                for inp in n.input:
                    if inp in dt:
                        dt[o] = dt[inp]
                        changed = True
                        break
    return dt


def _strip_fp16_casts(g: onnx.GraphProto, dt: dict) -> int:
    """Remove Cast nodes to/from fp16 (identity rewire). Returns count."""
    removed = 0
    for node in list(g.node):
        if node.op_type != "Cast":
            continue
        to = next((a.i for a in node.attribute if a.name == "to"), None)
        src = dt.get(node.input[0])
        if to != FLOAT16 and src != FLOAT16:
            continue
        out, inp = node.output[0], node.input[0]
        for other in g.node:
            if other is not node:
                other.input[:] = [inp if x == out else x for x in other.input]
        for go in g.output:
            if go.name == out:
                go.name = inp
        g.node.remove(node)
        removed += 1
    return removed


def _drop_value_info(g: onnx.GraphProto) -> None:
    """Drop stale fp16 type declarations from value_info (all nesting levels)."""
    del g.value_info[:]
    for sub in _subgraphs(g):
        _drop_value_info(sub)


def _const_value(g: onnx.GraphProto, name: str):
    for init in g.initializer:
        if init.name == name:
            return numpy_helper.to_array(init)
    for n in g.node:
        if n.op_type == "Constant" and n.output[0] == name:
            for a in n.attribute:
                if a.name == "value":
                    return numpy_helper.to_array(a.t)
    return None


def resolve_if_nodes(model: onnx.ModelProto, take_then: bool) -> int:
    """Replace every `If` with the chosen branch, inlined into the parent.

    MobileCLIP exports `If(Equal(shape[0]==1))` that conditionally squeezes the
    singleton batch dim out of the token sequence (`then`: Squeeze(axes=[1]);
    `else`: Identity). onnx2tf cannot convert `If` control flow at all. With
    the batch pinned to 1 the `then` branch always executes (verified by
    comparing staged vs original outputs afterwards; see prepare_fp32).
    Returns number of If nodes resolved.
    """
    g = model.graph
    resolved = 0
    new_nodes = []
    for node in g.node:
        if node.op_type != "If":
            new_nodes.append(node)
            continue
        attrs = {a.name: a.g for a in node.attribute}
        branch = attrs["then_branch"] if take_then else attrs["else_branch"]
        branch_nodes = list(branch.node)
        if len(branch_nodes) != 1:
            raise SystemExit(
                f"cannot inline multi-node If branch for {node.name} "
                f"({len(branch_nodes)} nodes); extend resolve_if_nodes"
            )
        clone = onnx.NodeProto()
        clone.CopyFrom(branch_nodes[0])
        clone.output[0] = node.output[0]
        new_nodes.append(clone)
        resolved += 1
    del g.node[:]
    g.node.extend(new_nodes)
    return resolved


def fix_dynamic_shapes(model: onnx.ModelProto, input_shapes: dict) -> int:
    """Freeze every dynamic dimension at the ONNX level; return Reshapes frozen.

    onnx2tf mishandles the MobileCLIP dynamic-batch Shape/Gather/Concat chains
    (it fabricates wa/Squeeze workarounds on `[?, ?, 768]` shapes that cannot
    squeeze). Since the app always runs batch 1 (and the text model a fixed
    seq-len 77), we pin the graph inputs, capture the real intermediate shapes
    with one ORT CPU run, then make every dimension statically known:

      * every `Shape` node is replaced by a Constant holding its runtime value
        (the cls-token `Expand`, the text causal-mask machinery, and other
        shape chains derive dims from `Shape` outputs; graphsurgeon's
        infer_shapes cannot fold them, leaving `unk__N` symbolic dims that
        make onnx2tf treat rank-3 transformer tensors as NCW images and
        transpose them into nonsense shapes like [1,2304,2304]);
      * every runtime-computed Reshape shape input is replaced by a Constant.

    Dead-on-the-data-path tensors (shape-machinery leftovers) get pruned by
    ORT, so the tensors we need are temporarily promoted to real graph outputs
    — ORT then must compute them.
    """
    g = model.graph
    inits = {i.name for i in g.initializer}
    for i in g.input:
        if i.name not in input_shapes:
            continue
        t = i.type.tensor_type
        del t.shape.dim[:]
        for d in input_shapes[i.name]:
            t.shape.dim.add().dim_value = d
    shape_outs = [n.output[0] for n in g.node if n.op_type == "Shape"]
    reshape_outs = [n.output[0] for n in g.node if n.op_type == "Reshape" and n.input[1] not in inits]
    dt = _tensor_dtypes(g)
    orig_out_count = len(g.output)
    existing = {o.name for o in g.output}
    for out in shape_outs + reshape_outs:
        if out not in existing:
            dtype = INT64 if out in shape_outs else dt.get(out, FLOAT)
            g.output.append(helper.make_tensor_value_info(out, dtype, None))
    feed = {}
    for i in g.input:
        if i.name not in input_shapes:
            continue
        shape = input_shapes[i.name]
        if i.type.tensor_type.elem_type == INT64:
            feed[i.name] = np.zeros(shape, dtype=np.int64)
        else:
            feed[i.name] = np.zeros(shape, dtype=np.float32)
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    sess = ort.InferenceSession(model.SerializeToString(), so, providers=["CPUExecutionProvider"])
    results = sess.run([o.name for o in g.output], feed)
    runtime = dict(zip([o.name for o in g.output], results))
    del g.output[orig_out_count:]  # remove temp outputs
    # Freeze Shape nodes -> Constant of their runtime value.
    frozen_shapes = 0
    for node in list(g.node):
        if node.op_type != "Shape" or node.output[0] not in runtime:
            continue
        val = np.asarray(runtime[node.output[0]]).astype(np.int64)
        const = numpy_helper.from_array(val, name=f"{node.name}_const")
        g.initializer.append(const)
        out = node.output[0]
        for other in g.node:
            if other is not node:
                other.input[:] = [const.name if x == out else x for x in other.input]
        for go in g.output:
            if go.name == out:
                go.name = const.name
        g.node.remove(node)
        frozen_shapes += 1
    # Freeze runtime-computed Reshape shape inputs -> Constant.
    replaced = 0
    for node in g.node:
        if node.op_type != "Reshape" or node.input[1] in {i.name for i in g.initializer}:
            continue
        out = node.output[0]
        if out not in runtime:
            raise SystemExit(f"no runtime shape captured for {node.name} output {out}")
        shape = list(runtime[out].shape)
        if any(d is None for d in shape):
            raise SystemExit(f"runtime shape of {node.name} still dynamic: {shape}")
        const = numpy_helper.from_array(np.array(shape, dtype=np.int64), name=f"{node.name}_shape")
        g.initializer.append(const)
        node.input[1] = const.name
        replaced += 1
    print(f"  froze {frozen_shapes} Shape nodes and {replaced} dynamic Reshape shapes")
    return replaced


def count_unknown_dims(model: onnx.ModelProto) -> int:
    """How many tensor dims remain symbolic/unknown after graphsurgeon inference.

    onnx2tf relies on graphsurgeon's own shape inference (it ignores ONNX
    value_info), so this is the number that decides whether its NCHW
    transposition heuristic can misfire on rank-3 transformer tensors.
    """
    import onnx_graphsurgeon as gs

    gg = gs.import_onnx(model)
    try:
        gg.infer_shapes()
    except Exception as exc:  # pragma: no cover - defensive
        raise SystemExit(f"graphsurgeon shape inference failed: {exc}") from exc
    unk = 0
    for t in gg.tensors().values():
        if t.shape is None:
            unk += 1
            continue
        for d in t.shape:
            if isinstance(d, str) or d is None:
                unk += 1
    return unk


def _make_feed(model: onnx.ModelProto, input_shapes: dict) -> dict:
    feed = {}
    for i in model.graph.input:
        if i.name not in input_shapes:
            continue
        shape = input_shapes[i.name]
        if i.type.tensor_type.elem_type == INT64:
            feed[i.name] = np.zeros(shape, dtype=np.int64)
        else:
            feed[i.name] = np.zeros(shape, dtype=np.float32)
    return feed


def _verify_against_original(src: Path, model: onnx.ModelProto, input_shapes: dict) -> bool:
    """Check the staged f32 model reproduces the original fp16 model's output.

    Runs both ONNX graphs on ORT CPU with identical frozen inputs and compares
    the final output (image_embeds / text_embeds) by shape + cosine similarity.
    f32-vs-fp16 rounding keeps cosine at ~0.999+, so 0.99 is a safe gate; a
    wrong If branch would change the shape entirely and fail instantly.
    """
    orig = onnx.load(src)
    feed = _make_feed(orig, input_shapes)
    ref = ort.InferenceSession(orig.SerializeToString(), providers=["CPUExecutionProvider"]).run(None, feed)[0]
    got = ort.InferenceSession(model.SerializeToString(), providers=["CPUExecutionProvider"]).run(None, feed)[0]
    if ref.shape != got.shape:
        print(f"  verify FAIL: output shape {got.shape} != original {ref.shape}")
        return False
    a = ref.astype(np.float32).reshape(-1)
    b = got.astype(np.float32).reshape(-1)
    cos = float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))
    ok = cos > 0.99
    print(f"  verify vs original: cosine {cos:.6f} (shape {ref.shape}) {'OK' if ok else 'FAIL'}")
    return ok


def prepare_fp32(src: Path, dst: Path, input_shapes: dict) -> None:
    """Stage a pure-float32, fully-static copy of a MobileCLIP fp16 ONNX graph."""
    model = onnx.load(src)
    g = model.graph
    cast_inits = _cast_fp16_inits_and_constants(g)
    dt = _tensor_dtypes(g)
    for sub in _subgraphs(g):
        dt.update(_tensor_dtypes(sub))
    stripped = _strip_fp16_casts(g, dt)
    for sub in _subgraphs(g):
        stripped += _strip_fp16_casts(sub, dt)
    # Stale value_info (top-level AND If-subgraph) declares fp16 where tensors
    # are now f32; drop it before any ORT run so types re-infer cleanly.
    _drop_value_info(g)
    resolved = resolve_if_nodes(model, take_then=True)
    if not _verify_against_original(src, model, input_shapes):
        print("  verify with then-branch failed; retrying with else-branch")
        model = onnx.load(src)
        g = model.graph
        _cast_fp16_inits_and_constants(g)
        dt = _tensor_dtypes(g)
        for sub in _subgraphs(g):
            dt.update(_tensor_dtypes(sub))
        _strip_fp16_casts(g, dt)
        for sub in _subgraphs(g):
            _strip_fp16_casts(sub, dt)
        _drop_value_info(g)
        resolve_if_nodes(model, take_then=False)
        if not _verify_against_original(src, model, input_shapes):
            raise SystemExit("staged fp32 model does not reproduce the original; aborting")
    frozen = fix_dynamic_shapes(model, input_shapes)
    unk = count_unknown_dims(model)
    if unk:
        raise SystemExit(f"staged model still has {unk} unknown dims; onnx2tf will misfire")
    print(
        f"  cast {cast_inits} fp16 weights, stripped {stripped} fp16 Casts, "
        f"resolved {resolved} If nodes, froze {frozen} dynamic Reshape shapes, "
        f"0 unknown dims after graphsurgeon inference"
    )
    dst.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, dst)
    print(f"staged fp32: {src.name} -> {dst.name} ({dst.stat().st_size / 1e6:.1f} MB)")


def convert(src: Path, dst: Path, keep: str, ois_shape: str, extra: list) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        env = dict(os.environ)
        env["TF_CPP_MIN_LOG_LEVEL"] = "2"
        env["ONNX2TF_QUIET"] = "1"
        # onnx2tf invokes `onnxsim` as a subprocess; it lives in the venv bin.
        env["PATH"] = f"{VENV_BIN}:{env.get('PATH', '')}"
        cmd = [
            str(VENV_BIN / "onnx2tf"),
            "-i", str(src),
            "-o", tmp,
            # Pin exact input shapes (batch 1; text seq-len 77). -b would
            # freeze ALL dynamic dims, collapsing the text model's seq-len to 1.
            "-ois", f"{keep}:{ois_shape}",
            "-dsm",  # skip strict accuracy-correction (crashes on const Reshape)
            "--copy_onnx_input_output_names_to_tflite",
        ] + extra
        subprocess.run(cmd, env=env, check=True)
        produced = [p for p in Path(tmp).glob("*_float32.tflite")]
        if len(produced) != 1:
            raise SystemExit(f"expected exactly one float32 tflite, got {produced}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(produced[0]), dst)
        print(f"{src.name} -> {dst.name} ({dst.stat().st_size / 1e6:.1f} MB)")


def main() -> None:
    for src, fp32, dst, keep, ois_shape, extra in MODELS:
        prepare_fp32(src, fp32, {keep: [int(x) for x in ois_shape.split(",")]})
        convert(fp32, dst, keep, ois_shape, extra)
    print("conversion done")


if __name__ == "__main__":
    main()
