#!/usr/bin/env python3
"""OK-sign circle geometry feature dump.

The user requirement: an OK sign is a CIRCLE made with the thumb and index
finger, which must FACE THE CAMERA. Just "thumb tip touches index tip" is too
broad (folded hands / holding something also satisfy it).

This script dumps the ring geometry for every detected hand where the thumb
tip is near the index tip, across real OK-sign samples (3/3_ok_sign*.jpg) and
the no_score false positives (no_score_holding_on_folded_hands etc.), so we
can design a circle detector:

  ring area   — shoelace area of the closed loop [2,3,4,8,7,6] / handSize^2.
                A real circle facing the camera has a large projected area;
                folded/parallel fingers collapse to a thin sliver (area ~ 0).
  circularity — 4*pi*Area / Perimeter^2 (1 = perfect circle). The "faces the
                camera" proxy: an edge-on ring projects as a thin ellipse.
  ring w/h    — the loop's width (dist 2-5) vs height (perp dist of the tip
                contact point from the 2-5 line), both / handSize.
  curls       — how bent the thumb (2->3->4) and index (5->6->7->8) are vs
                their straight-line distance.
"""
import cv2
import math
import numpy as np
import onnxruntime as ort
import sys
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
        x1 = max(int(o[0] / ratio), 0);
        y1 = max(int(o[1] / ratio), 0)
        x2 = min(int(o[2] / ratio), iw);
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


def shoelace_area(poly):
    area = 0.0
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        area += x1 * y2 - x2 * y1
    return abs(area) / 2.0


def ring_features(p, hs):
    """Geometry of the thumb+index loop (circle) — all normalized by hs."""
    ring = [p[2], p[3], p[4], p[8], p[7], p[6]]
    area = shoelace_area(ring) / (hs * hs)
    perim = sum(dist(ring[i], ring[(i + 1) % len(ring)]) for i in range(len(ring))) / hs
    circularity = 4 * math.pi * shoelace_area(ring) / (perim * hs) ** 2 if perim > 0 else 0.0
    # ring width = thumb MCP -> index MCP; ring height = perp dist of the tip
    # contact midpoint from the (2->5) line
    tip_mid = ((p[4][0] + p[8][0]) / 2, (p[4][1] + p[8][1]) / 2)
    v = (p[5][0] - p[2][0], p[5][1] - p[2][1])
    vlen = math.hypot(*v)
    cross = v[0] * (tip_mid[1] - p[2][1]) - v[1] * (tip_mid[0] - p[2][0])
    ring_h = abs(cross) / vlen / hs if vlen > 0 else 0.0
    ring_w = dist(p[2], p[5]) / hs
    # curl: bent length / straight length
    thumb_curl = (dist(p[2], p[3]) + dist(p[3], p[4])) / dist(p[2], p[4]) if dist(p[2],
                                                                                  p[4]) > 0 else 0.0
    index_curl = (dist(p[5], p[6]) + dist(p[6], p[7]) + dist(p[7], p[8])) / dist(p[5],
                                                                                 p[8]) if dist(p[5],
                                                                                               p[
                                                                                                   8]) > 0 else 0.0
    return {
        "tip_dist": dist(p[4], p[8]) / hs,
        "ring_area": area,
        "circularity": circularity,
        "ring_w": ring_w,
        "ring_h": ring_h,
        "thumb_curl": thumb_curl,
        "index_curl": index_curl,
    }


FILES = [
    "3/3_ok_sign.jpg",
    "3/3_ok_sign_peanuts.jpg",
    "no_score/no_score_holding_on_folded_hands.jpg",
    "no_score/no_score_holding_in_hand.jpg",
    "no_score/no_score_holding_in_two_hands.jpg",
    "no_score/no_score_hands.jpg",
    "no_score/no_score_no_hands.jpg",
    "2/2_coffee.jpg",
    "2/2_peanuts.jpg",
    "3/3_open_hand_palm_down.jpg",
]

# also check the exact FP rotation (180, both factors) of holding_in_two_hands
FP_FILES = [
    "no_score/no_score_holding_in_two_hands.jpg",
]


def dump(fpath, factor):
    f = SAMPLES / fpath
    img = decode_640(f)
    iw, ih = img.shape[1], img.shape[0]
    boxes = rtmdet_boxes(img)
    for box in boxes[:MAX_HANDS]:
        if box[4] < MIN_DET:
            print(f"{fpath:<40}  --   det={box[4]:.2f} < {MIN_DET}")
            continue
        palm_deg = palm_rotation(img, box[:4])
        cx, cy, side = expand_square(box[:4], iw, ih, factor)
        printed = False
        for deg in sorted(set([0, 90, 180, 270, palm_deg])):
            crop = rotate_and_crop_square(img, cx, cy, side, deg)
            if crop is None:
                continue
            kps = rtmpose_landmarks(crop)
            kp_mean = float(kps[:, 2].mean())
            p = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(21)]
            hs = dist(p[0], p[9])
            if hs <= 0:
                continue
            feats = ring_features(p, hs)
            # also compute whether the old gesture set would classify it
            mid_ext = dist(p[12], p[0]) > dist(p[10], p[0])
            ring_ext = dist(p[16], p[0]) > dist(p[14], p[0])
            pink_ext = dist(p[20], p[0]) > dist(p[18], p[0])
            ok_circle = (
                    feats["tip_dist"] < 0.2 and mid_ext and ring_ext and pink_ext and
                    feats["thumb_curl"] >= 1.08 and feats["index_curl"] >= 1.15 and
                    feats["circularity"] >= 0.40
            )
            print(f"f{factor} {fpath:<40} {deg:>4} {kp_mean:>5.2f} {feats['tip_dist']:>5.2f} "
                  f"{feats['ring_area']:>6.3f} {feats['circularity']:>5.2f} "
                  f"{feats['ring_w']:>5.2f} {feats['ring_h']:>5.2f} "
                  f"{feats['thumb_curl']:>6.2f} {feats['index_curl']:>6.2f} "
                  f"mid={mid_ext} ring={ring_ext} pink={pink_ext} -> {'OK_CIRCLE' if ok_circle else '-'}")
            printed = True
        if not printed:
            print(f"f{factor} {fpath:<40}  --   no crops")


def main():
    print(
        f"{'file':<47} {'deg':>4} {'kp':>5} {'tip':>5} {'area':>6} {'circ':>5} {'w':>5} {'h':>5} {'tcurl':>6} {'icurl':>6} {'ext-flags'} {'verdict'}")
    print("-" * 120)
    for fpath in FILES:
        dump(fpath, 1.2)
    print("\n=== FP check: holding_in_two_hands at both factors ===")
    for fpath in FP_FILES:
        dump(fpath, 1.2)
        dump(fpath, 0.85)


if __name__ == "__main__":
    sys.exit(main())
