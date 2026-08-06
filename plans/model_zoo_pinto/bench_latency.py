#!/usr/bin/env python3
"""Benchmark ONNX hand models: CPU vs CoreML EP (a real GPU/ANE proxy on this
Mac; on Android the analog is NNAPI). Reports median ms per inference over N
warm + M runs for every provider a model can load with, plus model size.

Usage:
  onnx/.venv/bin/python plans/model_zoo_pinto/bench_latency.py [--runs 30] MODEL.onnx [MORE.onnx...]
"""
import argparse
import numpy as np
import onnxruntime as ort
import time
from pathlib import Path

PROVIDERS = ["CoreMLExecutionProvider", "CPUExecutionProvider"]


def bench(path: str, runs: int) -> None:
    p = Path(path)
    size_mb = p.stat().st_size / 1048576
    print(f"\n===== {p.name} ({size_mb:.2f} MB) =====")
    sess_opts = ort.SessionOptions()
    for prov in PROVIDERS:
        try:
            sess = ort.InferenceSession(str(p), sess_opts, providers=[prov, "CPUExecutionProvider"])
            actual = sess.get_providers()
            if prov not in actual:
                print(f"  {prov:24s} NOT USED (fell back to {actual})")
                continue
            feeds = {}
            for inp in sess.get_inputs():
                shape = [s if isinstance(s, int) else 1 for s in inp.shape]
                if inp.type.startswith("tensor(int"):
                    feeds[inp.name] = np.zeros(shape, dtype=np.int64)
                else:
                    feeds[inp.name] = np.zeros(shape, dtype=np.float32)
            # Warm up, then time.
            for _ in range(5):
                sess.run(None, feeds)
            times = []
            for _ in range(runs):
                t0 = time.perf_counter()
                sess.run(None, feeds)
                times.append((time.perf_counter() - t0) * 1000)
            times.sort()
            med = times[len(times) // 2]
            print(
                f"  {prov:24s} median {med:7.2f} ms   (min {times[0]:.2f}, p95 {times[int(len(times) * 0.95)]:.2f})")
        except Exception as e:
            print(f"  {prov:24s} FAILED: {type(e).__name__}: {str(e)[:100]}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="+")
    ap.add_argument("--runs", type=int, default=30)
    args = ap.parse_args()
    for m in args.models:
        bench(m, args.runs)
