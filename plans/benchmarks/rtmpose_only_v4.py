#!/usr/bin/env python3
"""RTMPose-only v4 — no sparse model, no palm rotation model.

Only fingers that should be STRAIGHT for a given gesture are checked for
straightness. For OK_SIGN, middle can be curled (touching thumb), so only
ring and pinky must be straight. For ROCK, index and pinky must be straight.
For OPEN_PALM, all 4 must be straight. For PEACE, index and middle must be
straight. For THUMBS, the curled fingers are irrelevant (they're curled).
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
RTMPOSE = MODELS / "rtmpose_hand.onnx"
MIN_DET = 0.25
BOX_EXPANSION = 1.2
MAX_HANDS = 2
DECODE_MIN_DIM = 640
MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)

sess_rtm = ort.InferenceSession(str(RTMDET), providers=["CPUExecutionProvider"])
sess_rtmp = ort.InferenceSession(str(RTMPOSE), providers=["CPUExecutionProvider"])


def decode_640(path): img = cv2.imread(str(path)); ih, iw = img.shape[:2]; s = max(
    min(ih, iw) // DECODE_MIN_DIM, 1); return cv2.resize(img, (iw // s, ih // s)) if s > 1 else img


def rtmdet_boxes(img):
    iw, ih = img.shape[1], img.shape[0];
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB);
    size = 320;
    ratio = min(size / iw, size / ih)
    nw, nh = max(int(iw * ratio), 1), max(int(ih * ratio), 1);
    resized = cv2.resize(rgb, (nw, nh))
    padded = np.zeros((size, size, 3), np.uint8);
    padded[:nh, :nw] = resized
    f = ((padded.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32)
    dets_out, _ = sess_rtm.run(None, {"input": f})
    boxes, scores = dets_out[0][:, :4], dets_out[0][:, 4];
    m = scores > 0;
    boxes, scores = boxes[m], scores[m]
    dets = []
    for o, s in zip(boxes, scores):
        x1 = max(int(o[0] / ratio), 0);
        y1 = max(int(o[1] / ratio), 0);
        x2 = min(int(o[2] / ratio), iw);
        y2 = min(int(o[3] / ratio), ih)
        if x2 > x1 and y2 > y1 and x2 - x1 >= 8 and y2 - y1 >= 8: dets.append(
            (x1, y1, x2, y2, float(s)))
    dets.sort(key=lambda d: -d[4]);
    return dets


def rotate_and_crop_square(img, cx, cy, side, degree):
    ih, iw = img.shape[:2];
    size = (int(math.sqrt(iw * iw + ih * ih)) + 2) * 2
    padded = np.zeros((size, size, 3), np.uint8);
    start_h, start_w = size // 2 - ih // 2, size // 2 - iw // 2
    padded[start_h:start_h + ih, start_w:start_w + iw] = img;
    cx_p, cy_p = cx + abs(size - iw) / 2, cy + abs(size - ih) / 2
    theta = math.radians(degree);
    cos_t, sin_t = math.cos(theta), math.sin(theta);
    hw = hh = side / 2
    xs = [cx_p + hw * cos_t - hh * sin_t, cx_p - hw * cos_t - hh * sin_t,
          cx_p - hw * cos_t + hh * sin_t, cx_p + hw * cos_t + hh * sin_t]
    ys = [cy_p + hw * sin_t + hh * cos_t, cy_p - hw * sin_t + hh * cos_t,
          cy_p - hw * sin_t - hh * cos_t, cy_p + hw * sin_t - hh * cos_t]
    min_x, max_x = int(min(xs)), int(max(xs)) + 1;
    min_y, max_y = int(min(ys)), int(max(ys)) + 1
    cxx, cyy = (min_x + max_x) // 2, (min_y + max_y) // 2;
    bw, bh = max_x - min_x, max_y - min_y
    x0, y0 = cxx - bw // 2, cyy - bh // 2
    if x0 < 0 or x0 + bw > padded.shape[1] or y0 < 0 or y0 + bh > padded.shape[0]: return None
    crop = padded[y0:y0 + bh, x0:x0 + bw].copy()
    rot = cv2.getRotationMatrix2D((crop.shape[1] // 2, crop.shape[0] // 2), int(degree), 1)
    abs_c, abs_s = abs(rot[0, 0]), abs(rot[0, 1])
    bound_w = int(crop.shape[0] * abs_s + crop.shape[1] * abs_c);
    bound_h = int(crop.shape[0] * abs_c + crop.shape[1] * abs_s)
    rot[0, 2] += bound_w / 2 - crop.shape[1] / 2;
    rot[1, 2] += bound_h / 2 - crop.shape[0] / 2
    rotated = cv2.warpAffine(crop, rot, (bound_w, bound_h))
    ccx, ccy = rotated.shape[1] // 2, rotated.shape[0] // 2;
    rw = rh = int(side)
    x0f, y0f = ccx - rw // 2, ccy - rh // 2
    if x0f < 0 or x0f + rw > rotated.shape[1] or y0f < 0 or y0f + rh > rotated.shape[0]: return None
    return rotated[y0f:y0f + rh, x0f:x0f + rw].copy()


def expand_square(box, iw, ih, factor=BOX_EXPANSION): cx, cy = (box[0] + box[2]) / 2, (
            box[1] + box[3]) / 2; side = max((box[2] - box[0]) * factor,
                                             (box[3] - box[1]) * factor); return cx, cy, side


def top_down_affine(crop):
    h, w = crop.shape[:2];
    bbox_w, bbox_h = 1.25 * w, 1.25 * h;
    w_scaled = max(bbox_h * 0.75, bbox_w);
    scale = 256 / w_scaled
    m = np.float32([[scale, 0, 256 / 2 - scale * w / 2], [0, scale, 256 / 2 - scale * h / 2]]);
    return cv2.warpAffine(crop, m, (256, 256), borderValue=0)


def rtmpose_landmarks(crop):
    h, w = crop.shape[:2];
    warped = top_down_affine(crop)
    f = ((warped.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32)
    bbox = np.asarray([[w, h]], dtype=np.int64)
    return sess_rtmp.run(None, {"input": f, "bboxes_width_height": bbox})[0][0]


def dist(a, b): return math.hypot(a[0] - b[0], a[1] - b[1])


def vec(a, b): return (b[0] - a[0], b[1] - a[1])


def angle_between(a, b):
    dot = a[0] * b[0] + a[1] * b[1];
    mag = math.hypot(*a) * math.hypot(*b)
    if mag == 0: return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, dot / mag))))


def is_ext(p, pip_i, tip_i): return dist(p[tip_i], p[0]) > dist(p[pip_i], p[0])


def finger_curl(p, pip_i, tip_i, hs): return dist(p[tip_i], p[pip_i]) / hs


def classify(p, max_curl=0.25):
    hs = dist(p[0], p[9])
    if hs <= 0: return None
    thumb_len = dist(p[2], p[4]) / hs
    thumb_index = dist(p[4], p[8]) / hs
    # Base extension checks (old method)
    idx_ext = is_ext(p, 6, 8)
    mid_ext = is_ext(p, 10, 12)
    ring_ext = is_ext(p, 14, 16)
    pink_ext = is_ext(p, 18, 20)
    # Finger curl values
    idx_curl = finger_curl(p, 6, 8, hs)
    mid_curl = finger_curl(p, 10, 12, hs)
    ring_curl = finger_curl(p, 14, 16, hs)
    pink_curl = finger_curl(p, 18, 20, hs)
    tips = [p[8], p[12], p[16], p[20]]
    spread = sum(dist(tips[i], tips[i + 1]) for i in range(3)) / 3 / hs
    tdir = vec(p[2], p[4])
    idir = vec(p[5], p[8])
    tf_angle = angle_between(tdir, idir)
    # OK sign: thumb touches index tip, mid/ring/pink extended.
    # For OK_SIGN, ring and pinky must be straight (mid can be curled as it's touching thumb).
    if thumb_index < 0.2 and mid_ext and ring_ext and pink_ext:
        if ring_curl < max_curl and pink_curl < max_curl:
            return ("OK_SIGN", 3)
    # PEACE: index and middle extended, ring and pinky curled.
    if idx_ext and mid_ext and not ring_ext and not pink_ext:
        if idx_curl < max_curl and mid_curl < max_curl:
            return ("PEACE", 2)
    # ROCK: index and pinky extended, middle curled.
    if idx_ext and pink_ext and not mid_ext:
        if idx_curl < max_curl and pink_curl < max_curl:
            return ("ROCK", 5)
    # OPEN PALM: all extended, splayed, and straight.
    if idx_ext and mid_ext and ring_ext and pink_ext and spread > 0.2:
        if idx_curl < max_curl and mid_curl < max_curl and ring_curl < max_curl and pink_curl < max_curl:
            return ("OPEN_PALM", 3)
    # THUMBS: fingers curled (old extension check), thumb away.
    all_curled = not (
                is_ext(p, 6, 8) or is_ext(p, 10, 12) or is_ext(p, 14, 16) or is_ext(p, 18, 20))
    if all_curled and thumb_len > 0.25 and tf_angle > 85:
        dx, dy = tdir
        angle = abs(math.degrees(math.atan2(dx, -dy)))
        score = 5 if angle < 40 else 4 if angle < 70 else 3 if angle < 110 else 2 if angle < 150 else 1
        return ("THUMBS", score)
    return None


def main():
    for max_curl in [0.25, 0.30, 0.35]:
        print(f"\n{'=' * 80}\n  max_curl = {max_curl}\n{'=' * 80}")
        print(f"{'sample':<38} {'RTMPose-only v4':<44}")
        correct = 0;
        total_scored = 0;
        false_positives = []
        for f in sorted(SAMPLES.rglob("*.jpg")):
            rel = str(f.relative_to(SAMPLES));
            score_dir = rel.split("/")[0]
            if score_dir == "search": continue
            img = decode_640(f);
            iw, ih = img.shape[1], img.shape[0];
            boxes = rtmdet_boxes(img)
            per_box = []
            for box in boxes[:MAX_HANDS]:
                if box[4] < MIN_DET: per_box.append("det<0.25"); continue
                cx, cy, side = expand_square(box[:4], iw, ih);
                best = None
                for deg in sorted(set([0, 90, 180, 270])):
                    crop = rotate_and_crop_square(img, cx, cy, side, deg)
                    if crop is None: continue
                    kps = rtmpose_landmarks(crop);
                    kp_mean = float(kps[:, 2].mean())
                    p = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(21)]
                    cl = classify(p, max_curl)
                    if cl is not None:
                        cand = (cl[0], cl[1], kp_mean, deg)
                        if best is None or kp_mean > best[2]: best = cand
                        if kp_mean >= 0.3: break
                if best is None:
                    per_box.append("unrated")
                else:
                    per_box.append(f"{best[0]}-{best[1]}@{best[3]} kp={best[2]:.2f}")
            has_gesture = any(
                not p.startswith("unrated") and not p.startswith("det<") for p in per_box)
            if score_dir == "no_score":
                status = "NO_GESTURE" if not has_gesture else "FP"
                print(f"{rel:<38} [{status:<42}] {'; '.join(per_box)}")
                if has_gesture: false_positives.append(rel)
            else:
                total_scored += 1;
                expected = int(score_dir)
                ok = any(f"-{expected}" in p for p in per_box if
                         not p.startswith("unrated") and not p.startswith("det<"))
                if ok: correct += 1
                print(f"{rel:<38} [{'OK' if ok else 'MISS':<42}] {'; '.join(per_box)}")
        print(f"accuracy {correct}/{total_scored}, false positives: {false_positives}")


if __name__ == "__main__":
    sys.exit(main())
