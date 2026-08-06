#!/usr/bin/env python3
"""Check finger-straightness for no_score vs real open palm."""
import cv2
import math
import numpy as np
import onnxruntime as ort
import sys
from pathlib import Path

SAMPLES = Path(__file__).resolve().parent.parent / "samples"
MODELS = Path(__file__).resolve().parent.parent.parent / "onnx" / "models"
RTMDET = MODELS / "rtmdet_n_hand.onnx"
RTMPOSE = MODELS / "rtmpose_hand.onnx"
MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)
BOX_EXPANSION = 1.2

sess_rtm = ort.InferenceSession(str(RTMDET), providers=["CPUExecutionProvider"])
sess_rtmp = ort.InferenceSession(str(RTMPOSE), providers=["CPUExecutionProvider"])


def decode_640(path):
    img = cv2.imread(str(path))
    ih, iw = img.shape[:2]
    s = max(min(ih, iw) // 640, 1)
    if s > 1: img = cv2.resize(img, (iw // s, ih // s))
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
    abs_c, abs_s = abs(rot[0, 0]), abs(rot[0, 1])
    bound_w = int(crop.shape[0] * abs_s + crop.shape[1] * abs_c)
    bound_h = int(crop.shape[0] * abs_c + crop.shape[1] * abs_s)
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
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    side = max((box[2] - box[0]) * factor, (box[3] - box[1]) * factor)
    return cx, cy, side


def top_down_affine(crop):
    h, w = crop.shape[:2]
    bbox_w, bbox_h = 1.25 * w, 1.25 * h
    w_scaled = max(bbox_h * 0.75, bbox_w)
    scale = 256 / w_scaled
    m = np.float32([[scale, 0, 256 / 2 - scale * w / 2],
                    [0, scale, 256 / 2 - scale * h / 2]])
    return cv2.warpAffine(crop, m, (256, 256), borderValue=0)


def rtmpose_landmarks(crop):
    h, w = crop.shape[:2]
    warped = top_down_affine(crop)
    f = ((warped.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32)
    bbox = np.asarray([[w, h]], dtype=np.int64)
    return sess_rtmp.run(None, {"input": f, "bboxes_width_height": bbox})[0][0]


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


files = [
    "3/3_open_hand_palm_down.jpg",
    "no_score/no_score_holding_in_hand.jpg",
    "no_score/no_score_holding_in_two_hands.jpg",
    "no_score/no_score_holding_on_folded_hands.jpg",
    "3/3_ok_sign.jpg",
    "3/3_ok_sign_peanuts.jpg",
]

print(
    f"{'file':<42} {'deg':>6} {'kp':<6} {'idx_pp':<8} {'mid_pp':<8} {'rng_pp':<8} {'pnk_pp':<8} {'spread':<8} {'idx_ext':<8} {'mid_ext':<8} {'rng_ext':<8} {'pnk_ext':<8} {'n_straight(0.25)':<16} {'n_straight(0.20)':<16}")
print("-" * 170)

for fpath in files:
    f = SAMPLES / fpath
    if not f.exists():
        continue
    img = decode_640(f)
    iw, ih = img.shape[1], img.shape[0]
    boxes = rtmdet_boxes(img)
    for bi, box in enumerate(boxes[:1]):
        if box[4] < 0.25:
            continue
        cx, cy, side = expand_square(box[:4], iw, ih)
        for deg in sorted(set([0, 90, 180, 270])):
            crop = rotate_and_crop_square(img, cx, cy, side, deg)
            if crop is None:
                continue
            kps = rtmpose_landmarks(crop)
            kp_mean = float(kps[:, 2].mean())
            p = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(21)]
            hs = dist(p[0], p[9])
            if hs == 0:
                continue


            def is_ext(pip_i, tip_i):
                return dist(p[tip_i], p[0]) > dist(p[pip_i], p[0])


            idx_pp = dist(p[8], p[6]) / hs
            mid_pp = dist(p[12], p[10]) / hs
            ring_pp = dist(p[16], p[14]) / hs
            pink_pp = dist(p[20], p[18]) / hs
            tips = [8, 12, 16, 20]
            spread = sum(dist(p[tips[i]], p[tips[i + 1]]) for i in range(3)) / 3 / hs
            idx_ext = is_ext(6, 8)
            mid_ext = is_ext(10, 12)
            ring_ext = is_ext(14, 16)
            pink_ext = is_ext(18, 20)
            n_straight_25 = sum([idx_pp < 0.25, mid_pp < 0.25, ring_pp < 0.25, pink_pp < 0.25])
            n_straight_20 = sum([idx_pp < 0.20, mid_pp < 0.20, ring_pp < 0.20, pink_pp < 0.20])
            print(
                f"{fpath:<42} {deg:>6} {kp_mean:<6.3f} {idx_pp:<8.3f} {mid_pp:<8.3f} {ring_pp:<8.3f} {pink_pp:<8.3f} "
                f"{spread:<8.3f} {str(idx_ext):<8} {str(mid_ext):<8} {str(ring_ext):<8} {str(pink_ext):<8} "
                f"{n_straight_25}/4{' ✓' if n_straight_25 >= 3 else ' ✗':<13} {n_straight_20}/4{' ✓' if n_straight_20 >= 3 else ' ✗':<13}")
            if kp_mean >= 0.3:
                break
