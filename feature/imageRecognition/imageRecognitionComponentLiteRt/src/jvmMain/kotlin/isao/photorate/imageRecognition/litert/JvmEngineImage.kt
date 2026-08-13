package isao.photorate.imageRecognition.litert

import isao.photorate.imageRecognition.classify.LandmarkCandidate
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * JVM [EngineImage] backed by a [BufferedImage]. Pixels are read once into an ARGB [IntArray] and
 * the pure-Kotlin [PixelEngineImage] ops do the rest.
 */
class JvmEngineImage
private constructor(
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

  override fun getPixels(): IntArray = pixels

  override fun release() = Unit

  companion object {
    fun fromImage(image: BufferedImage): JvmEngineImage {
      val width = image.width
      val height = image.height
      val pixels = IntArray(width * height)
      var i = 0
      for (y in 0 until height) {
        for (x in 0 until width) {
          pixels[i++] =
            image.getRGB(
              x,
              y,
            )
        }
      }
      return JvmEngineImage(
        width,
        height,
        pixels,
      )
    }
  }
}

actual fun decodeEngineImage(bytes: ByteArray): EngineImage {
  val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("Failed to decode image")
  return JvmEngineImage.fromImage(image)
}

actual fun LandmarkCandidate.toEngineImage(): EngineImage = JvmEngineImage.fromImage(this)
