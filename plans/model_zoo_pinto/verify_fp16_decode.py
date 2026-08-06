import cv2
import numpy as np
import onnxruntime as ort

F32 = "plans/model_zoo_pinto/rtmpose_hand_256_f32clean.onnx"
F16 = "plans/model_zoo_pinto/rtmpose_hand_256_f32clean_fp16.onnx"
MEAN_RGB = np.array([123.675, 116.28, 103.53], dtype=np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], dtype=np.float32)
RTMPOSE_SIZE = 256

img = cv2.imread("plans/samples/5/5_kenya.jpg")
ih, iw = img.shape[:2]
s = max(min(ih, iw) // 640, 1)
if s > 1:
    img = cv2.resize(img, (iw // s, ih // s), interpolation=cv2.INTER_AREA)

# Simulate the app's RTMPose input: a hand-sized crop from the sample.
crop = img[100:400, 200:500]
h, w = crop.shape[:2]
bbox_w, bbox_h = 1.25 * w, 1.25 * h
w_scaled = max(bbox_h * 0.75, bbox_w)
scale = RTMPOSE_SIZE / w_scaled
m = np.float32([[scale, 0, RTMPOSE_SIZE / 2 - scale * w / 2],
                [0, scale, RTMPOSE_SIZE / 2 - scale * h / 2]])
warped = cv2.warpAffine(crop, m, (RTMPOSE_SIZE, RTMPOSE_SIZE), borderValue=0)
f = ((warped.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)[None].astype(np.float32)

sess32 = ort.InferenceSession(F32)
sess16 = ort.InferenceSession(F16)
sx32, sy32 = sess32.run(None, {"input": f})
sx16, sy16 = sess16.run(None, {"input": f})


# Decode (mirror the app).
def decode(sx, sy):
    scale2 = RTMPOSE_SIZE / max(1.25 * h * 0.75, 1.25 * w)
    cx, cy = w / 2.0, h / 2.0
    pts = []
    for i in range(21):
        xi = int(np.argmax(sx[0, i]))
        yi = int(np.argmax(sy[0, i]))
        pts.append(((xi / 2.0 - 128) / scale2 + cx, (yi / 2.0 - 128) / scale2 + cy))
    return np.array(pts)


pts32 = decode(sx32, sy32)
pts16 = decode(sx16, sy16)
diff = np.abs(pts32 - pts16)
print("fp32 vs fp16 keypoint max abs diff (px):", float(diff.max()))
print("fp32 vs fp16 heatmap max abs diff:", float(np.abs(sx32 - sx16).max()),
      float(np.abs(sy32 - sy16).max()))
print("argmax bin flips:", int(np.sum(np.argmax(sx32[0], 1) != np.argmax(sx16[0], 1)) + np.sum(
    np.argmax(sy32[0], 1) != np.argmax(sy16[0], 1))))
