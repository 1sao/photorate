#!/usr/bin/env python3
"""INT8 (QDQ) quantization following the OFFICIAL ORT mobile workflow.

Sources:
  - https://onnxruntime.ai/docs/tutorials/mobile/helpers/  (ORT mobile helpers)
  - https://github.com/microsoft/onnxruntime-inference-examples/blob/main/
    quantization/notebooks/imagenet_v2/mobilenet.ipynb  (plain quantize_static)

Pipeline per the docs:
  1. update_onnx_opset --opset 13   (official tool; QDQ needs opset 13+)
  2. quantize_static (QDQ default, simple CalibrationDataReader like the
     mobilenet notebook — no per-channel, no node exclusions)
  3. optimize_qdq_model              (official QDQ post-processor)
  4. IoU-based parity vs the f32clean models on the 31-sample set.

Usage:
  onnx/.venv/bin/python plans/model_zoo_pinto/quant_int8_documented.py
"""
import cv2
import math
import numpy as np
import onnx
import onnxruntime as ort
import subprocess
import sys
from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize_static
from pathlib import Path

SAMPLES = Path(__file__).resolve().parent.parent / "samples"
PZ = Path(__file__).resolve().parent
ML = Path(__file__).resolve().parent.parent.parent / "ml" / "original_models"
VENV_PY = Path(__file__).resolve().parent.parent.parent / "onnx" / ".venv" / "bin" / "python"

CLEAN_RTMDET = ML / "rtmdet_nano_8xb32-300e_hand-267f9c8f" / "end2end_f32clean.onnx"
CLEAN_RTMPOSE = ML / "rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320" / "end2end_f32clean.onnx"
TMP_DIR = PZ / "int8_tmp"
INT8_RTMDET = TMP_DIR / "rtmdet_320_int8.onnx"
INT8_RTMPOSE = TMP_DIR / "rtmpose_hand_256_int8.onnx"

RTMPOSE_SIZE = 256
SIMCC_SIZE = 512
SIMCC_SPLIT_RATIO = 2.0
BOX_EXPANSION = 1.2
FALLBACK_FACTORS = (1.2, 0.85)
MAX_HANDS = 2
DECODE_MIN_DIM = 640
MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)
NMS_MAX_OUT = 200
NMS_IOU_THR = 0.6
NMS_SCORE_THR = 0.05

ALL_SAMPLES = sorted(
    [f for d in ["5", "4", "3", "2", "1", "no_score"] for f in (SAMPLES / d).glob("*.jpg")]
)


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


def nms(boxes, scores, iou_thr=NMS_IOU_THR, score_thr=NMS_SCORE_THR, max_out=NMS_MAX_OUT):
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


def top_down_affine(crop):
    h, w = crop.shape[:2]
    bbox_w = 1.25 * w
    bbox_h = 1.25 * h
    w_scaled = max(bbox_h * 0.75, bbox_w)
    scale = RTMPOSE_SIZE / w_scaled
    cx, cy = w / 2.0, h / 2.0
    m = cv2.getRotationMatrix2D((cx, cy), 0, scale)
    m[0, 2] += RTMPOSE_SIZE / 2.0 - cx
    m[1, 2] += RTMPOSE_SIZE / 2.0 - cy
    return cv2.warpAffine(
        crop, m, (RTMPOSE_SIZE, RTMPOSE_SIZE), flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0),
    )


def pose_input(crop):
    warped = top_down_affine(crop)
    f = (warped.astype(np.float32) - MEAN_RGB) / STD_RGB
    return f.transpose(2, 0, 1)[None].astype(np.float32)


def expand_square(box, factor):
    x1, y1, x2, y2 = box
    cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
    side = max((x2 - x1) * factor, (y2 - y1) * factor)
    return (int(cx - side / 2), int(cy - side / 2), int(cx + side / 2), int(cy + side / 2))


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
    rotated = cv2.warpAffine(crop, rot, (bound_w, bound_h), flags=cv2.INTER_LINEAR)
    cxc, cyc = rotated.shape[1] // 2, rotated.shape[0] // 2
    x0c, y0c = cxc - int(side) // 2, cyc - int(side) // 2
    if x0c < 0 or y0c < 0 or x0c + int(side) > rotated.shape[1] or y0c + int(side) > rotated.shape[
        0]:
        return None
    return rotated[y0c:y0c + int(side), x0c:x0c + int(side)]


class DetCalibReader(CalibrationDataReader):
    """Feeds real detector inputs (letterboxed 320x320) — like the notebook."""

    def __init__(self):
        self._it = iter(self._gen())

    def _gen(self):
        for f in ALL_SAMPLES:
            img = decode_640(f)
            f32, _, _, _ = det_input(img)
            yield {"input": f32}

    def get_next(self):
        return next(self._it, None)


class PoseCalibReader(CalibrationDataReader):
    """Feeds real RTMPose inputs (expanded-square crops) from detector boxes."""

    def __init__(self):
        self._it = iter(self._gen())

    def _gen(self):
        sess = ort.InferenceSession(str(CLEAN_RTMDET), providers=["CPUExecutionProvider"])
        for f in ALL_SAMPLES:
            img = decode_640(f)
            f32, ratio, iw, ih = det_input(img)
            br, sr = sess.run(None, {"input": f32})
            keep = nms(br[0], sr[0, :, 0])
            for i in keep[:MAX_HANDS]:
                o = br[0][i]
                x1, y1 = max(int(o[0] / ratio), 0), max(int(o[1] / ratio), 0)
                x2, y2 = min(int(o[2] / ratio), iw), min(int(o[3] / ratio), ih)
                if x2 - x1 < 8 or y2 - y1 < 8:
                    continue
                box = (x1, y1, x2, y2)
                for factor in FALLBACK_FACTORS:
                    square = expand_square(box, factor)
                    cx, cy = (square[0] + square[2]) / 2.0, (square[1] + square[3]) / 2.0
                    side = square[2] - square[0]
                    for deg in (0, 90, 180, 270):
                        crop = rotate_and_crop_square(img, cx, cy, side, deg)
                        if crop is None or crop.shape[0] < 8:
                            continue
                        yield {"input": pose_input(crop)}

    def get_next(self):
        return next(self._it, None)


def update_opset(src, dst, opset=13):
    subprocess.run(
        [str(VENV_PY), "-m", "onnxruntime.tools.update_onnx_opset", "--opset", str(opset),
         str(src), str(dst)],
        check=True, capture_output=True,
    )


def optimize_qdq(src, dst):
    subprocess.run(
        [str(VENV_PY), "-m", "onnxruntime.tools.qdq_helpers.optimize_qdq_model",
         str(src), str(dst)],
        check=True, capture_output=True,
    )


def quantize_documented(model_path, calib_reader, out_path):
    print(f"  update_onnx_opset ->13 ...")
    opset13 = out_path.with_name(out_path.stem + "_opset13.onnx")
    update_opset(model_path, opset13)
    print(f"  quantize_static (QDQ, uint8 activations for NNAPI) ...")
    quantize_static(model_input=str(opset13), model_output=str(out_path),
                    calibration_data_reader=calib_reader,
                    activation_type=QuantType.QUInt8,
                    weight_type=QuantType.QInt8,
                    extra_options={"ActivationSymmetric": False})
    print(f"  optimize_qdq_model ...")
    opt = out_path.with_name(out_path.stem + "_opt.onnx")
    optimize_qdq(out_path, opt)
    opt.replace(out_path)
    m = onnx.load(str(out_path))
    sizes = [p.stat().st_size / 1e6 for p in (model_path, out_path)]
    print(
        f"  size {sizes[0]:.1f}MB -> {sizes[1]:.1f}MB, opset={[(o.domain, o.version) for o in m.opset_import]}")
    for o in m.graph.output:
        print(f"  output {o.name} elem_type={o.type.tensor_type.elem_type} (1=float32)")
    opset13.unlink(missing_ok=True)


def box_iou(a, b):
    xx1, yy1 = max(a[0], b[0]), max(a[1], b[1])
    xx2, yy2 = min(a[2], b[2]), min(a[3], b[3])
    w, h = max(0, xx2 - xx1), max(0, yy2 - yy1)
    inter = w * h
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def parity():
    print("\n=== PARITY: f32clean vs int8 (CPU EP) ===")
    s_f_det = ort.InferenceSession(str(CLEAN_RTMDET), providers=["CPUExecutionProvider"])
    s_i_det = ort.InferenceSession(str(INT8_RTMDET), providers=["CPUExecutionProvider"])
    s_f_pose = ort.InferenceSession(str(CLEAN_RTMPOSE), providers=["CPUExecutionProvider"])
    s_i_pose = ort.InferenceSession(str(INT8_RTMPOSE), providers=["CPUExecutionProvider"])

    top_box_iou = []
    score_ratios = []
    det_gap_fp = 0
    det_gap_int = 0
    missed = []  # fp32 detects a hand (>=0.25) that int8 loses
    kp_max = 0.0
    kp_n = 0

    for f in ALL_SAMPLES:
        img = decode_640(f)
        f32, ratio, iw, ih = det_input(img)
        boxes = {}
        for tag, s in (("fp32", s_f_det), ("int8", s_i_det)):
            br, sr = s.run(None, {"input": f32})
            keep = nms(br[0], sr[0, :, 0])
            out = []
            for i in keep:
                o = br[0][i]
                x1, y1 = max(int(o[0] / ratio), 0), max(int(o[1] / ratio), 0)
                x2, y2 = min(int(o[2] / ratio), iw), min(int(o[3] / ratio), ih)
                if x2 - x1 >= 8 and y2 - y1 >= 8:
                    out.append((x1, y1, x2, y2, float(sr[0, i, 0])))
            out.sort(key=lambda d: -d[4])
            boxes[tag] = out
        if boxes["fp32"]:
            det_gap_fp += 0
        if boxes["int8"]:
            det_gap_int += 0
        if boxes["fp32"] and boxes["fp32"][0][4] >= 0.25 and (
                not boxes["int8"] or boxes["int8"][0][4] < 0.25):
            missed.append(f.name)
        if boxes["fp32"] and boxes["int8"]:
            bf = boxes["fp32"][0]
            best = max(boxes["int8"], key=lambda b: box_iou(bf, b))
            top_box_iou.append(box_iou(bf, best))
            score_ratios.append(boxes["int8"][0][4] / boxes["fp32"][0][4])
            # keypoint parity on the matched box (deg-0 crop)
            for factor in FALLBACK_FACTORS:
                square = expand_square((bf[0], bf[1], bf[2], bf[3]), factor)
                cx, cy = (square[0] + square[2]) / 2.0, (square[1] + square[3]) / 2.0
                side = square[2] - square[0]
                crop = rotate_and_crop_square(img, cx, cy, side, 0)
                if crop is None:
                    continue
                sx_f, sy_f = s_f_pose.run(None, {"input": pose_input(crop)})
                sx_i, sy_i = s_i_pose.run(None, {"input": pose_input(crop)})
                for i in range(21):
                    xf, yf = int(np.argmax(sx_f[0, i])), int(np.argmax(sy_f[0, i]))
                    xi, yi = int(np.argmax(sx_i[0, i])), int(np.argmax(sy_i[0, i]))
                    d = math.hypot((xf - xi) / SIMCC_SPLIT_RATIO, (yf - yi) / SIMCC_SPLIT_RATIO)
                    kp_max = max(kp_max, d)
                kp_n += 21
    if top_box_iou:
        print(
            f"top-box IoU (int8 vs fp32): mean={np.mean(top_box_iou):.3f} min={min(top_box_iou):.3f}")
        print(f"top-score ratio int8/fp32: mean={np.mean(score_ratios):.3f}")
    print(f"detections int8 lost vs fp32 (fp32>=0.25, int8<0.25): {len(missed)} {missed[:6]}")
    print(f"keypoint argmax shift: max={kp_max:.2f}px (256-space) over {kp_n} keypoints")


def main():
    TMP_DIR.mkdir(exist_ok=True)
    print("Quantizing RTMDet (documented workflow)...")
    quantize_documented(CLEAN_RTMDET, DetCalibReader(), INT8_RTMDET)
    print("Quantizing RTMPose (documented workflow)...")
    quantize_documented(CLEAN_RTMPOSE, PoseCalibReader(), INT8_RTMPOSE)
    parity()


if __name__ == "__main__":
    main()
