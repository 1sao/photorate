/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package isao.photorate.imageRecognition.litert

/**
 * EngineImage → normalized float tensor conversion with zero per-frame allocation.
 *
 * Configure once with the model's input geometry and normalization, then call [load] per frame. The
 * returned array is owned by this instance and overwritten by the next call. Model-specific
 * parameters (mean/std, layout, channel order) are constructor arguments — do not edit a vendored
 * copy to change them.
 *
 * Normalization applied per channel c: `value = (pixel[c] / 255 - mean[c]) / std[c]` (skip the `/
 * 255` with `scaleTo01 = false` for raw 0–255 models such as YOLOX).
 */
class ImageTensor(
  private val width: Int,
  private val height: Int,
  private val mean: FloatArray = floatArrayOf(0f, 0f, 0f),
  private val std: FloatArray = floatArrayOf(1f, 1f, 1f),
  private val layout: Layout = Layout.NCHW,
  private val channelOrder: ChannelOrder = ChannelOrder.RGB,
  private val scaleTo01: Boolean = true,
) {
  /** Tensor memory layout: channels-first (PyTorch exports) or channels-last. */
  enum class Layout {
    NCHW,
    NHWC,
  }

  /** Channel order expected by the model ([mean]/[std] are indexed in this order). */
  enum class ChannelOrder {
    RGB,
    BGR,
  }

  companion object {
    val IMAGENET_MEAN =
      floatArrayOf(
        0.485f,
        0.456f,
        0.406f,
      )
    val IMAGENET_STD =
      floatArrayOf(
        0.229f,
        0.224f,
        0.225f,
      )
  }

  /** Destination tensor, reused across [load] calls. */
  val floats = FloatArray(3 * width * height)

  private val pixels = IntArray(width * height)

  /**
   * Converts [image] (resized to this tensor's geometry first if needed) into the configured float
   * layout and returns [floats].
   */
  fun load(image: EngineImage): FloatArray {
    val ready =
      if (image.width == width && image.height == height) {
        image
      } else {
        image.resized(
          width,
          height,
        )
      }
    ready
      .getPixels()
      .copyInto(
        pixels,
        0,
        0,
        width * height,
      )

    val plane = width * height
    for (i in 0 until plane) {
      val p = pixels[i]
      var c0 = ((p shr 16) and 0xFF).toFloat()
      var c1 = ((p shr 8) and 0xFF).toFloat()
      var c2 = (p and 0xFF).toFloat()
      if (channelOrder == ChannelOrder.BGR) {
        val swap = c0
        c0 = c2
        c2 = swap
      }
      if (scaleTo01) {
        c0 /= 255f
        c1 /= 255f
        c2 /= 255f
      }
      c0 = (c0 - mean[0]) / std[0]
      c1 = (c1 - mean[1]) / std[1]
      c2 = (c2 - mean[2]) / std[2]
      if (layout == Layout.NCHW) {
        floats[i] = c0
        floats[plane + i] = c1
        floats[2 * plane + i] = c2
      } else {
        val j = i * 3
        floats[j] = c0
        floats[j + 1] = c1
        floats[j + 2] = c2
      }
    }
    return floats
  }

  /** No-op: pixel scratch lives on the managed heap now. */
  fun release() = Unit
}
