import cv2
import numpy as np
import onnxruntime as ort

img = cv2.imread("plans/samples/5/5_kenya.jpg")
ih, iw = img.shape[:2]
s = max(min(ih, iw) // 640, 1)
if s > 1:
    img = cv2.resize(img, (iw // s, ih // s), interpolation=cv2.INTER_AREA)

MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)

rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
size = 320
ratio = min(size / img.shape[1], size / img.shape[0])
nw, nh = max(int(img.shape[1] * ratio), 1), max(int(img.shape[0] * ratio), 1)
resized = cv2.resize(rgb, (nw, nh))
padded = np.zeros((size, size, 3), np.uint8)
padded[:nh, :nw] = resized
f = ((padded.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(np.float32)

baked = ort.InferenceSession("ml/original_models/rtmdet_nano_8xb32-300e_hand-267f9c8f/end2end.onnx")
fp32 = ort.InferenceSession("onnx/models_fp32/rtmdet_n_hand_fp32.onnx")

dets, labels = baked.run(None, {"input": f})
print("baked dets (first 5):")
for o in dets[0][:5]:
    print(f"  {o}")

boxes, scores = fp32.run(None, {"input": f})
s = scores[0, 0]  # [2100]
print("\nfp32 raw scores: min", s.min(), "max", s.max(), "mean", s.mean())
top = np.argsort(-s)[:5]
print("fp32 top-5 scores:", s[top])
print("fp32 top-5 boxes:")
for i in top:
    print(f"  {boxes[0, i]} score={s[i]:.4f}")
