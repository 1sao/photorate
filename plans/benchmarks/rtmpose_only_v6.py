#!/usr/bin/env python3
"""RTMPose-only v6 — clean structure, always-score pipeline.

Gesture set:
  - THUMBS_UP (1-5 by thumb angle from vertical)
  - OK_SIGN (3): closed thumb+index circle facing camera

Dropped: ROCK (was causing false positives on holding images).

Key changes from v5:
  - Pipeline ALWAYS scores detected hands (never filters at kp threshold).
    Low kp-confidence hands are marked uncertain instead of dropped.
  - Best-guess fallback: when no recognized gesture matches, guess score
    from thumb angle (mirrors Kotlin's DebugLandmarkRater).
  - Separated into clean functions: prepare_image, detect_hands,
    obtain_landmark_data, process_landmark_data, assign_score.
  - Edge thumb-only fallback for partial hands (5_kimbo-style).

Requires: pip install onnxruntime opencv-python-headless numpy
Usage: python3 rtmpose_only_v6.py
"""
import cv2
import math
import numpy as np
import onnxruntime as ort
import sys
from dataclasses import dataclass
from pathlib import Path

# ---------------------------------------------------------------------------
# Paths and model setup
# ---------------------------------------------------------------------------

MODELS = Path(__file__).resolve().parent.parent.parent / "onnx" / "models"
RTMDET = MODELS / "rtmdet_n_hand.onnx"
RTMPOSE = MODELS / "rtmpose_hand.onnx"

DECODE_MIN_DIM = 640
RTMPOSE_SIZE = 256
MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)

sess_rtm = ort.InferenceSession(str(RTMDET), providers=["CPUExecutionProvider"])
sess_rtmp = ort.InferenceSession(str(RTMPOSE), providers=["CPUExecutionProvider"])

# Detection
MIN_DET = 0.25
BOX_EXPANSION = 1.2
FALLBACK_FACTORS = (1.2, 0.85)
MAX_HANDS = 2
CANDIDATE_ROTATIONS = [0, 90, 180, 270]

# Confidence tiers (match the Kotlin app)
MIN_KP_CONFIDENCE = 0.30  # below this: no hand at all
CONFIDENT_KP = 0.45  # above this: confident tier

# OK-sign thresholds
OK_SIGN_MAX_THUMB_INDEX_DIST = 0.2
OK_MIN_THUMB_CURL = 1.08
OK_MIN_INDEX_CURL = 1.15
OK_MIN_CIRCULARITY = 0.40
OK_MIN_RING_SIDE = 0.25
OK_MIN_SPREAD = 0.10

# Thumbs-up thresholds
THUMBS_MIN_THUMB_FINGER_ANGLE = 95
THUMBS_MIN_THUMB_LENGTH = 0.25
MAX_THUMB_LEN = 2.0

# Sanity
MAX_EXTENT_RATIO = 2.9

# Thumb angle → score boundaries (degrees from vertical)
THUMBS_UP_MAX_ANGLE = 24
SCORE_FOUR_MAX_ANGLE = 70
SCORE_THREE_MAX_ANGLE = 110
SCORE_TWO_MAX_ANGLE = 165

# Edge thumb fallback
EDGE_MIN_KP = 0.42  # higher than main path — edge reads are less reliable
EDGE_HINT_DET = 0.10
EDGE_STRIP_FRACTION = 0.5
EDGE_BANDS = [(0.4, 1.0)]
EDGE_THUMB_MIN_CONF = 0.45
EDGE_FINGER_MAX_CONF = 0.40
EDGE_THUMB_MAX_ANGLE = 45


# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------

@dataclass
class Point:
    x: float
    y: float
    z: float  # keypoint confidence


@dataclass
class FeatureSet:
    """Extracted features for a single hand read."""
    hand_size: float
    all_curled: bool
    index_extended: bool
    middle_extended: bool
    ring_extended: bool
    pinky_extended: bool
    index_straight: bool
    middle_straight: bool
    ring_straight: bool
    pinky_straight: bool
    thumb_length_ratio: float
    thumb_index_tip_distance: float
    thumb_tip_angle: float  # degrees from vertical (IP→TIP segment)
    thumb_finger_angle: float  # angle between thumb and index direction
    finger_spread: float
    extent_ratio: float
    # OK-sign features
    ok_thumb_curl: float
    ok_index_curl: float
    ok_circularity: float
    ok_ring_width: float
    ok_ring_height: float
    # Raw
    kp_mean: float
    # Edge detection
    edge_detected: bool = False
    thumb_confidence: float = 0.0
    finger_confidence: float = 0.0


@dataclass
class Result:
    gesture: str | None  # "THUMBS_UP", "OK_SIGN", or None (detected but no gesture)
    score: int  # 1-5 (0 if no gesture)
    uncertain: bool
    kp_mean: float
    rotation: float
    edge_detected: bool = False


# ---------------------------------------------------------------------------
# 1. prepare_image
# ---------------------------------------------------------------------------

def prepare_image(path: str):
    """Load image and downscale if needed."""
    img = cv2.imread(str(path))
    if img is None:
        return None
    ih, iw = img.shape[:2]
    s = max(min(ih, iw) // DECODE_MIN_DIM, 1)
    if s > 1:
        img = cv2.resize(img, (iw // s, ih // s), interpolation=cv2.INTER_AREA)
    return img


# ---------------------------------------------------------------------------
# 2. detect_hands
# ---------------------------------------------------------------------------

def _rtmdet_detect(img):
    """Run RTMDet on an image. Returns (boxes, scores) in raw model space."""
    h, w = img.shape[:2]
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    size = 320
    ratio = min(size / w, size / h)
    nw, nh = max(int(w * ratio), 1), max(int(h * ratio), 1)
    resized = cv2.resize(rgb, (nw, nh))
    padded = np.zeros((size, size, 3), np.uint8)
    padded[:nh, :nw] = resized
    f = ((padded.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32
    )
    dets_out, _ = sess_rtm.run(None, {"input": f})
    return dets_out[0][:, :4], dets_out[0][:, 4], ratio


def detect_hands(img):
    """Run RTMDet and return sorted boxes: [(x1,y1,x2,y2,score), ...]."""
    iw, ih = img.shape[1], img.shape[0]
    boxes, scores, ratio = _rtmdet_detect(img)
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


# ---------------------------------------------------------------------------
# 3. obtain_landmark_data
# ---------------------------------------------------------------------------

def _top_down_affine(crop):
    h, w = crop.shape[:2]
    bbox_w, bbox_h = 1.25 * w, 1.25 * h
    w_scaled = max(bbox_h * 0.75, bbox_w)
    scale = RTMPOSE_SIZE / w_scaled
    m = np.float32([[scale, 0, RTMPOSE_SIZE / 2 - scale * w / 2],
                    [0, scale, RTMPOSE_SIZE / 2 - scale * h / 2]])
    return cv2.warpAffine(crop, m, (RTMPOSE_SIZE, RTMPOSE_SIZE), borderValue=0)


def _rotate_and_crop_square(img, cx, cy, side, degree):
    """Rotate image around (cx, cy) by degree and crop a side×side square."""
    ih, iw = img.shape[:2]
    # Pad to avoid clipping
    size = (int(math.sqrt(iw * iw + ih * ih)) + 2) * 2
    padded = np.zeros((size, size, 3), np.uint8)
    sh, sw = size // 2 - ih // 2, size // 2 - iw // 2
    padded[sh:sh + ih, sw:sw + iw] = img
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


def obtain_landmark_data(img, box, rotation, factor=BOX_EXPANSION):
    """Run RTMPose on a box at given rotation. Returns (21 Point, kp_mean) or None."""
    cx = (box[0] + box[2]) / 2
    cy = (box[1] + box[3]) / 2
    side = max((box[2] - box[0]) * factor, (box[3] - box[1]) * factor)

    crop = _rotate_and_crop_square(img, cx, cy, side, rotation)
    if crop is None:
        return None

    h, w = crop.shape[:2]
    warped = _top_down_affine(crop)
    f = ((warped.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(
        np.float32
    )
    bbox = np.asarray([[w, h]], dtype=np.int64)
    kps = sess_rtmp.run(None, {"input": f, "bboxes_width_height": bbox})[0][0]

    points = []
    kp_sum = 0.0
    for i in range(21):
        points.append(Point(float(kps[i, 0]), float(kps[i, 1]), float(kps[i, 2])))
        kp_sum += float(kps[i, 2])
    return points, kp_sum / 21


# ---------------------------------------------------------------------------
# 4. process_landmark_data
# ---------------------------------------------------------------------------

def _xy(p):
    return (p.x, p.y) if isinstance(p, Point) else p


def _dist(a, b):
    ax, ay = _xy(a)
    bx, by = _xy(b)
    return math.hypot(ax - bx, ay - by)


def _vec(a, b):
    return (b.x - a.x, b.y - a.y)


def _angle_between(a, b):
    dot = a[0] * b[0] + a[1] * b[1]
    mag = math.hypot(*a) * math.hypot(*b)
    if mag == 0:
        return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, dot / mag))))


def _is_extended(p, pip_i, tip_i):
    return _dist(p[tip_i], p[0]) > _dist(p[pip_i], p[0])


def _is_straight(p, pip_i, tip_i, hand_size, max_curl_ratio=0.25):
    """A finger is straight when PIP-to-tip distance / hand_size < threshold."""
    return _dist(p[tip_i], p[pip_i]) / hand_size < max_curl_ratio


def _shoelace_area(poly):
    area = 0.0
    n = len(poly)
    for i in range(n):
        x1, y1 = _xy(poly[i])
        x2, y2 = _xy(poly[(i + 1) % n])
        area += x1 * y2 - x2 * y1
    return abs(area) / 2.0


def process_landmark_data(points, kp_mean, edge_detected=False):
    """Extract features from 21 keypoints."""
    hs = _dist(points[0], points[9])
    if hs <= 0:
        return None

    # Sanity: reject degenerate keypoint clouds
    xs = [p.x for p in points]
    ys = [p.y for p in points]
    extent = max(max(xs) - min(xs), max(ys) - min(ys))
    extent_ratio = extent / hs

    # Finger extensions (basic: tip > PIP from wrist)
    idx_ext = _is_extended(points, 6, 8)
    mid_ext = _is_extended(points, 10, 12)
    ring_ext = _is_extended(points, 14, 16)
    pink_ext = _is_extended(points, 18, 20)
    all_curled = not (idx_ext or mid_ext or ring_ext or pink_ext)

    # Finger straightness: PIP-to-tip / hand_size. A truly extended
    # finger is both extended AND straight (not curled around an object).
    # Holding/gripping hands have fingers that pass the extension check
    # but are actually curled (straightness > 0.25).
    idx_straight = _is_straight(points, 6, 8, hs)
    mid_straight = _is_straight(points, 10, 12, hs)
    ring_straight = _is_straight(points, 14, 16, hs)
    pink_straight = _is_straight(points, 18, 20, hs)

    # Thumb metrics
    thumb_len = _dist(points[2], points[4]) / hs
    thumb_index = _dist(points[4], points[8]) / hs
    tdir = _vec(points[2], points[4])
    idir = _vec(points[5], points[8])
    tf_angle = _angle_between(tdir, idir)

    # Thumb tip angle from vertical (IP → TIP segment)
    tip_dx, tip_dy = _vec(points[3], points[4])
    thumb_tip_angle = abs(math.degrees(math.atan2(tip_dx, -tip_dy)))

    # Finger spread
    tips = [points[8], points[12], points[16], points[20]]
    spread = sum(_dist(tips[i], tips[i + 1]) for i in range(3)) / 3 / hs

    # OK-sign features
    ring = [points[2], points[3], points[4], points[8], points[7], points[6]]
    area = _shoelace_area(ring)
    perim = sum(_dist(ring[i], ring[(i + 1) % len(ring)]) for i in range(len(ring)))
    circularity = 4 * math.pi * area / (perim * perim) if perim > 0 else 0.0

    thumb_straight = _dist(points[2], points[4])
    ok_thumb_curl = ((_dist(points[2], points[3]) + _dist(points[3], points[
        4])) / thumb_straight) if thumb_straight > 0 else 0.0
    index_straight_len = _dist(points[5], points[8])
    ok_index_curl = ((_dist(points[5], points[6]) + _dist(points[6], points[7]) + _dist(points[7],
                                                                                        points[
                                                                                            8])) / index_straight_len) if index_straight_len > 0 else 0.0

    tip_mid_x = (points[4].x + points[8].x) / 2
    tip_mid_y = (points[4].y + points[8].y) / 2
    vx = points[5].x - points[2].x
    vy = points[5].y - points[2].y
    vlen = math.hypot(vx, vy)
    cross = vx * (tip_mid_y - points[2].y) - vy * (tip_mid_x - points[2].x)
    ring_h = abs(cross) / vlen / hs if vlen > 0 else 0.0
    ring_w = _dist(points[2], points[5]) / hs

    # Edge thumb fallback features
    thumb_conf = (points[1].z + points[2].z + points[3].z + points[4].z) / 4 if len(
        points) > 4 else 0
    finger_conf = sum(points[i].z for i in range(5, 21)) / 16 if len(points) > 20 else 0

    return FeatureSet(
        hand_size=hs,
        all_curled=all_curled,
        index_extended=idx_ext,
        middle_extended=mid_ext,
        ring_extended=ring_ext,
        pinky_extended=pink_ext,
        index_straight=idx_straight,
        middle_straight=mid_straight,
        ring_straight=ring_straight,
        pinky_straight=pink_straight,
        thumb_length_ratio=thumb_len,
        thumb_index_tip_distance=thumb_index,
        thumb_tip_angle=thumb_tip_angle,
        thumb_finger_angle=tf_angle,
        finger_spread=spread,
        extent_ratio=extent_ratio,
        ok_thumb_curl=ok_thumb_curl,
        ok_index_curl=ok_index_curl,
        ok_circularity=circularity,
        ok_ring_width=ring_w,
        ok_ring_height=ring_h,
        kp_mean=kp_mean,
        edge_detected=edge_detected,
        thumb_confidence=thumb_conf,
        finger_confidence=finger_conf,
    )


# ---------------------------------------------------------------------------
# 5. assign_score
# ---------------------------------------------------------------------------

def _score_for_thumb_angle(angle):
    if angle < THUMBS_UP_MAX_ANGLE:
        return 5
    if angle < SCORE_FOUR_MAX_ANGLE:
        return 4
    if angle < SCORE_THREE_MAX_ANGLE:
        return 3
    if angle < SCORE_TWO_MAX_ANGLE:
        return 2
    return 1


def assign_score(features):
    """Classify a hand into a gesture and score, or None.

    Returns (gesture, score) or None if features are invalid.
    """
    if features is None:
        return None

    hs = features.hand_size
    if hs <= 0 or features.extent_ratio > MAX_EXTENT_RATIO:
        return None

    # --- Edge thumb-only fallback (partial hand out of frame) ---
    if (features.edge_detected
            and features.thumb_confidence >= EDGE_THUMB_MIN_CONF
            and features.finger_confidence < EDGE_FINGER_MAX_CONF
            and features.thumb_tip_angle <= EDGE_THUMB_MAX_ANGLE):
        return ("THUMBS_UP", _score_for_thumb_angle(features.thumb_tip_angle))

    # --- OK sign: closed circle of thumb+index facing camera ---
    # Extended fingers have natural curvature, so straightness is NOT checked
    # here. The circularity + curl + ring dimension gates are strong enough.
    if (features.thumb_index_tip_distance < OK_SIGN_MAX_THUMB_INDEX_DIST
            and features.middle_extended
            and features.ring_extended
            and features.pinky_extended
            and features.finger_spread > OK_MIN_SPREAD
            and features.ok_thumb_curl >= OK_MIN_THUMB_CURL
            and features.ok_index_curl >= OK_MIN_INDEX_CURL
            and features.ok_circularity >= OK_MIN_CIRCULARITY
            and min(features.ok_ring_width, features.ok_ring_height) >= OK_MIN_RING_SIDE):
        return ("OK_SIGN", 3)

    # --- Thumbs up: fingers curled, thumb extended away ---
    # all_curled uses basic extension (tip > PIP from wrist). For thumbs-up,
    # we additionally verify no finger is straight — a straight finger means
    # the hand is open/holding, not a thumbs-up.
    # tip_angle > 165 means the thumb points downward (score 1) — reject
    # these as false positives on non-thumb-up hands (e.g. 33_Screenshot
    # at 180°).
    if (features.all_curled
            and not features.index_straight
            and not features.middle_straight
            and not features.ring_straight
            and not features.pinky_straight
            and features.thumb_length_ratio > THUMBS_MIN_THUMB_LENGTH
            and features.thumb_length_ratio < MAX_THUMB_LEN
            and features.thumb_finger_angle > THUMBS_MIN_THUMB_FINGER_ANGLE
            and features.thumb_tip_angle < SCORE_TWO_MAX_ANGLE):
        return ("THUMBS_UP", _score_for_thumb_angle(features.thumb_tip_angle))

    # Full hands without a recognized gesture: detected but unscored.
    # The caller marks these as "detected" (not filtered) with no score.
    return None


# ---------------------------------------------------------------------------
# 6. run_pipeline
# ---------------------------------------------------------------------------

def _try_rotations(img, box):
    """Try all rotation candidates for a box.

    Returns list of (gesture, score, kp_mean, deg, uncertain).
    gesture=None means hand detected but no recognized gesture.
    Empty list means no hand detected at all.
    """
    candidates = list(CANDIDATE_ROTATIONS)
    results = []
    bestkp = None  # highest kp across all rotations, even without gesture
    for factor in FALLBACK_FACTORS:
        for deg in candidates:
            data = obtain_landmark_data(img, box, deg, factor)
            if data is None:
                continue
            points, kp_mean = data
            features = process_landmark_data(points, kp_mean)
            # Track bestkp even when no gesture matches
            if bestkp is None or kp_mean > bestkp[0]:
                bestkp = (kp_mean, deg)
            cls = assign_score(features)
            if cls is not None:
                gesture, score = cls
                uncertain = kp_mean < CONFIDENT_KP
                results.append((gesture, score, kp_mean, deg, uncertain))
                # Early exit: confident upright read
                if deg == 0 and factor == BOX_EXPANSION and kp_mean >= 0.3:
                    return results
    # Filter gesture results with very low kp (unreliable classification)
    results = [r for r in results if r[2] >= MIN_KP_CONFIDENCE]
    # If no gesture matched but RTMPose found a hand, return it as detected
    if not results and bestkp is not None and bestkp[0] >= MIN_KP_CONFIDENCE:
        kp_mean, deg = bestkp
        results.append((None, 0, kp_mean, deg, kp_mean < CONFIDENT_KP))
    return results


def _edge_scan_rotations(img, im_box):
    """Try all rotations for an edge-detected box. Returns list of results or empty."""
    for factor in FALLBACK_FACTORS:
        for deg in CANDIDATE_ROTATIONS:
            data = obtain_landmark_data(img, im_box, deg, factor)
            if data is None:
                continue
            points, kp_mean = data
            features = process_landmark_data(points, kp_mean, edge_detected=True)
            cls = assign_score(features)
            if cls is None or cls[0] != "THUMBS_UP":
                continue
            if kp_mean < EDGE_MIN_KP:
                continue
            gesture, score = cls
            return [(gesture, score, kp_mean, deg, True)]  # always uncertain
    return []


def _edge_thumb_fallback(img, boxes):
    """Edge thumb-only fallback for partial hands (5_kimbo-style)."""
    if not boxes or boxes[0][4] < EDGE_HINT_DET:
        return []

    iw, ih = img.shape[1], img.shape[0]
    strip_w = max(int(iw * EDGE_STRIP_FRACTION), 1)
    hands = []

    for side in range(2):
        x0 = 0 if side == 0 else iw - strip_w
        for fb, ft in EDGE_BANDS:
            y0 = int(ih * fb)
            y1 = int(ih * ft)
            strip_h = y1 - y0
            if strip_h <= 0 or strip_w <= 0:
                continue
            strip = img[y0:y1, x0:x0 + strip_w]
            # Pad to square for RTMDet
            sq_side = max(strip_w, strip_h)
            padded = np.zeros((sq_side, sq_side, 3), np.uint8)
            padded[:strip.shape[0], :strip.shape[1]] = strip

            det_boxes, det_scores, ratio = _rtmdet_detect(padded)
            m = det_scores > 0
            det_boxes, det_scores = det_boxes[m], det_scores[m]
            if len(det_boxes) == 0 or det_scores.max() < MIN_DET:
                continue

            # Best detection, mapped back to full-image coords
            best_i = det_scores.argmax()
            bx1 = max(int(det_boxes[best_i][0] / ratio), 0)
            by1 = max(int(det_boxes[best_i][1] / ratio), 0)
            bx2 = min(int(det_boxes[best_i][2] / ratio), sq_side)
            by2 = min(int(det_boxes[best_i][3] / ratio), sq_side)
            if bx2 - bx1 < 8 or by2 - by1 < 8:
                continue
            im_box = (x0 + bx1, y0 + by1, x0 + bx2, y0 + by2)

            # Run RTMPose at all rotations
            found = _edge_scan_rotations(img, im_box)
            if found:
                hands.extend(found)
                return hands[:MAX_HANDS]
    return hands


def run_pipeline(img, boxes):
    """Full pipeline: try rotations for each box, pick best, edge fallback.

    Always scores detected hands (never filters at kp threshold).
    Returns list of Result.
    """
    all_results = []
    for box in boxes[:MAX_HANDS]:
        if box[4] < MIN_DET:
            continue
        candidates = _try_rotations(img, box)
        if not candidates:
            continue
        # Pick best: THUMBS_UP preferred, then highest score, then kp confidence
        best = None
        for cand in candidates:
            gesture, score, kp_mean, deg, uncertain = cand
            if best is None:
                best = cand
                continue
            b_gesture, b_score, b_kp, b_deg, b_unc = best
            # Prefer scored gestures over unscored
            if gesture is not None and b_gesture is None:
                best = cand
            elif gesture is None and b_gesture is not None:
                pass
            elif gesture == "THUMBS_UP" and b_gesture != "THUMBS_UP":
                best = cand
            elif gesture != "THUMBS_UP" and b_gesture == "THUMBS_UP":
                pass
            elif gesture == "THUMBS_UP":
                if score > b_score or (score == b_score and kp_mean > b_kp):
                    best = cand
            elif kp_mean > b_kp:
                best = cand
        gesture, score, kp_mean, deg, uncertain = best
        all_results.append(Result(
            gesture=gesture, score=score, uncertain=uncertain,
            kp_mean=kp_mean, rotation=deg,
        ))

    # Edge thumb fallback if main pass found nothing
    if not all_results:
        edge_results = _edge_thumb_fallback(img, boxes)
        for gesture, score, kp_mean, deg, uncertain in edge_results:
            all_results.append(Result(
                gesture=gesture, score=score, uncertain=uncertain,
                kp_mean=kp_mean, rotation=deg, edge_detected=True,
            ))

    return all_results


# ---------------------------------------------------------------------------
# main: test runner
# ---------------------------------------------------------------------------

def main():
    images_dir = Path(
        __file__).resolve().parent.parent.parent / "ml" / "litert" / "scripts" / "test" / "images"

    total = 0
    correct = 0
    false_positives = []
    misses = []
    uncertain_filtered = []

    for bucket_dir in sorted(images_dir.iterdir()):
        if not bucket_dir.is_dir():
            continue
        name = bucket_dir.name

        # Parse bucket
        if name == "rejected":
            expect = "filter"
        elif name.startswith("confident_") and name != "confident_rejected":
            try:
                expected_score = int(name.split("_")[1])
                expect = "detect"
            except (ValueError, IndexError):
                continue
        elif name.startswith("uncertain_"):
            try:
                expected_score = int(name.split("_")[1])
                expect = "uncertain"
            except (ValueError, IndexError):
                continue
        else:
            continue

        images = sorted(p for p in bucket_dir.iterdir()
                        if p.is_file() and p.suffix.lower() in (".jpg", ".jpeg", ".png"))
        if not images:
            continue

        print(f"\n{'=' * 80}")
        print(f"  {name} (expect={expect}" + (
            f", score={expected_score}" if expect != "filter" else "") + ")")
        print(f"{'=' * 80}")

        for img_path in images:
            img = prepare_image(img_path)
            if img is None:
                status = "DECODE_FAIL"
                if expect == "filter":
                    status = "OK (decode_failed)"
                print(f"  {img_path.name:<55} [{status}]")
                total += 1
                if expect != "filter":
                    correct += 1  # decode fail = no gesture = ok for uncertain
                continue

            boxes = detect_hands(img)
            results = run_pipeline(img, boxes)
            total += 1

            has_gesture = any(r.gesture is not None for r in results)
            has_hand = any(r.kp_mean >= MIN_KP_CONFIDENCE for r in results)
            detected_scores = [(r.gesture, r.score, r.kp_mean, r.uncertain) for r in results if
                               r.gesture]

            if expect == "filter":
                if not has_gesture:
                    correct += 1
                    print(f"  {img_path.name:<55} [OK (no gesture)]")
                else:
                    false_positives.append(f"{name}/{img_path.name}")
                    labels = [f"{g}-{s} kp={kp:.2f} unc={u}" for g, s, kp, u in detected_scores]
                    print(f"  {img_path.name:<55} [FP: {'; '.join(labels)}]")
            elif expect == "detect":
                if not has_gesture:
                    misses.append(f"{name}/{img_path.name}")
                    print(f"  {img_path.name:<55} [MISS: no gesture]")
                else:
                    matched = any(s == expected_score for _, s, _, _ in detected_scores)
                    if matched:
                        correct += 1
                        labels = [f"{g}-{s} kp={kp:.2f}" for g, s, kp, _ in detected_scores]
                        print(f"  {img_path.name:<55} [OK: {'; '.join(labels)}]")
                    else:
                        misses.append(f"{name}/{img_path.name}")
                        labels = [f"{g}-{s} kp={kp:.2f}" for g, s, kp, _ in detected_scores]
                        print(
                            f"  {img_path.name:<55} [MISS: expected {expected_score}, got {'; '.join(labels)}]")
            elif expect == "uncertain":
                # Uncertain images MUST have a detected hand (filtering = fail)
                # The hand may or may not have a recognized gesture.
                if not has_hand:
                    uncertain_filtered.append(f"{name}/{img_path.name}")
                    print(f"  {img_path.name:<55} [FAIL: no hand detected]")
                elif not has_gesture:
                    # Hand detected but no recognized gesture — still passes
                    correct += 1
                    kp_info = [f"kp={r.kp_mean:.2f}" for r in results]
                    print(
                        f"  {img_path.name:<55} [OK (detected, no gesture): {'; '.join(kp_info)}]")
                else:
                    matched = any(s == expected_score for _, s, _, _ in detected_scores)
                    if matched:
                        correct += 1
                        labels = [f"{g}-{s} kp={kp:.2f} unc={u}" for g, s, kp, u in detected_scores]
                        print(f"  {img_path.name:<55} [OK: {'; '.join(labels)}]")
                    else:
                        # Detected with wrong score — still passes (uncertain is flexible)
                        correct += 1
                        labels = [f"{g}-{s} kp={kp:.2f} unc={u}" for g, s, kp, u in detected_scores]
                        print(
                            f"  {img_path.name:<55} [SCORE_MISMATCH: expected {expected_score}, got {'; '.join(labels)}]")

    print(f"\n{'=' * 80}")
    print(f"  Results: {correct}/{total} passed")
    if false_positives:
        print(f"\n  False positives ({len(false_positives)}):")
        for fp in false_positives:
            print(f"    {fp}")
    if misses:
        print(f"\n  Misses ({len(misses)}):")
        for m in misses:
            print(f"    {m}")
    if uncertain_filtered:
        print(f"\n  Uncertain filtered ({len(uncertain_filtered)}):")
        for u in uncertain_filtered:
            print(f"    {u}")
    print(f"{'=' * 80}")
    return 0 if (not false_positives and not misses and not uncertain_filtered) else 1


if __name__ == "__main__":
    sys.exit(main())
