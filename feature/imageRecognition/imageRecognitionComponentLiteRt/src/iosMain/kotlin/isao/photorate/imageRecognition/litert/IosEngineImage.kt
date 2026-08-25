package isao.photorate.imageRecognition.litert

import isao.photorate.imageRecognition.classify.LandmarkCandidate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage

/**
 * iOS [EngineImage] backed by a [UIImage]. Pixels are extracted once, into an ARGB [IntArray], and
 * the pure-Kotlin [PixelEngineImage] ops do the rest.
 */
class IosEngineImage
private constructor(
  private val uiImage: UIImage,
  override val width: Int,
  override val height: Int,
  private val pixels: IntArray,
) : EngineImage {

  override fun resized(w: Int, h: Int): EngineImage =
    PixelEngineImage(width, height, pixels).resized(w, h)

  override fun drawnOn(
    w: Int,
    h: Int,
    dx: Int,
    dy: Int,
    background: Int,
  ): EngineImage =
    PixelEngineImage(width, height, pixels)
      .drawnOn(
        w,
        h,
        dx,
        dy,
        background,
      )

  override fun cropped(x0: Int, y0: Int, w: Int, h: Int): EngineImage? =
    PixelEngineImage(width, height, pixels).cropped(x0, y0, w, h)

  override fun rotatedWithoutCrop(degrees: Float): EngineImage =
    PixelEngineImage(width, height, pixels).rotatedWithoutCrop(degrees)

  override fun scaledAboutCenter(
    scale: Float,
    targetSize: Int,
    background: Int,
  ): EngineImage =
    PixelEngineImage(width, height, pixels)
      .scaledAboutCenter(
        scale,
        targetSize,
        background,
      )

  override fun affineWarp(warp: FloatArray, dstW: Int, dstH: Int, background: Int): EngineImage =
    PixelEngineImage(width, height, pixels).affineWarp(warp, dstW, dstH, background)

  override fun getPixels(): IntArray = pixels

  override fun release() = Unit

  companion object {
    @OptIn(ExperimentalForeignApi::class)
    fun fromImage(image: UIImage): IosEngineImage {
      val width = image.size.useContents { width.toInt() }
      val height = image.size.useContents { height.toInt() }
      val bytesPerRow = width * 4
      val byteArray = ByteArray(bytesPerRow * height)
      // Draw + read INSIDE
      // usePinned:
      // CGContextDrawImage writes
      // through the
      // pinned buffer's address,
      // which is only valid while
      // pinned.
      return byteArray.usePinned { pinned ->
        val context =
          CGBitmapContextCreate(
            pinned.addressOf(0),
            width.toULong(),
            height.toULong(),
            8u,
            bytesPerRow.toULong(),
            CGColorSpaceCreateDeviceRGB(),
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
          ) ?: error("CGBitmapContextCreate failed")
        CGContextDrawImage(
          context,
          CGRectMake(
            0.0,
            0.0,
            width.toDouble(),
            height.toDouble(),
          ),
          image.CGImage,
        )
        // Repack RGBA bytes -> ARGB ints.
        val pixels = IntArray(width * height)
        for (i in 0 until width * height) {
          val o = i * 4
          val r = byteArray[o].toInt() and 0xFF
          val g = byteArray[o + 1].toInt() and 0xFF
          val b = byteArray[o + 2].toInt() and 0xFF
          val a = byteArray[o + 3].toInt() and 0xFF
          pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        CGContextRelease(context)
        IosEngineImage(
          image,
          width,
          height,
          pixels,
        )
      }
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
actual fun decodeEngineImage(bytes: ByteArray): EngineImage {
  val data = bytes.usePinned { pinned ->
    NSData.create(
      bytes = pinned.addressOf(0),
      length = bytes.size.toULong(),
    )
  }
  val image = UIImage(data = data) ?: error("Failed to decode image")
  return IosEngineImage.fromImage(image)
}

@OptIn(ExperimentalForeignApi::class)
actual fun LandmarkCandidate.toEngineImage(): EngineImage = IosEngineImage.fromImage(this)
