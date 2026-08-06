#!/usr/bin/env python3
"""Dump reference inputs/outputs from the RTM hand ONNX models (f32clean).

Ground truth for every later step (host verify, on-device verification).
Fixed inputs — one fixed-seed random tensor + one real preprocessed sample
(plans/samples/5/5_kenya.jpg) — run through onnxruntime CPU; inputs AND
outputs are saved as .npy next to the recipe. Regenerate only when the
source model changes.

Preprocessing mirrors the app's AndroidOnnxHandLandmarker exactly:
- detector: top-left letterbox to 320, gray-114 padding, RGB order,
  mmdet mean/std (123.675, 116.28, 103.53) / (58.395, 57.12, 57.375).
- pose: mmpose top-down affine to 256 (black fill), BGR order, same stats.

Usage: ml/litert/.venv/bin/python ml/litert/scripts/dump_refs.py
"""

import json

import cv2
import numpy as np
import onnxruntime as ort

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]          # ml/litert
ORIG = ROOT.parent / "original_models"              # ml/original_models
REFS = ROOT / "refs"
SAMPLE = ROOT.parents[1] / "plans" / "samples" / "5" / "5_kenya.jpg"

DET_ONNX = ORIG / "rtmdet_nano_8xb32-300e_hand-267f9c8f" / "end2end_f32clean.onnx"
POSE_ONNX = ORIG / "rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320" / "end2end_f32clean.onnx"

MEAN = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD = np.array([58.395, 57.12, 57.375], dtype=np.float32)

NMS_IOU = 0.6
NMS_SCORE = 0.05
NMS_MAX_OUT = 200


def to_chw_norm(hwc_bgr: np.ndarray, rgb_order: bool) -> np.ndarray:
    """(H,W,3) BGR uint8 -> (3,H,W) float32, mmdet mean/std normalization."""
    img = hwc_bgr.astype(np.float32)
    # channel k uses MEAN[k]/STD[k]; swap to RGB order only for rgb_order.
    if rgb_order:
        img = img[:, :, ::-1]
    img = (img - MEAN) / STD
    return np.transpose(img, (2, 0, 1))[None]  # (1,3,H,W)


def letterbox_top_left(img_bgr: np.ndarray, size: int, pad: int = 114) -> np.ndarray:
    h, w = img_bgr.shape[:2]
    ratio = min(size / w, size / h)
    nw, nh = max(int(w * ratio), 1), max(int(h * ratio), 1)
    resized = cv2.resize(img_bgr, (nw, nh), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((size, size, 3), pad, dtype=np.uint8)
    canvas[:nh, :nw] = resized
    return canvas


def top_down_affine(img_bgr: np.ndarray, size: int = 256) -> np.ndarray:
    """Port of the app's topDownAffine (mmpose top-down affine, rot 0)."""
    h, w = img_bgr.shape[:2]
    bbox_w, bbox_h = 1.25 * w, 1.25 * h
    scale = size / max(bbox_h * 0.75, bbox_w)
    cx, cy = w / 2.0, h / 2.0
    m = cv2.getRotationMatrix2D((cx, cy), 0, scale)
    m[0, 2] += size / 2.0 - cx
    m[1, 2] += size / 2.0 - cy
    return cv2.warpAffine(img_bgr, m, (size, size), flags=cv2.INTER_LINEAR, borderValue=(0, 0, 0))


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


def decode_simcc(simcc_x: np.ndarray, simcc_y: np.ndarray) -> np.ndarray:
    """(1,21,512) x2 -> (21,3) keypoints in 256-space (x, y, conf)."""
    sx = simcc_x[0]
    sy = simcc_y[0]
    xi = np.argmax(sx, axis=1)
    yi = np.argmax(sy, axis=1)
    xv = sx[np.arange(21), xi]
    yv = sy[np.arange(21), yi]
    kps = np.stack([xi / 2.0, yi / 2.0, np.minimum(xv, yv)], axis=1)
    return kps.astype(np.float32)


def dump_detector(sess: ort.InferenceSession, rng: np.random.Generator) -> None:
    real = cv2.imread(str(SAMPLE))
    assert real is not None, SAMPLE
    real_input = to_chw_norm(letterbox_top_left(real, 320), rgb_order=True)
    rand_input = rng.standard_normal((1, 3, 320, 320), dtype=np.float32)

    for tag, arr in (("rand", rand_input), ("real", real_input)):
        boxes, scores = sess.run(None, {"input": arr})
        scores_flat = scores.reshape(-1)
        keep = nms(boxes[0], scores_flat)
        ref = np.concatenate(
            [boxes[0][keep].reshape(-1, 4), scores_flat[keep].reshape(-1, 1)], axis=1
        )
        np.save(REFS / f"det_{tag}_input.npy", arr)
        np.save(REFS / f"det_{tag}_boxes.npy", boxes)
        np.save(REFS / f"det_{tag}_scores.npy", scores)
        np.save(REFS / f"det_{tag}_refboxes.npy", ref)
        print(f"det {tag}: {len(keep)} boxes after NMS")


def dump_pose(sess: ort.InferenceSession, rng: np.random.Generator) -> None:
    real = cv2.imread(str(SAMPLE))
    assert real is not None, SAMPLE
    real_input = to_chw_norm(top_down_affine(real), rgb_order=False)
    rand_input = rng.standard_normal((1, 3, 256, 256), dtype=np.float32)

    for tag, arr in (("rand", rand_input), ("real", real_input)):
        simcc_x, simcc_y = sess.run(None, {"input": arr})
        kps = decode_simcc(simcc_x, simcc_y)
        np.save(REFS / f"pose_{tag}_input.npy", arr)
        np.save(REFS / f"pose_{tag}_simcc_x.npy", simcc_x)
        np.save(REFS / f"pose_{tag}_simcc_y.npy", simcc_y)
        np.save(REFS / f"pose_{tag}_kps.npy", kps)
        print(f"pose {tag}: kp max conf {kps[:, 2].max():.3f}")

    np.save(REFS / "pose_real_ref_kps.npy", np.load(REFS / "pose_real_kps.npy"))


def main() -> None:
    REFS.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(42)
    det = ort.InferenceSession(str(DET_ONNX), providers=["CPUExecutionProvider"])
    pose = ort.InferenceSession(str(POSE_ONNX), providers=["CPUExecutionProvider"])
    dump_detector(det, rng)
    dump_pose(pose, rng)
    (REFS / "refs.json").write_text(
        json.dumps(
            {
                "sample": str(SAMPLE),
                "seed": 42,
                "nms": {"iou": NMS_IOU, "score_thr": NMS_SCORE, "max_out": NMS_MAX_OUT},
                "note": "inputs are the exact tensors fed to the models; "
                        "device tests must feed them verbatim",
            },
            indent=2,
        )
    )
    print("refs dumped to", REFS)


if __name__ == "__main__":
    main()
