#!/usr/bin/env python3
"""Full-pipeline benchmark: detector -> palm rotation -> sparse landmark -> rater.

Mirrors the app's ONNX pipeline (AndroidOnnxHandLandmarker) and the shared
LandmarkRaterByThumb / HandGestureClassifier. For each detector (gold,
rtmdet-n-hand) reports per sample the surviving hands' presence + gesture +
score, or why they were dropped (no-lm = presence < 0.35).

Requires: pip install onnxruntime opencv-python-headless numpy
Usage: python3 full_pipeline.py [path-to-rtmdet-n-hand.onnx]
"""
import math
import sys
import numpy as np
import cv2
import onnxruntime as ort
from pathlib import Path

SAMPLES = Path(__file__).resolve().parent.parent / "samples"
MODELS = Path(__file__).resolve().parent.parent.parent / "onnx" / "models"
GOLD = MODELS / "gold_yolo_hand.onnx"
RTMDET = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/rtmdet-n-hand.onnx")
PALM = MODELS / "palm_detection_full.onnx"
LM = MODELS / "hand_landmark_sparse.onnx"
MIN_DET = 0.15
MIN_PRES = 0.35
MAX_HANDS = 2
DECODE_MIN_DIM = 640

MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)

sess_gold = ort.InferenceSession(str(GOLD), providers=["CPUExecutionProvider"])
sess_rtm = ort.InferenceSession(str(RTMDET), providers=["CPUExecutionProvider"])
sess_palm = ort.InferenceSession(str(PALM), providers=["CPUExecutionProvider"])
sess_lm = ort.InferenceSession(str(LM), providers=["CPUExecutionProvider"])


def decode_640(path):
    img = cv2.imread(str(path))
    ih, iw = img.shape[:2]
    sample = max(min(ih, iw) // DECODE_MIN_DIM, 1)
    if sample > 1:
        img = cv2.resize(img, (iw // sample, ih // sample), interpolation=cv2.INTER_AREA)
    return img


def letterbox(img, tw=640, th=480):
    ih, iw = img.shape[:2]
    scale = min(tw / iw, th / ih)
    nw, nh = max(int(iw * scale), 1), max(int(ih * scale), 1)
    resized = cv2.resize(img, (nw, nh))
    canvas = np.zeros((th, tw, 3), np.uint8)
    x0, y0 = (tw - nw) // 2, (th - nh) // 2
    canvas[y0:y0 + nh, x0:x0 + nw] = resized
    return canvas, scale, x0, y0


def keep_aspect_resize_and_pad(image, tw, th):
    ih, iw = image.shape[:2]
    ash, asw = th / ih, tw / iw
    if asw < ash:
        nw, nh = max(int(iw * asw), 1), max(int(ih * asw), 1)
    else:
        nw, nh = max(int(iw * ash), 1), max(int(ih * ash), 1)
    resized = cv2.resize(image, (nw, nh))
    padded = np.zeros((th, tw, 3), np.uint8)
    start_h, start_w = th // 2 - nh // 2, tw // 2 - nw // 2
    padded[start_h:start_h + nh, start_w:start_w + nw] = resized
    return padded, resized


def normalize_radians(angle):
    return angle - 2 * math.pi * math.floor((angle + math.pi) / (2 * math.pi))


def rotate_and_crop_rectangle(img, rect):
    cx, cy, width, height, degree = rect[0]
    ih, iw = img.shape[:2]
    size = (int(math.sqrt(iw * iw + ih * ih)) + 2) * 2
    padded = np.zeros((size, size, 3), np.uint8)
    start_h, start_w = size // 2 - ih // 2, size // 2 - iw // 2
    padded[start_h:start_h + ih, start_w:start_w + iw] = img
    cx_p, cy_p = cx + abs(size - iw) / 2, cy + abs(size - ih) / 2
    theta = math.radians(degree)
    cos_t, sin_t = math.cos(theta), math.sin(theta)
    hw, hh = width / 2, height / 2
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
        return None, None
    crop = padded[y0:y0 + bh, x0:x0 + bw].copy()
    w_diff, h_diff = crop.shape[1] - int(width) + 1, crop.shape[0] - int(height) + 1
    h, w = crop.shape[:2]
    rot = cv2.getRotationMatrix2D((w // 2, h // 2), int(degree), 1)
    abs_cos, abs_sin = abs(rot[0, 0]), abs(rot[0, 1])
    bound_w, bound_h = int(h * abs_sin + w * abs_cos), int(h * abs_cos + w * abs_sin)
    rot[0, 2] += bound_w / 2 - w / 2
    rot[1, 2] += bound_h / 2 - h / 2
    rotated = cv2.warpAffine(crop, rot, (bound_w, bound_h))
    ccx, ccy = rotated.shape[1] // 2, rotated.shape[0] // 2
    rw, rh = int(width), int(height)
    x0f, y0f = ccx - rw // 2, ccy - rh // 2
    if x0f < 0 or y0f < 0 or x0f + rw > rotated.shape[1] or y0f + rh > rotated.shape[0]:
        return None, None
    final = rotated[y0f:y0f + rh, x0f:x0f + rw].copy()
    return [final], [np.asarray([w_diff, h_diff], dtype=np.float32)]


def palm_rotation(img, box):
    x1, y1, x2, y2 = box[:4]
    hand_img = img[y1:y2, x1:x2]
    padded192, resized192 = keep_aspect_resize_and_pad(hand_img, 192, 192)
    inp = np.asarray([padded192.transpose(2, 0, 1) / 255.0], dtype=np.float32)
    _, palm_out = sess_palm.run(None, {"input": inp})
    o = palm_out[0]
    score = float(o[0])
    wh = max(resized192.shape)
    w = o[3] * wh
    wrist_cx, wrist_cy = o[4] * 192, o[5] * 192
    mid_cx, mid_cy = o[6] * 192, o[7] * 192
    if w <= 0:
        return score, 0.0
    kp02x, kp02y = mid_cx - wrist_cx, mid_cy - wrist_cy
    rotation = math.pi / 2.0 - math.atan2(-kp02y, kp02x)
    degree = math.degrees(normalize_radians(rotation))
    return score, degree


def landmark_one(img, box, degree):
    x1, y1, x2, y2 = box[:4]
    if x2 <= x1 or y2 <= y1:
        return None
    iw, ih = img.shape[1], img.shape[0]
    rect = np.asarray([[(x1 + x2) / 2, (y1 + y2) / 2, abs(x2 - x1), abs(y2 - y1), degree]], dtype=np.float32)
    rot_crops, rec_diffs = rotate_and_crop_rectangle(img, rect)
    if not rot_crops:
        return None
    hand_rot, rec_diff = rot_crops[0], rec_diffs[0]
    padded224, resized224 = keep_aspect_resize_and_pad(hand_rot, 224, 224)
    inp = np.asarray([padded224.transpose(2, 0, 1) / 255.0], dtype=np.float32)
    xyz, hand_score, _ = sess_lm.run(None, {"input": inp})
    hs = float(hand_score[0][0])
    if hs < MIN_PRES:
        return None
    xyz21 = xyz.reshape(-1, 21, 3)[0]
    pad_h = abs(padded224.shape[0] - resized224.shape[0])
    pad_w = abs(padded224.shape[1] - resized224.shape[1])
    scale_h = resized224.shape[0] / hand_rot.shape[0]
    scale_w = resized224.shape[1] / hand_rot.shape[1]
    xy = xyz21[:, :2].copy()
    xy[:, 0] = (xy[:, 0] - pad_w / 2) / scale_w
    xy[:, 1] = (xy[:, 1] - pad_h / 2) / scale_h
    cx112, cy112 = (112 - pad_w / 2) / scale_w, (112 - pad_h / 2) / scale_h
    theta = math.radians(degree)
    cos_t, sin_t = math.cos(theta), math.sin(theta)
    pts = []
    for (px, py) in xy:
        dx, dy = px - cx112, py - cy112
        rx = cos_t * dx - sin_t * dy + cx112
        ry = sin_t * dx + cos_t * dy + cy112
        pts.append(((rx + x1 - rec_diff[0] / 2) / iw, (ry + y1 - rec_diff[1] / 2) / ih))
    return hs, pts


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


def classify(p):
    hand_size = dist(p[0], p[9])
    if hand_size <= 0:
        return None
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
    if thumb_index < 0.2 and mid_ext and ring_ext and pink_ext:
        return "OK_SIGN", 3
    if idx_ext and pink_ext and not mid_ext:
        return "ROCK", 5
    if idx_ext and mid_ext and ring_ext and pink_ext and spread > 0.2:
        return "OPEN_PALM", 3
    all_curled = not (idx_ext or mid_ext or ring_ext or pink_ext)
    if all_curled and thumb_len > 0.5 and tf_angle > 120:
        dx, dy = tdir
        angle = abs(math.degrees(math.atan2(dx, -dy)))
        score = 5 if angle < 40 else 4 if angle < 70 else 3 if angle < 110 else 2 if angle < 150 else 1
        return "THUMBS", score
    return None


def gold_boxes(img):
    iw, ih = img.shape[1], img.shape[0]
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    lb, scale, x0, y0 = letterbox(rgb)
    x = np.asarray([lb.transpose(2, 0, 1) / 255.0], dtype=np.float32)
    out = sess_gold.run(None, {"input": x})[0]
    dets = []
    for o in out:
        score = float(o[6])
        if score <= 0:
            continue
        cx = (o[2] + o[4]) / 2
        cy = (o[3] + o[5]) / 2
        w = abs(o[2] - o[4]) * 1.2
        h = abs(o[3] - o[5]) * 1.2
        bx0 = max(int((cx - w / 2 - x0) / scale), 0)
        by0 = max(int((cy - h / 2 - y0) / scale), 0)
        bx1 = min(int((cx + w / 2 - x0) / scale), iw)
        by1 = min(int((cy + h / 2 - y0) / scale), ih)
        if bx1 - bx0 >= 8 and by1 - by0 >= 8:
            dets.append((bx0, by0, bx1, by1, score))
    dets.sort(key=lambda d: -d[4])
    return dets


def rtmdet_boxes(img):
    iw, ih = img.shape[1], img.shape[0]
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    size = 320
    ratio = min(size / iw, size / ih)
    nw, nh = int(iw * ratio), int(ih * ratio)
    resized = cv2.resize(rgb, (nw, nh))
    padded = np.zeros((size, size, 3), np.uint8)
    padded[:nh, :nw] = resized
    f = ((padded.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(np.float32)
    dets_out, labels_out = sess_rtm.run(None, {"input": f})
    boxes, scores = dets_out[0][:, :4], dets_out[0][:, 4]
    m = scores >= 0.3
    boxes, scores = boxes[m], scores[m]
    dets = []
    for o, s in zip(boxes, scores):
        x1 = max(int(o[0] / ratio), 0)
        y1 = max(int(o[1] / ratio), 0)
        x2 = min(int(o[2] / ratio), iw)
        y2 = min(int(o[3] / ratio), ih)
        if x2 > x1 and y2 > y1:
            dets.append((x1, y1, x2, y2, float(s)))
    dets.sort(key=lambda d: -d[4])
    return dets


def pipeline(img, boxes, name):
    results = []
    for box in boxes[:MAX_HANDS]:
        b = box[:4]
        if box[4] < MIN_DET:
            results.append("det<0.15")
            continue
        ps, deg = palm_rotation(img, b)
        lm = landmark_one(img, b, deg)
        if lm is None:
            results.append("no-lm")
            continue
        hs, pts = lm
        cl = classify(pts)
        results.append(f"{hs:.2f}{'/' + cl[0] + '-' + str(cl[1]) if cl else ''}")
    return ";".join(results) if results else "-"


print(f"{'sample':<38} {'GOLD det->rate':<32} {'RTMDET det->rate'}")
for f in sorted(SAMPLES.rglob("*.jpg")):
    rel = str(f.relative_to(SAMPLES))
    img = decode_640(f)
    g = pipeline(img, gold_boxes(img), "gold")
    r = pipeline(img, rtmdet_boxes(img), "rtmdet")
    print(f"{rel:<38} [{g:<30}] [{r}]")
