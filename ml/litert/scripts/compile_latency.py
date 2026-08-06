#!/usr/bin/env python3
"""Compile GPU latency medians (per instrumented run) for the RTM model variants."""
import statistics

# GPU median_ms per asset per instrumented run.
# det/pose f32: 3 runs this session + 3 earlier full-suite runs.
# det/pose f16/i8: 5 runs this session + 2 earlier.
data = {
    "det f32": [33, 25, 25, 24, 22, 24],
    "det f16": [21, 26, 25, 20, 22, 25, 23],
    "det i8": [27, 24, 22, 23, 30, 24, 23],
    "pose f32": [38, 35, 35, 34, 33],
    "pose f16": [33, 33, 35, 33, 33, 38, 37],
    "pose i8": [33, 34, 36, 34, 36, 38, 33],
}

hdr = f"{'asset':8s} {'n':>2s} {'median':>7s} {'mean':>7s} {'min':>4s} {'max':>4s} {'stdev':>6s}"
print(hdr)
for k, v in data.items():
    print(
        f"{k:8s} {len(v):2d} {statistics.median(v):7.1f} {statistics.mean(v):7.1f} "
        f"{min(v):4d} {max(v):4d} {statistics.stdev(v):6.1f}"
    )

print()
print("median deltas vs f32 (ms):")
for row in ("det", "pose"):
    for var in ("f16", "i8"):
        d = statistics.median(data[f"{row} {var}"]) - statistics.median(data[f"{row} f32"])
        print(f"  {row} {var}-f32 = {d:+.1f}")
