#!/usr/bin/env python3
"""Benchmark hand-detection models on the plans/samples dataset.

Mirrors the app decode (min-dim 640) and runs each detector, reporting the
boxes + scores found per sample.

Detectors:
  - gold_yolo: RGB letterbox to 640x480, [N,7] output (NMS baked at 0.15).
  - rtmdet-n-hand: ezonnx pipeline (RGB, longest-side-320 top-left pad,
    mean/std in RGB order, score>=0.3 + NMS iou 0.45) -> dets [1,N,5] + labels.

Requires: pip install onnxruntime opencv-python-headless numpy
Usage: python3 benchmark_detectors.py [path-to-rtmdet-n-hand.onnx]
"""
import sys
import numpy as np
import cv2
import onnxruntime as ort
from pathlib import Path

SAMPLES = Path(__file__).resolve().parent.parent / "samples"
GOLD = Path(__file__).resolve().parent.parent.parent / "onnx" / "models" / "gold_yolo_hand.onnx"
RTMDET = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/tmp/rtmdet-n-hand.onnx")
DECODE_MIN_DIM = 640
MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)


def decode_640(path):
    img = cv2.imread(str(path))
    ih, iw = img.shape[:2]
    sample = max(min(ih, iw) // DECODE_MIN_DIM, 1)
    if sample > 1:
        img = cv2.resize(img, (iw // sample, ih // sample), interpolation=cv2.INTER_AREA)
    return img


def gold_letterbox(img, tw=640, th=480):
    ih, iw = img.shape[:2]
    scale = min(tw / iw, th / ih)
    nw, nh = max(int(iw * scale), 1), max(int(ih * scale), 1)
    resized = cv2.resize(img, (nw, nh))
    canvas = np.zeros((th, tw, 3), np.uint8)
    x0, y0 = (tw - nw) // 2, (th - nh) // 2
    canvas[y0:y0 + nh, x0:x0 + nw] = resized
    return canvas, scale, x0, y0


def nms(boxes, scores, iou_thr=0.45):
    x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
    areas = (x2 - x1) * (y2 - y1)
    order = scores.argsort()[::-1]
    keep = []
    while order.size > 0:
        i = order[0]
        keep.append(i)
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        inter = np.maximum(0.0, xx2 - xx1) * np.maximum(0.0, yy2 - yy1)
        union = areas[i] + areas[order[1:]] - inter
        iou = np.where(union > 0, inter / np.maximum(union, 1e-9), 0)
        order = order[1:][iou <= iou_thr]
    return keep


def run_gold(img, sess):
    iw, ih = img.shape[1], img.shape[0]
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    lb, scale, x0, y0 = gold_letterbox(rgb)
    x = np.asarray([lb.transpose(2, 0, 1) / 255.0], dtype=np.float32)
    out = sess.run(None, {"input": x})[0]
    dets = []
    for o in out:
        score = float(o[6])
        if score <= 0:
            continue
        cx = (o[2] + o[4]) / 2
        cy = (o[3] + o[5]) / 2
        w = abs(o[2] - o[4]) * 1.2
        h = abs(o[3] - o[5]) * 1.2
        bx0 = max(int((cx - w / 2 - x0) / scale), 0)
        by0 = max(int((cy - h / 2 - y0) / scale), 0)
        bx1 = min(int((cx + w / 2 - x0) / scale), iw)
        by1 = min(int((cy + h / 2 - y0) / scale), ih)
        if bx1 - bx0 >= 8 and by1 - by0 >= 8:
            dets.append((bx0, by0, bx1, by1, score))
    dets.sort(key=lambda d: -d[4])
    return dets


def run_rtmdet(img, sess):
    iw, ih = img.shape[1], img.shape[0]
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    size = 320
    ratio = min(size / iw, size / ih)
    nw, nh = int(iw * ratio), int(ih * ratio)
    resized = cv2.resize(rgb, (nw, nh))
    padded = np.zeros((size, size, 3), np.uint8)
    padded[:nh, :nw] = resized  # top-left aligned, like ezonnx
    f = ((padded.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(np.float32)
    dets_out, labels_out = sess.run(None, {"input": f})
    boxes, scores, labels = dets_out[0][:, :4], dets_out[0][:, 4], labels_out[0]
    m = scores >= 0.3
    boxes, scores, labels = boxes[m], scores[m], labels[m]
    keep = nms(boxes, scores)
    boxes, scores, labels = boxes[keep], scores[keep], labels[keep]
    dets = []
    for o, s, l in zip(boxes, scores, labels):
        x1 = max(int(o[0] / ratio), 0)
        y1 = max(int(o[1] / ratio), 0)
        x2 = min(int(o[2] / ratio), iw)
        y2 = min(int(o[3] / ratio), ih)
        if x2 > x1 and y2 > y1:
            dets.append((x1, y1, x2, y2, float(s), int(l)))
    dets.sort(key=lambda d: -d[4])
    return dets


sess_gold = ort.InferenceSession(str(GOLD), providers=["CPUExecutionProvider"])
sess_rtm = ort.InferenceSession(str(RTMDET), providers=["CPUExecutionProvider"])

print(f"{'sample':<38} {'gold':<45} {'rtmdet':<45}")
for f in sorted(SAMPLES.rglob("*.jpg")):
    rel = str(f.relative_to(SAMPLES))
    img = decode_640(f)
    g = run_gold(img, sess_gold)
    r = run_rtmdet(img, sess_rtm)
    gs = ";".join(f"{s:.2f}@({a},{b},{c},{d})" for a, b, c, d, s in g[:2]) or "-"
    rs = ";".join(f"{s:.2f}@({a},{b},{c},{d})" for a, b, c, d, s, _ in r[:2]) or "-"
    print(f"{rel:<38} [{len(g)}] {gs:<45} [{len(r)}] {rs}")
