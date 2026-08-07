package isao.photorate.photoslitert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import isao.photorate.inference.classify.LandmarkCandidate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Android [EngineImage] wrapping a [Bitmap]. Delegates every op to the exact Bitmap/Canvas/Matrix
 * calls the original verified pipeline used, so the on-device behavior is unchanged by the module
 * split. [decodeEngineImage] and [LandmarkCandidate.toEngineImage] both funnel through here.
 */
class BitmapEngineImage(private val bitmap: Bitmap) : EngineImage {

  override val width: Int
    get() = bitmap.width

  override val height: Int
    get() = bitmap.height

  override fun resized(w: Int, h: Int): EngineImage =
    BitmapEngineImage(Bitmap.createScaledBitmap(bitmap, w, h, true))

  override fun drawnOn(w: Int, h: Int, dx: Int, dy: Int, background: Int): EngineImage {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    if (background != 0) canvas.drawColor(background)
    canvas.drawBitmap(bitmap, dx.toFloat(), dy.toFloat(), Paint())
    return BitmapEngineImage(out)
  }

  override fun cropped(x0: Int, y0: Int, w: Int, h: Int): EngineImage? {
    if (x0 < 0 || y0 < 0 || x0 + w > bitmap.width || y0 + h > bitmap.height) return null
    return BitmapEngineImage(Bitmap.createBitmap(bitmap, x0, y0, w, h))
  }

  /** Matches `Matrix.postRotate(-degree, w/2, h/2)` + `postTranslate(...)`. */
  override fun rotatedWithoutCrop(degrees: Float): EngineImage {
    val w = bitmap.width
    val h = bitmap.height
    val theta = Math.toRadians(degrees.toDouble())
    val absCos = abs(cos(theta))
    val absSin = abs(sin(theta))
    val boundW = (h * absSin + w * absCos).toInt()
    val boundH = (h * absCos + w * absSin).toInt()
    val matrix = Matrix()
    matrix.postRotate(-degrees, w / 2f, h / 2f)
    matrix.postTranslate(boundW / 2f - w / 2f, boundH / 2f - h / 2f)
    val out = Bitmap.createBitmap(boundW, boundH, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(bitmap, matrix, Paint())
    return BitmapEngineImage(out)
  }

  /** Matches the topDownAffine matrix: scale about center, black fill. */
  override fun scaledAboutCenter(scale: Float, targetSize: Int, background: Int): EngineImage {
    val cx = bitmap.width / 2f
    val cy = bitmap.height / 2f
    val matrix = Matrix()
    matrix.postScale(scale, scale, cx, cy)
    matrix.postTranslate(targetSize / 2f - cx, targetSize / 2f - cy)
    val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(background)
    canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    return BitmapEngineImage(out)
  }

  override fun getPixels(): IntArray {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return pixels
  }

  override fun release() {
    if (!bitmap.isRecycled) bitmap.recycle()
  }

  /** Raw ARGB accessor used by [ImageTensor] via [getPixels] parity. */
  internal fun toBitmap(): Bitmap = bitmap
}

actual fun decodeEngineImage(bytes: ByteArray): EngineImage {
  val bitmap =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Failed to decode image")
  return BitmapEngineImage(bitmap)
}

actual fun LandmarkCandidate.toEngineImage(): EngineImage = BitmapEngineImage(this)
