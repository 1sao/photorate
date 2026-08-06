import numpy as np
import onnx
import onnxruntime as ort
import sys
from onnxconverter_common import float16

MODELS = sys.argv[1:]


def run(sess, feeds):
    return sess.run(None, feeds)


for path in MODELS:
    print(f"\n===== {path} =====")
    m = onnx.load(path)
    m_fp16 = float16.convert_float_to_float16(m, keep_io_types=True,
                                              op_block_list={"Shape", "Slice", "Concat", "Gather",
                                                             "Reshape", "ReduceMax", "Transpose",
                                                             "Squeeze", "Unsqueeze", "TopK",
                                                             "Resize"})
    dst = path.replace(".onnx", "_fp16.onnx")
    onnx.save(m_fp16, dst)
    import os

    print(
        f"  size: {os.path.getsize(path) / 1048576:.1f} MB -> {os.path.getsize(dst) / 1048576:.1f} MB")

    fp32 = ort.InferenceSession(path)
    fp16 = ort.InferenceSession(dst)

    feeds = {}
    for inp in fp32.get_inputs():
        shape = [s if isinstance(s, int) and s > 0 else 1 for s in inp.shape]
        feeds[inp.name] = np.random.default_rng(0).standard_normal(shape).astype(np.float32)

    o32 = run(fp32, feeds)
    o16 = run(fp16, feeds)
    for i, (a, b) in enumerate(zip(o32, o16)):
        if a.size == 0:
            continue
        print(
            f"  out[{i}] {a.shape} max abs diff: {np.abs(a.astype(np.float64) - b.astype(np.float64)).max():.6f}")
