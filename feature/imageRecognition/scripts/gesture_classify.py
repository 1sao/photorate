#!/usr/bin/env python3
"""Gesture classification: 1:1 Python port of the Kotlin recognizers.

Kotlin counterparts (KDoc links both ways):
- feature/imageRecognition/imageRecognitionComponentApi/src/commonMain/kotlin/isao/photorate/
    imageRecognition/classify/HandFeatures2.kt          -> HandFeatures
    imageRecognition/classify/ThumbSignalRecognizer.kt  -> ThumbSignalRecognizer
    imageRecognition/classify/OkSignRecognizer.kt       -> OkSignRecognizer
    imageRecognition/classify/ThumbOnlyGestureRecognizer.kt -> ThumbOnlyRecognizer
    imageRecognition/classify/LenientThumbGestureRecognizer.kt -> LenientThumbRecognizer
    imageRecognition/classify/Score.kt                  -> score_for_thumb_angle / Score enum

Recognizer order matches LiteRtGestureRecognizerProvider.createRecognizers():
ThumbSignal -> OkSign -> ThumbOnly -> LenientThumb. First match wins
(see TryRecognizers.kt and LiteRtHandLandmarker.detectWithRecognizers).

Confidence semantics (must match Kotlin exactly):
- ThumbSignalRecognizer / OkSignRecognizer: confidence 1.0 when kpMean >= 0.45,
  else confidence = kpMean.
- ThumbOnlyRecognizer / LenientThumbRecognizer: confidence always 0.
"""
from __future__ import annotations

import math

# ---------------------------------------------------------------------------
# Keypoint indices (HandFeatures2 companion)
# ---------------------------------------------------------------------------
WRIST = 0
THUMB_CMC = 1
THUMB_MCP = 2
THUMB_IP = 3
THUMB_TIP = 4
INDEX_MCP = 5
INDEX_PIP = 6
INDEX_DIP = 7
INDEX_TIP = 8
MIDDLE_MCP = 9
MIDDLE_PIP = 10
MIDDLE_TIP = 12
RING_PIP = 14
RING_TIP = 16
PINKY_PIP = 18
PINKY_TIP = 20
NUM_LANDMARKS = 21

STRAIGHT_THRESHOLD = 0.25
RAD_TO_DEG = 180.0 / math.pi


def dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def angle_between(ax, ay, bx, by):
    dot = ax * bx + ay * by
    mag = math.hypot(ax, ay) * math.hypot(bx, by)
    if mag == 0:
        return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, dot / mag))))


class HandFeatures:
    """Port of HandFeatures2 — structured features from 21 keypoints.

    points: list of (x, y, confidence) tuples in crop/image pixel space.
    """

    def __init__(self, points):
        self.points = points
        p = points
        self.hand_size = dist(p[WRIST], p[MIDDLE_MCP])

        xs = [q[0] for q in p]
        ys = [q[1] for q in p]
        self.extent_ratio = (
            max(max(xs) - min(xs), max(ys) - min(ys)) / self.hand_size
            if self.hand_size > 0
            else 0.0
        )
        self.kp_mean = sum(q[2] for q in p) / len(p)

        tips = [p[INDEX_TIP], p[MIDDLE_TIP], p[RING_TIP], p[PINKY_TIP]]
        spread_total = sum(dist(tips[i], tips[i + 1]) for i in range(len(tips) - 1))
        self.finger_spread = (
            spread_total / (len(tips) - 1) / self.hand_size if self.hand_size > 0 else 0.0
        )

        self.index = _Finger(p, INDEX_PIP, INDEX_TIP, self.hand_size)
        self.middle = _Finger(p, MIDDLE_PIP, MIDDLE_TIP, self.hand_size)
        self.ring = _Finger(p, RING_PIP, RING_TIP, self.hand_size)
        self.pinky = _Finger(p, PINKY_PIP, PINKY_TIP, self.hand_size)
        self.fingers = [self.index, self.middle, self.ring, self.pinky]
        self.all_curled = not any(f.is_extended for f in self.fingers)

        self.thumb = _Thumb(p, self.hand_size)

        # Mean keypoint confidence of the thumb chain (kp1..4) and of the four fingers (kp5..20)
        self.thumb_conf = sum(p[i][2] for i in range(THUMB_CMC, THUMB_TIP + 1)) / 4
        self.finger_conf = sum(p[i][2] for i in range(INDEX_MCP, NUM_LANDMARKS)) / (
                NUM_LANDMARKS - INDEX_MCP
        )


class _Finger:
    """Port of HandFeatures2.Finger (extension + straightness)."""

    def __init__(self, p, pip_i, tip_i, hand_size):
        self.is_extended = dist(p[tip_i], p[WRIST]) > dist(p[pip_i], p[WRIST])
        self.is_straight = (
            dist(p[tip_i], p[pip_i]) / hand_size < STRAIGHT_THRESHOLD
            if hand_size > 0
            else False
        )


class _Thumb:
    """Port of HandFeatures2.Thumb (MCP->TIP angle conventions)."""

    def __init__(self, p, hand_size):
        self.length_ratio = (
            dist(p[THUMB_MCP], p[THUMB_TIP]) / hand_size if hand_size > 0 else 0.0
        )
        self.tip_angle = 0.0
        self.finger_angle = 0.0
        self.index_tip_distance = 0.0
        if hand_size <= 0:
            return
        dx = p[THUMB_TIP][0] - p[THUMB_MCP][0]
        dy = p[THUMB_TIP][1] - p[THUMB_MCP][1]
        self.tip_angle = abs(math.degrees(math.atan2(dx, -dy)))
        ix = p[INDEX_TIP][0] - p[INDEX_MCP][0]
        iy = p[INDEX_TIP][1] - p[INDEX_MCP][1]
        self.finger_angle = angle_between(dx, dy, ix, iy)
        self.index_tip_distance = dist(p[THUMB_TIP], p[INDEX_TIP]) / hand_size


def score_for_thumb_angle(angle):
    """Score boundaries from ThumbSignal.kt: 30/75/105/165 (updated)."""
    if angle < 30:
        return 5
    if angle < 75:
        return 4
    if angle < 105:
        return 3
    if angle < 165:
        return 2
    return 1


# ---------------------------------------------------------------------------
# Recognizers (order = LiteRtGestureRecognizerProvider)
# ---------------------------------------------------------------------------

class RecognizedGesture:
    def __init__(self, gesture, score, confidence):
        self.gesture = gesture
        self.score = score
        self.confidence = confidence


class ThumbSignalRecognizer:
    """Port of ThumbSignalRecognizer.kt — full thumbs-up: all fingers curled AND
    straight (not gripping), thumb pointing away."""

    MAX_EXTENT_RATIO = 2.9
    MIN_THUMB_LENGTH = 0.25
    MAX_THUMB_LENGTH = 2.0
    MIN_THUMB_FINGER_ANGLE = 95
    CONFIDENT_KP = 0.45

    def recognize(self, f):
        if f.hand_size <= 0 or f.extent_ratio > self.MAX_EXTENT_RATIO:
            return None
        if f.all_curled is False:
            return None
        if any(fin.is_straight for fin in f.fingers):
            return None
        t = f.thumb
        if t.length_ratio <= self.MIN_THUMB_LENGTH or t.length_ratio >= self.MAX_THUMB_LENGTH:
            return None
        if t.finger_angle <= self.MIN_THUMB_FINGER_ANGLE:
            return None
        confidence = 1.0 if f.kp_mean >= self.CONFIDENT_KP else f.kp_mean
        return RecognizedGesture("THUMBS_UP", score_for_thumb_angle(t.tip_angle), confidence)


class OkSignRecognizer:
    """Port of OkSignRecognizer.kt — closed thumb+index circle, other fingers extended."""

    MAX_EXTENT_RATIO = 2.9
    OK_SIGN_MAX_THUMB_INDEX_DIST = 0.2
    OK_MIN_THUMB_CURL = 1.08
    OK_MIN_INDEX_CURL = 1.15
    OK_MIN_CIRCULARITY = 0.40
    OK_MIN_RING_SIDE = 0.25
    OK_MIN_SPREAD = 0.10
    CONFIDENT_KP = 0.45

    def recognize(self, f):
        if f.hand_size <= 0 or f.extent_ratio > self.MAX_EXTENT_RATIO:
            return None
        if any(not fin.is_extended for fin in f.fingers[1:]):
            return None
        if f.finger_spread <= self.OK_MIN_SPREAD:
            return None
        if f.thumb.index_tip_distance >= self.OK_SIGN_MAX_THUMB_INDEX_DIST:
            return None
        if self._thumb_curl(f) < self.OK_MIN_THUMB_CURL:
            return None
        if self._index_curl(f) < self.OK_MIN_INDEX_CURL:
            return None
        if self._circularity(f) < self.OK_MIN_CIRCULARITY:
            return None
        if min(self._ring_width(f), self._ring_height(f)) < self.OK_MIN_RING_SIDE:
            return None
        confidence = 1.0 if f.kp_mean >= self.CONFIDENT_KP else f.kp_mean
        return RecognizedGesture("OK_SIGN", 3, confidence)

    def _thumb_curl(self, f):
        p = f.points
        straight = dist(p[THUMB_MCP], p[THUMB_TIP])
        if straight <= 0:
            return 0.0
        return (dist(p[THUMB_MCP], p[THUMB_IP]) + dist(p[THUMB_IP], p[THUMB_TIP])) / straight

    def _index_curl(self, f):
        p = f.points
        straight = dist(p[INDEX_MCP], p[INDEX_TIP])
        if straight <= 0:
            return 0.0
        return (
                dist(p[INDEX_MCP], p[INDEX_PIP])
                + dist(p[INDEX_PIP], p[INDEX_DIP])
                + dist(p[INDEX_DIP], p[INDEX_TIP])
        ) / straight

    def _circularity(self, f):
        p = f.points
        ring = [p[THUMB_MCP], p[THUMB_IP], p[THUMB_TIP], p[INDEX_TIP], p[INDEX_DIP], p[INDEX_PIP]]
        area = 0.0
        for i in range(len(ring)):
            x1, y1 = ring[i][0], ring[i][1]
            x2, y2 = ring[(i + 1) % len(ring)][0], ring[(i + 1) % len(ring)][1]
            area += x1 * y2 - x2 * y1
        area = abs(area) / 2.0
        perim = sum(dist(ring[i], ring[(i + 1) % len(ring)]) for i in range(len(ring)))
        if perim <= 0:
            return 0.0
        return 4 * math.pi * area / (perim * perim)

    def _ring_width(self, f):
        p = f.points
        return dist(p[THUMB_MCP], p[INDEX_MCP]) / f.hand_size

    def _ring_height(self, f):
        p = f.points
        tip_mid_x = (p[THUMB_TIP][0] + p[INDEX_TIP][0]) / 2
        tip_mid_y = (p[THUMB_TIP][1] + p[INDEX_TIP][1]) / 2
        vx = p[INDEX_MCP][0] - p[THUMB_MCP][0]
        vy = p[INDEX_MCP][1] - p[THUMB_MCP][1]
        vlen = math.hypot(vx, vy)
        if vlen <= 0:
            return 0.0
        cross = vx * (tip_mid_y - p[THUMB_MCP][1]) - vy * (tip_mid_x - p[THUMB_MCP][0])
        return abs(cross) / vlen / f.hand_size


class ThumbOnlyRecognizer:
    """Port of ThumbOnlyGestureRecognizer.kt — permissive: fingers may be extended.

    - Angular gate is the thumb's direction against the image's vertical axis
      (tip_angle: 0 = straight up, 90 = horizontal, 180 = straight down)
      instead of the thumb-vs-index finger_angle, since a rotated view can make
      thumb and index nearly parallel even for a real thumbs-up.
    - The fallback only applies to partial/edge hands: fingers not confidently
      tracked (finger_conf low) but thumb chain solid (thumb_conf high).
    """

    MIN_KP_CONFIDENCE = 0.30
    MAX_EXTENT_RATIO = 3.3
    FALLBACK_MIN_THUMB_LENGTH = 0.4
    FALLBACK_MIN_TIP_ANGLE = 15
    FALLBACK_MAX_TIP_ANGLE = 165
    FALLBACK_MAX_FINGER_CONF = 0.35
    FALLBACK_MIN_THUMB_CONF = 0.35

    def recognize(self, f):
        if f.kp_mean < self.MIN_KP_CONFIDENCE:
            return None
        if f.hand_size <= 0 or f.extent_ratio > self.MAX_EXTENT_RATIO:
            return None
        if f.finger_conf >= self.FALLBACK_MAX_FINGER_CONF:
            return None
        if f.thumb_conf < self.FALLBACK_MIN_THUMB_CONF:
            return None
        t = f.thumb
        if t.length_ratio <= self.FALLBACK_MIN_THUMB_LENGTH:
            return None
        if t.tip_angle <= self.FALLBACK_MIN_TIP_ANGLE or t.tip_angle > self.FALLBACK_MAX_TIP_ANGLE:
            return None
        return RecognizedGesture("THUMBS_UP", score_for_thumb_angle(t.tip_angle), 0.0)


class LenientThumbRecognizer:
    """Port of LenientThumbGestureRecognizer.kt — last-resort patterns A/B/C."""

    MIN_KP = 0.30
    MIN_LENGTH_RATIO = 0.2
    HIGH_TIP_RANGE = (100.0, 180.0)
    LONG_THUMB_RATIO = 1.0
    VERY_HIGH_TIP_MIN = 155.0
    LOW_TIP_RANGE = (10.0, 35.0)

    def recognize(self, f):
        if f.kp_mean < self.MIN_KP:
            return None
        if f.hand_size <= 0:
            return None
        t = f.thumb
        if t.length_ratio <= self.MIN_LENGTH_RATIO:
            return None
        has_straight = any(fin.is_straight for fin in f.fingers)
        has_extended = any(fin.is_extended for fin in f.fingers)

        # Pattern A: extended fingers, high tipAngle, long thumb
        if has_extended and self.HIGH_TIP_RANGE[0] <= t.tip_angle <= self.HIGH_TIP_RANGE[1]:
            if t.length_ratio >= self.LONG_THUMB_RATIO:
                return RecognizedGesture("THUMBS_UP", score_for_thumb_angle(t.tip_angle), 0.0)

        # Pattern B: all curled, thumb nearly straight down
        if f.all_curled and t.tip_angle >= self.VERY_HIGH_TIP_MIN:
            return RecognizedGesture("THUMBS_UP", score_for_thumb_angle(t.tip_angle), 0.0)

        # Pattern C: extended fingers, low tipAngle, no straight fingers
        if (
                has_extended
                and not has_straight
                and self.LOW_TIP_RANGE[0] <= t.tip_angle <= self.LOW_TIP_RANGE[1]
        ):
            return RecognizedGesture("THUMBS_UP", score_for_thumb_angle(t.tip_angle), 0.0)

        return None


def create_recognizers():
    """Order mirrors LiteRtGestureRecognizerProvider.createRecognizers()."""
    return [
        ThumbSignalRecognizer(),
        OkSignRecognizer(),
        ThumbOnlyRecognizer(),
        LenientThumbRecognizer(),
    ]
