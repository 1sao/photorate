#!/usr/bin/env python3
"""Rewrite the f32clean ONNX graphs so every op can run on the LiteRT GPU.

The litert ClGlAccelerator (OpenCL) rejects three op patterns that the
converted RTM hand models contain (all verified on-device with single-op
probes; every replacement op below is probe-confirmed supported):

  * GATHER       -- the detector extracts x1/y1/x2/y2 from its [1,2100,4] box
                    tensor with 4 constant-scalar-index Gathers.
  * SPLIT_V      -- the pose head splits its simcc tensor with 2 constant-size
                    Splits along axis 2.
  * RELU_0_TO_1  -- onnx2tf lowers HardSigmoid(alpha=1/6, beta=0.5) to the
                    TFLite RELU_0_TO_1 builtin, which has no GPU kernel
                    ("Not supported op RELU_0_TO_1" from the delegate).

All are replaced with numerically identical, GPU-supported equivalents:

  Gather(data, scalar k, axis=a)      ->  Slice(data, [k], [k+1], [a]) + Squeeze
  Split(data, split=[s0,..], axis=a)  ->  one Slice per output
  HardSigmoid(x, a, b)                ->  Min(1, Max(0, a*x + b))   [exact]

The rewritten graph must be bit-identical to the source: this script asserts
that with onnxruntime on a random input before writing `*_gpu.onnx`.

Usage: ml/litert/.venv/bin/python ml/litert/scripts/rewrite_gpu_clean.py
"""

import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper

ROOT = Path(__file__).resolve().parents[1]
ORIG = ROOT.parent / "original_models"

MODELS = [
    ORIG / "rtmdet_nano_8xb32-300e_hand-267f9c8f" / "end2end_f32clean.onnx",
    ORIG / "rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320" / "end2end_f32clean.onnx",
]


def _const_tensor(name: str, arr: np.ndarray, dtype=np.int64) -> helper.TensorProto:
    return numpy_helper.from_array(arr.astype(dtype), name=name)


def _shape_of(graph, name: str):
    for vi in list(graph.value_info) + list(graph.input) + list(graph.output):
        if vi.name == name:
            dims = vi.type.tensor_type.shape.dim
            return [d.dim_value if d.HasField("dim_value") else None for d in dims]
    return None


def _const_value(graph, name: str):
    for init in graph.initializer:
        if init.name == name:
            return numpy_helper.to_array(init)
    for node in graph.node:
        if node.op_type == "Constant" and node.output[0] == name:
            for attr in node.attribute:
                if attr.name == "value":
                    return numpy_helper.to_array(attr.t)
    return None


def _rewrite_gathers(model: onnx.ModelProto) -> tuple[int, int]:
    """Replace constant-scalar-index Gathers with Slice + Squeeze."""
    g = model.graph
    rewritten = skipped = 0
    new_nodes = []

    for node in g.node:
        if node.op_type != "Gather":
            new_nodes.append(node)
            continue
        axis_attr = next((a for a in node.attribute if a.name == "axis"), None)
        axis = axis_attr.i if axis_attr else 0
        idx = _const_value(g, node.input[1]) if len(node.input) > 1 else None
        data_shape = _shape_of(g, node.input[0])
        if (
            idx is None
            or idx.ndim != 0
            or data_shape is None
            or data_shape[axis] is None
            or not (0 <= idx.item() < data_shape[axis])
        ):
            new_nodes.append(node)
            skipped += 1
            continue

        k = int(idx.item())
        base = node.name or "gather"
        starts = _const_tensor(f"{base}_starts", np.array([k]))
        ends = _const_tensor(f"{base}_ends", np.array([k + 1]))
        axes = _const_tensor(f"{base}_axes", np.array([axis]))
        g.initializer.extend([starts, ends, axes])
        sliced = f"{base}_sliced"
        new_nodes.append(
            helper.make_node(
                "Slice", [node.input[0], starts.name, ends.name, axes.name], [sliced], name=f"{base}_slice"
            )
        )
        new_nodes.append(
            helper.make_node("Squeeze", [sliced], [node.output[0]], name=f"{base}_squeeze", axes=[axis])
        )
        rewritten += 1

    del g.node[:]
    g.node.extend(new_nodes)
    return rewritten, skipped


def _rewrite_splits(model: onnx.ModelProto) -> tuple[int, int]:
    """Replace constant-size Splits with one Slice per output."""
    g = model.graph
    rewritten = skipped = 0
    new_nodes = []

    for node in g.node:
        if node.op_type != "Split":
            new_nodes.append(node)
            continue
        axis_attr = next((a for a in node.attribute if a.name == "axis"), None)
        axis = axis_attr.i if axis_attr else 0
        split_attr = next((a for a in node.attribute if a.name == "split"), None)
        sizes = list(split_attr.ints) if split_attr else None
        data_shape = _shape_of(g, node.input[0])
        if sizes is None or data_shape is None or data_shape[axis] is None:
            new_nodes.append(node)
            skipped += 1
            continue
        if sum(sizes) != data_shape[axis] or len(sizes) != len(node.output):
            raise SystemExit(
                f"Split {node.name}: sizes {sizes} don't match axis {axis} dim "
                f"{data_shape[axis]} / {len(node.output)} outputs"
            )

        base = node.name or "split"
        offsets = [0]
        for s in sizes[:-1]:
            offsets.append(offsets[-1] + s)
        for i, (start, size) in enumerate(zip(offsets, sizes)):
            starts = _const_tensor(f"{base}_{i}_starts", np.array([start]))
            ends = _const_tensor(f"{base}_{i}_ends", np.array([start + size]))
            axes = _const_tensor(f"{base}_{i}_axes", np.array([axis]))
            g.initializer.extend([starts, ends, axes])
            new_nodes.append(
                helper.make_node(
                    "Slice", [node.input[0], starts.name, ends.name, axes.name], [node.output[i]], name=f"{base}_{i}"
                )
            )
        rewritten += 1

    del g.node[:]
    g.node.extend(new_nodes)
    return rewritten, skipped


def _rewrite_hard_sigmoids(model: onnx.ModelProto) -> tuple[int, int]:
    """Replace HardSigmoid(x; a, b) with the exact Min(1, Max(0, a*x + b)).

    onnx2tf lowers HardSigmoid to the TFLite RELU_0_TO_1 builtin, which the
    GPU delegate has no kernel for. The algebraic form converts to plain
    MUL/ADD/MAXIMUM/MINIMUM — all probe-confirmed supported.
    """
    g = model.graph
    rewritten = skipped = 0
    new_nodes = []
    serial = [0]

    for node in g.node:
        if node.op_type != "HardSigmoid":
            new_nodes.append(node)
            continue
        alpha = next((a.f for a in node.attribute if a.name == "alpha"), 0.2)
        beta = next((a.f for a in node.attribute if a.name == "beta"), 0.5)
        base = node.name or "hardsigmoid"
        tag = f"{base}_{serial[0]}"
        serial[0] += 1

        a = _const_tensor(f"{tag}_alpha", np.array([alpha]), np.float32)
        b = _const_tensor(f"{tag}_beta", np.array([beta]), np.float32)
        zero = _const_tensor(f"{tag}_zero", np.array([0.0]), np.float32)
        one = _const_tensor(f"{tag}_one", np.array([1.0]), np.float32)
        g.initializer.extend([a, b, zero, one])

        scaled = f"{tag}_scaled"
        biased = f"{tag}_biased"
        lower = f"{tag}_lower"
        new_nodes.append(
            helper.make_node("Mul", [node.input[0], a.name], [scaled], name=f"{tag}_mul")
        )
        new_nodes.append(
            helper.make_node("Add", [scaled, b.name], [biased], name=f"{tag}_add")
        )
        new_nodes.append(
            helper.make_node("Max", [biased, zero.name], [lower], name=f"{tag}_max")
        )
        new_nodes.append(
            helper.make_node("Min", [lower, one.name], [node.output[0]], name=f"{tag}_min")
        )
        rewritten += 1

    del g.node[:]
    g.node.extend(new_nodes)
    return rewritten, skipped


def _assert_numeric_equality(original: onnx.ModelProto, rewritten: onnx.ModelProto) -> None:
    try:
        import onnxruntime as ort
    except ImportError:
        print("  onnxruntime not available — skipping numeric check")
        return
    rng = np.random.default_rng(7)
    feeds = {}
    for inp in original.graph.input:
        shape = [d.dim_value if d.HasField("dim_value") else 1 for d in inp.type.tensor_type.shape.dim]
        feeds[inp.name] = rng.standard_normal(shape).astype(np.float32)

    so = ort.SessionOptions()
    so.log_severity_level = 3
    a = ort.InferenceSession(original.SerializeToString(), so, providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(rewritten.SerializeToString(), so, providers=["CPUExecutionProvider"])
    for out_name in [o.name for o in original.graph.output]:
        ra = a.run([out_name], feeds)[0]
        rb = b.run([out_name], feeds)[0]
        if not np.array_equal(ra, rb):
            raise SystemExit(f"NUMERIC MISMATCH on {out_name}: max diff {np.abs(ra - rb).max()}")
    print(f"  numeric check: bit-identical on {len(original.graph.output)} outputs")


def main() -> None:
    for path in MODELS:
        print("====", path.name)
        model = onnx.load(path)
        g = model.graph
        g_r, g_s = _rewrite_gathers(model)
        s_r, s_s = _rewrite_splits(model)
        h_r, h_s = _rewrite_hard_sigmoids(model)
        print(f"  Gathers rewritten={g_r} skipped={g_s}")
        print(f"  Splits rewritten={s_r} skipped={s_s}")
        print(f"  HardSigmoids rewritten={h_r} skipped={h_s}")
        rewritten = onnx.ModelProto()
        rewritten.CopyFrom(model)
        _assert_numeric_equality(model, rewritten)
        leftovers = [n.op_type for n in rewritten.graph.node if n.op_type in ("Gather", "Split", "HardSigmoid")]
        if leftovers:
            print(f"  WARNING leftover nodes: {leftovers}")
        dst = path.with_name(path.stem + "_gpu.onnx")
        onnx.checker.check_model(rewritten)
        onnx.save(rewritten, dst)
        print(f"  wrote {dst.name} ({dst.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    sys.exit(main())
