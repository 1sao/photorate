package isao.photorate.imageRecognition.litert

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Pure-Kotlin [EngineImage] backed by an ARGB [IntArray]. Serves the iOS and JVM targets (Android
 * wraps `Bitmap` instead so the verified on-device pipeline keeps Bitmap/Canvas semantics exactly).
 *
 * Ops implement the same math as the original Android Bitmap pipeline: bilinear resize,
 * inverse-mapped rotation/affine, canvas draw.
 */
class PixelEngineImage(
  override val width: Int,
  override val height: Int,
  private val argb: IntArray,
) : EngineImage {

  init {
    require(argb.size == width * height) { "pixel buffer size mismatch" }
  }

  private fun pixel(x: Int, y: Int): Int = argb[y * width + x]

  override fun resized(w: Int, h: Int): EngineImage {
    if (w == width && h == height) return this
    val out = IntArray(w * h)
    val scaleX = width.toFloat() / w
    val scaleY = height.toFloat() / h
    for (y in 0 until h) {
      val srcY = (y + 0.5f) * scaleY - 0.5f
      val y0 =
        floor(srcY)
          .toInt()
          .coerceIn(
            0,
            height - 1,
          )
      val y1 =
        (y0 + 1).coerceIn(
          0,
          height - 1,
        )
      val fy = srcY - y0
      for (x in 0 until w) {
        val srcX = (x + 0.5f) * scaleX - 0.5f
        val x0 =
          floor(srcX)
            .toInt()
            .coerceIn(
              0,
              width - 1,
            )
        val x1 =
          (x0 + 1).coerceIn(
            0,
            width - 1,
          )
        val fx = srcX - x0
        out[y * w + x] =
          bilinear(
            x0,
            y0,
            x1,
            y1,
            fx,
            fy,
          )
      }
    }
    return PixelEngineImage(w, h, out)
  }

  override fun drawnOn(
    w: Int,
    h: Int,
    dx: Int,
    dy: Int,
    background: Int,
  ): EngineImage {
    val out = IntArray(w * h) { background }
    for (y in 0 until height) {
      val ty = dy + y
      if (ty !in 0 until h) continue
      val srcRow = y * width
      val dstRow = ty * w
      for (x in 0 until width) {
        val tx = dx + x
        if (tx in 0 until w) out[dstRow + tx] = argb[srcRow + x]
      }
    }
    return PixelEngineImage(w, h, out)
  }

  override fun cropped(x0: Int, y0: Int, w: Int, h: Int): EngineImage? {
    if (x0 < 0 || y0 < 0 || x0 + w > width || y0 + h > height) return null
    val out = IntArray(w * h)
    for (y in 0 until h) {
      val srcStart = (y0 + y) * width + x0
      argb.copyInto(
        out,
        y * w,
        srcStart,
        srcStart + w,
      )
    }
    return PixelEngineImage(w, h, out)
  }

  /**
   * Inverse-mapped rotation about the center with canvas expansion. The matrix matches
   * `Matrix.postRotate(-deg, w/2, h/2)` + `postTranslate(boundW/2 - w/2, boundH/2 - h/2)`; the
   * pipeline only ever passes multiples of 90°, where bilinear sampling is exact.
   */
  override fun rotatedWithoutCrop(degrees: Float): EngineImage {
    val theta = degrees / 180f * PI.toFloat()
    val absCos = abs(cos(theta))
    val absSin = abs(sin(theta))
    val boundW = (height * absSin + width * absCos).toInt()
    val boundH = (height * absCos + width * absSin).toInt()
    val out = IntArray(boundW * boundH)
    val cosT = cos(theta)
    val sinT = sin(theta)
    val cx = width / 2f
    val cy = height / 2f
    val tx = boundW / 2f - width / 2f
    val ty = boundH / 2f - height / 2f
    for (y in 0 until boundH) {
      for (x in 0 until boundW) {
        // Inverse of forward: dst = R(-θ)(src - c) + c + t
        val dy = x - cx - tx
        val dx = y - cy - ty
        val srcX = dx * cosT - dy * sinT + cx
        val srcY = dx * sinT + dy * cosT + cy
        out[y * boundW + x] =
          sample(
            srcX,
            srcY,
          )
      }
    }
    return PixelEngineImage(boundW, boundH, out)
  }

  /** Inverse-mapped scale about the center into a target square (topDownAffine). */
  override fun scaledAboutCenter(
    scale: Float,
    targetSize: Int,
    background: Int,
  ): EngineImage {
    val cx = width / 2f
    val cy = height / 2f
    val tx = targetSize / 2f - width / 2f
    val ty = targetSize / 2f - height / 2f
    val out = IntArray(targetSize * targetSize) { background }
    for (y in 0 until targetSize) {
      for (x in 0 until targetSize) {
        // Inverse of forward: dst = scale·(src - c) + c + t
        val srcX = (x - cx - tx) / scale + cx
        val srcY = (y - cy - ty) / scale + cy
        if (srcX < 0f || srcY < 0f || srcX > width - 1f || srcY > height - 1f) continue
        out[y * targetSize + x] =
          sample(
            srcX,
            srcY,
          )
      }
    }
    return PixelEngineImage(targetSize, targetSize, out)
  }

  override fun affineWarp(warp: FloatArray, dstW: Int, dstH: Int, background: Int): EngineImage {
    val m00 = warp[0]
    val m01 = warp[1]
    val m02 = warp[2]
    val m10 = warp[3]
    val m11 = warp[4]
    val m12 = warp[5]
    val det = m00 * m11 - m01 * m10
    val invDet = 1f / det
    val a = m11 * invDet
    val b = -m01 * invDet
    val c = -m10 * invDet
    val d = m00 * invDet
    val e = (m01 * m12 - m11 * m02) * invDet
    val f = (m10 * m02 - m00 * m12) * invDet
    val w = width.toFloat()
    val h = height.toFloat()
    val out = IntArray(dstW * dstH) { background }
    for (y in 0 until dstH) {
      for (x in 0 until dstW) {
        val srcX = a * x + b * y + e
        val srcY = c * x + d * y + f
        if (srcX < 0f || srcY < 0f || srcX > w - 1f || srcY > h - 1f) continue
        out[y * dstW + x] = sample(srcX, srcY)
      }
    }
    return PixelEngineImage(dstW, dstH, out)
  }

  override fun getPixels(): IntArray = argb

  override fun release() = Unit

  private fun bilinear(
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    fx: Float,
    fy: Float,
  ): Int {
    val p00 = pixel(x0, y0)
    val p10 = pixel(x1, y0)
    val p01 = pixel(x0, y1)
    val p11 = pixel(x1, y1)
    val a00 = (1f - fx) * (1f - fy)
    val a10 = fx * (1f - fy)
    val a01 = (1f - fx) * fy
    val a11 = fx * fy
    var out = 0
    for (shift in CHANNEL_SHIFTS) {
      val c =
        (p00 shr shift and 0xFF) * a00 +
          (p10 shr shift and 0xFF) * a10 +
          (p01 shr shift and 0xFF) * a01 +
          (p11 shr shift and 0xFF) * a11
      out = out or (c.toInt() and 0xFF shl shift)
    }
    return out
  }

  private companion object {
    // ARGB channel byte offsets for [bilinear] (blue=0,
    // green=8, red=16,
    // alpha=24). Hoisted out of the per-pixel loop to
    // avoid an allocation.
    val CHANNEL_SHIFTS = intArrayOf(0, 8, 16, 24)
  }

  private fun sample(srcX: Float, srcY: Float): Int {
    val x0 = floor(srcX).toInt()
    val y0 = floor(srcY).toInt()
    val x1 = x0 + 1
    val y1 = y0 + 1
    if (
      x0 !in 0 until width || y0 !in 0 until height || x1 !in 0 until width || y1 !in 0 until height
    )
      return 0
    return bilinear(x0, y0, x1, y1, srcX - x0, srcY - y0)
  }
}
