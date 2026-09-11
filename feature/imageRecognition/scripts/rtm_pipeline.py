#!/usr/bin/env python3
"""Shared RTMDet + RTMPose hand pipeline with two interchangeable backends.

Python counterpart of feature/imageRecognition/imageRecognitionComponentLiteRt/src/commonMain/
kotlin/isao/photorate/imageRecognition/litert/LiteRtHandLandmarker.kt (KDoc links both ways).

Backends
--------
- ``onnx``  — the **rtmlib** package running the app's local ONNX models
  (ml/original_models/onnx/rtmdet_n_hand.onnx + rtmpose_hand_rawsimcc.onnx). This is the
  ground-truth oracle: rtmlib's bbox_xyxy2cs(padding=1.25) + top_down_affine
  is exactly the crop pipeline LiteRtHandLandmarker.getWarpMatrix mirrors.
  Expected to produce BETTER results than the device because the Kotlin
  LiteRT path uses a fixed 1.25x crop at rotation 0 (see
  LITERT_INVESTIGATION_SUMMARY.md).
- ``tflite`` — the actual converted models the device runs
  (ml/litert/converted/rtmdet_hand_320_f32.tflite +
  rtmpose_hand_256_f32.tflite) via ai-edge-litert. Reproduces
  LiteRtHandLandmarker 1:1: gray-114 top-left letterbox, caller-side NMS
  (iou=0.5, score=0.05), 1.25x rtplib crop, rotation 0, caller-side SimCC
  decode (split ratio 2.0, conf=(xv+yv)/2).

Usage:
    from rtm_pipeline import Pipeline, detect_with_recognizers
    pipe = Pipeline("onnx")   # or "tflite"
    hands = pipe.detect_with_recognizers(path, recognizers)
"""
from __future__ import annotations

import cv2
import math
import numpy as np
import sys
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPTS_DIR.parents[2]

# ---------------------------------------------------------------------------
# Constants mirroring LiteRtHandLandmarker's companion object
# ---------------------------------------------------------------------------
DETECTOR_SIZE = 320
RTMPOSE_SIZE = 256
RTPLIB_CROP = 1.25
MIN_DET = 0.25
# Tier-2 admission for the second-ranked box: when its detScore falls below
# MIN_DET it may still be pose-run and classified, but only a high-quality,
# geometrically-plausible gesture read is accepted. This rescues real hands
# (e.g. confident_5/IMG_20250209_221817.jpg, box1 det 0.186) without letting
# tiny/degenerate landmark clouds through.
TIER2_MIN_DET = 0.15
TIER2_KP_FLOOR = 0.45
MIN_HAND_TO_BOX_RATIO = 0.15
MAX_THUMB_LENGTH_RATIO = 2.0
MIN_KP_CONFIDENCE = 0.30
MIN_HAND_SIZE = 40
MAX_HANDS = 2
MIN_BOX_SIDE = 8
NUM_LANDMARKS = 21
SIMCC_SIZE = 512
SIMCC_SPLIT_RATIO = 2.0
RTMLIB_DET_PAD = 114  # 0x72 gray, top-left aligned (LiteRtHandLandmarker uses 0xFF727272)
RTMLIB_NMS_IOU = 0.5
RTMLIB_NMS_SCORE = 0.05
RTMLIB_MEAN = np.array([123.675, 116.28, 103.53], dtype=np.float32)
RTMLIB_STD = np.array([58.395, 57.12, 57.375], dtype=np.float32)
DECODE_MIN_DIM = 640

ONNX_MODELS_DIR = PROJECT_ROOT / "ml" / "original_models" / "onnx"
ONNX_DETECTOR = ONNX_MODELS_DIR / "rtmdet_n_hand.onnx"
ONNX_POSE = ONNX_MODELS_DIR / "rtmpose_hand.onnx"

TFLITE_DIR = PROJECT_ROOT / "ml" / "litert" / "converted"
TFLITE_DETECTOR = TFLITE_DIR / "rtmdet_hand_320_f32.tflite"
TFLITE_POSE = TFLITE_DIR / "rtmpose_hand_256_f32.tflite"


def decode_image(path, min_dim=DECODE_MIN_DIM):
    """App-parity decode: full-res decode then scale so min(w,h) <= min_dim.

    Mirrors LandmarkerTest.decodeFullResThenResize (FULL_RES_DECODE=true).
    """
    img = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if img is None:
        return None
    ih, iw = img.shape[:2]
    scale = min(iw, ih) / float(min_dim)
    if scale > 1.0:
        img = cv2.resize(img, (int(iw / scale), int(ih / scale)), interpolation=cv2.INTER_AREA)
    return img


def _normalize(img):
    """BGR pixels, mmdet mean/std, NCHW float32."""
    arr = img.astype(np.float32)
    arr = (arr - RTMLIB_MEAN) / RTMLIB_STD
    return np.transpose(arr, (2, 0, 1))[np.newaxis, ...]


def nms(boxes, scores, iou_thr=RTMLIB_NMS_IOU, score_thr=RTMLIB_NMS_SCORE):
    """Caller-side NMS matching LiteRtHandLandmarker's MathOps.nms params."""
    keep = []
    scores = np.asarray(scores, dtype=np.float64).reshape(-1)
    order = np.argsort(-scores)
    for i in order:
        if scores[i] < score_thr:
            continue
        b = boxes[i]
        drop = False
        for k in keep:
            bk = boxes[k]
            xx1, yy1 = max(b[0], bk[0]), max(b[1], bk[1])
            xx2, yy2 = min(b[2], bk[2]), min(b[3], bk[3])
            w, h = max(0.0, xx2 - xx1), max(0.0, yy2 - yy1)
            inter = w * h
            union = (b[2] - b[0]) * (b[3] - b[1]) + (bk[2] - bk[0]) * (bk[3] - bk[1]) - inter
            if union > 0 and inter / union > iou_thr:
                drop = True
                break
        if not drop:
            keep.append(int(i))
    return keep


def _nms_baked(boxes, scores, iou_thr=RTMLIB_NMS_IOU, score_thr=RTMLIB_NMS_SCORE):
    """NMS for the ONNX detector whose NMS is baked into the graph.

    The baked graph already applied NMS + sort; we only threshold + sort.
    """
    mask = scores >= score_thr
    boxes, scores = boxes[mask], scores[mask]
    order = np.argsort(-scores)
    return boxes[order], scores[order]


# ---------------------------------------------------------------------------
# ONNX backend via rtmlib
# ---------------------------------------------------------------------------

class OnnxBackend:
    """Ground-truth oracle: rtmlib's exact math on the app's local ONNX models.

    Both sessions are driven directly (rtmlib's tool classes can't be used
    as-is here: its RTMDet wrapper converts to RGB and mangles the baked-NMS
    output rows, and its RTMPose.__call__ does not forward the
    bboxes_width_height input this export requires). Instead this backend
    mirrors rtmlib's own pre/post-processing functions 1:1 — verified against
    the installed rtmlib source (pre_processings.py, post_processings.py):

    - Detector: BGR input (the PINTO export expects it — RGB starves the
      detector), mmdet mean/std, top-left 114 letterbox (the app's
      convention), [1, N, 5] baked-NMS output thresholded + sorted.
    - Pose: rtmlib bbox_xyxy2cs(padding=1.25) → aspect-fixed scale →
      get_warp_matrix(rot=0) → raw SimCC → get_simcc_maximum → /split_ratio
      → rescale to image space via center/scale (rtmlib postprocess exactly).

    Uses the raw-SimCC export (rtmpose_hand_rawsimcc.onnx, generated by
    strip_simcc_postprocess.py) so the oracle decodes with rtmlib's own math
    instead of the PINTO in-graph decode, whose bboxes_width_height
    convention differs from rtmlib's rescale.
    """

    def __init__(self, models_dir=ONNX_MODELS_DIR):
        import onnxruntime as ort

        models = Path(models_dir)
        self.det_session = ort.InferenceSession(
            str(models / "rtmdet_n_hand.onnx"), providers=["CPUExecutionProvider"],
        )
        self.pose_session = ort.InferenceSession(
            str(models / "rtmpose_hand_rawsimcc.onnx"), providers=["CPUExecutionProvider"],
        )

    def detect_hands(self, img):
        """Return [(x1, y1, x2, y2, score), ...] sorted by score desc."""
        ih, iw = img.shape[:2]
        ratio = min(DETECTOR_SIZE / iw, DETECTOR_SIZE / ih)
        nw, nh = max(int(iw * ratio), 1), max(int(ih * ratio), 1)
        resized = cv2.resize(img, (nw, nh))  # BGR kept
        padded = np.full((DETECTOR_SIZE, DETECTOR_SIZE, 3), RTMLIB_DET_PAD, np.uint8)
        padded[:nh, :nw] = resized  # top-left aligned (app convention)

        self.det_session_in = self.det_session.get_inputs()[0].name
        dets = self.det_session.run(None, {self.det_session_in: _normalize(padded)})[0]
        dets = np.asarray(dets)[0]  # [N, 5] x1,y1,x2,y2,score

        out = []
        for o in dets:
            score = float(o[4])
            if score < RTMLIB_NMS_SCORE:
                continue
            x1 = max(int(o[0] / ratio), 0)
            y1 = max(int(o[1] / ratio), 0)
            x2 = min(int(o[2] / ratio), iw)
            y2 = min(int(o[3] / ratio), ih)
            if x2 - x1 < MIN_BOX_SIDE or y2 - y1 < MIN_BOX_SIDE:
                continue
            out.append((x1, y1, x2, y2, score))
        out.sort(key=lambda d: -d[4])
        return out

    def landmarks(self, img, box):
        """[21, 3] x,y,conf in image-pixel space — rtmlib RTMPose postprocess exactly."""
        x1, y1, x2, y2 = [float(v) for v in box[:4]]
        bbox = np.asarray([x1, y1, x2, y2])

        # rtmlib bbox_xyxy2cs(padding=1.25)
        center = np.asarray([(bbox[0] + bbox[2]) / 2.0, (bbox[1] + bbox[3]) / 2.0])
        scale = np.asarray([(bbox[2] - bbox[0]) * RTPLIB_CROP, (bbox[3] - bbox[1]) * RTPLIB_CROP])
        # top_down_affine aspect fix for a square input
        if scale[0] > scale[1]:
            scale[1] = scale[0]
        else:
            scale[0] = scale[1]

        # rtmlib get_warp_matrix(center, scale, rot=0): identical math to
        # LiteRtHandLandmarker.getWarpMatrix and to _get_warp_matrix below.
        sf = RTMPOSE_SIZE / scale[0]
        warp = np.float32([
            [sf, 0, RTMPOSE_SIZE / 2 - sf * center[0]],
            [0, sf, RTMPOSE_SIZE / 2 - sf * center[1]],
        ])
        warped = cv2.warpAffine(
            img, warp, (RTMPOSE_SIZE, RTMPOSE_SIZE),
            flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0,
        )

        outputs = self.pose_session.run(None, {
            "input": _normalize(warped),
            "bboxes_width_height": np.asarray(
                [[int(bbox[2] - bbox[0]), int(bbox[3] - bbox[1])]], dtype=np.int64
            ),
        })
        simcc_x = np.asarray(outputs[0], dtype=np.float64)  # [1, 21, 512]
        simcc_y = np.asarray(outputs[1], dtype=np.float64)  # [1, 21, 512]

        # rtmlib get_simcc_maximum: argmax per axis, conf = mean of axis maxes
        x_locs = np.argmax(simcc_x, axis=2)[0]
        y_locs = np.argmax(simcc_y, axis=2)[0]
        max_x = np.amax(simcc_x, axis=2)[0]
        max_y = np.amax(simcc_y, axis=2)[0]
        conf = (max_x + max_y) / 2.0

        # rtmlib RTMPose.postprocess: kp = locs / split_ratio, then rescale:
        #   kp = kp / model_input_size * scale + center - scale / 2
        SPLIT_RATIO = 2.0
        kp256 = np.stack([x_locs, y_locs], axis=1).astype(np.float64) / SPLIT_RATIO
        kp256 = kp256 / RTMPOSE_SIZE * scale
        kp_img = kp256 + center - scale / 2.0

        points = np.zeros((NUM_LANDMARKS, 3), dtype=np.float64)
        points[:, 0] = kp_img[:, 0]
        points[:, 1] = kp_img[:, 1]
        points[:, 2] = conf
        return points


# ---------------------------------------------------------------------------
# TFLite backend (what the device actually runs)
# ---------------------------------------------------------------------------

class TfliteBackend:
    """Reproduces LiteRtHandLandmarker 1:1 on host via ai-edge-litert.

    gray-114 top-left letterbox -> raw anchor outputs -> caller-side NMS
    (iou=0.5, score=0.05) -> 1.25x rtplib crop at rotation 0 -> SimCC decode
    (split ratio 2.0, conf=(xv+yv)/2).
    """

    def __init__(self, det_path=TFLITE_DETECTOR, pose_path=TFLITE_POSE):
        import ai_edge_litert.interpreter as litert

        self.det_inter = litert.Interpreter(model_path=str(det_path), num_threads=4)
        self.det_inter.allocate_tensors()
        self.pose_inter = litert.Interpreter(model_path=str(pose_path), num_threads=4)
        self.pose_inter.allocate_tensors()
        d_in = self.det_inter.get_input_details()[0]
        self.det_in_idx = d_in["index"]
        self.det_out = [o["index"] for o in self.det_inter.get_output_details()]
        p_in = self.pose_inter.get_input_details()[0]
        self.pose_in_idx = p_in["index"]
        self.pose_out = [o["index"] for o in self.pose_inter.get_output_details()]

    def detect_hands(self, img):
        ih, iw = img.shape[:2]
        ratio = min(DETECTOR_SIZE / iw, DETECTOR_SIZE / ih)
        nw, nh = max(int(iw * ratio), 1), max(int(ih * ratio), 1)
        resized = cv2.resize(img, (nw, nh))
        padded = np.full((DETECTOR_SIZE, DETECTOR_SIZE, 3), RTMLIB_DET_PAD, np.uint8)
        padded[:nh, :nw] = resized  # top-left aligned, like the Kotlin code

        self.det_inter.set_tensor(self.det_in_idx, _normalize(padded))
        self.det_inter.invoke()
        boxes = self.det_inter.get_tensor(self.det_out[0])[0]  # [2100, 4]
        scores = self.det_inter.get_tensor(self.det_out[1])[0].reshape(-1)  # [2100]

        keep = nms(boxes, scores.reshape(-1))
        dets = []
        for i in keep:
            o, s = boxes[i], float(scores[i])
            x1 = max(int(o[0] / ratio), 0)
            y1 = max(int(o[1] / ratio), 0)
            x2 = min(int(o[2] / ratio), iw)
            y2 = min(int(o[3] / ratio), ih)
            if x2 - x1 < MIN_BOX_SIDE or y2 - y1 < MIN_BOX_SIDE:
                continue
            dets.append((x1, y1, x2, y2, s))
        dets.sort(key=lambda d: -d[4])
        return dets

    def landmarks(self, img, box):
        """[21, 3] x,y,conf in image-pixel space (rtplib crop, rotation 0)."""
        x1, y1, x2, y2 = [float(v) for v in box[:4]]
        cx = (x1 + x2) / 2.0
        cy = (y1 + y2) / 2.0
        bw = (x2 - x1) * RTPLIB_CROP
        bh = (y2 - y1) * RTPLIB_CROP
        scale = max(bw, bh)

        warp = _get_warp_matrix(cx, cy, scale, RTMPOSE_SIZE, RTMPOSE_SIZE)
        warped = cv2.warpAffine(img, warp, (RTMPOSE_SIZE, RTMPOSE_SIZE), flags=cv2.INTER_LINEAR,
                                borderMode=cv2.BORDER_CONSTANT, borderValue=0)

        self.pose_inter.set_tensor(self.pose_in_idx, _normalize(warped))
        self.pose_inter.invoke()
        simcc_x = self.pose_inter.get_tensor(self.pose_out[0])[0]  # [21, 512]
        simcc_y = self.pose_inter.get_tensor(self.pose_out[1])[0]  # [21, 512]
        kps = _decode_simcc_mean(simcc_x, simcc_y)

        points = np.zeros((NUM_LANDMARKS, 3), dtype=np.float64)
        for i in range(NUM_LANDMARKS):
            points[i, 0] = kps[i * 3] / RTMPOSE_SIZE * scale + cx - scale / 2.0
            points[i, 1] = kps[i * 3 + 1] / RTMPOSE_SIZE * scale + cy - scale / 2.0
            points[i, 2] = kps[i * 3 + 2]
        return points


def _get_warp_matrix(cx, cy, src_w, dst_w, dst_h):
    """1:1 port of LiteRtHandLandmarker.getWarpMatrix (rtplib bbox_xyxy2cs + top_down_affine)."""
    src_dir = (0.0, src_w * -0.5)
    dst_dir = (0.0, dst_w * -0.5)

    src0 = (cx, cy)
    src1 = (cx + src_dir[0], cy + src_dir[1])
    src2 = (src0[0] + (-src_dir[1]), src0[1] + src_dir[0])

    dst0 = (dst_w / 2.0, dst_h / 2.0)
    dst1 = (dst0[0] + dst_dir[0], dst0[1] + dst_dir[1])
    dst2 = (dst0[0] + (-dst_dir[1]), dst0[1] + dst_dir[0])

    det = (src0[0] - src2[0]) * (src1[1] - src2[1]) - (src1[0] - src2[0]) * (src0[1] - src2[1])
    if det == 0:
        return np.array([1, 0, 0, 0, 1, 0], dtype=np.float32)

    m = np.zeros(6, dtype=np.float64)
    m[0] = ((dst0[0] - dst2[0]) * (src1[1] - src2[1]) - (dst1[0] - dst2[0]) * (
            src0[1] - src2[1])) / det
    m[1] = ((dst1[0] - dst2[0]) * (src0[0] - src2[0]) - (dst0[0] - dst2[0]) * (
            src1[0] - src2[0])) / det
    m[2] = dst0[0] - m[0] * src0[0] - m[1] * src0[1]
    m[3] = ((dst0[1] - dst2[1]) * (src1[1] - src2[1]) - (dst1[1] - dst2[1]) * (
            src0[1] - src2[1])) / det
    m[4] = ((dst1[1] - dst2[1]) * (src0[0] - src2[0]) - (dst0[1] - dst2[1]) * (
            src1[0] - src2[0])) / det
    m[5] = dst0[1] - m[3] * src0[0] - m[4] * src0[1]
    return m.astype(np.float32).reshape(2, 3)


def _decode_simcc_mean(simcc_x, simcc_y):
    """Caller-side SimCC decode: argmax, /split_ratio, conf=(xv+yv)/2."""
    out = np.zeros(NUM_LANDMARKS * 3, dtype=np.float64)
    for i in range(NUM_LANDMARKS):
        xv_idx = int(np.argmax(simcc_x[i]))
        yv_idx = int(np.argmax(simcc_y[i]))
        xv = float(simcc_x[i][xv_idx])
        yv = float(simcc_y[i][yv_idx])
        out[i * 3] = xv_idx / SIMCC_SPLIT_RATIO
        out[i * 3 + 1] = yv_idx / SIMCC_SPLIT_RATIO
        out[i * 3 + 2] = (xv + yv) / 2.0
    return out


# ---------------------------------------------------------------------------
# Pipeline facade
# ---------------------------------------------------------------------------

class Pipeline:
    """One facade over both backends. Mirrors LiteRtHandLandmarker.detectWithRecognizers:
    MIN_DET=0.25 gate, top MAX_HANDS boxes, first-matching-recognizer selection,
    unmatched hands above the kpMean floor retained as gesture-less results."""

    def __init__(self, backend="onnx", models_dir=None):
        if backend == "onnx":
            self.backend = OnnxBackend(models_dir or ONNX_MODELS_DIR)
        elif backend == "tflite":
            self.backend = TfliteBackend(
                *((models_dir / TFLITE_DETECTOR.name, models_dir / TFLITE_POSE.name)
                  if models_dir else (TFLITE_DETECTOR, TFLITE_POSE)))
        else:
            raise ValueError(f"unknown backend: {backend}")
        self.backend_name = backend

    def detect_with_recognizers(self, img, recognizers):
        """Returns list of dicts: gesture, score, kpMean, points, detScore, rotation.

        Mirrors LiteRtHandLandmarker.detectWithRecognizers: tiered MIN_DET gate
        (rank-0 box >= MIN_DET; rank-1 box >= TIER2_MIN_DET with quality gates),
        top MAX_HANDS boxes, first-matching-recognizer selection, unmatched
        hands above the kpMean floor retained as gesture-less results.
        """
        if isinstance(img, (str, Path)):
            img = decode_image(img)
            if img is None:
                return []
        boxes = self.backend.detect_hands(img)
        results = []
        for rank, box in enumerate(boxes[:MAX_HANDS]):
            det = float(box[4])
            is_tier2 = rank > 0 and det < MIN_DET
            if is_tier2:
                if det < TIER2_MIN_DET:
                    continue
            elif det < MIN_DET:
                continue
            points = self.backend.landmarks(img, box)
            kp_mean = float(np.mean(points[:, 2]))
            features = HandFeatures(points)
            if features.hand_size < MIN_HAND_SIZE:
                continue
            recognized = None
            for r in recognizers:
                recognized = r.recognize(features)
                if recognized is not None:
                    break
            if recognized is not None and is_tier2:
                if kp_mean < TIER2_KP_FLOOR or not _is_plausible_hand(features, box):
                    recognized = None
            if recognized is not None:
                gesture, score, confidence = recognized.gesture, recognized.score, recognized.confidence
            elif kp_mean >= MIN_KP_CONFIDENCE:
                gesture, score, confidence = None, 0, 0.0
            else:
                continue
            results.append({
                "gesture": gesture,
                "score": score,
                "confidence": confidence,
                "kpMean": round(kp_mean, 4),
                "detScore": round(det, 4),
                "rotation": 0,
                "box": [int(box[0]), int(box[1]), int(box[2]), int(box[3])],
                "points": [(float(p[0]), float(p[1]), float(p[2])) for p in points],
            })
        return results


# Recognizers import lives at the bottom to avoid a circular import
# (gesture_classify imports nothing from rtm_pipeline).
from gesture_classify import HandFeatures  # noqa: E402


def _is_plausible_hand(features, box):
    """Geometric validity gate for tier-2 admission: rejects degenerate landmark
    clouds (tiny hand inside a huge box, physically impossible thumb)."""
    box_side = max(box[2] - box[0], box[3] - box[1])
    if box_side <= 0:
        return False
    if features.hand_size / box_side < MIN_HAND_TO_BOX_RATIO:
        return False
    if features.thumb.length_ratio > MAX_THUMB_LENGTH_RATIO:
        return False
    return True


def _simcc_maximum(simcc_x, simcc_y):
    """rtmlib get_simcc_maximum (val = max(xv, yv) per kp), local copy so the
    oracle does not depend on rtmlib internals at call time."""
    import sys

    n, k, _ = simcc_x.shape
    flat_x = simcc_x.reshape(n * k, -1)
    flat_y = simcc_y.reshape(n * k, -1)
    x_locs = np.argmax(flat_x, axis=1)
    y_locs = np.argmax(flat_y, axis=1)
    x_vals = flat_x[np.arange(len(x_locs)), x_locs]
    y_vals = flat_y[np.arange(len(y_locs)), y_locs]
    locs = np.stack((x_locs, y_locs), axis=-1).astype(np.float64).reshape(n, k, 2)
    vals = np.minimum(x_vals, y_vals).reshape(n, k)
    return locs, vals


def detect_with_recognizers(path_or_img, recognizers, backend="onnx"):
    """Convenience one-shot: decode + detect + classify."""
    pipe = Pipeline(backend)
    return pipe.detect_with_recognizers(path_or_img, recognizers)
