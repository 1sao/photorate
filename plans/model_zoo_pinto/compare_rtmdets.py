import numpy as np
import onnxruntime as ort

ORIG = "ml/original_models/rtmdet_nano_8xb32-300e_hand-267f9c8f/end2end.onnx"
FP32 = "onnx/models_fp32/rtmdet_n_hand_fp32.onnx"

rng = np.random.default_rng(0)
x = rng.standard_normal((1, 3, 320, 320)).astype(np.float32)

a = ort.InferenceSession(ORIG)
b = ort.InferenceSession(FP32)

print("=== original end2end outputs ===")
for o in a.get_outputs():
    print(f"  {o.name} {o.shape} {o.type}")
print("=== fp32 re-export outputs ===")
for o in b.get_outputs():
    print(f"  {o.name} {o.shape} {o.type}")

oa = a.run(None, {"input": x})
ob = b.run(None, {"input": x})
print("\norig out0 shape:", oa[0].shape)
print("fp32 out0 shape:", ob[0].shape)

# If both have boxes in the same space, compare top boxes by score.
for i, (na, nb) in enumerate(zip(oa, ob)):
    arr_a, arr_b = np.asarray(na), np.asarray(nb)
    if arr_a.shape != arr_b.shape:
        print(f"  out[{i}] shapes differ: {arr_a.shape} vs {arr_b.shape}")
        continue
    if arr_a.dtype == np.float32 and arr_a.ndim >= 2 and arr_a.shape[-1] in (4, 5):
        diff = np.abs(arr_a - arr_b).max()
        print(f"  out[{i}] shape {arr_a.shape} max abs diff: {diff:.6f}")
    else:
        print(
            f"  out[{i}] shape {arr_a.shape} dtype {arr_a.dtype} max diff: {np.abs(arr_a.astype(np.float64) - arr_b.astype(np.float64)).max():.6f}")
