#!/usr/bin/env python3
"""Parity harness for the fp32 re-exports (see export_fp32_models.py).

  1. RTMPose fp32 vs original: max keypoint diff over all sample crops and
     the full app-search winner comparison. Expected: max diff 0.0, 0 diffs.
  2. RTMDet stripped + caller NMS vs the original baked NMS: real detections
     must match exactly (the original's zero-pad filler row is expected and
     ignored — the app drops it).

Run:  onnx/.venv/bin/python plans/benchmarks/parity_fp32_models.py
"""
import cv2
import numpy as np
import onnxruntime as ort
import sys
from pathlib import Path

sys.path.insert(0, "plans/rtmpose_hand")
from overlay_hands import (DET_SIZE, MEAN_RGB, STD_RGB, app_rotate_and_crop_square,
                           app_search, decode_image, detect_boxes, expand_square,
                           load_sessions, top_down_affine)

MODELS = Path("onnx/models")
FP32 = Path("onnx/models_fp32")
SAMPLES = Path("plans/samples")
det, pose_orig = load_sessions(MODELS)
pose_fp32 = ort.InferenceSession(str(FP32 / "rtmpose_hand_fp32.onnx"),
                                 providers=["CPUExecutionProvider"])
det_strip = ort.InferenceSession(str(FP32 / "rtmdet_n_hand_fp32.onnx"),
                                 providers=["CPUExecutionProvider"])
det_orig = ort.InferenceSession(str(MODELS / "rtmdet_n_hand.onnx"),
                                providers=["CPUExecutionProvider"])
SCORE_THR, IOU_THR, MAX_OUT, TOP_K = 0.05, 0.6, 200, 100


class FloatBboxShim:
    """Present the fp32 pose session under rtmpose_landmarks' int64-bbox
    calling convention (cast the bbox tensor to float32)."""

    def __init__(self, sess):
        self._s = sess

    def run(self, outputs, feed):
        feed = dict(feed)
        feed["bboxes_width_height"] = feed["bboxes_width_height"].astype(np.float32)
        return self._s.run(outputs, feed)


def pose_with(sess, crop, fp32):
    h, w = crop.shape[:2]
    warped = top_down_affine(crop)
    f = ((warped.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32)
    bbox = np.asarray([[w, h]], dtype=np.float32 if fp32 else np.int64)
    return sess.run(None, {"input": f, "bboxes_width_height": bbox})[0][0]


def caller_nms(boxes, scores):
    order = np.where(scores > SCORE_THR)[0]
    order = order[np.argsort(-scores[order])]
    keep = []
    while order.size and len(keep) < MAX_OUT:
        i = order[0]
        keep.append(i)
        if order.size == 1:
            break
        rest = order[1:]
        xx1 = np.maximum(boxes[i, 0], boxes[rest, 0])
        yy1 = np.maximum(boxes[i, 1], boxes[rest, 1])
        xx2 = np.minimum(boxes[i, 2], boxes[rest, 2])
        yy2 = np.minimum(boxes[i, 3], boxes[rest, 3])
        inter = np.maximum(0, xx2 - xx1) * np.maximum(0, yy2 - yy1)
        a_i = (boxes[i, 2] - boxes[i, 0]) * (boxes[i, 3] - boxes[i, 1])
        a_r = (boxes[rest, 2] - boxes[rest, 0]) * (boxes[rest, 3] - boxes[rest, 1])
        iou = inter / (a_i + a_r - inter + 1e-9)
        order = rest[iou < IOU_THR]
    return np.array(keep[:TOP_K])


def check_pose():
    maxdiff, worst = 0.0, None
    n_crops, winner_diffs, n_boxes = 0, 0, 0
    for f in sorted(SAMPLES.rglob("*.jpg")):
        img = decode_image(f)
        for box in detect_boxes(img, det, pad_val=0, center_pad=False)[:2]:
            if box[4] < 0.25:
                continue
            n_boxes += 1
            cx, cy, side = expand_square(box, img.shape[1], img.shape[0], 1.2)
            for deg in (0, 90, 180, 270):
                got = app_rotate_and_crop_square(img, cx, cy, side, deg)
                if got is None:
                    continue
                crop, _ = got
                k1 = pose_with(pose_orig, crop, False)
                k2 = pose_with(pose_fp32, crop, True)
                d = float(np.abs(k1 - k2).max())
                n_crops += 1
                if d > maxdiff:
                    maxdiff, worst = d, (str(f.relative_to(SAMPLES)), deg)
            w1 = app_search(img, box, pose_orig)
            w2 = app_search(img, box, FloatBboxShim(pose_fp32))
            if (w1 is None) != (w2 is None):
                winner_diffs += 1
                continue
            if w1 is not None and w2 is not None:
                same = (w1["rot"] == w2["rot"] and w1["factor"] == w2["factor"]
                        and w1["gesture"] == w2["gesture"]
                        and abs(w1["kp_mean"] - w2["kp_mean"]) < 1e-4
                        and w1["gated"] == w2["gated"])
                if not same:
                    winner_diffs += 1
    print(f"[pose] {n_crops} crops, max |orig - fp32| = {maxdiff:.3e} ({worst}); "
          f"{winner_diffs}/{n_boxes} winner diffs")


def check_det():
    worst, worst_img, mism = 0.0, None, 0
    n_img = 0
    for f in sorted(SAMPLES.rglob("*.jpg")):
        img = cv2.imread(str(f))
        ih, iw = img.shape[:2]
        ratio = min(DET_SIZE / iw, DET_SIZE / ih)
        nw, nh = max(int(iw * ratio), 1), max(int(ih * ratio), 1)
        resized = cv2.resize(cv2.cvtColor(img, cv2.COLOR_BGR2RGB), (nw, nh))
        padded = np.zeros((DET_SIZE, DET_SIZE, 3), np.uint8)
        padded[:nh, :nw] = resized
        f32 = ((padded.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[
            None].astype(np.float32)
        n_img += 1
        ref = det_orig.run(None, {"input": f32})[0][0]
        boxes, scores = det_strip.run(None, {"input": f32})
        keep = caller_nms(boxes[0], scores[0, 0])
        mine = np.column_stack([boxes[0][keep], scores[0, 0][keep]])
        # The original's output ends with zero-pad rows (score 0) — drop them
        # before comparing (the app drops them too).
        ref = ref[ref[:, 4] > 0]
        mine = mine[mine[:, 4] > 0]
        if ref.shape[0] != mine.shape[0]:
            mism += 1
            print(f"!! {f.name}: count orig={ref.shape[0]} mine={mine.shape[0]}")
        n = min(ref.shape[0], mine.shape[0])
        if n:
            d = float(np.abs(ref[:n] - mine[:n]).max())
            if d > worst:
                worst, worst_img = d, str(f.relative_to(SAMPLES))
    print(f"[det] {n_img} images, {mism} count mismatches, "
          f"max |orig - caller-NMS| = {worst:.3e} ({worst_img})")


if __name__ == "__main__":
    check_pose()
    check_det()
