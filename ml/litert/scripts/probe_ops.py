#!/usr/bin/env python3
"""Generate tiny single-op tflite probes to discover the GPU delegate's op set.

Each probe is a ~1KB model `input -> ONE OP -> output`. The device test
`gpuOpSupportProbe` compiles each with the strict GPU accelerator and logs
SUPPORTED / NOT_SUPPORTED, so we know exactly which ops the litert
ClGlAccelerator rejects before rewriting the real models.

Usage: ml/litert/.venv/bin/python ml/litert/scripts/probe_ops.py
"""

import os
from pathlib import Path

import numpy as np
import tensorflow as tf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "probes"

_RNG = np.random.default_rng(1)


def const(*shape):
    # Real constant weights: tf.random.normal inside tracing would emit a
    # RANDOM_STANDARD_NORMAL graph op (and break GPU compile) instead.
    return tf.constant(_RNG.standard_normal(shape).astype(np.float32))


def convert(name: str, build, spec: tf.TensorSpec = tf.TensorSpec([1, 4, 4, 3], tf.float32)) -> None:
    """build: fn(placeholder) -> tensor. spec: input spec (default NHWC f32)."""
    @tf.function
    def f(x):
        return build(x)

    concrete = f.get_concrete_function(spec)
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete])
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    tflite = converter.convert()
    (OUT / f"{name}.tflite").write_bytes(tflite)
    print(f"{name}: {len(tflite)} bytes")


def main() -> None:
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
    OUT.mkdir(parents=True, exist_ok=True)

    def conv(x):
        return tf.nn.conv2d(x, const(3, 3, 3, 8), strides=[1, 1, 1, 1], padding="SAME")

    def dwconv(x):
        return tf.nn.depthwise_conv2d(x, const(3, 3, 3, 1), strides=[1, 1, 1, 1], padding="SAME")

    def fc(x):
        return tf.matmul(tf.reshape(x, [1, 48]), const(48, 16))

    def matmul3d(x):
        # BATCH_MATMUL: (1,4,3) x (1,3,4)
        return tf.matmul(x, const(1, 3, 4))

    def split_v(x):
        return tf.concat(tf.split(x, 2, axis=1), axis=1)

    def gather(x):
        return tf.gather(x, tf.constant([0, 2, 3], tf.int32), axis=1)

    def strided_slice(x):
        return x[:, 1:3, 1:3, :]

    def slice_op(x):
        return tf.raw_ops.Slice(input=x, begin=[0, 1, 1, 0], size=[1, 2, 2, 3])

    def squeeze(x):
        return tf.squeeze(tf.reshape(x, [1, 1, 48]), axis=[1])

    def abs_op(x):
        return tf.abs(x)

    def cast_op(x):
        # Mirrors the S1 graph: int64 token ids -> int32 before EMBEDDING_LOOKUP.
        return tf.cast(x, tf.int32)

    def embedding_lookup(x):
        table = const(8, 4)  # f32 [8, 4] embedding table
        return tf.nn.embedding_lookup(table, x)

    probes = {
        "CONV_2D": conv,
        "DEPTHWISE_CONV_2D": dwconv,
        "FULLY_CONNECTED": fc,
        "ADD": lambda x: x + 1.0,
        "MUL": lambda x: x * 2.0,
        "SUB": lambda x: x - 1.0,
        "DIV": lambda x: x / 2.0,
        "MAXIMUM": lambda x: tf.maximum(x, 0.5),
        "MINIMUM": lambda x: tf.minimum(x, 0.5),
        "LOGISTIC": tf.sigmoid,
        "RELU": tf.nn.relu,
        "RELU6": tf.nn.relu6,
        "RELU_0_TO_1": lambda x: tf.clip_by_value(x, 0.0, 1.0),
        "CONCATENATION": lambda x: tf.concat([x, x], axis=-1),
        "RESHAPE": lambda x: tf.reshape(x, [1, 2, 8, 3]),
        "TRANSPOSE": lambda x: tf.transpose(x, [0, 2, 1, 3]),
        "PAD": lambda x: tf.pad(x, [[0, 0], [1, 1], [1, 1], [0, 0]]),
        "MEAN": lambda x: tf.reduce_mean(x, axis=[1, 2], keepdims=True),
        "SUM": lambda x: tf.reduce_sum(x, axis=[1, 2], keepdims=True),
        "SQRT": tf.sqrt,
        # MobileCLIP-S1 ops (added for the combined CLIP tflite GPU check).
        "GELU": tf.nn.gelu,
        "RSQRT": tf.math.rsqrt,
        "SQUARED_DIFFERENCE": lambda x: tf.math.squared_difference(x, 0.5),
        # Remaining S1 ops found by the completeness sweep (int-typed inputs).
        "ABS": abs_op,
        "CAST": (cast_op, tf.TensorSpec([1, 4, 4, 3], tf.int64)),
        "EMBEDDING_LOOKUP": (embedding_lookup, tf.TensorSpec([1, 4], tf.int32)),
        "GATHER": gather,
        "SPLIT_V": split_v,
        "STRIDED_SLICE": strided_slice,
        "SLICE": slice_op,
        "SQUEEZE": squeeze,
        "BATCH_MATMUL": matmul3d,
        "SOFTMAX": tf.nn.softmax,
        "MAX_POOL_2D": lambda x: tf.nn.max_pool2d(x, 2, 2, "SAME"),
        "AVERAGE_POOL_2D": lambda x: tf.nn.avg_pool2d(x, 2, 2, "SAME"),
        "RESIZE_NEAREST_NEIGHBOR": lambda x: tf.raw_ops.ResizeNearestNeighbor(images=x, size=[8, 8]),
        "RESIZE_BILINEAR": lambda x: tf.raw_ops.ResizeBilinear(images=x, size=[8, 8]),
    }
    for name, entry in probes.items():
        fn, spec = entry if isinstance(entry, tuple) else (entry, None)
        try:
            convert(name, fn, spec or tf.TensorSpec([1, 4, 4, 3], tf.float32))
        except Exception as e:  # noqa: BLE001
            print(f"{name}: FAILED to build: {e}")
    print("probes written to", OUT)


if __name__ == "__main__":
    main()
