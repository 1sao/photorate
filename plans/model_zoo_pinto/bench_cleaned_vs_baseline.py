#!/usr/bin/env python3
"""End-to-end parity: current baked models vs the cleaned (raw-output) models.

The cleaned RTMDet outputs raw anchors (boxes [1,2100,4] + scores [1,2100,1]);
the cleaned RTMPose outputs raw SimCC heatmaps (simcc_x/simcc_y [1,21,512]).
This script reimplements the app-side post-processing (score filter + NMS +
SimCC argmax decode) in Python and compares ratings against the baked models
on the real 33-sample set.

Usage:
  onnx/.venv/bin/python plans/model_zoo_pinto/bench_cleaned_vs_baseline.py
"""
import cv2
import math
import numpy as np
import onnxruntime as ort
from pathlib import Path

SAMPLES = Path(__file__).resolve().parent.parent / "samples"
PZ = Path(__file__).resolve().parent
ML = Path(__file__).resolve().parent.parent.parent / "ml" / "original_models"

# --- baseline (baked) models ---
BAKED_RTMDET = ML / "rtmdet_nano_8xb32-300e_hand-267f9c8f" / "end2end.onnx"
BAKED_RTMPOSE = ML / "rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320" / "end2end.onnx"
# --- cleaned (raw-output) models ---
CLEAN_RTMDET = PZ / "rtmdet_320_f32clean_unsorted.onnx"
CLEAN_RTMPOSE = PZ / "rtmpose_hand_256_f32clean.onnx"

MIN_DET = 0.25
RTMPOSE_SIZE = 256
SIMCC_SIZE = 512
SIMCC_SPLIT_RATIO = 2.0
BOX_EXPANSION = 1.2
FALLBACK_FACTORS = (1.2, 0.85)
MAX_HANDS = 2
DECODE_MIN_DIM = 640
MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)

sess_b_det = ort.InferenceSession(str(BAKED_RTMDET), providers=["CPUExecutionProvider"])
sess_b_pose = ort.InferenceSession(str(BAKED_RTMPOSE), providers=["CPUExecutionProvider"])
sess_c_det = ort.InferenceSession(str(CLEAN_RTMDET), providers=["CPUExecutionProvider"])
sess_c_pose = ort.InferenceSession(str(CLEAN_RTMPOSE), providers=["CPUExecutionProvider"])

# OK-sign thresholds (same as rtmpose_only_v5.py)
OK_MIN_THUMB_CURL = 1.08
OK_MIN_INDEX_CURL = 1.15
OK_MIN_CIRCULARITY = 0.40
OK_MIN_RING_SIDE = 0.25


def decode_640(path):
    img = cv2.imread(str(path))
    ih, iw = img.shape[:2]
    s = max(min(ih, iw) // DECODE_MIN_DIM, 1)
    if s > 1:
        img = cv2.resize(img, (iw // s, ih // s), interpolation=cv2.INTER_AREA)
    return img


def det_input(img):
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
    return f, ratio, iw, ih


# Baked NMS params from the original export (initializers 1443/1444/1445).
NMS_MAX_OUT = 200
NMS_IOU_THR = 0.6
NMS_SCORE_THR = 0.05


def nms(boxes, scores, iou_thr=NMS_IOU_THR, score_thr=NMS_SCORE_THR, max_out=NMS_MAX_OUT):
    """Port of ONNX NonMaxSuppression (single class): filter by score, then
    suppress by IoU, keep at most max_out in descending score order.
    Returns indices into the ORIGINAL (unfiltered) input arrays."""
    m = scores > score_thr
    kept_mask_idx = np.nonzero(m)[0]
    boxes = boxes[m].astype(np.float64)
    scores = scores[m]
    order = np.argsort(-scores, kind="stable")
    boxes = boxes[order]
    scores = scores[order]
    keep = []
    for i in range(min(len(boxes), max_out)):
        suppressed = False
        for j in keep:
            xx1 = max(boxes[i, 0], boxes[j, 0])
            yy1 = max(boxes[i, 1], boxes[j, 1])
            xx2 = min(boxes[i, 2], boxes[j, 2])
            yy2 = min(boxes[i, 3], boxes[j, 3])
            w = max(0.0, xx2 - xx1)
            h = max(0.0, yy2 - yy1)
            inter = w * h
            area_i = (boxes[i, 2] - boxes[i, 0]) * (boxes[i, 3] - boxes[i, 1])
            area_j = (boxes[j, 2] - boxes[j, 0]) * (boxes[j, 3] - boxes[j, 1])
            iou = inter / (area_i + area_j - inter + 1e-9)
            if iou > iou_thr:
                suppressed = True
                break
        if not suppressed:
            keep.append(i)
    return kept_mask_idx[order[keep]]


def rtmdet_boxes_baked(img):
    """Baseline: baked-NMS dets [N,5] from the original end2end export."""
    f, ratio, iw, ih = det_input(img)
    out, _ = sess_b_det.run(None, {"input": f})
    dets = out[0]
    boxes = []
    for o in dets:
        if o[4] <= 0:
            continue
        x1 = max(int(o[0] / ratio), 0)
        y1 = max(int(o[1] / ratio), 0)
        x2 = min(int(o[2] / ratio), iw)
        y2 = min(int(o[3] / ratio), ih)
        if x2 > x1 and y2 > y1 and x2 - x1 >= 8 and y2 - y1 >= 8:
            boxes.append((x1, y1, x2, y2, float(o[4])))
    boxes.sort(key=lambda d: -d[4])
    return boxes


def rtmdet_boxes_cleaned(img):
    """Candidate: raw anchors + score filter + NMS (baked-model params) in
    Python — mirrors the app-side post-processing the cleaned model needs."""
    f, ratio, iw, ih = det_input(img)
    boxes_raw, scores_raw = sess_c_det.run(None, {"input": f})
    boxes_raw = boxes_raw[0]  # [2100,4] in 320-space (top-left padded)
    scores_raw = scores_raw[0, :, 0]
    keep = nms(boxes_raw, scores_raw)
    boxes = []
    for i in keep:
        o = boxes_raw[i]
        x1 = max(int(o[0] / ratio), 0)
        y1 = max(int(o[1] / ratio), 0)
        x2 = min(int(o[2] / ratio), iw)
        y2 = min(int(o[3] / ratio), ih)
        if x2 > x1 and y2 > y1 and x2 - x1 >= 8 and y2 - y1 >= 8:
            boxes.append((x1, y1, x2, y2, float(scores_raw[i])))
    boxes.sort(key=lambda d: -d[4])
    return boxes


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
    scale = RTMPOSE_SIZE / w_scaled
    m = np.float32([[scale, 0, RTMPOSE_SIZE / 2 - scale * w / 2],
                    [0, scale, RTMPOSE_SIZE / 2 - scale * h / 2]])
    return cv2.warpAffine(crop, m, (RTMPOSE_SIZE, RTMPOSE_SIZE), borderValue=0)


def kps_baked(crop):
    """Baseline: raw SimCC export, decoded identically to kps_cleaned (the
    original end2end.onnx has the same raw SimCC outputs)."""
    return _simcc_decode(crop, sess_b_pose)


def kps_cleaned(crop):
    """Port of AndroidOnnxHandLandmarker.rtmposeLandmarks (SimCC decode +
    inverse affine). Returns [21,3] crop-space keypoints."""
    return _simcc_decode(crop, sess_c_pose)


def _simcc_decode(crop, sess):
    h, w = crop.shape[:2]
    warped = top_down_affine(crop)
    f = ((warped.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32)
    simcc_x, simcc_y = sess.run(None, {"input": f})
    sx = simcc_x[0]
    sy = simcc_y[0]
    bbox_w, bbox_h = 1.25 * w, 1.25 * h
    scale = RTMPOSE_SIZE / max(bbox_h * 0.75, bbox_w)
    cx, cy = w / 2.0, h / 2.0
    out = np.zeros((21, 3), np.float32)
    for i in range(21):
        xi = int(np.argmax(sx[i]))
        yi = int(np.argmax(sy[i]))
        out[i, 0] = (xi / SIMCC_SPLIT_RATIO - RTMPOSE_SIZE / 2.0) / scale + cx
        out[i, 1] = (yi / SIMCC_SPLIT_RATIO - RTMPOSE_SIZE / 2.0) / scale + cy
        out[i, 2] = min(sx[i, xi], sy[i, yi])
    return out


def box_iou(a, b):
    xx1 = max(a[0], b[0])
    yy1 = max(a[1], b[1])
    xx2 = min(a[2], b[2])
    yy2 = min(a[3], b[3])
    w = max(0.0, xx2 - xx1)
    h = max(0.0, yy2 - yy1)
    inter = w * h
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    return inter / (area_a + area_b - inter + 1e-9)


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


def shoelace_area(poly):
    area = 0.0
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        area += x1 * y2 - x2 * y1
    return abs(area) / 2.0


def ok_circle(p, hs):
    ring = [p[2], p[3], p[4], p[8], p[7], p[6]]
    area = shoelace_area(ring)
    perim = sum(dist(ring[i], ring[(i + 1) % len(ring)]) for i in range(len(ring)))
    circularity = 4 * math.pi * area / (perim * perim) if perim > 0 else 0.0
    thumb_curl = (dist(p[2], p[3]) + dist(p[3], p[4])) / dist(p[2], p[4]) if dist(p[2],
                                                                                  p[4]) > 0 else 0.0
    index_curl = (dist(p[5], p[6]) + dist(p[6], p[7]) + dist(p[7], p[8])) / dist(p[5],
                                                                                 p[8]) if dist(p[5],
                                                                                               p[
                                                                                                   8]) > 0 else 0.0
    tip_mid = ((p[4][0] + p[8][0]) / 2, (p[4][1] + p[8][1]) / 2)
    v = (p[5][0] - p[2][0], p[5][1] - p[2][1])
    vlen = math.hypot(*v)
    cross = v[0] * (tip_mid[1] - p[2][1]) - v[1] * (tip_mid[0] - p[2][0])
    ring_h = abs(cross) / vlen / hs if vlen > 0 else 0.0
    ring_w = dist(p[2], p[5]) / hs
    return {
        "circularity": circularity,
        "thumb_curl": thumb_curl,
        "index_curl": index_curl,
        "ring_w": ring_w,
        "ring_h": ring_h,
    }


def classify(p):
    hs = dist(p[0], p[9])
    if hs <= 0:
        return None
    thumb_len = dist(p[2], p[4]) / hs
    thumb_index = dist(p[4], p[8]) / hs
    idx_ext = is_extended(p, 6, 8)
    mid_ext = is_extended(p, 10, 12)
    ring_ext = is_extended(p, 14, 16)
    pink_ext = is_extended(p, 18, 20)
    tdir = vec(p[2], p[4])
    idir = vec(p[5], p[8])
    tf_angle = angle_between(tdir, idir)

    if thumb_index < 0.2 and mid_ext and ring_ext and pink_ext:
        ring = ok_circle(p, hs)
        if ring["thumb_curl"] >= OK_MIN_THUMB_CURL and ring["index_curl"] >= OK_MIN_INDEX_CURL and \
                ring["circularity"] >= OK_MIN_CIRCULARITY and min(ring["ring_w"], ring[
            "ring_h"]) >= OK_MIN_RING_SIDE:
            return ("OK_SIGN", 3)

    if idx_ext and pink_ext and not mid_ext:
        return ("ROCK", 5)

    all_curled = not (idx_ext or mid_ext or ring_ext or pink_ext)
    if all_curled and thumb_len > 0.25 and tf_angle > 85:
        tip_dx, tip_dy = vec(p[3], p[4])
        angle = abs(math.degrees(math.atan2(tip_dx, -tip_dy)))
        score = 5 if angle < 40 else 4 if angle < 70 else 3 if angle < 110 else 2 if angle < 150 else 1
        return ("THUMBS", score)
    return None


def run_pipeline(img, boxes, kps_fn):
    iw, ih = img.shape[1], img.shape[0]
    per_box = []
    for box in boxes[:MAX_HANDS]:
        if box[4] < MIN_DET:
            per_box.append(("det<0.25", None))
            continue
        image_space = None
        best_rotated = None
        for factor in FALLBACK_FACTORS:
            cx, cy, side = expand_square(box[:4], iw, ih, factor)
            for deg in (0, 90, 180, 270):
                crop = rotate_and_crop_square(img, cx, cy, side, deg)
                if crop is None:
                    continue
                kps = kps_fn(crop)
                kp_mean = float(kps[:, 2].mean())
                p = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(21)]
                cl = classify(p)
                if cl is None:
                    continue
                entry = {"kp_mean": kp_mean, "deg": deg, "cls": cl}
                if deg == 0:
                    if image_space is None or kp_mean > image_space["kp_mean"]:
                        image_space = entry
                    continue
                if best_rotated is None:
                    best_rotated = entry
                elif cl[0] == "THUMBS" and best_rotated["cls"][0] != "THUMBS":
                    best_rotated = entry
                elif cl[0] != "THUMBS" and best_rotated["cls"][0] == "THUMBS":
                    pass
                elif cl[0] == "THUMBS":
                    if cl[1] > best_rotated["cls"][1] or (
                            cl[1] == best_rotated["cls"][1] and kp_mean > best_rotated["kp_mean"]):
                        best_rotated = entry
                elif kp_mean > best_rotated["kp_mean"]:
                    best_rotated = entry
        winner = None
        if image_space is not None and image_space["kp_mean"] >= 0.3:
            winner = image_space
        elif best_rotated is not None:
            winner = best_rotated
        else:
            winner = image_space
        if winner is None:
            per_box.append(("no-kps", None))
            continue
        per_box.append(
            (f"{winner['cls'][0]}-{winner['cls'][1]}" if winner["cls"] else "unrated", winner))
    return per_box


def main():
    print(f"{'sample':<38} {'baked':<12} {'cleaned':<12} match")
    same = 0
    total = 0
    max_kp_diff = 0.0
    box_diffs = []
    for f in sorted(SAMPLES.rglob("*.jpg")):
        rel = str(f.relative_to(SAMPLES))
        if rel.split("/")[0] == "search":
            continue
        img = decode_640(f)
        boxes_b = rtmdet_boxes_baked(img)
        boxes_c = rtmdet_boxes_cleaned(img)

        # Compare detection boxes by IoU matching (positional zip mixes up
        # which hand is "first" when scores are near-tied).
        matched = 0
        for b in boxes_b[:MAX_HANDS]:
            best_iou = 0.0
            for c in boxes_c:
                iou = box_iou(b[:4], c[:4])
                best_iou = max(best_iou, iou)
            if best_iou > 0.5:
                matched += 1
                bb = np.array(b[:4], dtype=np.float64)
                # find the actual matched box center distance
                for c in boxes_c:
                    if box_iou(b[:4], c[:4]) == best_iou and best_iou > 0.5:
                        box_diffs.append(math.dist(b[:4], c[:4]))
                        break
        if matched != len(boxes_b[:MAX_HANDS]):
            print(
                f"  !! box count/overlap mismatch on {rel}: baked={len(boxes_b[:MAX_HANDS])} matched={matched} cleaned={len(boxes_c)}")

        res_b = run_pipeline(img, boxes_b, kps_baked)
        res_c = run_pipeline(img, boxes_c, kps_cleaned)
        labels_b = ";".join(r[0] for r in res_b)
        labels_c = ";".join(r[0] for r in res_c)
        match = "OK" if labels_b == labels_c else "DIFF"
        if match == "OK":
            same += 1
        total += 1
        print(f"{rel:<38} {labels_b:<12} {labels_c:<12} {match}")

        # Keypoint parity on the same deg-0 1.2x crop for box 0.
        if boxes_b and boxes_c:
            cx, cy, side = expand_square(boxes_b[0][:4], img.shape[1], img.shape[0])
            crop = rotate_and_crop_square(img, cx, cy, side, 0)
            if crop is not None:
                kb = kps_baked(crop)
                kc = kps_cleaned(crop)
                d = float(np.abs(kb - kc).max())
                max_kp_diff = max(max_kp_diff, d)

    print(f"\nsamples agreeing: {same}/{total}")
    print(
        f"box L2 diff (mean over matched first-hand boxes): {np.mean(box_diffs) if box_diffs else 0:.3f}px")
    print(f"max keypoint diff (deg-0 1.2x crop): {max_kp_diff:.4f}px")


if __name__ == "__main__":
    main()
