#!/usr/bin/env python3
"""Run the rtmpose_only_v5 pipeline on on_device_test images and dump detailed features.

Reports per-image: gesture, score, kp_mean, and whether it matches expectations.
For uncertain_rejected and user_corrections images, dumps the full feature set
to understand why each was scored as it was.
"""
import cv2
import math
import numpy as np
import onnxruntime as ort
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODELS = HERE.parent / "onnx" / "models"
IMAGES = HERE / "images"

RTMDET = MODELS / "rtmdet_n_hand.onnx"
PALM = MODELS / "palm_detection_full.onnx"
RTMPOSE = MODELS / "rtmpose_hand.onnx"

MIN_DET = 0.25
MIN_RTMPOSE_KP = 0.3
BOX_EXPANSION = 1.2
FALLBACK_FACTORS = (1.2, 0.85)
MAX_HANDS = 2
DECODE_MIN_DIM = 640
RTMPOSE_SIZE = 256
MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)

sess_rtm = ort.InferenceSession(str(RTMDET), providers=["CPUExecutionProvider"])
sess_palm = ort.InferenceSession(str(PALM), providers=["CPUExecutionProvider"])
sess_rtmp = ort.InferenceSession(str(RTMPOSE), providers=["CPUExecutionProvider"])

# OK-sign thresholds (from rtmpose_only_v5.py)
OK_MIN_THUMB_CURL = 1.08
OK_MIN_INDEX_CURL = 1.15
OK_MIN_CIRCULARITY = 0.40
OK_MIN_RING_SIDE = 0.25


def decode_640(path):
    img = cv2.imread(str(path))
    if img is None:
        return None
    ih, iw = img.shape[:2]
    s = max(min(ih, iw) // DECODE_MIN_DIM, 1)
    if s > 1:
        img = cv2.resize(img, (iw // s, ih // s), interpolation=cv2.INTER_AREA)
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
          cy_p - hw * sin_t - hh * cos_t, cy_p + hw * sin_t + hh * cos_t]
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
    return kps[0]


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
        "area_n": area / (hs * hs),
        "thumb_curl": thumb_curl,
        "index_curl": index_curl,
        "ring_w": ring_w,
        "ring_h": ring_h,
    }


def classify_with_features(p):
    """Returns (gesture, score, features_dict) where features_dict contains
    the raw measurements used for classification."""
    hs = dist(p[0], p[9])
    if hs <= 0:
        return None, None, {}
    thumb_len = dist(p[2], p[4]) / hs
    thumb_index = dist(p[4], p[8]) / hs
    idx_ext = is_extended(p, 6, 8)
    mid_ext = is_extended(p, 10, 12)
    ring_ext = is_extended(p, 14, 16)
    pink_ext = is_extended(p, 18, 20)
    tips = [p[8], p[12], p[16], p[20]]
    spread = sum(dist(tips[i], tips[i + 1]) for i in range(3)) / 3 / hs
    tdir = vec(p[2], p[4])
    idir = vec(p[5], p[8])
    tf_angle = angle_between(tdir, idir)
    # tip direction for thumb angle (like the Kotlin code)
    tip_d = vec(p[3], p[4])
    tip_angle = abs(math.degrees(math.atan2(tip_d[0], -tip_d[1])))
    feats = {
        "hs": hs,
        "thumb_len": thumb_len,
        "thumb_index": thumb_index,
        "idx_ext": idx_ext,
        "mid_ext": mid_ext,
        "ring_ext": ring_ext,
        "pink_ext": pink_ext,
        "spread": spread,
        "tf_angle": tf_angle,
        "tip_angle": tip_angle,
    }
    # Landmark geometry sanity: extent ratio and point spread
    xs = [pp[0] for pp in p]
    ys = [pp[1] for pp in p]
    extent = max(max(xs) - min(xs), max(ys) - min(ys))
    feats["extent_ratio"] = extent / hs if hs > 0 else 0.0
    # wrist-to-MCP straightness check
    feats["wrist_mcp_dist"] = dist(p[0], p[9])
    # All points clustering: std dev of distances from centroid
    cx = sum(xs) / len(xs)
    cy = sum(ys) / len(ys)
    dists_from_center = [math.hypot(x - cx, y - cy) for x, y in zip(xs, ys)]
    mean_d = sum(dists_from_center) / len(dists_from_center)
    std_d = math.sqrt(sum((d - mean_d) ** 2 for d in dists_from_center) / len(dists_from_center))
    feats["point_spread"] = std_d / hs if hs > 0 else 0.0

    if thumb_index < 0.2 and mid_ext and ring_ext and pink_ext:
        ring = ok_circle(p, hs)
        feats.update({f"ring_{k}": v for k, v in ring.items()})
        if ring["thumb_curl"] >= OK_MIN_THUMB_CURL and \
                ring["index_curl"] >= OK_MIN_INDEX_CURL and \
                ring["circularity"] >= OK_MIN_CIRCULARITY and \
                min(ring["ring_w"], ring["ring_h"]) >= OK_MIN_RING_SIDE:
            return ("OK_SIGN", 3, feats)
    if idx_ext and pink_ext and not mid_ext:
        return ("ROCK", 5, feats)
    all_curled = not (idx_ext or mid_ext or ring_ext or pink_ext)
    if all_curled and thumb_len > 0.25 and tf_angle > 85:
        angle = tip_angle
        score = 5 if angle < 40 else 4 if angle < 70 else 3 if angle < 110 else 2 if angle < 150 else 1
        return ("THUMBS", score, feats)
    # Unclassified — return features only
    return None, None, feats


def run_pipeline_detailed(img, boxes, use_palm=True):
    """Run the full pipeline with detailed features per box."""
    iw, ih = img.shape[1], img.shape[0]
    results = []
    for box in boxes[:MAX_HANDS]:
        box_area = (box[2] - box[0]) * (box[3] - box[1])
        img_area = iw * ih
        bbox_area_pct = box_area / img_area * 100 if img_area > 0 else 0
        if box[4] < MIN_DET:
            results.append(
                {"status": "det<0.25", "det_score": box[4], "bbox_area_pct": bbox_area_pct})
            continue
        palm_deg = palm_rotation(img, box[:4]) if use_palm else None
        candidates = sorted(set([0, 90, 180, 270] + ([palm_deg] if use_palm else [])))
        image_space = None
        best_rotated = None
        best_rotated_feats = {}
        for factor in FALLBACK_FACTORS:
            cx, cy, side = expand_square(box[:4], iw, ih, factor)
            for deg in candidates:
                crop = rotate_and_crop_square(img, cx, cy, side, deg)
                if crop is None:
                    continue
                kps = rtmpose_landmarks(crop)
                kp_mean = float(kps[:, 2].mean())
                p = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(21)]
                cl, score, feats = classify_with_features(p)
                if cl is None:
                    continue
                entry = {"kp_mean": kp_mean, "deg": deg, "cls": (cl, score), "feats": feats,
                         "factor": factor}
                if deg == 0:
                    if image_space is None or kp_mean > image_space["kp_mean"]:
                        image_space = entry
                    if factor == BOX_EXPANSION and image_space["kp_mean"] >= MIN_RTMPOSE_KP:
                        break
                    continue
                if best_rotated is None:
                    best_rotated = entry
                    best_rotated_feats = feats
                elif cl == "THUMBS" and (
                best_rotated["cls"][0] if best_rotated else None) != "THUMBS":
                    best_rotated = entry
                    best_rotated_feats = feats
                elif cl != "THUMBS" and (
                best_rotated["cls"][0] if best_rotated else None) == "THUMBS":
                    pass
                elif cl == "THUMBS":
                    if score > best_rotated["cls"][1] or (
                            score == best_rotated["cls"][1] and kp_mean > best_rotated["kp_mean"]):
                        best_rotated = entry
                        best_rotated_feats = feats
                elif kp_mean > best_rotated["kp_mean"]:
                    best_rotated = entry
                    best_rotated_feats = feats
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
            # Still get features from best read for analysis
            cx, cy, side = expand_square(box[:4], iw, ih)
            crop = rotate_and_crop_square(img, cx, cy, side, 0)
            feats_only = {}
            if crop is not None:
                kps = rtmpose_landmarks(crop)
                kp_mean = float(kps[:, 2].mean())
                p = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(21)]
                _, _, feats_only = classify_with_features(p)
            results.append(
                {"status": "unrated", "det_score": box[4], "bbox_area_pct": bbox_area_pct,
                 "feats": feats_only})
            continue
        cl, score = winner["cls"]
        results.append({
            "status": f"{cl}-{score}",
            "gesture": cl,
            "score": score,
            "kp_mean": winner["kp_mean"],
            "deg": winner["deg"],
            "det_score": box[4],
            "bbox_area_pct": bbox_area_pct,
            "feats": winner.get("feats", {}),
        })
    return results


def main():
    categories = {
        "confident_kept": "expected: hand detected",
        "uncertain_kept": "expected: could be filtered (user says still invalid)",
        "uncertain_rejected": "expected: NO hand detected / filtered out",
        "user_corrections": "expected: correct score (match user verdict)",
    }
    user_verdicts = {
        "42": 5,  # confirmed
        "66": 4,  # downgraded
        "147": 2,  # major downgrade
        "148": 3,  # confirmed
        "3484": 4,  # downgraded
    }

    for cat, desc in categories.items():
        cat_dir = IMAGES / cat
        if not cat_dir.exists():
            continue
        print(f"\n{'=' * 120}")
        print(f"  {cat}/ — {desc}")
        print(f"{'=' * 120}")
        print(f"{'file':<65} {'result':<25} {'det':>5} {'kp':>6} {'bbox%':>7} features")
        print("-" * 160)

        for f in sorted(cat_dir.glob("*.jpg")):
            media_id = f.name.split("_")[0]
            img = decode_640(f)
            if img is None:
                print(f"{f.name:<65} DECODE_FAILED")
                continue
            boxes = rtmdet_boxes(img)
            results = run_pipeline_detailed(img, boxes)

            for r in results:
                status = r["status"]
                det = r.get("det_score", 0)
                kp = r.get("kp_mean", 0)
                bbox = r.get("bbox_area_pct", 0)
                feats = r.get("feats", {})

                # Build feature string
                feat_str = ""
                if feats:
                    ext_r = feats.get("extent_ratio", 0)
                    p_spread = feats.get("point_spread", 0)
                    feat_str = f"ext={ext_r:.1f} spread={p_spread:.3f}"
                    if "idx_ext" in feats:
                        feat_str += f" idx={feats['idx_ext']} mid={feats['mid_ext']} ring={feats['ring_ext']} pink={feats['pink_ext']}"
                        feat_str += f" t_len={feats['thumb_len']:.2f} t_i={feats['thumb_index']:.2f} tf_a={feats['tf_angle']:.0f}"

                print(f"{f.name:<65} {status:<25} {det:>5.2f} {kp:>6.3f} {bbox:>6.2f}% {feat_str}")

            # For user_corrections, show expected score
            if cat == "user_corrections" and media_id in user_verdicts:
                expected = user_verdicts[media_id]
                has_correct = any(r.get("score") == expected for r in results)
                print(f"  ^^ user verdict: {expected}, {'MATCH' if has_correct else 'MISMATCH'}")


if __name__ == "__main__":
    main()
