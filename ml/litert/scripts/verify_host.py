#!/usr/bin/env python3
"""Host-side parity check: converted .tflite vs the ONNX reference dumps.

Runs every ref input through the tflite model via the ai-edge-litert
CompiledModel API on the CPU accelerator and compares against the .npy
references (correlation, max abs diff) plus the task gates (NMS box set for
the detector, decoded keypoints for the pose model).

Then runs the litert_gpu_toolkit GPU compatibility check (host GPU — a
failed host GPU compile does not fail the run on macOS hosts without GPU
support; the on-device run is the real gate).

Usage:
  ml/litert/.venv/bin/python ml/litert/scripts/verify_host.py [DET_MODEL [POSE_MODEL]]

The optional positional args pick the tflite variants to check (defaults to
the float32 shipped models) so the same harness verifies every quantized
variant (e.g. rtmdet_hand_320_f16.tflite rtmpose_hand_256_i8.tflite).
"""

import os
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
REFS = ROOT / "refs"
CONVERTED = ROOT / "converted"

NMS_IOU = 0.6
NMS_SCORE = 0.05
NMS_MAX_OUT = 200


def load_npy(name: str) -> np.ndarray:
    return np.load(REFS / name)


def corr(a: np.ndarray, b: np.ndarray) -> float:
    a = a.reshape(-1).astype(np.float64)
    b = b.reshape(-1).astype(np.float64)
    if a.std() == 0 and b.std() == 0:
        return 1.0
    return float(np.corrcoef(a, b)[0, 1])


def iou(a: np.ndarray, b: np.ndarray) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    inter_w = max(0.0, min(ax2, bx2) - max(ax1, bx1))
    inter_h = max(0.0, min(ay2, by2) - max(ay1, by1))
    inter = inter_w * inter_h
    area_a = (ax2 - ax1) * (ay2 - ay1)
    area_b = (bx2 - bx1) * (by2 - by1)
    return inter / (area_a + area_b - inter + 1e-9)


def nms(boxes: np.ndarray, scores: np.ndarray) -> list:
    order = np.argsort(-scores)
    keep = []
    for i in order:
        if scores[i] < NMS_SCORE:
            break
        if len(keep) >= NMS_MAX_OUT:
            break
        if any(iou(boxes[i], boxes[j]) > NMS_IOU for j in keep):
            continue
        keep.append(int(i))
    return keep


def _static(shape) -> list:
    return [int(s) if int(s) > 0 else 1 for s in shape]


def run_cpu(tflite: Path, input_npy: str):
    from ai_edge_litert.compiled_model import CompiledModel
    from ai_edge_litert.hardware_accelerator import HardwareAccelerator

    model = CompiledModel.from_file(str(tflite), hardware_accel=HardwareAccelerator.CPU)
    try:
        sigs = model.get_signature_list()
        sig = next(iter(sigs)) if sigs else ""
        in_details = model.get_input_tensor_details(sig)
        out_details = model.get_output_tensor_details(sig)
        inp = load_npy(input_npy)
        in_bufs = {
            name: model.create_input_buffer_by_name(sig, name) for name in in_details
        }
        out_bufs = {
            name: model.create_output_buffer_by_name(sig, name) for name in out_details
        }
        next(iter(in_bufs.values())).write(np.ascontiguousarray(inp))
        model.run_by_name(sig, in_bufs, out_bufs)
        actual = {}
        for name, detail in out_details.items():
            shape = _static(detail["shape"])
            actual[name] = out_bufs[name].read(
                int(np.prod(shape)), np.dtype(detail["dtype"])
            ).reshape(shape)
        return actual
    finally:
        model.close()


def friendly_actual(prefix: str, actual: dict) -> dict:
    """Map the tflite's generic output names to the reference file stems.

    The tflite output names are Identity/Identity_1 (no flatc name copying);
    map by shape for the detector (4-col boxes vs 1-col scores) and by output
    order for the pose model (simcc_x before simcc_y, same as the ONNX).
    """
    if prefix == "det":
        boxes_name = next(n for n, a in actual.items() if a.shape[-1] == 4)
        scores_name = next(n for n, a in actual.items() if a.shape[-1] == 1)
        return {"boxes": actual[boxes_name], "scores": actual[scores_name]}
    names = list(actual)
    return {"simcc_x": actual[names[0]], "simcc_y": actual[names[1]]}


def compare_outputs(tag: str, refs: dict, actual: dict) -> dict:
    row = {"tag": tag}
    for name, ref in refs.items():
        act = actual[name]
        if act.shape != ref.shape:
            act = act.reshape(ref.shape)
        row[f"{name}.corr"] = round(corr(ref, act), 6)
        row[f"{name}.max_abs_diff"] = float(np.abs(ref - act).max())
    return row


def verify_model(prefix: str, tflite: Path, stems: list) -> list:
    rows = []
    for tag in ("rand", "real"):
        actual = friendly_actual(prefix, run_cpu(tflite, f"{prefix}_{tag}_input.npy"))
        refs = {stem: load_npy(f"{prefix}_{tag}_{stem}.npy") for stem in stems}
        rows.append(compare_outputs(f"{prefix} {tag}", refs, actual))
    return rows


def det_task_gate(tflite: Path) -> dict:
    results = {}
    for tag in ("rand", "real"):
        actual = friendly_actual("det", run_cpu(tflite, f"det_{tag}_input.npy"))
        boxes = actual["boxes"].reshape(-1, 4)
        scores = actual["scores"].reshape(-1)
        keep = nms(boxes, scores)
        ref = load_npy(f"det_{tag}_refboxes.npy")
        results[tag] = {"keep": len(keep), "ref_keep": ref.shape[0]}
        if len(keep) == ref.shape[0]:
            results[tag]["box_max_diff"] = float(np.abs(boxes[keep] - ref[:, :4]).max())
        else:
            results[tag]["box_max_diff"] = None
    return results


def pose_task_gate(tflite: Path) -> dict:
    results = {}
    for tag in ("rand", "real"):
        actual = friendly_actual("pose", run_cpu(tflite, f"pose_{tag}_input.npy"))
        simcc_x, simcc_y = actual["simcc_x"], actual["simcc_y"]
        xi = np.argmax(simcc_x[0], axis=1)
        yi = np.argmax(simcc_y[0], axis=1)
        xv = simcc_x[0][np.arange(21), xi]
        yv = simcc_y[0][np.arange(21), yi]
        kps = np.stack([xi / 2.0, yi / 2.0, np.minimum(xv, yv)], axis=1).astype(np.float32)
        ref = load_npy(f"pose_{tag}_kps.npy")
        results[tag] = {
            "kp_max_px_diff": float(np.abs(kps[:, :2] - ref[:, :2]).max()),
            "conf_max_diff": float(np.abs(kps[:, 2] - ref[:, 2]).max()),
        }
    return results


def main() -> None:
    args = sys.argv[1:]
    det = CONVERTED / (args[0] if args else "rtmdet_hand_320_f32.tflite")
    pose = CONVERTED / (args[1] if len(args) > 1 else "rtmpose_hand_256_f32.tflite")
    for r in verify_model("det", det, ["boxes", "scores"]):
        print(r)
    for r in verify_model("pose", pose, ["simcc_x", "simcc_y"]):
        print(r)
    print("det task gate:", det_task_gate(det))
    print("pose task gate:", pose_task_gate(pose))

    try:
        sys.path.insert(
            0,
            os.environ.get(
                "LITERT_SAMPLES_UTILS",
                "/Users/ihp/AndroidStudioProjects/samples/litert-samples/utilities",
            ),
        )
        from litert_gpu_toolkit import check_gpu_compatibility, print_report
        for model in (det, pose):
            print(f"\n=== GPU compat: {model.name} ===")
            print_report(check_gpu_compatibility(str(model)))
    except Exception as e:  # noqa: BLE001
        print(f"gpu toolkit check skipped: {e}")


if __name__ == "__main__":
    main()
