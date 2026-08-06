#!/usr/bin/env python3
"""INT8 (QDQ) quantization of the f32clean hand models + parity check.

The Tensor G4's darwinn TPU rejects float32 operands (darwinn_mlir_converter
RET_CHECK: input.type != TENSOR_FLOAT32 fires when input.type IS float32), so
NNAPI always falls back to the slow nnapi-reference CPU backend. INT8
quantization makes the graphs NNAPI-eligible: ORT's NNAPI EP natively runs
QDQ-quantized models (int8 internal, float32 I/O kept via keep_io_types).

Calibration data is the REAL input distribution from plans/samples:
  - RTMDet: the letterboxed 320x320 detector inputs of all samples.
  - RTMPose: the actual expanded-square crops the pipeline feeds it
    (detector boxes -> 1.2x/0.85x square crops at 0/90/180/270 rotations).

Usage:
  onnx/.venv/bin/python plans/model_zoo_pinto/quant_int8.py
"""
import cv2
import math
import numpy as np
import onnx
import onnxruntime as ort
import shutil
import sys
from onnxruntime.quantization import QuantType, quantize_static
from onnxruntime.quantization.calibrate import CalibrationDataReader, CalibrationMethod
from pathlib import Path

SAMPLES = Path(__file__).resolve().parent.parent / "samples"
PZ = Path(__file__).resolve().parent
ML = Path(__file__).resolve().parent.parent.parent / "ml" / "original_models"

CLEAN_RTMDET = ML / "rtmdet_nano_8xb32-300e_hand-267f9c8f" / "end2end_f32clean.onnx"
CLEAN_RTMPOSE = ML / "rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320" / "end2end_f32clean.onnx"
INT8_RTMDET = PZ / "rtmdet_320_int8.onnx"
INT8_RTMPOSE = PZ / "rtmpose_hand_256_int8.onnx"

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
NMS_MAX_OUT = 200
NMS_IOU_THR = 0.6
NMS_SCORE_THR = 0.05


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
    """Port of the app's topDownAffine: scale about center, black fill."""
    h, w = crop.shape[:2]
    bbox_w = 1.25 * w
    bbox_h = 1.25 * h
    w_scaled = max(bbox_h * 0.75, bbox_w)
    scale = RTMPOSE_SIZE / w_scaled
    cx, cy = w / 2.0, h / 2.0
    m = cv2.getRotationMatrix2D((cx, cy), 0, scale)
    m[0, 2] += RTMPOSE_SIZE / 2.0 - cx
    m[1, 2] += RTMPOSE_SIZE / 2.0 - cy
    out = cv2.warpAffine(crop, m, (RTMPOSE_SIZE, RTMPOSE_SIZE), flags=cv2.INTER_LINEAR,
                         borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    return out


def pose_input(crop):
    # RTMPose consumes BGR-order pixels normalized with the mmdet RGB stats
    # (verified Python, 0.000 keypoint diff) — crop is already BGR from cv2.
    warped = top_down_affine(crop)
    f = (warped.astype(np.float32) - MEAN_RGB) / STD_RGB
    f = f.transpose(2, 0, 1)[None].astype(np.float32)
    return f


def collect_det_inputs():
    inputs = []
    for score_dir in ["5", "4", "3", "2", "1", "no_score"]:
        for f in sorted((SAMPLES / score_dir).glob("*.jpg")):
            img = decode_640(f)
            f32, _, _, _ = det_input(img)
            inputs.append(f32)
    return inputs


def collect_pose_inputs():
    """Real RTMPose inputs: detector boxes -> expanded square crops @ all rotations."""
    sess_det = ort.InferenceSession(str(CLEAN_RTMDET), providers=["CPUExecutionProvider"])
    inputs = []
    for score_dir in ["5", "4", "3", "2", "1", "no_score"]:
        for f in sorted((SAMPLES / score_dir).glob("*.jpg")):
            img = decode_640(f)
            f32, ratio, iw, ih = det_input(img)
            boxes_raw, scores_raw = sess_det.run(None, {"input": f32})
            boxes_raw = boxes_raw[0]
            scores_raw = scores_raw[0, :, 0]
            keep = nms(boxes_raw, scores_raw)
            for i in keep[:MAX_HANDS]:
                o = boxes_raw[i]
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
                        if crop is None or crop.shape[0] < 8 or crop.shape[1] < 8:
                            continue
                        inputs.append(pose_input(crop))
    return inputs


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


class ArrayDataReader(CalibrationDataReader):
    def __init__(self, inputs, input_name):
        self._it = iter(inputs)
        self._name = input_name

    def get_next(self):
        try:
            return {self._name: next(self._it)}
        except StopIteration:
            return None


def to_opset13(model_path, tmp_path):
    """QDQ quantize emits axis-annotated DQ/Q ops that need opset >= 13."""
    from onnx import version_converter
    m = onnx.load(str(model_path))
    bumped = version_converter.convert_version(m, 13)
    onnx.save(bumped, str(tmp_path))
    return tmp_path


# Quantization-sensitive HEAD nodes to keep in fp32 (detector cls/reg heads
# and the SimCC heatmap head produce the exact scores/argmax bins the app's
# post-processing depends on; INT8 collapses them — measured 0.774->0.283
# scores and ~160px SimCC argmax shifts with full-graph quantization).
RTMDET_HEAD_CONVS = [
    "Conv_316", "Conv_319", "Conv_329", "Conv_332",  # scale 0 cls + reg
    "Conv_344", "Conv_347", "Conv_357", "Conv_360",  # scale 1 cls + reg
    "Conv_372", "Conv_375", "Conv_385", "Conv_388",  # scale 2 cls + reg
]
RTMPOSE_HEAD_CONVS = ["Conv_205"]


def quantize(model_path, out_path, calib_inputs, input_name, head_convs):
    if out_path.exists():
        out_path.unlink()
    tmp = Path(str(out_path) + ".opset13.onnx")
    print(f"  bumping opset to 13...")
    to_opset13(model_path, tmp)
    reader = ArrayDataReader(calib_inputs, input_name)
    quantize_static(
        model_input=str(tmp),
        model_output=str(out_path),
        calibration_data_reader=reader,
        quant_format=onnxruntime_quant_format(),
        per_channel=True,
        activation_type=QuantType.QInt8,
        weight_type=QuantType.QInt8,
        calibrate_method=CalibrationMethod.Entropy,
        nodes_to_exclude=head_convs,
    )
    # QDQ keeps the external I/O float32 by default; verify that.
    m = onnx.load(str(out_path))
    print(f"  quantized opset={[(o.domain, o.version) for o in m.opset_import]}")
    print(
        f"  size {model_path.stat().st_size / 1e6:.1f}MB -> {out_path.stat().st_size / 1e6:.1f}MB")
    for o in m.graph.output:
        t = o.type.tensor_type
        print(f"  output {o.name} elem_type={t.elem_type} (1=float32, 3=int8, 2=uint8)")
    tmp.unlink(missing_ok=True)


def onnxruntime_quant_format():
    from onnxruntime.quantization import QuantFormat
    return QuantFormat.QDQ


def parity():
    """fp32 vs int8: boxes + keypoints on all samples (app post-processing)."""
    print("\n=== PARITY (fp32 f32clean vs int8, CPU) ===")
    s_f_det = ort.InferenceSession(str(CLEAN_RTMDET), providers=["CPUExecutionProvider"])
    s_f_pose = ort.InferenceSession(str(CLEAN_RTMPOSE), providers=["CPUExecutionProvider"])
    s_i_det = ort.InferenceSession(str(INT8_RTMDET), providers=["CPUExecutionProvider"])
    s_i_pose = ort.InferenceSession(str(INT8_RTMPOSE), providers=["CPUExecutionProvider"])
    max_box = 0.0
    max_kp = 0.0
    box_flips = 0
    n_box = 0
    n_kp = 0
    for score_dir in ["5", "4", "3", "2", "1", "no_score"]:
        for f in sorted((SAMPLES / score_dir).glob("*.jpg")):
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
            n_box += max(len(boxes["fp32"]), len(boxes["int8"]))
            for b in boxes["fp32"]:
                best = min(boxes["int8"], key=lambda o: abs(o[4] - b[4])) if boxes["int8"] else None
                if best is None:
                    box_flips += 1
                    continue
                d = max(abs(b[k] - best[k]) for k in range(4))
                max_box = max(max_box, d)
                if d > 2:
                    box_flips += 1
            # keypoint parity on matched box
            if boxes["fp32"] and boxes["int8"]:
                b = boxes["fp32"][0]
                best = min(boxes["int8"], key=lambda o: abs(o[4] - b[4]))
                for factor in FALLBACK_FACTORS:
                    square = expand_square((b[0], b[1], b[2], b[3]), factor)
                    cx, cy = (square[0] + square[2]) / 2.0, (square[1] + square[3]) / 2.0
                    side = square[2] - square[0]
                    for deg in (0,):
                        crop = rotate_and_crop_square(img, cx, cy, side, deg)
                        if crop is None:
                            continue
                        for tag, s in (("fp32", s_f_pose), ("int8", s_i_pose)):
                            sx, sy = s.run(None, {"input": pose_input(crop)})
                            kp = []
                            for i in range(21):
                                xi = int(np.argmax(sx[0, i]))
                                yi = int(np.argmax(sy[0, i]))
                                kp.append((xi / SIMCC_SPLIT_RATIO, yi / SIMCC_SPLIT_RATIO))
                            boxes.setdefault("kp_" + tag, kp)
                        d = max(math.hypot(a[0] - c[0], a[1] - c[1]) for a, c in
                                zip(boxes["kp_fp32"], boxes["kp_int8"]))
                        max_kp = max(max_kp, d)
                        n_kp += 1
    print(f"boxes: max |dx| = {max_box:.1f}px over {n_box} box-comps, flips={box_flips}")
    print(f"keypoints: max dist = {max_kp:.2f}px over {n_kp} crops (256-space)")


def main():
    print("Collecting calibration data (real sample distribution)...")
    det_in = collect_det_inputs()
    print(f"  RTMDet calibration inputs: {len(det_in)}")
    pose_in = collect_pose_inputs()
    print(f"  RTMPose calibration inputs: {len(pose_in)}")
    if not det_in or not pose_in:
        print("ERROR: no calibration data collected")
        sys.exit(1)
    print("Quantizing RTMDet...")
    quantize(CLEAN_RTMDET, INT8_RTMDET, det_in, "input", RTMDET_HEAD_CONVS)
    print("Quantizing RTMPose...")
    quantize(CLEAN_RTMPOSE, INT8_RTMPOSE, pose_in, "input", RTMPOSE_HEAD_CONVS)
    parity()


if __name__ == "__main__":
    main()
