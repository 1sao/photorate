package isao.photorate.photoslitert

import isao.photorate.inference.classify.LandmarkCandidate

/**
 * Platform-neutral image the shared vision pipeline consumes. Android wraps a `Bitmap` (delegating
 * to Bitmap/Canvas/Matrix so the verified on-device pipeline is untouched); iOS wraps a `UIImage`,
 * JVM a `BufferedImage`. The pure-Kotlin [PixelEngineImage] implementation serves the non-Android
 * targets.
 *
 * Ops mirror exactly the Bitmap ops the original Android pipeline used (bilinear
 * `createScaledBitmap`, Matrix-based rotation/affine, canvas draw).
 */
interface EngineImage {
  val width: Int
  val height: Int

  /** Bilinear resize (matches `Bitmap.createScaledBitmap(w, h, true)`). */
  fun resized(w: Int, h: Int): EngineImage

  /**
   * New [w]×[h] canvas filled with [background] (ARGB), this image drawn at ([dx], [dy]) (matches
   * `createBitmap(w,h)` + `Canvas.drawBitmap`).
   */
  fun drawnOn(w: Int, h: Int, dx: Int, dy: Int, background: Int): EngineImage

  /** Bounds-checked crop; null when the rect falls outside the image. */
  fun cropped(x0: Int, y0: Int, w: Int, h: Int): EngineImage?

  /**
   * Rotates about the center and expands the canvas so nothing is cropped (matches
   * `Matrix.postRotate` + `postTranslate` with plain Paint — the pipeline only ever rotates by
   * multiples of 90°).
   */
  fun rotatedWithoutCrop(degrees: Float): EngineImage

  /**
   * Scales about the center by [scale] into a [targetSize]×[targetSize] canvas filled with
   * [background] (matches `topDownAffine`'s matrix).
   */
  fun scaledAboutCenter(
    scale: Float,
    targetSize: Int,
    background: Int,
  ): EngineImage

  /** Row-major ARGB pixels (length width×height). */
  fun getPixels(): IntArray

  /** Releases native resources (Bitmap recycle / CGContext free). */
  fun release()
}

/** Decodes JPEG/PNG bytes into an [EngineImage] (BitmapFactory / UIImage / ImageIO). */
expect fun decodeEngineImage(bytes: ByteArray): EngineImage

/** Adapts the platform [LandmarkCandidate] (Bitmap / UIImage / BufferedImage). */
expect fun LandmarkCandidate.toEngineImage(): EngineImage
