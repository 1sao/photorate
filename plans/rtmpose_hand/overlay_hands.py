#!/usr/bin/env python3
"""RTMPose hand landmark overlay — official MMPose-style processing on the
models this app ships.

Feed it an image, get back an image with up to MAX_HANDS hands overlaid with
the 21 COCO-WholeBody hand keypoints (colored per finger, confidence-gated),
the detection box + detection score, and the mean keypoint confidence.

Pipeline (mirrors the official MMPose hand pipeline from
`from_mmpose/topdown_demo_with_mmdet.py` + the hand5 config's val_pipeline
`from_mmpose/rtmpose-m_8xb256-210e_hand5-256x256.py`):

  1. Detect: RTMDet-nano hand, 320x320 letterbox, RGB, mean/std
     (123.675, 116.28, 103.53) / (58.395, 57.12, 57.375). NMS is baked into
     the exported ONNX. The official mmdet test pipeline pads the letterbox
     with 114, centered; the app pads with 0 top-left — both are supported
     (`--det-pad`, `--det-align`) because the pad value is a caller choice
     (the model graph starts directly with a Conv, no baked letterbox).
  2. Pose: for each of the top-N boxes, take the centered square of side
     max(w, h) (contains the whole box), apply the mmpose top-down affine
     (1.25x bbox -> 256x256, see `top_down_affine`), feed BGR pixels with the
     same mean/std. The ONNX has the SimCC decode baked in; it scales the
     decoded keypoints back to the caller's bbox space (internally the graph
     multiplies `bboxes_width_height` by 1.25, matching the affine) and
     returns [21, 3] = x, y, confidence in the crop's pixel space.
  3. Overlay: box + det score + per-keypoint confidence + mean kp confidence.

Modes:
  - default: the straightforward official-style deg-0 inference, so you can
    see what the model itself produces (no rotation search, no 1.2x/0.85x
    crop expansion, no gesture classifier).
  - `--rotations`: the app's factor x rotation search (port of
    AndroidOnnxHandLandmarker.rtmposeRating): each box is tried at the 1.2x
    and 0.85x crop expansions, at rotations 0/90/180/270, and labeled with
    the winning rotation, its mean confidence and the gesture class (e.g.
    `rot 180(1.2x) kp 0.32 THUMBS-4`); orange label = rotated winner, `GATED`
    = winner below the app's 0.3 kp floor (the app drops the hand; shown
    dimmed here for inspection). The 0.85x pass is skipped when the 1.2x
    crop already yields a confident upright rating, exactly like the app.
    The palm-rotation model is not used by the shipped app (it was removed)
    and is not replicated here.
  - `--rotate-deg`: probe a single rotated crop. Rotated-crop keypoints are
    un-rotated back to image space before drawing, so the overlay stays
    truthful at any rotation.
  - `--contact-sheet`: side-by-side comparison for eyeballing — left panel
    is the official-style deg-0 pass (official letterbox, pad 114 centered),
    right panel is the app's rotation search (app letterbox, pad 0 top-left,
    winner rotation + gesture labels). Both panels run on the same decoded
    image, so different detection boxes between the panels are a real
    letterbox effect, not a difference in input. In `--input-dir` mode a
    combined grid `contact_sheet_all.jpg` is also written.

Other differences from the APP (AndroidOnnxHandLandmarker): the default mode
is raw model outputs only (no gesture classifier / confidence gates);
`--rotations` replicates the app's search including its classifier, kp floor
and 1.2x/0.85x expansions. Image decode is a min-dim 640 scale-down by
default (same as the app's decode); `--full-res` disables it.

This file was created because the rtmpose project's old
`2_RTMPose_inference_with_onnxruntime_in_python` example is no longer present
in the upstream repo (see from_mmpose/examples_README.md); the processing
above follows the official demo + configs that ARE present.

Run:
  onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py IMG.png [--show]
  onnx/.venv/bin/python plans/rtmpose_hand/overlay_hands.py --input-dir plans/samples --output-dir plans/rtmpose_hand/output
"""
import argparse
import csv
import cv2
import math
import numpy as np
import onnxruntime as ort
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_MODELS = HERE.parent.parent / "onnx" / "models"
DETECTOR_ASSET = "rtmdet_n_hand.onnx"
RTMPOSE_ASSET = "rtmpose_hand.onnx"

DET_SIZE = 320  # RTMDet-nano hand input
POSE_SIZE = 256  # RTMPose-m hand5 input
DECODE_MIN_DIM = 640  # app-style decode target (shortest side after scale)
MIN_BOX_SIDE = 8
NUM_LANDMARKS = 21
# The app's multi-rotation search and its image-space trust threshold: the
# deg-0 read wins whenever its mean keypoint confidence reaches this bar,
# otherwise the best rotated read is used (AndroidOnnxHandLandmarker).
ROTATION_SEARCH = (0, 90, 180, 270)
# The app's crop-expansion sweep: each box is tried as a 1.2x square (the
# standard expansion, includes the whole hand) and, when the 1.2x pass cannot
# produce a confident upright rating, a tighter 0.85x square that isolates the
# hand when the detected box is tall/thin and mostly background (2_peanuts).
BOX_EXPANSION = 1.2
FALLBACK_FACTORS = (1.2, 0.85)
MIN_RTMPOSE_KP = 0.3

# mmdet normalization. RTMPose consumes BGR pixels with the same stats
# (verified in the app + plans/benchmarks).
MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)

# COCO-WholeBody hand metainfo (from_mmpose/coco_wholebody_hand.py): RGB
# colors, converted to BGR for cv2, per-finger skeleton links.
KEYPOINT_COLORS_BGR = [
    (255, 255, 255),  # 0  wrist
    *(  # thumb
        (0, 128, 255) for _ in range(4)),  # 1-4
    *(  # forefinger
        (255, 153, 255) for _ in range(4)),  # 5-8
    *(  # middle finger
        (255, 178, 102) for _ in range(4)),  # 9-12
    *(  # ring finger
        (51, 51, 255) for _ in range(4)),  # 13-16
    *(  # pinky
        (0, 255, 0) for _ in range(4)),  # 17-20
]
SKELETON = [
    (0, 1), (1, 2), (2, 3), (3, 4),  # thumb
    (0, 5), (5, 6), (6, 7), (7, 8),  # forefinger
    (0, 9), (9, 10), (10, 11), (11, 12),  # middle finger
    (0, 13), (13, 14), (14, 15), (15, 16),  # ring finger
    (0, 17), (17, 18), (18, 19), (19, 20),  # pinky
]

# --- App gesture classifier (ported 1:1 from the verified Python mirror
# plans/benchmarks/rtmpose_only_v5.py `classify()`, which mirrors
# HandGestureClassifier in the app). Returns a (gesture, score) tuple or
# None. Gestures: OK_SIGN-3, ROCK-5, THUMBS-1..5 (thumb angle). ----
OK_MIN_THUMB_CURL = 1.08
OK_MIN_INDEX_CURL = 1.15
OK_MIN_CIRCULARITY = 0.40
OK_MIN_RING_SIDE = 0.25


def _dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def _vec(a, b):
    return (b[0] - a[0], b[1] - a[1])


def _angle_between(a, b):
    dot = a[0] * b[0] + a[1] * b[1]
    mag = math.hypot(*a) * math.hypot(*b)
    if mag == 0:
        return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, dot / mag))))


def _is_extended(p, pip_i, tip_i):
    return _dist(p[tip_i], p[0]) > _dist(p[pip_i], p[0])


def _shoelace_area(poly):
    area = 0.0
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        area += x1 * y2 - x2 * y1
    return abs(area) / 2.0


def _ok_circle(p, hs):
    """The thumb+index loop must be a real ring facing the camera."""
    ring = [p[2], p[3], p[4], p[8], p[7], p[6]]
    area = _shoelace_area(ring)
    perim = sum(_dist(ring[i], ring[(i + 1) % len(ring)]) for i in range(len(ring)))
    circularity = 4 * math.pi * area / (perim * perim) if perim > 0 else 0.0
    thumb_curl = ((_dist(p[2], p[3]) + _dist(p[3], p[4])) / _dist(p[2], p[4])
                  if _dist(p[2], p[4]) > 0 else 0.0)
    index_curl = ((_dist(p[5], p[6]) + _dist(p[6], p[7]) + _dist(p[7], p[8])) /
                  _dist(p[5], p[8]) if _dist(p[5], p[8]) > 0 else 0.0)
    tip_mid = ((p[4][0] + p[8][0]) / 2, (p[4][1] + p[8][1]) / 2)
    v = _vec(p[2], p[5])
    vlen = math.hypot(*v)
    cross = v[0] * (tip_mid[1] - p[2][1]) - v[1] * (tip_mid[0] - p[2][0])
    ring_h = abs(cross) / vlen / hs if vlen > 0 else 0.0
    ring_w = _dist(p[2], p[5]) / hs
    return {
        "circularity": circularity,
        "thumb_curl": thumb_curl,
        "index_curl": index_curl,
        "ring_w": ring_w,
        "ring_h": ring_h,
    }


def classify_gesture(p):
    """classify the 21 points (in crop pixels, like the app does)."""
    hs = _dist(p[0], p[9])
    if hs <= 0:
        return None
    thumb_index = _dist(p[4], p[8]) / hs
    idx_ext = _is_extended(p, 6, 8)
    mid_ext = _is_extended(p, 10, 12)
    ring_ext = _is_extended(p, 14, 16)
    pink_ext = _is_extended(p, 18, 20)
    # The gate (thumb away from the fingers) uses the MCP->TIP vector
    # (kp2->kp4); the SCORE direction uses the IP->TIP segment (kp3->kp4)
    # instead: the CMC/MCP joints sit at the palm and are noisy (low kp
    # confidence on many hands) and the thumb is often curved, so a fit
    # through the whole chain misreads where the tip is pointing. The tip
    # segment is where the pointing lives and is the model's most confident
    # part of the thumb (verified: keeps all THUMBS-5 reads, fixes 1_tuna's
    # borderline 147.7/153 deg angle to the label's ONE).
    tdir = _vec(p[2], p[4])
    idir = _vec(p[5], p[8])
    tf_angle = _angle_between(tdir, idir)

    # OK sign: closed circle of thumb+index facing the camera.
    if thumb_index < 0.2 and mid_ext and ring_ext and pink_ext:
        ring = _ok_circle(p, hs)
        if (ring["thumb_curl"] >= OK_MIN_THUMB_CURL and
                ring["index_curl"] >= OK_MIN_INDEX_CURL and
                ring["circularity"] >= OK_MIN_CIRCULARITY and
                min(ring["ring_w"], ring["ring_h"]) >= OK_MIN_RING_SIDE):
            return ("OK_SIGN", 3)

    # ROCK: index + pinky extended, middle curled.
    if idx_ext and pink_ext and not mid_ext:
        return ("ROCK", 5)

    # THUMBS: fingers curled, thumb away. Thumb rotation from up = score.
    all_curled = not (idx_ext or mid_ext or ring_ext or pink_ext)
    if all_curled and _dist(p[2], p[4]) / hs > 0.25 and tf_angle > 85:
        tip_dx, tip_dy = _vec(p[3], p[4])
        angle = abs(math.degrees(math.atan2(tip_dx, -tip_dy)))
        score = (5 if angle < 40 else 4 if angle < 70 else 3 if angle < 110
        else 2 if angle < 150 else 1)
        return ("THUMBS", score)
    return None


def decode_image(path, full_res=False):
    """App-style decode: scale down so min(iw, ih) <= DECODE_MIN_DIM."""
    img = cv2.imread(str(path))
    if img is None:
        raise ValueError(f"cannot read image: {path}")
    ih, iw = img.shape[:2]
    if not full_res:
        s = max(min(ih, iw) // DECODE_MIN_DIM, 1)
        if s > 1:
            img = cv2.resize(img, (iw // s, ih // s), interpolation=cv2.INTER_AREA)
    return img


def load_sessions(models_dir):
    det_path = models_dir / DETECTOR_ASSET
    pose_path = models_dir / RTMPOSE_ASSET
    if not det_path.exists() or not pose_path.exists():
        raise FileNotFoundError(
            f"expected {det_path} and {pose_path}; pass --models-dir")
    det = ort.InferenceSession(str(det_path), providers=["CPUExecutionProvider"])
    pose = ort.InferenceSession(str(pose_path), providers=["CPUExecutionProvider"])
    return det, pose


# --- Detection stage (RTMDet letterbox) -----------------------------------

def detect_boxes(img, det_sess, pad_val=114, center_pad=True):
    """RTMDet-nano hand -> list of (x1, y1, x2, y2, score) in image pixels.

    Letterbox: keep-aspect resize to 320x320, then pad. The official mmdet
    test pipeline (from_mmpose/rtmdet_nano_320-8xb32_hand.py) pads with 114,
    centered; the app pads with 0, top-left. `pad_val`/`center_pad` expose
    both.
    """
    iw, ih = img.shape[1], img.shape[0]
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    ratio = min(DET_SIZE / iw, DET_SIZE / ih)
    nw, nh = max(int(iw * ratio), 1), max(int(ih * ratio), 1)
    resized = cv2.resize(rgb, (nw, nh))
    padded = np.full((DET_SIZE, DET_SIZE, 3), pad_val, np.uint8)
    if center_pad:
        px, py = (DET_SIZE - nw) // 2, (DET_SIZE - nh) // 2
    else:
        px, py = 0, 0
    padded[py:py + nh, px:px + nw] = resized

    f = ((padded.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32)
    dets_out = det_sess.run(None, {"input": f})[0]  # [1, N, 5] x1,y1,x2,y2,score (320-space)
    boxes = []
    for o in dets_out[0]:
        score = float(o[4])
        if score <= 0:
            continue
        x1 = max(int((o[0] - px) / ratio), 0)
        y1 = max(int((o[1] - py) / ratio), 0)
        x2 = min(int((o[2] - px) / ratio), iw)
        y2 = min(int((o[3] - py) / ratio), ih)
        if x2 - x1 < MIN_BOX_SIDE or y2 - y1 < MIN_BOX_SIDE:
            continue
        boxes.append((x1, y1, x2, y2, score))
    boxes.sort(key=lambda b: -b[4])
    return boxes


# --- Pose stage (top-down affine, exact port of the verified pipeline) ----

def expand_square(box, iw, ih, factor=BOX_EXPANSION):
    """The app's expandSquare: the box, expanded `factor`x around its center
    and made square, as integer pixel bounds (Kotlin toInt() truncation — the
    crop side and origin must match the Android crop exactly). Returns the
    crop center and side.
    """
    cx = (box[0] + box[2]) / 2
    cy = (box[1] + box[3]) / 2
    side = max((box[2] - box[0]) * factor, (box[3] - box[1]) * factor)
    x0 = int(cx - side / 2)
    y0 = int(cy - side / 2)
    x1 = int(cx + side / 2)
    y1 = int(cy + side / 2)
    return (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0


def app_rotate_and_crop_square(img, cx, cy, side, deg):
    """Exact port of AndroidOnnxHandLandmarker.rotateAndCropRectangle for a
    square crop of `side` px centered at (cx, cy), rotated by `deg` (90-degree
    increments): pad the image into a canvas sized to the full diagonal, crop
    the axis-aligned bounding box of the rotated square
    (boundingBoxFromRotatedRect), rotate it about its center without cropping
    (imageRotationWithoutCrop), then center-crop back to side x side. `side`
    is the app's corner-rounded square width (see expand_square), so the
    pixels fed to RTMPose are identical to the app's — this is the frame the
    app classifies in (the hand appears upright in the rotated crop).

    Returns (crop, origin) where origin is the crop's top-left corner in
    image coordinates (the frame keypoints are offset by after un-rotation),
    or None when the crop would be out of bounds (the app skips those
    candidates).
    """
    ih, iw = img.shape[:2]
    size = (int(math.sqrt(iw * iw + ih * ih)) + 2) * 2
    padded = np.zeros((size, size, 3), np.uint8)
    start_h, start_w = size // 2 - ih // 2, size // 2 - iw // 2
    padded[start_h:start_h + ih, start_w:start_w + iw] = img
    cx_p = cx + abs(size - iw) / 2
    cy_p = cy + abs(size - ih) / 2
    # boundingBoxFromRotatedRect(cx_p, cy_p, side, side, deg): axis-aligned
    # bbox of the rotated square's four corners.
    theta = math.radians(deg)
    cos_t, sin_t = math.cos(theta), math.sin(theta)
    hw = hh = side / 2
    xs = [cx_p + hw * cos_t - hh * sin_t, cx_p - hw * cos_t - hh * sin_t,
          cx_p - hw * cos_t + hh * sin_t, cx_p + hw * cos_t + hh * sin_t]
    ys = [cy_p + hw * sin_t + hh * cos_t, cy_p - hw * sin_t + hh * cos_t,
          cy_p - hw * sin_t - hh * cos_t, cy_p + hw * sin_t - hh * cos_t]
    min_x, max_x = math.floor(min(xs)), math.floor(max(xs)) + 1
    min_y, max_y = math.floor(min(ys)), math.floor(max(ys)) + 1
    bw, bh = int(max_x - min_x), int(max_y - min_y)
    cxx, cyy = int(min_x + max_x) // 2, int(min_y + max_y) // 2
    x0, y0 = cxx - bw // 2, cyy - bh // 2
    if x0 < 0 or y0 < 0 or x0 + bw > size or y0 + bh > size:
        return None
    crop = padded[y0:y0 + bh, x0:x0 + bw].copy()
    # imageRotationWithoutCrop(crop, deg): rotate about the center, expanding
    # the canvas so nothing is cut off (interpolated, like the app's
    # Matrix.postRotate + drawBitmap).
    rot = cv2.getRotationMatrix2D((crop.shape[1] / 2.0, crop.shape[0] / 2.0), deg, 1)
    abs_c, abs_s = abs(rot[0, 0]), abs(rot[0, 1])
    bound_w = int(crop.shape[0] * abs_s + crop.shape[1] * abs_c)
    bound_h = int(crop.shape[0] * abs_c + crop.shape[1] * abs_s)
    rot[0, 2] += bound_w / 2 - crop.shape[1] / 2
    rot[1, 2] += bound_h / 2 - crop.shape[0] / 2
    rotated = cv2.warpAffine(crop, rot, (bound_w, bound_h))
    # Final center-crop back to side x side (cropRect with the canvas center).
    ccx, ccy = rotated.shape[1] // 2, rotated.shape[0] // 2
    rw = rh = int(side)
    x0f, y0f = ccx - rw // 2, ccy - rh // 2
    if x0f < 0 or y0f < 0 or x0f + rw > rotated.shape[1] or y0f + rh > rotated.shape[0]:
        return None
    final = rotated[y0f:y0f + rh, x0f:x0f + rw].copy()
    return final, (x0 + x0f - start_w, y0 + y0f - start_h)


def crop_square(img, cx, cy, side):
    """Centered square crop of `side` px around (cx, cy), padding with 0 to
    cover out-of-bounds regions (a deg-0 slice of the app's
    rotate_and_crop_square). Returns the crop.
    """
    ih, iw = img.shape[:2]
    half = side / 2
    x0, y0 = int(cx - half), int(cy - half)
    x1, y1 = x0 + side, y0 + side
    pad_l = max(-x0, 0)
    pad_t = max(-y0, 0)
    pad_r = max(x1 - iw, 0)
    pad_b = max(y1 - ih, 0)
    if pad_l or pad_t or pad_r or pad_b:
        img = cv2.copyMakeBorder(img, pad_t, pad_b, pad_l, pad_r,
                                 cv2.BORDER_CONSTANT, value=0)
    return img[y0 + pad_t:y0 + pad_t + side, x0 + pad_l:x0 + pad_l + side].copy()


def top_down_affine(crop, deg=0):
    """mmpose TopdownAffine for a 256x256 model: 1.25x bbox (here the square
    crop), aspect-fixed, centered warp to 256x256. Verified against the baked
    ONNX post-processing (which also uses max(0.75*1.25h, 1.25w)). `deg`
    rotates the crop (cv2.ROTATE_90_CLOCKWISE increments) before warping for
    manual rotation probing.
    """
    if deg:
        for _ in range((deg // 90) % 4):
            crop = cv2.rotate(crop, cv2.ROTATE_90_CLOCKWISE)
    h, w = crop.shape[:2]
    bbox_w, bbox_h = 1.25 * w, 1.25 * h
    w_scaled = max(bbox_h * 0.75, bbox_w)
    scale = POSE_SIZE / w_scaled
    m = np.float32([[scale, 0, POSE_SIZE / 2 - scale * w / 2],
                    [0, scale, POSE_SIZE / 2 - scale * h / 2]])
    return cv2.warpAffine(crop, m, (POSE_SIZE, POSE_SIZE), borderValue=0)


def rtmpose_landmarks(crop, pose_sess, deg=0):
    """[21, 3] x,y,conf in crop-pixel space (deg applied to the warp)."""
    h, w = crop.shape[:2]
    warped = top_down_affine(crop, deg)
    f = ((warped.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32)
    bbox = np.asarray([[w, h]], dtype=np.int64)
    kps = pose_sess.run(None, {"input": f, "bboxes_width_height": bbox})[0]
    return kps[0]  # [21, 3]


def unrotate_kp(x, y, side, deg):
    """Map a keypoint from the rotated crop back to the original crop space
    (cv2.rotate keeps the canvas size for 90-degree multiples)."""
    deg = deg % 360
    if deg == 90:  # ROTATE_90_CLOCKWISE inverse
        return y, side - 1 - x
    if deg == 180:
        return side - 1 - x, side - 1 - y
    if deg == 270:  # ROTATE_90_COUNTERCLOCKWISE inverse
        return side - 1 - y, x
    return x, y


def app_search(img, box, pose_sess):
    """App-faithful rotation search over the 1.2x/0.85x crop expansions — a
    port of AndroidOnnxHandLandmarker.rtmposeRating (winner rule verified in
    plans/benchmarks/rtmpose_only_v5.py run_pipeline).

    For each expansion factor, RTMPose reads the centered square crop at
    {0, 90, 180, 270}; gesture classification runs on the RAW rotated-crop
    frame (the app rotates the crop so the hand appears upright before
    classifying — the THUMBS angle is measured against that frame's "up").
    Only reads that form a gesture enter imageSpace (deg 0) / bestRotated
    (the rest), with bestRotated preferring THUMBS, then gesture score, then
    kp. The 0.85x pass is skipped when the 1.2x pass already produced a
    confident (kp >= MIN_RTMPOSE_KP) upright rating (the app's image-space
    preference). The winner is the image-space read when its kp >= 0.3,
    otherwise the best rotated / highest-kp read that still clears the app's
    kp floor; the app drops the hand when even that is below the floor, which
    we keep (flagged `gated`) so the overlay can still show the near-miss.

    Returns a dict with the winning rot/factor/gesture/kp_mean, the winner
    keypoints mapped to IMAGE space (rotated reads un-rotated), a per-factor
    rotation kp table for the manifest, and the gated flag. Returns None only
    when no read succeeded at all (never happens with real detections —
    RTMPose always returns keypoints). Rotated-winner keypoints are
    approximate to ~1px: the un-rotation pivots on the final crop center,
    which can sit half a pixel from the interpolated rotation's true center.
    """
    iw, ih = img.shape[1], img.shape[0]
    image_space = None  # (kp_mean, cls, deg, factor) classified deg-0 reads
    best_rotated = None  # (kp_mean, cls, deg, factor) classified rotated reads
    best_kp = None  # (kp_mean, cls, deg, factor) across ALL reads
    raw_kps = {}  # (factor, deg) -> [21, 3] raw rotated-crop frame
    origins = {}  # (factor, deg) -> crop top-left in image coordinates
    sides = {}  # factor -> crop side (for un-rotating the winner)
    kp_means = {}  # factor -> {deg: float}
    for factor in FALLBACK_FACTORS:
        cx, cy, side = expand_square(box, iw, ih, factor)
        sides[factor] = side
        for deg in ROTATION_SEARCH:
            got = app_rotate_and_crop_square(img, cx, cy, side, deg)
            if got is None:
                continue  # the app skips out-of-bounds candidates
            crop, origin = got
            kps = rtmpose_landmarks(crop, pose_sess)
            kp_mean = float(kps[:, 2].mean())
            kp_means.setdefault(factor, {})[deg] = kp_mean
            raw_kps[(factor, deg)] = kps
            origins[(factor, deg)] = origin
            pts = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(NUM_LANDMARKS)]
            cls = classify_gesture(pts)  # rotated frame — the hand is upright
            entry = (kp_mean, cls, deg, factor)
            if best_kp is None or kp_mean > best_kp[0]:
                best_kp = entry
            if cls is None:
                continue
            if deg == 0:
                if image_space is None or kp_mean > image_space[0]:
                    image_space = entry
                # Early exit (mirrors the app's rtmposeRating): a confident
                # upright read on the standard 1.2x crop is the guaranteed
                # winner (image-space preference below), so the rotated sweep
                # and the 0.85x pass cannot change the outcome — skip them
                # (verified: identical winners on all plans/samples, RTMPose
                # runs 204 -> 123, -40%).
                if factor == BOX_EXPANSION and image_space[0] >= MIN_RTMPOSE_KP:
                    break
                continue
            if best_rotated is None:
                best_rotated = entry
            elif cls[0] == "THUMBS" and best_rotated[1][0] != "THUMBS":
                best_rotated = entry
            elif cls[0] != "THUMBS" and best_rotated[1][0] == "THUMBS":
                pass
            elif cls[0] == "THUMBS":
                if cls[1] > best_rotated[1][1] or (cls[1] == best_rotated[1][1]
                                                   and kp_mean > best_rotated[0]):
                    best_rotated = entry
            elif kp_mean > best_rotated[0]:
                best_rotated = entry
        # Image-space preference: a confident upright rating on the standard
        # 1.2x crop is the winner, so skip the 0.85x sweep (mirrors the app).
        if (factor == BOX_EXPANSION and image_space is not None
                and image_space[0] >= MIN_RTMPOSE_KP):
            break
    if image_space is not None and image_space[0] >= MIN_RTMPOSE_KP:
        winner, gated = image_space, False
    elif best_rotated is not None and best_rotated[0] >= MIN_RTMPOSE_KP:
        winner, gated = best_rotated, False
    elif best_kp is not None and best_kp[0] >= MIN_RTMPOSE_KP:
        winner, gated = best_kp, False
    elif image_space is not None:
        winner, gated = image_space, True
    elif best_kp is not None:
        winner, gated = best_kp, True
    else:
        return None
    kp_mean, cls, deg, factor = winner
    # Map the winner's keypoints to image space: un-rotate rotated reads back
    # to the crop frame (the final crop center is the rotation center for
    # 90-degree multiples), then offset by the crop's image-space origin.
    kps = raw_kps[(factor, deg)].copy()
    if deg:
        for i in range(NUM_LANDMARKS):
            x, y = unrotate_kp(kps[i, 0], kps[i, 1], sides[factor], deg)
            kps[i, 0], kps[i, 1] = x, y
    ox, oy = origins[(factor, deg)]
    kps[:, 0] += ox
    kps[:, 1] += oy
    rot_kps = "; ".join(
        f"{f}x[" + ",".join(
            f"{d}:{kp_means[f][d]:.2f}" for d in ROTATION_SEARCH if d in kp_means[f]
        ) + "]"
        for f in FALLBACK_FACTORS if f in kp_means)
    return {
        "rot": deg,
        "factor": factor,
        "gesture": f"{cls[0]}-{cls[1]}" if cls else "",
        "kp_mean": kp_mean,
        "kps": kps,
        "rot_kps": rot_kps,
        "gated": gated,
    }


# --- Edge thumb-only fallback (5_kimbo-style partial hands) ---------------
# When the main pass finds no hands at all, probe the left/right edges: a
# hand that is mostly out of frame (only the thumb in shot) is invisible to
# full-frame RTMDet but reads as a confident upright thumb chain when the
# edge is zoomed. Such hands rate as a LOW-CERTAINTY THUMBS guess (the
# fingers are off-frame, so RTMPose hallucinates them; only the thumb chain
# is trustworthy). Gates (tuned on plans/samples): the strip detector must
# fire >= 0.25, the mean thumb-chain confidence (kp1..4) must be >= 0.45,
# the four finger confidences (kp5..20) must average < 0.40 (off-frame /
# unreliable — no_score holding hands have confident fingers and never
# qualify), and the thumb must point up-ish (angle from up <= 45).
EDGE_THUMB_MIN_CONF = 0.45
EDGE_FINGER_MAX_CONF = 0.40
EDGE_THUMB_MAX_ANGLE = 45.0
EDGE_STRIP_FRACTION = 0.5
# Cost reduction: a single y-band (the lower 60%) instead of three overlapping
# bands — the only edge-hand sample (5_kimbo) qualifies on this band (det
# 0.46, thumbC 0.66) and every no_score strip fires nothing, so the extra
# bands only cost detector calls. Tradeoff: an edge hand in the top 40% of
# the image would be missed (documented; re-add bands if a sample needs it).
EDGE_BANDS = ((0.4, 1.0),)
# Skip the fallback entirely when the FULL-FRAME detector (already run for the
# main pass) has no box above this hint — an edge hand still leaves a weak
# full-frame response (5_kimbo: 0.167). Kept at 0.10 (not higher) because the
# zoomed strip detection is largely independent of the full-frame response: a
# hand even more out of frame could score below 0.12 full-frame yet still read
# a confident thumb in an edge strip. 0.10 still skips genuine no-hand photos
# (their detector noise is far lower; the no_score samples at 0.107-0.124
# contain real, unratable hands), making them cost 0 strip detections.
EDGE_HINT_DET = 0.10
EDGE_DET_PAD = 114


def edge_thumb_scan(img, box, pose_sess):
    """Run RTMPose on an edge-hugging box at all rotations and return the best
    read whose thumb chain qualifies as a partial-hand thumbs-up (see the
    gates above), or None. The returned hand dict is in image space and
    carries `gated=True` (the app marks such hands uncertain — the fingers
    are unreliable so the score is a best guess)."""
    iw, ih = img.shape[1], img.shape[0]
    cx, cy, side = expand_square(box, iw, ih, BOX_EXPANSION)
    best = None  # (thumb_conf, kp_mean, ...)
    for deg in ROTATION_SEARCH:
        got = app_rotate_and_crop_square(img, cx, cy, side, deg)
        if got is None:
            continue
        crop, origin = got
        kps = rtmpose_landmarks(crop, pose_sess)
        kp_mean = float(kps[:, 2].mean())
        if kp_mean < MIN_RTMPOSE_KP:
            continue
        thumb_c = float(kps[1:5, 2].mean())
        finger_c = float(kps[5:21, 2].mean())
        if thumb_c < EDGE_THUMB_MIN_CONF or finger_c >= EDGE_FINGER_MAX_CONF:
            continue
        pts = [(float(kps[i, 0]), float(kps[i, 1])) for i in range(NUM_LANDMARKS)]
        angle = abs(math.degrees(math.atan2(pts[4][0] - pts[3][0],
                                            -(pts[4][1] - pts[3][1]))))
        if angle > EDGE_THUMB_MAX_ANGLE:
            continue
        key = (thumb_c, kp_mean)
        if best is None or key > best[0]:
            best = (key, kps, origin, deg, side, kp_mean, angle)
        # Deg-0-first acceptance (mirrors the app's edgeThumbScan): the
        # upright read is the only gate-qualifying rotation on the edge
        # sample (5_kimbo), so accept it immediately and skip the rotated
        # sweep; the sweep still runs when deg 0 fails the gates.
        if deg == 0:
            break
    if best is None:
        return None
    _, kps, origin, deg, side, kp_mean, angle = best
    kps = kps.copy()
    if deg:
        for i in range(NUM_LANDMARKS):
            x, y = unrotate_kp(kps[i, 0], kps[i, 1], side, deg)
            kps[i, 0], kps[i, 1] = x, y
    ox, oy = origin
    kps[:, 0] += ox
    kps[:, 1] += oy
    score = (5 if angle < 40 else 4 if angle < 70 else 3 if angle < 110
    else 2 if angle < 150 else 1)
    return {
        "box": (int(box[0]), int(box[1]), int(box[2]), int(box[3])),
        "det_score": float(box[4]),
        "kps": [(float(kps[i, 0]), float(kps[i, 1]), float(kps[i, 2]))
                for i in range(NUM_LANDMARKS)],
        "rot": deg,
        "factor": BOX_EXPANSION,
        "gesture": f"THUMBS-{score}",
        "gated": True,
        "rot_kps": "",
        "kp_mean": kp_mean,
        "kp_min": float(kps[:, 2].min()),
        "n_kp_ge_0.3": int((kps[:, 2] >= 0.3).sum()),
        "n_kp_ge_0.5": int((kps[:, 2] >= 0.5).sum()),
        "thumb_c": thumb_c,
        "edge": True,
    }


def edge_thumb_fallback(img, det_sess, pose_sess, det_thr, full_frame_best=1.0):
    """Probe the left and right edge strips for a partial-hand thumbs-up when
    the main pass found nothing. At most one hand per edge. `full_frame_best`
    is the best score the full-frame detector returned for this image (already
    computed by the main pass): when it is below [EDGE_HINT_DET] the image has
    no hand signal at all and the fallback is skipped entirely."""
    if full_frame_best < EDGE_HINT_DET:
        return []
    ih, iw = img.shape[:2]
    sw = max(int(iw * EDGE_STRIP_FRACTION), 1)
    hands = []
    for side in (0, 1):
        x0 = 0 if side == 0 else iw - sw
        for (fb, ft) in EDGE_BANDS:
            y0, y1 = int(ih * fb), int(ih * ft)
            strip = img[y0:y1, x0:x0 + sw]
            sh, cw = strip.shape[:2]
            if cw < sh:
                strip = np.hstack([strip, np.full((sh, sh - cw, 3), EDGE_DET_PAD, np.uint8)])
            elif sh < cw:
                strip = np.vstack([strip, np.full((cw - sh, cw, 3), EDGE_DET_PAD, np.uint8)])
            boxes = detect_boxes(strip, det_sess, pad_val=EDGE_DET_PAD, center_pad=False)
            if not boxes or boxes[0][4] < det_thr:
                continue
            b = boxes[0]
            im_box = (x0 + int(b[0]), y0 + int(b[1]), x0 + int(b[2]),
                      y0 + int(b[3]), float(b[4]))
            hand = edge_thumb_scan(img, im_box, pose_sess)
            if hand is not None:
                hands.append(hand)
                break
    return hands


def hand_from_box(img, box, pose_sess, det_thr, rotate_deg=0, search_rotations=False):
    """Run RTMPose on one detection box and map the keypoints back to image
    space. Two modes:
      - official (default): single deg-0 inference (or the `rotate_deg` probe)
        on a 1.0x square crop of side max(w, h);
      - app-style (`search_rotations`): the app's factor x rotation search
        (app_search), labeling the hand with the winning rotation; the winner's
        keypoints arrive already in image space.
    Returns dict or None. In official mode the square crop is an isometry of
    the box, so crop-space == image space for the box region; the offset uses
    the CLAMPED crop origin because crop_square pads out-of-bounds regions
    (edge-adjacent boxes) and an unclamped origin would shift the skeleton by
    the pad amount.
    """
    x1, y1, x2, y2, score = box
    if score < det_thr:
        return None
    if search_rotations:
        found = app_search(img, box, pose_sess)
        if found is None:
            return None
        kps = found["kps"]  # already mapped to image space by app_search
        win_deg, win_factor = found["rot"], found["factor"]
        gesture, gated = found["gesture"], found["gated"]
        kp_mean, rot_kps = found["kp_mean"], found["rot_kps"]
        ox, oy = 0, 0
    else:
        cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
        side = max(x2 - x1, y2 - y1)
        crop = crop_square(img, cx, cy, side)
        kps = rtmpose_landmarks(crop, pose_sess, rotate_deg)
        win_deg, win_factor = rotate_deg, 1.0
        gesture, gated = "", False
        kp_mean, rot_kps = None, ""
        ox, oy = max(int(cx - side / 2), 0), max(int(cy - side / 2), 0)
        if win_deg:
            kps = kps.copy()
            for i in range(NUM_LANDMARKS):
                x, y = unrotate_kp(kps[i, 0], kps[i, 1], side, win_deg)
                kps[i, 0], kps[i, 1] = x, y
    pts = []
    for i in range(NUM_LANDMARKS):
        x, y, c = float(kps[i, 0]) + ox, float(kps[i, 1]) + oy, float(kps[i, 2])
        pts.append((x, y, c))
    kp_mean = kp_mean if kp_mean is not None else float(kps[:, 2].mean())
    return {
        "box": (x1, y1, x2, y2),
        "det_score": score,
        "kps": pts,
        "rot": win_deg,
        "factor": win_factor,
        "gesture": gesture,
        "gated": gated,
        "rot_kps": rot_kps,
        "kp_mean": kp_mean,
        "kp_min": float(kps[:, 2].min()),
        "n_kp_ge_0.3": int((kps[:, 2] >= 0.3).sum()),
        "n_kp_ge_0.5": int((kps[:, 2] >= 0.5).sum()),
    }


# --- Visualization --------------------------------------------------------

def draw_hand(img, hand, kpt_thr=0.3):
    x1, y1, x2, y2 = hand["box"]
    # Detection box (white, thin) + label at the top-left corner. Yellow =
    # upright winner, orange = rotated winner (app-style search mode).
    cv2.rectangle(img, (x1, y1), (x2, y2), (255, 255, 255), 1)
    gesture = f" {hand['gesture']}" if hand["gesture"] else " no-gesture"
    gated = " GATED" if hand["gated"] else ""
    factor = "" if hand["factor"] == 1.0 else f"({hand['factor']:g}x)"
    label = (f"det {hand['det_score']:.2f} | rot {hand['rot']}{factor} "
             f"kp {hand['kp_mean']:.2f}{gesture}{gated}"
             f" ({hand['n_kp_ge_0.5']}/{NUM_LANDMARKS} ge 0.5)")
    label_color = (0, 255, 255) if hand["rot"] == 0 else (255, 128, 0)
    (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
    cv2.rectangle(img, (x1, max(y1 - th - 6, 0)), (x1 + tw + 4, max(y1, 0)),
                  (0, 0, 0), -1)
    cv2.putText(img, label, (x1 + 2, max(y1 - 3, th)), cv2.FONT_HERSHEY_SIMPLEX,
                0.45, label_color, 1, cv2.LINE_AA)

    kps = hand["kps"]
    # Skeleton links in the official per-finger colors, only when BOTH
    # endpoints are above the confidence threshold.
    for (a, b) in SKELETON:
        pa, pb = kps[a], kps[b]
        if pa[2] < kpt_thr or pb[2] < kpt_thr:
            continue
        cv2.line(img, (int(pa[0]), int(pa[1])), (int(pb[0]), int(pb[1])),
                 KEYPOINT_COLORS_BGR[a], 2, cv2.LINE_AA)
    # Keypoints: filled when confident, hollow (dim) otherwise.
    for i, (x, y, c) in enumerate(kps):
        color = KEYPOINT_COLORS_BGR[i]
        if c < kpt_thr:
            cv2.circle(img, (int(x), int(y)), 3, (60, 60, 60), 1, cv2.LINE_AA)
        else:
            cv2.circle(img, (int(x), int(y)), 4, color, -1, cv2.LINE_AA)
            cv2.circle(img, (int(x), int(y)), 4, (0, 0, 0), 1, cv2.LINE_AA)


def overlay(img, hands, kpt_thr=0.3):
    out = img.copy()
    for hand in hands:
        draw_hand(out, hand, kpt_thr)
    return out


# --- Contact sheet (official deg-0 | app rotations, side by side) ---------

def _with_header(img, lines, color):
    band = 36 if len(lines) > 1 else 26
    out = cv2.copyMakeBorder(img, band, 0, 0, 0, cv2.BORDER_CONSTANT,
                             value=(24, 24, 24))
    y = 19
    for line in lines:
        cv2.putText(out, line, (8, y), cv2.FONT_HERSHEY_SIMPLEX, 0.45, color,
                    1, cv2.LINE_AA)
        y += 14
    return out


def contact_sheet_pair(img, det_sess, pose_sess, args):
    """Side-by-side panel: official-style deg-0 (official letterbox) on the
    left, the app's rotation search (app letterbox) on the right, both on the
    same decoded image. Returns (pair_image, official_hands, app_hands).
    """
    kpt_thr = args.kpt_thr
    hands_o = []
    for box in detect_boxes(img, det_sess, pad_val=114, center_pad=True)[:args.max_hands]:
        hand = hand_from_box(img, box, pose_sess, args.det_thr, 0, False)
        if hand is not None:
            hands_o.append(hand)
    panel_o = _with_header(
        overlay(img, hands_o, kpt_thr),
        ["OFFICIAL deg-0", "pad 114, centered"], (255, 255, 255))

    hands_a = []
    for box in detect_boxes(img, det_sess, pad_val=0, center_pad=False)[:args.max_hands]:
        hand = hand_from_box(img, box, pose_sess, args.det_thr, 0, True)
        if hand is not None:
            hands_a.append(hand)
    panel_a = _with_header(
        overlay(img, hands_a, kpt_thr),
        ["APP rotations", "pad 0, top-left"], (0, 200, 255))
    return np.hstack([panel_o, panel_a]), hands_o, hands_a


def _tile(imgs, cols, cell_w=480):
    """Tile images into a tidy grid: scale each to `cell_w` wide, pad cells
    to a common height with black, then arrange `cols` per row.
    """
    scaled = []
    max_h = 0
    for im in imgs:
        h, w = im.shape[:2]
        nh = max(int(h * cell_w / w), 1)
        s = cv2.resize(im, (cell_w, nh), interpolation=cv2.INTER_AREA)
        scaled.append(s)
        max_h = max(max_h, nh)
    rows = (len(scaled) + cols - 1) // cols
    canvas = np.full((max_h * rows, cell_w * cols, 3), 20, np.uint8)
    for i, s in enumerate(scaled):
        r, c = divmod(i, cols)
        canvas[r * max_h:r * max_h + s.shape[0], c * cell_w:c * cell_w + cell_w] = s
    return canvas


def process_contact_sheet(path, args, det_sess, pose_sess, out_path=None):
    img = decode_image(path, args.full_res)
    pair, hands_o, hands_a = contact_sheet_pair(img, det_sess, pose_sess, args)
    if out_path is not None:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(out_path), pair)
    return pair, hands_o, hands_a


# --- CLI ------------------------------------------------------------------

def _hand_summary(h):
    factor = f"({h['factor']:g}x)" if h["factor"] != 1.0 else ""
    return (f"[{h['det_score']:.2f} | rot {h['rot']}{factor} | {h['kp_mean']:.2f}"
            f"{(' ' + h['gesture']) if h['gesture'] else ''}"
            f"{' GATED' if h['gated'] else ''} | {h['n_kp_ge_0.5']}/21]")


def parse_args(argv):
    p = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("input", nargs="?", help="image file (or use --input-dir)")
    p.add_argument("--input-dir", type=Path)
    p.add_argument("--output", type=Path, help="output image for single-file mode")
    p.add_argument("--output-dir", type=Path,
                   help="output dir for --input-dir mode (mirrors the input tree); "
                        "defaults to plans/rtmpose_hand/output_cs for "
                        "--contact-sheet, plans/rtmpose_hand/output otherwise")
    p.add_argument("--models-dir", type=Path, default=DEFAULT_MODELS)
    p.add_argument("--max-hands", type=int, default=2)
    p.add_argument("--det-thr", type=float, default=0.25)
    p.add_argument("--kpt-thr", type=float, default=0.3)
    p.add_argument("--det-pad", type=int, choices=(0, 114), default=114,
                   help="letterbox pad value: 114 = official mmdet test "
                        "pipeline, 0 = app")
    p.add_argument("--det-align", choices=("center", "top-left"),
                   default="center", help="letterbox alignment (official pads "
                                          "centered, app pads top-left)")
    p.add_argument("--app-det", action="store_true",
                   help="shorthand for --det-pad 0 --det-align top-left "
                        "(exact app preprocessing)")
    rot_group = p.add_mutually_exclusive_group()
    rot_group.add_argument("--rotate-deg", type=int, choices=(0, 90, 180, 270),
                           default=0, help="probe a single crop rotation "
                                           "(the app searches 0/90/180/270; "
                                           "official uses 0)")
    rot_group.add_argument(
        "--rotations", action="store_true",
        help="run the app's rotation search (1.2x/0.85x crop expansions x "
             "0/90/180/270) and label each hand with the winning rotation + "
             "confidence (orange label = rotated winner, GATED = winner below "
             "the app's 0.3 kp floor, which the app would drop). Overrides "
             "--rotate-deg.")
    rot_group.add_argument(
        "--contact-sheet", action="store_true",
        help="side-by-side comparison: official deg-0 (left) vs the app's "
             "rotation search (right); in --input-dir mode also writes a "
             "combined grid contact_sheet_all.jpg. Overrides --rotate-deg.")
    p.add_argument("--edge-fallback", action="store_true",
                   help="when the main pass finds no hands, probe the left/right "
                        "edge strips for a partial-hand thumbs-up (the app's edge "
                        "thumb-only fallback: 5_kimbo) and label it UNCERTAIN")
    p.add_argument("--full-res", action="store_true",
                   help="do not scale the input down to min-dim 640")
    p.add_argument("--show", action="store_true",
                   help="cv2.imshow (blocking)")
    p.add_argument("--no-csv", action="store_true")
    return p.parse_args(argv)


def process_one(path, args, det_sess, pose_sess, out_path=None):
    img = decode_image(path, args.full_res)
    boxes = detect_boxes(img, det_sess, pad_val=args.det_pad,
                         center_pad=args.det_align == "center")
    hands = []
    for box in boxes[:args.max_hands]:
        hand = hand_from_box(img, box, pose_sess, args.det_thr, args.rotate_deg,
                             args.rotations)
        if hand is not None:
            hands.append(hand)
    if not hands and args.edge_fallback:
        hint = boxes[0][4] if boxes else 0.0
        hands = edge_thumb_fallback(img, det_sess, pose_sess, args.det_thr, hint)
    vis = overlay(img, hands, args.kpt_thr)
    if out_path is not None:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(out_path), vis)
    if args.show:
        cv2.imshow(str(path), vis)
        cv2.waitKey(0)
        cv2.destroyAllWindows()
    return hands


def main(argv=None):
    args = parse_args(argv)
    if (args.input is None) == (args.input_dir is None):
        sys.exit("provide exactly one of: input image, --input-dir")
    det_sess, pose_sess = load_sessions(args.models_dir)
    if args.app_det:
        args.det_pad, args.det_align = 0, "top-left"
    if args.output_dir is None:
        args.output_dir = HERE / ("output_cs" if args.contact_sheet else "output")

    if not args.contact_sheet:
        header = (f"{'image':<42} hands "
                  f"(det | kp-mean | kp>=0.5)")
        print(header)
        print("-" * len(header))

    if args.input is not None:
        src = Path(args.input)
        if args.contact_sheet:
            out_path = args.output or (HERE / "output_cs" / f"{src.stem}_cs.jpg")
            pair, ho, ha = process_contact_sheet(src, args, det_sess, pose_sess, out_path)
            print(f"{src.name:<42} official: "
                  f"{'; '.join(_hand_summary(h) for h in ho) or 'no hands'}")
            print(f"{'':<42} app:      "
                  f"{'; '.join(_hand_summary(h) for h in ha) or 'no hands'}")
            print(f"wrote {out_path}")
            return 0
        out_path = args.output or (args.output_dir / src.name)
        hands = process_one(src, args, det_sess, pose_sess, out_path)
        summary = "; ".join(_hand_summary(h) for h in hands) or "no hands"
        print(f"{src.name:<42} {summary}")
        print(f"wrote {out_path}")
        return 0

    rows = []
    files = sorted(args.input_dir.rglob("*"))
    files = [f for f in files if f.suffix.lower() in (".jpg", ".jpeg", ".png")]
    if args.contact_sheet:
        pairs = []
        for f in files:
            rel = f.relative_to(args.input_dir)
            out_path = args.output_dir / rel
            pair, ho, ha = process_contact_sheet(f, args, det_sess, pose_sess, out_path)
            pairs.append(pair)
            print(f"{str(rel):<42} official: "
                  f"{'; '.join(_hand_summary(h) for h in ho) or 'no hands'}")
            print(f"{'':<42} app:      "
                  f"{'; '.join(_hand_summary(h) for h in ha) or 'no hands'}")
        if pairs:
            grid = _tile(pairs, cols=5)
            grid_path = args.output_dir / "contact_sheet_all.jpg"
            cv2.imwrite(str(grid_path), grid)
            print(f"\nwrote combined grid {grid_path}"
                  f" ({len(pairs)} pairs, {grid.shape[1]}x{grid.shape[0]})")
        return 0

    for f in files:
        rel = f.relative_to(args.input_dir)
        out_path = args.output_dir / rel
        try:
            hands = process_one(f, args, det_sess, pose_sess, out_path)
        except ValueError as e:
            print(f"{str(rel):<42} ERROR: {e}")
            continue
        summary = "; ".join(_hand_summary(h) for h in hands) or "no hands"
        print(f"{str(rel):<42} {summary}")
        for i, h in enumerate(hands):
            rows.append({
                "image": str(rel),
                "hand": i,
                "det_score": f"{h['det_score']:.4f}",
                "box": " ".join(str(v) for v in h["box"]),
                "rot": h["rot"],
                "factor": f"{h['factor']:g}",
                "gated": int(h["gated"]),
                "gesture": h["gesture"],
                "rot_kps": h["rot_kps"],
                "kp_mean": f"{h['kp_mean']:.4f}",
                "kp_min": f"{h['kp_min']:.4f}",
                "n_kp_ge_0.3": h["n_kp_ge_0.3"],
                "n_kp_ge_0.5": h["n_kp_ge_0.5"],
            })
    if rows and not args.no_csv:
        csv_path = args.output_dir / "manifest.csv"
        csv_path.parent.mkdir(parents=True, exist_ok=True)
        with open(csv_path, "w", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
        print(f"\nwrote manifest {csv_path} ({len(rows)} hands)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
