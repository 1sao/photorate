#!/usr/bin/env python3
"""RTMPose-only with gesture-based filtering — no sparse model at all.

The idea: replace the sparse model's presence gate with the gesture classifier
itself. Every box from RTMDet goes through RTMPose -> HandGestureClassifier.
If the classifier returns a valid gesture, it's a rating hand. If not, drop it.

This benchmark reports per-sample the gesture classifier's output on RTMPose
landmarks (including detailed feature values for no_score hands), so we can
tune thresholds to filter the no_score false positives while keeping all
scored samples.

Requires: pip install onnxruntime opencv-python-headless numpy
Usage: python3 gesture_filter_benchmark.py
"""
import cv2
import math
import numpy as np
import onnxruntime as ort
import sys
import time
from pathlib import Path

SAMPLES = Path(__file__).resolve().parent.parent / "samples"
MODELS = Path(__file__).resolve().parent.parent.parent / "onnx" / "models"
RTMDET = MODELS / "rtmdet_n_hand.onnx"
PALM = MODELS / "palm_detection_full.onnx"
RTMPOSE = MODELS / "rtmpose_hand.onnx"
MIN_DET = 0.25
BOX_EXPANSION = 1.2
MAX_HANDS = 2
DECODE_MIN_DIM = 640
RTMPOSE_SIZE = 256

MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)

sess_rtm = ort.InferenceSession(str(RTMDET), providers=["CPUExecutionProvider"])
sess_palm = ort.InferenceSession(str(PALM), providers=["CPUExecutionProvider"])
sess_rtmp = ort.InferenceSession(str(RTMPOSE), providers=["CPUExecutionProvider"])


def decode_640(path):
    img = cv2.imread(str(path))
    ih, iw = img.shape[:2]
    sample = max(min(ih, iw) // DECODE_MIN_DIM, 1)
    if sample > 1:
        img = cv2.resize(img, (iw // sample, ih // sample), interpolation=cv2.INTER_AREA)
    return img


def rtmdet_boxes(img):
    iw, ih = img.shape[1], img.shape[0]
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    size = 320
    ratio = min(size / iw, size / ih)
    nw, nh = max(int(iw * ratio), 1), max(int(ih * ratio), 1)
    resized = cv2.resize(rgb, (nw, nh))
    padded = np.zeros((size, size, 3), np.uint8)
    padded[:nh, :nw] = resized
    f = ((padded.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32)
    dets_out, _ = sess_rtm.run(None, {"input": f})
    boxes, scores = dets_out[0][:, :4], dets_out[0][:, 4]
    m = scores > 0
    boxes, scores = boxes[m], scores[m]
    dets = []
    for o, s in zip(boxes, scores):
        x1 = max(int(o[0] / ratio), 0)
        y1 = max(int(o[1] / ratio), 0)
        x2 = min(int(o[2] / ratio), iw)
        y2 = min(int(o[3] / ratio), ih)
        if x2 > x1 and y2 > y1 and x2 - x1 >= 8 and y2 - y1 >= 8:
            dets.append((x1, y1, x2, y2, float(s)))
    dets.sort(key=lambda d: -d[4])
    return dets


def keep_aspect_resize_and_pad(image, tw, th):
    ih, iw = image.shape[:2]
    ash, asw = th / ih, tw / iw
    nw, nh = (max(int(iw * asw), 1), max(int(ih * asw), 1)) if asw < ash else (
        max(int(iw * ash), 1), max(int(ih * ash), 1))
    resized = cv2.resize(image, (nw, nh))
    padded = np.zeros((th, tw, 3), np.uint8)
    start_h, start_w = th // 2 - nh // 2, tw // 2 - nw // 2
    padded[start_h:start_h + nh, start_w:start_w + nw] = resized
    return padded, resized


def normalize_radians(angle):
    return angle - 2 * math.pi * math.floor((angle + math.pi) / (2 * math.pi))


def rotate_and_crop_square(img, cx, cy, side, degree):
    ih, iw = img.shape[:2]
    size = (int(math.sqrt(iw * iw + ih * ih)) + 2) * 2
    padded = np.zeros((size, size, 3), np.uint8)
    start_h, start_w = size // 2 - ih // 2, size // 2 - iw // 2
    padded[start_h:start_h + ih, start_w:start_w + iw] = img
    cx_p, cy_p = cx + abs(size - iw) / 2, cy + abs(size - ih) / 2
    theta = math.radians(degree)
    cos_t, sin_t = math.cos(theta), math.sin(theta)
    hw = hh = side / 2
    xs = [cx_p + hw * cos_t - hh * sin_t, cx_p - hw * cos_t - hh * sin_t,
          cx_p - hw * cos_t + hh * sin_t, cx_p + hw * cos_t + hh * sin_t]
    ys = [cy_p + hw * sin_t + hh * cos_t, cy_p - hw * sin_t + hh * cos_t,
          cy_p - hw * sin_t - hh * cos_t, cy_p + hw * sin_t - hh * cos_t]
    min_x, max_x = int(min(xs)), int(max(xs)) + 1
    min_y, max_y = int(min(ys)), int(max(ys)) + 1
    cxx, cyy = (min_x + max_x) // 2, (min_y + max_y) // 2
    bw, bh = max_x - min_x, max_y - min_y
    x0, y0 = cxx - bw // 2, cyy - bh // 2
    if x0 < 0 or y0 < 0 or x0 + bw > padded.shape[1] or y0 + bh > padded.shape[0]:
        return None
    crop = padded[y0:y0 + bh, x0:x0 + bw].copy()
    rot = cv2.getRotationMatrix2D((crop.shape[1] // 2, crop.shape[0] // 2), int(degree), 1)
    abs_cos, abs_sin = abs(rot[0, 0]), abs(rot[0, 1])
    bound_w = int(crop.shape[0] * abs_sin + crop.shape[1] * abs_cos)
    bound_h = int(crop.shape[0] * abs_cos + crop.shape[1] * abs_sin)
    rot[0, 2] += bound_w / 2 - crop.shape[1] / 2
    rot[1, 2] += bound_h / 2 - crop.shape[0] / 2
    rotated = cv2.warpAffine(crop, rot, (bound_w, bound_h))
    ccx, ccy = rotated.shape[1] // 2, rotated.shape[0] // 2
    rw = rh = int(side)
    x0f, y0f = ccx - rw // 2, ccy - rh // 2
    if x0f < 0 or y0f < 0 or x0f + rw > rotated.shape[1] or y0f + rh > rotated.shape[0]:
        return None
    return rotated[y0f:y0f + rh, x0f:x0f + rw].copy()


def expand_square(box, iw, ih, factor=BOX_EXPANSION):
    x1, y1, x2, y2 = box[:4]
    cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
    w, h = (x2 - x1) * factor, (y2 - y1) * factor
    side = max(w, h)
    return cx, cy, side


def palm_rotation(img, box):
    x1, y1, x2, y2 = box[:4]
    hand_img = img[y1:y2, x1:x2]
    padded192, resized192 = keep_aspect_resize_and_pad(hand_img, 192, 192)
    inp = np.asarray([padded192.transpose(2, 0, 1) / 255.0], dtype=np.float32)
    _, palm_out = sess_palm.run(None, {"input": inp})
    o = palm_out[0]
    w = o[3] * max(resized192.shape)
    if w <= 0:
        return 0.0
    kp02x, kp02y = o[6] * 192 - o[4] * 192, o[7] * 192 - o[5] * 192
    rotation = math.pi / 2.0 - math.atan2(-kp02y, kp02x)
    return math.degrees(normalize_radians(rotation))


def top_down_affine(crop):
    h, w = crop.shape[:2]
    bbox_w, bbox_h = 1.25 * w, 1.25 * h
    w_scaled = max(bbox_h * 0.75, bbox_w)
    scale = RTMPOSE_SIZE / w_scaled
    m = np.float32([[scale, 0, RTMPOSE_SIZE / 2 - scale * w / 2],
                    [0, scale, RTMPOSE_SIZE / 2 - scale * h / 2]])
    return cv2.warpAffine(crop, m, (RTMPOSE_SIZE, RTMPOSE_SIZE), borderValue=0)


def rtmpose_landmarks(crop):
    h, w = crop.shape[:2]
    warped = top_down_affine(crop)
    f = ((warped.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32)
    bbox = np.asarray([[w, h]], dtype=np.int64)
    kps = sess_rtmp.run(None, {"input": f, "bboxes_width_height": bbox})[0]
    return kps[0]  # [21, 3] x,y,score in crop space


# --- rating (mirrors HandGestureClassifier) ---

def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def vec(a, b):
    return (b[0] - a[0], b[1] - a[1])


def angle_between(a, b):
    dot = a[0] * b[0] + a[1] * b[1]
    mag = math.hypot(*a) * math.hypot(*b)
    if mag == 0:
        return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, dot / mag))))


def is_extended(p, pip_i, tip_i):
    return dist(p[tip_i], p[0]) > dist(p[pip_i], p[0])


def classify_with_features(p):
    """Returns (gesture, score, features_dict) where features_dict contains
    the raw measurements used for classification. Useful for debugging fp."""
    hand_size = dist(p[0], p[9])
    if hand_size <= 0:
        return None, None, {}
    thumb_len = dist(p[2], p[4]) / hand_size
    thumb_index = dist(p[4], p[8]) / hand_size
    idx_ext = is_extended(p, 6, 8)
    mid_ext = is_extended(p, 10, 12)
    ring_ext = is_extended(p, 14, 16)
    pink_ext = is_extended(p, 18, 20)
    tips = [p[8], p[12], p[16], p[20]]
    spread = sum(dist(tips[i], tips[i + 1]) for i in range(3)) / 3 / hand_size
    tdir = vec(p[2], p[4])
    idir = vec(p[5], p[8])
    tf_angle = angle_between(tdir, idir)
    feats = {
        "hand_size": hand_size,
        "thumb_len": thumb_len,
        "thumb_index": thumb_index,
        "idx_ext": idx_ext,
        "mid_ext": mid_ext,
        "ring_ext": ring_ext,
        "pink_ext": pink_ext,
        "spread": spread,
        "tf_angle": tf_angle,
    }
    if thumb_index < 0.2 and mid_ext and ring_ext and pink_ext:
        return ("OK_SIGN", 3, feats)
    if idx_ext and pink_ext and not mid_ext:
        return ("ROCK", 5, feats)
    if idx_ext and mid_ext and ring_ext and pink_ext and spread > 0.2:
        return ("OPEN_PALM", 3, feats)
    all_curled = not (idx_ext or mid_ext or ring_ext or pink_ext)
    if all_curled and thumb_len > 0.25 and tf_angle > 85:
        dx, dy = tdir
        angle = abs(math.degrees(math.atan2(dx, -dy)))
        score = 5 if angle < 40 else 4 if angle < 70 else 3 if angle < 110 else 2 if angle < 150 else 1
        return ("THUMBS", score, feats)
    return None, None, feats


def main():
    print(f"{'sample':<38} {'gesture-filtered':<44} {'details'}")
    correct = 0
    total_scored = 0
    false_positives = []

    for f in sorted(SAMPLES.rglob("*.jpg")):
        rel = str(f.relative_to(SAMPLES))
        score_dir = rel.split("/")[0]
        if score_dir == "search":
            continue
        img = decode_640(f)
        iw, ih = img.shape[1], img.shape[0]
        boxes = rtmdet_boxes(img)
        per_box = []
        for box in boxes[:MAX_HANDS]:
            if box[4] < MIN_DET:
                per_box.append("det<0.25")
                continue
            palm_deg = palm_rotation(img, box[:4])
            cx, cy, side = expand_square(box[:4], iw, ih)
            best = None
            best_feats = {}
            for deg in sorted(set([palm_deg, 0, 90, 180, 270])):
                crop = rotate_and_crop_square(img, cx, cy, side, deg)
                if crop is None:
                    continue
                kps = rtmpose_landmarks(crop)
                kp_mean = float(kps[:, 2].mean())
                p = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(21)]
                cl, score, feats = classify_with_features(p)
                if cl is not None:
                    cand = (cl, score, kp_mean, deg, feats)
                    if best is None or kp_mean > best[2]:
                        best = cand
                    if kp_mean >= 0.3:
                        break
            if best is None:
                per_box.append("unrated")
            else:
                cl, score, kp_mean, deg, best_feats = best
                per_box.append(f"{cl}-{score}@{deg} kp={kp_mean:.2f}")
        # Check scored samples
        has_gesture = any(not p.startswith("unrated") and not p.startswith("det<") for p in per_box)
        if score_dir == "no_score":
            status = "NO_GESTURE" if not has_gesture else "FP"
            details = "; ".join(per_box)
            print(f"{rel:<38} [{status:<42}] {details}")
            if has_gesture:
                false_positives.append(rel)
                # Also print features for the FP
                for box in boxes[:MAX_HANDS]:
                    if box[4] < MIN_DET: continue
                    cx, cy, side = expand_square(box[:4], iw, ih)
                    for deg in sorted(set([palm_rotation(img, box[:4]), 0, 90, 180, 270])):
                        crop = rotate_and_crop_square(img, cx, cy, side, deg)
                        if crop is None: continue
                        kps = rtmpose_landmarks(crop)
                        p = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(21)]
                        cl, score, feats = classify_with_features(p)
                        if cl is not None:
                            print(f"  deg={deg}: {cl}-{score} spread={feats['spread']:.3f} "
                                  f"idx_ext={feats['idx_ext']} mid_ext={feats['mid_ext']} "
                                  f"ring_ext={feats['ring_ext']} pink_ext={feats['pink_ext']} "
                                  f"thumb_len={feats['thumb_len']:.3f} tf_angle={feats['tf_angle']:.0f}")
        else:
            total_scored += 1
            expected = int(score_dir)
            ok = any(
                p.startswith("THUMBS") or p.startswith("OPEN_PALM") or
                p.startswith("ROCK") or p.startswith("OK_SIGN") or
                p.startswith("PEACE")
                for p in per_box
            ) and any(
                f"-{expected}" in p for p in per_box
            )
            if ok:
                correct += 1
            print(f"{rel:<38} [{'OK' if ok else 'MISS':<42}] {'; '.join(per_box)}")

    print(f"\naccuracy {correct}/{total_scored}")
    print(f"false positives: {false_positives}")
    if false_positives:
        print(f"\nRECOMMENDATION: tighten OPEN_PALM spread threshold "
              f"(current 0.2) or add finger-curl detection to filter no_score_holding_in_hand")


if __name__ == "__main__":
    sys.exit(main())
