"""Print ONNX model metadata for the models in this directory.

Usage:
    python view_model_metadata.py [model.onnx ...]

With no arguments, prints metadata for every *.onnx in this directory.

The metadata printed here (producer, opset, IR version, graph name, custom
metadata_props, input/output tensor shapes) lives in the ONNX protobuf
header, which is what the `onnx` package reads via `onnx.load()`. Note that
`onnxruntime` cannot expose this data (it only gives you a session's
get_modelmeta() with producer/domain and the runtime input/output specs).

Run with the project venv, which has the `onnx` package installed:
    onnx/.venv/bin/python onnx/models/view_model_metadata.py onnx/models/*.onnx
"""

import sys
from pathlib import Path

import onnx
from onnx import TensorProto


def tensor_info(t: onnx.ValueInfoProto) -> str:
    """Human-readable 'name: shape (element type)' for an input/output value."""
    tt = t.type.tensor_type
    dims = [
        d.dim_value if d.dim_value > 0 else str(d.dim_param)
        for d in tt.shape.dim
    ]
    return f"{t.name}: {dims} ({TensorProto.DataType.Name(tt.elem_type)})"


def describe(path: Path) -> None:
    """Load a single .onnx file and print its header + graph metadata."""
    model = onnx.load(path, load_external_data=False)
    print(f"=== {path} ===")
    print("Producer:", model.producer_name, model.producer_version)
    print("Domain:", model.domain)
    print("Model version:", model.model_version)
    print("Doc string:", model.doc_string)
    print("IR version:", model.ir_version)
    print("Opset imports:", [(o.domain, o.version) for o in model.opset_import])
    print("Graph name:", model.graph.name)
    print("Graph doc string:", model.graph.doc_string)
    print("Inputs:")
    for i in model.graph.input:
        print("  ", tensor_info(i))
    print("Outputs:")
    for o in model.graph.output:
        print("  ", tensor_info(o))
    print("Custom metadata:")
    for prop in model.metadata_props:
        print(f"  {prop.key}: {prop.value}")
    print()


if __name__ == "__main__":
    paths = [Path(a) for a in sys.argv[1:]]
    if not paths:
        paths = sorted(Path(__file__).parent.glob("*.onnx"))
    if not paths:
        print("No .onnx files found; pass model paths as arguments.", file=sys.stderr)
        sys.exit(1)
    for p in paths:
        describe(p)
