#!/usr/bin/env python3
"""Cut the combined MobileCLIP-S1 tflite into separate text and vision models.

The combined graph (ml/original_models/mobileclip2_tflite/
mobileclip_s1_datacompdr_last.tflite) contains BOTH CLIP towers in one flatbuffer
subgraph. They are fully independent: the text tower (input_ids -> text
embedding) never touches the pixels tensor and the vision tower (pixel_values ->
image embedding) never touches the token ids (verified: zero shared ops). This
matters on-device because the ClGl accelerator compiles the vision tower but
rejects CAST + EMBEDDING_LOOKUP at the head of the text path, so a CPU+GPU
hybrid runs text on CPU and vision on GPU. Cutting the graph into two
single-tower tflites lets each path be benchmarked (and, if desired, shipped)
independently.

Surgery notes (tflite flatbuffer, tensorflow.lite.python.schema_py_generated):
- Traverse backwards from a tower's output tensor; keep every op that produces
  a needed tensor, and keep ALL of a kept op's outputs (multi-output ops must
  keep their full output list or the runtime misinterprets them).
- Reindex kept tensors + the buffers they reference; rewrite the signature's
  tensor indices; drop metadata (the TFLITE_METADATA blob index would point at
  the wrong renumbered buffer). No builtin option in this graph embeds tensor
  indices, so per-op builtinOptions bytes carry over untouched.

Each cut is self-verified on host: run the cut model and the source combined
model through tf.lite.Interpreter with identical inputs and require bitwise
equal outputs.

Usage:
  ml/litert/.venv/bin/python ml/litert/scripts/cut_clip_s1.py [MODEL ...]
MODEL defaults to the fp32 original; pass a quantized variant to cut that one.
The variant tag (f32/f16/i8) is derived from the filename. Outputs go to
ml/litert/bench/clip_s1_{text,vision}_<tag>.tflite.
"""

import sys
from pathlib import Path

import flatbuffers
import numpy as np
import tensorflow as tf
from tensorflow.lite.python import schema_py_generated as fb

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "ml/original_models/mobileclip2_tflite/mobileclip_s1_datacompdr_last.tflite"
OUT_DIR = ROOT / "ml/litert/bench"

TEXT_OUT = 1671  # StatefulPartitionedCall:1 (text embedding)
VISION_OUT = 972  # StatefulPartitionedCall:0 (image embedding)
TEXT_IN = 1  # args_1 (token ids)
VISION_IN = 0  # args_0 (pixel values)

# Original signature entries, keyed by graph tensor index.
SIG = {
    "inputs": {0: "args_0", 1: "args_1"},
    "outputs": {972: "output_0", 1671: "output_1"},
}


def variant_tag(src: Path) -> str:
    name = src.stem.lower()
    if "_f16" in name:
        return "f16"
    if "_i8" in name:
        return "i8"
    return "f32"


def reachable(sg, targets):
    """All tensors reachable backwards from [targets]; kept ops keep ALL outputs."""
    prod = {}
    for oi, op in enumerate(sg.operators):
        for o in op.outputs:
            prod.setdefault(o, []).append(oi)
    seen = set()
    q = list(targets)
    while q:
        t = q.pop()
        if t in seen:
            continue
        seen.add(t)
        for oi in prod.get(t, []):
            op = sg.operators[oi]
            for o in op.outputs:  # multi-output ops keep their full output list
                if o not in seen:
                    q.append(o)
            for i in list(op.inputs) + (list(op.intermediates) if op.intermediates else []):
                if i is not None and i >= 0 and i not in seen:
                    q.append(i)
    return seen


def build_cut(mt, kept, graph_inputs, graph_outputs, sig_entries, out_path):
    sg = mt.subgraphs[0]
    ordered = sorted(kept)
    tmap = {old: new for new, old in enumerate(ordered)}

    # Buffers: keep only the ones referenced by kept tensors (renumbered).
    bmap = {}
    new_buffers = []
    for old_t in ordered:
        b = sg.tensors[old_t].buffer
        if b not in bmap:
            bmap[b] = len(new_buffers)
            new_buffers.append(mt.buffers[b])

    new_tensors = []
    for old_t in ordered:
        st = sg.tensors[old_t]
        nt = fb.TensorT()
        nt.name = st.name
        nt.shape = st.shape
        nt.type = st.type
        nt.buffer = bmap[st.buffer]
        nt.hasRank = st.hasRank
        nt.shapeSignature = st.shapeSignature
        nt.isVariable = st.isVariable
        nt.quantization = st.quantization
        nt.sparsity = st.sparsity
        nt.variantTensors = st.variantTensors
        new_tensors.append(nt)

    kept_ops = [(oi, op) for oi, op in enumerate(sg.operators)
                if any(o in kept for o in op.outputs)]
    opcode_map = {}
    new_opcodes = []
    new_ops = []
    for _, op in kept_ops:
        if op.opcodeIndex not in opcode_map:
            opcode_map[op.opcodeIndex] = len(new_opcodes)
            new_opcodes.append(mt.operatorCodes[op.opcodeIndex])
        no = fb.OperatorT()
        no.opcodeIndex = opcode_map[op.opcodeIndex]
        no.inputs = [tmap[i] if i >= 0 else -1 for i in op.inputs]
        no.outputs = [tmap[i] for i in op.outputs]
        no.intermediates = [tmap[i] for i in op.intermediates] if op.intermediates else None
        no.builtinOptions = op.builtinOptions
        no.builtinOptionsType = op.builtinOptionsType
        no.builtinOptions2 = op.builtinOptions2
        no.builtinOptions2Type = op.builtinOptions2Type
        no.customOptions = op.customOptions
        no.customOptionsFormat = op.customOptionsFormat
        no.largeCustomOptionsOffset = op.largeCustomOptionsOffset
        no.largeCustomOptionsSize = op.largeCustomOptionsSize
        no.mutatingVariableInputs = op.mutatingVariableInputs
        no.debugMetadataIndex = op.debugMetadataIndex
        new_ops.append(no)

    nsg = fb.SubGraphT()
    nsg.name = sg.name
    nsg.tensors = new_tensors
    nsg.operators = new_ops
    nsg.inputs = [tmap[i] for i in graph_inputs]
    nsg.outputs = [tmap[i] for i in graph_outputs]
    nsg.debugMetadataIndex = sg.debugMetadataIndex

    nsig = fb.SignatureDefT()
    nsig.signatureKey = mt.signatureDefs[0].signatureKey
    nsig.subgraphIndex = 0
    nsig.inputs = []
    for i in graph_inputs:
        m = fb.TensorMapT()
        m.name = sig_entries["inputs"][i].encode()
        m.tensorIndex = tmap[i]
        nsig.inputs.append(m)
    nsig.outputs = []
    for i in graph_outputs:
        m = fb.TensorMapT()
        m.name = sig_entries["outputs"][i].encode()
        m.tensorIndex = tmap[i]
        nsig.outputs.append(m)

    nm = fb.ModelT()
    nm.version = mt.version
    nm.description = mt.description
    nm.operatorCodes = new_opcodes
    nm.subgraphs = [nsg]
    nm.buffers = new_buffers
    nm.signatureDefs = [nsig]
    # metadata dropped deliberately: metadataBuffer holds indices into the
    # original buffer list, which is renumbered here.

    builder = flatbuffers.Builder(0)
    root = nm.Pack(builder)
    builder.Finish(root, file_identifier=b"TFL3")  # tflite flatbuffer identifier
    out_path.write_bytes(bytes(builder.Output()))
    return len(kept_ops), len(ordered), len(new_buffers)


def host_verify(src: Path, cut_text: Path, cut_vision: Path) -> bool:
    """Run cut models + the source combined model on identical inputs; bitwise-compare."""
    rng = np.random.default_rng(0)
    pixels = rng.random((1, 3, 256, 256), dtype=np.float32)
    ids = np.zeros((1, 77), dtype=np.int64)
    ids[0, 0] = 49406
    ids[0, 1] = 320  # a real token id (bpe merges well inside the vocab)

    combined = tf.lite.Interpreter(model_path=str(src))
    combined.allocate_tensors()
    c_in = [i["index"] for i in combined.get_input_details()]
    c_out = [o["index"] for o in combined.get_output_details()]
    combined.set_tensor(c_in[0], pixels)
    combined.set_tensor(c_in[1], ids)
    combined.invoke()
    ref_text = combined.get_tensor(c_out[0])
    ref_image = combined.get_tensor(c_out[1])

    ok = True
    for cut, feed, ref, label in [
        (cut_text, [("int64", ids)], ref_text, "text"),
        (cut_vision, [("float32", pixels)], ref_image, "vision"),
    ]:
        interp = tf.lite.Interpreter(model_path=str(cut))
        interp.allocate_tensors()
        in_idx = [i["index"] for i in interp.get_input_details()]
        assert len(in_idx) == 1, f"{label} cut has {len(in_idx)} inputs"
        interp.set_tensor(in_idx[0], feed[0][1])
        interp.invoke()
        got = interp.get_tensor(interp.get_output_details()[0]["index"])
        same = np.array_equal(got, ref)
        maxdiff = float(np.max(np.abs(got - ref))) if not same else 0.0
        ok &= same
        print(f"  host parity {label:<8} bitwise={same} max_abs_diff={maxdiff:.3e}")
    return ok


def cut(src: Path) -> bool:
    tag = variant_tag(src)
    with open(src, "rb") as f:
        buf = bytearray(f.read())
    model = fb.Model.GetRootAsModel(buf, 0)
    mt = fb.ModelT.InitFromObj(model)
    sg = mt.subgraphs[0]

    text_kept = reachable(sg, [TEXT_OUT])
    vision_kept = reachable(sg, [VISION_OUT])
    assert TEXT_IN in text_kept and VISION_IN not in text_kept, "text cut leaked pixels"
    assert VISION_IN in vision_kept and TEXT_IN not in vision_kept, "vision cut leaked ids"
    assert not any(sg.tensors[t].isVariable for t in text_kept | vision_kept), \
        "variable tensors in cut — surgery does not handle graph state"

    out_dir = OUT_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    text_path = out_dir / f"clip_s1_text_{tag}.tflite"
    vision_path = out_dir / f"clip_s1_vision_{tag}.tflite"

    n_ops, n_t, n_b = build_cut(mt, text_kept, [TEXT_IN], [TEXT_OUT], SIG, text_path)
    print(f"[{tag}] text  : ops={n_ops} tensors={n_t} buffers={n_b} -> {text_path.name} ({text_path.stat().st_size / 1e6:.1f} MB)")
    n_ops, n_t, n_b = build_cut(mt, vision_kept, [VISION_IN], [VISION_OUT], SIG, vision_path)
    print(f"[{tag}] vision: ops={n_ops} tensors={n_t} buffers={n_b} -> {vision_path.name} ({vision_path.stat().st_size / 1e6:.1f} MB)")

    return host_verify(src, text_path, vision_path)


def main() -> None:
    sources = [Path(a) for a in sys.argv[1:]] or [SRC]
    all_ok = True
    for src in sources:
        print(f"=== cutting {src.name} ===")
        all_ok &= cut(src)
    print("\nALL CUTS VERIFIED" if all_ok else "\nSOME CUTS FAILED PARITY")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
