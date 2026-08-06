import numpy as np
import onnxruntime as ort
import sys

SORTED = sys.argv[1]  # original: TopK-sorted outputs
RAW = sys.argv[2]  # cut: raw unsorted outputs

rng = np.random.default_rng(0)
x = rng.standard_normal((1, 3, 320, 320)).astype(np.float32)

a = ort.InferenceSession(SORTED)
b = ort.InferenceSession(RAW)

oa = a.run(None, {"input": x})
ob = b.run(None, {"input": x})
scores_s, boxes_s = oa[1].reshape(-1), oa[0].reshape(-1, 4)
scores_r, boxes_r = ob[1].reshape(-1), ob[0].reshape(-1, 4)

# Raw outputs are the same data, just in anchor order (TopK was a full sort).
# Verify: sorting the raw outputs by descending score reproduces the sorted
# outputs exactly.
order = np.argsort(-scores_r, kind="stable")
boxes_resorted = boxes_r[order]
scores_resorted = scores_r[order]

print("sorted model: boxes shape", oa[0].shape, "scores shape", oa[1].shape)
print("raw model:    boxes shape", ob[0].shape, "scores shape", ob[1].shape)
print("max box diff after resort:", float(np.abs(boxes_resorted - boxes_s).max()))
print("max score diff after resort:", float(np.abs(scores_resorted - scores_s).max()))
print("score equality:", bool(np.array_equal(scores_resorted, scores_s)))
