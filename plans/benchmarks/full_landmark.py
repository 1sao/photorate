#!/usr/bin/env python3
"""Path B experiment: MediaPipe hand_landmark_full.tflite -> ONNX, benchmarked
as a replacement for BOTH the sparse landmark model and the RTMPose fallback.

The app ships the MediaPipe *sparse* landmarker (hand_landmark_sparse.onnx),
whose thumb geometry doesn't fit LandmarkRaterByThumb, so a 55 MB RTMPose
fallback was bolted on. hand_landmark_full (the tflite the MediaPipe pipeline
runs after its palm detector, already in app assets) has MediaPipe-native
geometry — if it fits the rater for ALL gestures (incl. THUMBS) and its
hand_score presence gate filters the no_score samples, it replaces BOTH
models with one 5.5 MB ONNX.

Steps:
  1. convert app/src/androidTest/assets/hand_landmarks_detector.tflite -> ONNX
     (tflite2onnx; no tensorflow needed)
  2. run the app's exact rotate-and-crop + multi-rotation search through it
  3. report presence + gesture/score per sample, plus latency vs sparse/rtmpose

Requires: pip install onnxruntime opencv-python-headless numpy tflite2onnx
Usage: python3 full_landmark.py
"""
import cv2
import math
import numpy as np
import onnxruntime as ort
import sys
import tflite2onnx
import time
from pathlib import Path

SAMPLES = Path(__file__).resolve().parent.parent / "samples"
MODELS = Path(__file__).resolve().parent.parent.parent / "onnx" / "models"
TFLITE = Path(
    __file__).resolve().parent.parent.parent / "app" / "src" / "androidTest" / "assets" / "hand_landmarks_detector.tflite"
OUT_ONNX = Path("/tmp/hand_landmark_full.onnx")
RTMDET = MODELS / "rtmdet_n_hand.onnx"
PALM = MODELS / "palm_detection_full.onnx"
SPARSE = MODELS / "hand_landmark_sparse.onnx"
RTMPOSE = MODELS / "rtmpose_hand.onnx"
MIN_DET = 0.25
BOX_EXPANSION = 1.2
MAX_HANDS = 2
DECODE_MIN_DIM = 640
MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)

sess_rtm = ort.InferenceSession(str(RTMDET), providers=["CPUExecutionProvider"])
sess_palm = ort.InferenceSession(str(PALM), providers=["CPUExecutionProvider"])
sess_sparse = ort.InferenceSession(str(SPARSE), providers=["CPUExecutionProvider"])
sess_rtmp = ort.InferenceSession(str(RTMPOSE), providers=["CPUExecutionProvider"])


def bench(fn, n=3):
    best = float("inf")
    for _ in range(n):
        t0 = time.perf_counter()
        fn()
        best = min(best, (time.perf_counter() - t0) * 1000)
    return best


# --- identical helpers to rtmpose_only.py / the app ---

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


def sparse_landmark(img, box, degree):
    x1, y1, x2, y2 = box[:4]
    iw, ih = img.shape[1], img.shape[0]
    cx, cy, side = expand_square(box[:4], iw, ih)
    crop = rotate_and_crop_square(img, cx, cy, side, degree)
    if crop is None:
        return None
    padded224, _ = keep_aspect_resize_and_pad(crop, 224, 224)
    inp = np.asarray([padded224.transpose(2, 0, 1) / 255.0], dtype=np.float32)
    xyz, hand_score, _ = sess_sparse.run(None, {"input": inp})
    return float(hand_score[0][0])


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
        return ("OK_SIGN", 3)
    if idx_ext and pink_ext and not mid_ext:
        return ("ROCK", 5)
    if idx_ext and mid_ext and ring_ext and pink_ext and spread > 0.2:
        return ("OPEN_PALM", 3)
    all_curled = not (idx_ext or mid_ext or ring_ext or pink_ext)
    if all_curled and thumb_len > 0.25 and tf_angle > 85:
        dx, dy = tdir
        angle = abs(math.degrees(math.atan2(dx, -dy)))
        score = 5 if angle < 40 else 4 if angle < 70 else 3 if angle < 110 else 2 if angle < 150 else 1
        return ("THUMBS", score)
    return None


def main():
    print("== Step 1: convert hand_landmarks_detector.tflite -> ONNX ==")
    if not OUT_ONNX.exists():
        tflite2onnx.convert(str(TFLITE), str(OUT_ONNX))
        print(f"converted -> {OUT_ONNX} ({OUT_ONNX.stat().st_size / 1e6:.1f} MB)")
    else:
        print(f"already converted -> {OUT_ONNX} ({OUT_ONNX.stat().st_size / 1e6:.1f} MB)")

    sess_full = ort.InferenceSession(str(OUT_ONNX), providers=["CPUExecutionProvider"])
    inp = sess_full.get_inputs()[0]
    print(f"input: name={inp.name} shape={inp.shape} type={inp.type}")
    for o in sess_full.get_outputs():
        print(f"output: name={o.name} shape={o.shape}")

    # inputs are RGB /255 CHW (MediaPipe tflite convention). Output ORDER is
    # the one HandLandmarkDirectTest uses: 0=xyz[1,63], 1=presence[1,1],
    # 2=handedness[1,1], 3=world[1,63].
    def run_full(crop):
        padded224, _ = keep_aspect_resize_and_pad(crop, 224, 224)
        inp_name = sess_full.get_inputs()[0].name
        rgb = cv2.cvtColor(padded224, cv2.COLOR_BGR2RGB)
        x = np.asarray([rgb.transpose(2, 0, 1) / 255.0], dtype=np.float32)
        out = sess_full.run(None, {inp_name: x})
        return padded224, out

    print("\n== Step 2: benchmark ==")
    print(f"{'sample':<38} {'full-lm presence/gesture':<40} {'sparse-hs'}")
    correct = 0
    total_scored = 0
    no_score_max, scored_min = -1.0, 1.0
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
                continue
            palm_deg = palm_rotation(img, box[:4])
            cx, cy, side = expand_square(box[:4], iw, ih)
            best = None
            for deg in sorted(set([palm_deg, 0, 90, 180, 270])):
                crop = rotate_and_crop_square(img, cx, cy, side, deg)
                if crop is None:
                    continue
                _, out = run_full(crop)
                xyz = np.asarray(out[0]).reshape(-1)[:63]
                presence = float(np.asarray(out[1]).reshape(-1)[0])
                pts = [(xyz[i * 3], xyz[i * 3 + 1]) for i in range(21)]
                cl = classify(pts)
                cand = (presence, cl, deg)
                if best is None or presence > best[0]:
                    best = cand
                if presence >= 0.5:
                    break
            if best is None:
                per_box.append("no-lm")
                continue
            hs, cl, deg = best
            label = f"{hs:.2f}@{deg}"
            label += f"/{cl[0]}-{cl[1]}" if cl else "/unrated"
            per_box.append(label)
        sparse_hs = []
        for box in boxes[:MAX_HANDS]:
            if box[4] < MIN_DET:
                continue
            hs = sparse_landmark(img, box[:4], palm_rotation(img, box[:4]))
            if hs is not None:
                sparse_hs.append(f"{hs:.2f}")
        print(f"{rel:<38} [{';'.join(per_box):<38}] {';'.join(sparse_hs)}")
        if score_dir == "no_score":
            for label in per_box:
                if label == "no-lm":
                    continue
                hs = float(label.split("@")[0])
                no_score_max = max(no_score_max, hs)
        else:
            total_scored += 1
            expected = int(score_dir)
            ok = any(
                ("-" + str(expected)) in label or (label.endswith(f"-{expected}"))
                for label in per_box
                if label != "no-lm"
            )
            for label in per_box:
                if label != "no-lm":
                    scored_min = min(scored_min, float(label.split("@")[0]))
            if ok:
                correct += 1
            print(f"    {'OK' if ok else 'MISS'} expected={expected} best={per_box}")
    print(f"\nscored presence min={scored_min:.3f}, no_score presence max={no_score_max:.3f}")
    print(f"accuracy {correct}/{total_scored}")

    # latency: full vs sparse vs rtmpose
    img = decode_640(next(SAMPLES.rglob("5/*.jpg")))
    boxes = rtmdet_boxes(img)
    box = boxes[0][:4]
    iw, ih = img.shape[1], img.shape[0]
    cx, cy, side = expand_square(box, iw, ih)
    crop = rotate_and_crop_square(img, cx, cy, side, 0)
    t_sparse = bench(lambda: sparse_landmark(img, box, 0))
    t_full = bench(lambda: run_full(crop))
    print(f"\nlatency: sparse {t_sparse:.1f} ms, full {t_full:.1f} ms (mac CPU, fp32)")


if __name__ == "__main__":
    sys.exit(main())
