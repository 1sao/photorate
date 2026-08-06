#!/usr/bin/env python3
"""RTMPose-only pipeline benchmark (no sparse landmark model).

Mirrors what the app pipeline would look like if the sparse landmark model
(hand_landmark_sparse.onnx) were removed: RTMDet detector -> palm rotation ->
RTMPose handles BOTH presence gating and rating.

Questions answered per sample:
  - What gesture/score does RTMPose rate (image-space preference like the app)?
  - What is the mean RTMPose keypoint confidence (kpMean) — the natural
    replacement for the sparse presence gate?
  - For no_score samples: does any kpMean threshold filter them?

Also reports per-inference latency for RTMPose vs the sparse model.

Requires: pip install onnxruntime opencv-python-headless numpy
Usage: python3 rtmpose_only.py
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
SPARSE = MODELS / "hand_landmark_sparse.onnx"
RTMPOSE = MODELS / "rtmpose_hand.onnx"
MIN_DET = 0.25  # app's OnnxLandmarkerOptions.minHandDetectionConfidence
MIN_RTMPOSE_KP = 0.3  # app's MIN_RTMPOSE_KP (image-space trust threshold)
BOX_EXPANSION = 1.2
FALLBACK_FACTORS = (1.2, 0.85)
MAX_HANDS = 2
DECODE_MIN_DIM = 640
RTMPOSE_SIZE = 256

MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)

sess_rtm = ort.InferenceSession(str(RTMDET), providers=["CPUExecutionProvider"])
sess_palm = ort.InferenceSession(str(PALM), providers=["CPUExecutionProvider"])
sess_sparse = ort.InferenceSession(str(SPARSE), providers=["CPUExecutionProvider"])
sess_rtmp = ort.InferenceSession(str(RTMPOSE), providers=["CPUExecutionProvider"])


# --- timing ---
def bench(fn, n=3):
    best = float("inf")
    for _ in range(n):
        t0 = time.perf_counter()
        fn()
        best = min(best, (time.perf_counter() - t0) * 1000)
    return best


# --- preprocessing ports (same math as AndroidOnnxHandLandmarker) ---

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
    # App's detectBoxes keeps score>0 (RTMDet's baked NMS zeroes suppressed
    # dets); the per-box minHandDetectionConfidence check happens in the
    # pipeline loop (MIN_DET below).
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


def top_down_affine(crop):
    """Port of AndroidOnnxHandLandmarker.topDownAffine (mmpose top-down)."""
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


def sparse_landmark(img, box, degree):
    """Sparse landmark presence + hand_score (current middle step)."""
    x1, y1, x2, y2 = box[:4]
    iw, ih = img.shape[1], img.shape[0]
    cx, cy, side = expand_square(box, iw, ih)
    crop = rotate_and_crop_square(img, cx, cy, side, degree)
    if crop is None:
        return None
    padded224, _ = keep_aspect_resize_and_pad(crop, 224, 224)
    inp = np.asarray([padded224.transpose(2, 0, 1) / 255.0], dtype=np.float32)
    xyz, hand_score, _ = sess_sparse.run(None, {"input": inp})
    return float(hand_score[0][0])


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


def rtmpose_only_pipeline(img, boxes):
    """RTMPose as the sole landmarker: rotations {0,90,180,270,palmDeg} x factors {1.2, 0.85}."""
    iw, ih = img.shape[1], img.shape[0]
    per_box = []
    for box in boxes[:MAX_HANDS]:
        if box[4] < MIN_DET:
            per_box.append(("det<0.25", None))
            continue
        palm_deg = palm_rotation(img, box[:4])[1]
        candidates = sorted(set([0, 90, 180, 270, palm_deg]))
        image_space = None
        best_rotated = None
        for factor in FALLBACK_FACTORS:
            cx, cy, side = expand_square(box[:4], iw, ih, factor)
            for deg in candidates:
                crop = rotate_and_crop_square(img, cx, cy, side, deg)
                if crop is None:
                    continue
                kps = rtmpose_landmarks(crop)
                kp_mean = float(kps[:, 2].mean())
                p = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(21)]
                cl = classify(p)
                entry = {"kp_mean": kp_mean, "deg": deg, "cls": cl}
                if deg == 0:
                    if image_space is None or kp_mean > image_space["kp_mean"]:
                        image_space = entry
                    continue
                if best_rotated is None:
                    best_rotated = entry
                elif cl is not None and cl[0] == "THUMBS" and best_rotated["cls"] is not None and \
                        best_rotated["cls"][0] != "THUMBS":
                    best_rotated = entry
                elif cl is not None and best_rotated["cls"] is not None and cl[0] == \
                        best_rotated["cls"][0] == "THUMBS":
                    if cl[1] > best_rotated["cls"][1] or (
                            cl[1] == best_rotated["cls"][1] and kp_mean > best_rotated["kp_mean"]):
                        best_rotated = entry
                elif cl is not None and best_rotated["cls"] is None:
                    best_rotated = entry
                elif kp_mean > best_rotated["kp_mean"] and (
                        best_rotated["cls"] is None or cl is not None and cl[0] != "THUMBS"):
                    if not (best_rotated["cls"] is not None and best_rotated["cls"][
                        0] == "THUMBS" and (cl is None or cl[0] != "THUMBS")):
                        best_rotated = entry
            # image-space preference: a confident upright rating on the 1.2x
            # crop is the winner; skip the 0.85x pass (mirrors the app).
            if factor == BOX_EXPANSION and image_space is not None and image_space[
                "kp_mean"] >= MIN_RTMPOSE_KP:
                break
        winner = None
        if image_space is not None and image_space["kp_mean"] >= MIN_RTMPOSE_KP:
            winner = image_space
        elif best_rotated is not None:
            winner = best_rotated
        else:
            winner = image_space
        if winner is None:
            per_box.append(("no-kps", None))
            continue
        cl = winner["cls"]
        label = f"{winner['kp_mean']:.2f}@{winner['deg']}"
        label += f"/{cl[0]}-{cl[1]}" if cl else "/unrated"
        per_box.append((label, winner))
    return per_box


def main():
    print(f"{'sample':<38} {'RTMPose-only':<40} {'sparse-hs'}")
    scored_min_kp, no_score_max_kp = 1.0, -1.0
    scored_kps, no_score_kps = [], []
    correct = 0
    total_scored = 0
    for f in sorted(SAMPLES.rglob("*.jpg")):
        rel = str(f.relative_to(SAMPLES))
        score_dir = rel.split("/")[0]
        img = decode_640(f)
        boxes = rtmdet_boxes(img)
        results = rtmpose_only_pipeline(img, boxes)
        # sparse hand_score using bestSparseLandmarks logic (multi-rot, best presence)
        sparse_hs = []
        for box in boxes[:MAX_HANDS]:
            if box[4] < MIN_DET:
                continue
            iw, ih = img.shape[1], img.shape[0]
            palm_deg = palm_rotation(img, box[:4])[1]
            cx, cy, side = expand_square(box[:4], iw, ih)
            best_hs = 0.0
            for deg in sorted(set([palm_deg, 0, 90, 180, 270])):
                hs = sparse_landmark(img, box[:4], deg)
                if hs is not None:
                    best_hs = max(best_hs, hs)
                    if hs >= 0.35:
                        break
            sparse_hs.append(f"{best_hs:.2f}") if best_hs > 0 else sparse_hs.append("0.00")
        labels = ";".join(r[0] for r in results) if results else "-"
        print(f"{rel:<38} [{labels:<38}] {';'.join(sparse_hs)}")
        if score_dir == "no_score":
            for _, w in results:
                if w is not None:
                    no_score_kps.append(w["kp_mean"])
                    no_score_max_kp = max(no_score_max_kp, w["kp_mean"])
        else:
            total_scored += 1
            expected = int(score_dir)
            best = None
            for _, w in results:
                if w is not None and w["cls"] is not None and w["cls"][1] == expected:
                    best = w
                    break
            # also record the best kp_mean among rated hands
            for _, w in results:
                if w is not None and w["cls"] is not None:
                    scored_kps.append(w["kp_mean"])
                    scored_min_kp = min(scored_min_kp, w["kp_mean"])
            ok = best is not None
            # also print what the 1.2x non-rotated (image-space) best gives
            ispace = None
            for _, w in results:
                if w is not None and w["deg"] == 0 and w["cls"] is not None and w["kp_mean"] >= 0.3:
                    if ispace is None or w["kp_mean"] > ispace["kp_mean"]:
                        ispace = w
            ispace_label = f"ispace={ispace['cls'] if ispace else None}" if ispace else "ispace=None"
            if ok:
                correct += 1
            print(
                f"    {'OK' if ok else 'MISS'} expected={expected} best={best['cls'] if best else None} {ispace_label}")
    print(f"\nscored rated kpMean min={scored_min_kp:.3f} (n={len(scored_kps)})")
    print(f"no_score kpMean max={no_score_max_kp:.3f} (n={len(no_score_kps)})")
    print(f"accuracy {correct}/{total_scored}")
    print(
        f"\ngate candidates: kp>=0.30 -> {'YES' if scored_min_kp >= 0.30 and no_score_max_kp < 0.30 else 'NO'}")
    print(
        f"gate candidates: kp>=0.25 -> {'YES' if scored_min_kp >= 0.25 and no_score_max_kp < 0.25 else 'NO'}")

    # latency: sparse 224 vs rtmpose 256 on the same crop
    img = decode_640(next(SAMPLES.rglob("5/*.jpg")))
    boxes = rtmdet_boxes(img)
    box = boxes[0][:4]
    cx, cy, side = expand_square(box, img.shape[1], img.shape[0])
    crop = rotate_and_crop_square(img, cx, cy, side, 0)
    t_sparse = bench(lambda: sparse_landmark(img, box, 0))
    t_rtm = bench(lambda: rtmpose_landmarks(crop))
    print(
        f"\nlatency per inference: sparse {t_sparse:.1f} ms, rtmpose {t_rtm:.1f} ms (ratio {t_rtm / max(t_sparse, 1e-9):.1f}x) — mac CPU, fp32")


if __name__ == "__main__":
    sys.exit(main())
