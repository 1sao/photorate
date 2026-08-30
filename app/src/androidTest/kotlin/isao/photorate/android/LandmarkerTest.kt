package isao.photorate.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.imageRecognition.classify.GestureRecognizer
import isao.photorate.imageRecognition.classify.HandLandmarker
import java.nio.ByteBuffer
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs a [HandLandmarker] over the image buckets from `app/src/androidTest/assets/`
 * (plans/samples), asserting gesture scores match bucket expectations. One concrete subclass per
 * runtime (ONNX, LiteRT) keeps the interchangeable pipelines on identical inputs and expectations.
 *
 * Bucket expectations:
 * - `confident_N` -> must detect gesture with score N
 * - `uncertain_N` -> must detect gesture with exact score N, confidence == 0
 * - `rejected` -> must NOT detect any gesture
 * - `*_temp_disabled` -> excluded
 */
abstract class LandmarkerTest {

  protected val context: Context = ApplicationProvider.getApplicationContext()
  protected val assets = InstrumentationRegistry.getInstrumentation().context.assets

  protected abstract val logTag: String

  protected abstract val recognizers: List<GestureRecognizer<*>>

  protected abstract fun createLandmarker(): HandLandmarker

  @Test
  fun allSamplesScoreAsExpected() {
    val landmarker = createLandmarker()

    val failures = mutableListOf<String>()
    landmarker.use { lm ->
      for (bucketDir in assets.list("")?.sorted().orEmpty()) {
        if (bucketDir.endsWith("_temp_disabled")) continue
        val config = parseBucket(bucketDir) ?: continue
        val files =
          assets
            .list(bucketDir)
            ?.filter { it.endsWith(".jpg") || it.endsWith(".png") }
            ?.sorted()
            .orEmpty()
        if (files.isEmpty()) continue

        for (file in files) {
          val bytes = assets.open("$bucketDir/$file").use { it.readBytes() }
          val bitmap = decode(bytes)

          val results = lm.detectWithRecognizers(bitmap, recognizers)
          val scores = results.mapNotNull { it.score }
          val hasGesture = scores.isNotEmpty()
          val detectedScores = results.map {
            "${it.gesture?.let { g -> g::class.simpleName }}-${it.score}"
          }

          val passed =
            when (config.expect) {
              "filter" -> !hasGesture
              "detect" ->
                scores.any { it.score == config.score } && results.any { it.confidence > 0f }
              "uncertain" ->
                results.any {
                  it.score?.score == config.score && it.gesture != null && it.confidence == 0f
                }
              else -> false
            }

          if (!passed) {
            val detail =
              when (config.expect) {
                "filter" -> "expected filter, got $detectedScores"
                "detect" ->
                  "expected score ${config.score} with non-zero confidence, got $detectedScores"
                "uncertain" ->
                  "expected score ${config.score} with zero confidence, got $detectedScores"
                else -> "unknown expectation"
              }
            failures += "$bucketDir/$file: $detail"
            log("$bucketDir/$file|FAIL: $detail")
          } else {
            log("$bucketDir/$file|OK: $detectedScores")
          }
        }
      }
    }
    log("FAILURES: ${failures.size}")
    failures.forEach { log("  $it") }
    assertEquals("Unexpected failures", emptyList<String>(), failures)
  }

  private data class BucketConfig(val expect: String, val score: Int = 0)

  private fun parseBucket(name: String): BucketConfig? {
    if (name == "rejected") return BucketConfig("filter")
    val m = Regex("^(confident|uncertain)_(\\d+)$").find(name) ?: return null
    val (level, score) = m.destructured
    return BucketConfig(
      expect = if (level == "uncertain") "uncertain" else "detect",
      score = score.toInt(),
    )
  }

  private fun decode(bytes: ByteArray): Bitmap =
    try {
      if (FULL_RES_DECODE) { // TODO make sure Landmarker uses this in app
        decodeFullResThenResize(bytes)
      } else {
        decodeWithSampleSize(bytes)
      }
    } catch (e: Exception) {
      throw AssertionError("Decode failed", e)
    }

  private fun decodeWithSampleSize(bytes: ByteArray): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val buffer = ByteBuffer.wrap(bytes)
      ImageDecoder.createSource(buffer).let { source ->
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
          val originalSize = min(info.size.width, info.size.height)
          val sampleSize = originalSize / DECODE_MIN_DIM
          decoder.setTargetSampleSize(sampleSize.coerceAtLeast(1))
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
      }
    } else {
      val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: throw AssertionError("BitmapFactory returned null")
    }
  }

  private fun decodeFullResThenResize(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val origW = bounds.outWidth
    val origH = bounds.outHeight
    if (origW <= 0 || origH <= 0)
      throw AssertionError("Invalid image dimensions: ${origW}x${origH}")

    val originalSize = min(origW, origH)
    val scaleFactor = originalSize.toFloat() / DECODE_MIN_DIM
    if (scaleFactor <= 1f) {
      val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
      return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: throw AssertionError("BitmapFactory returned null for small image")
    }

    var inSampleSize = 1
    while (inSampleSize * 2 <= scaleFactor) {
      inSampleSize *= 2
    }

    val preOptions =
      BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        this.inSampleSize = inSampleSize
      }
    val preBitmap =
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, preOptions)
        ?: throw AssertionError("BitmapFactory returned null after inSampleSize=$inSampleSize")

    val targetW = (origW / scaleFactor).toInt().coerceAtLeast(1)
    val targetH = (origH / scaleFactor).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(preBitmap, targetW, targetH, true)
    if (scaled !== preBitmap) preBitmap.recycle()
    return scaled
  }

  protected fun log(msg: String) {
    Log.i(logTag, "$logTag $msg")
  }

  companion object {
    private const val DECODE_MIN_DIM = 640
    private const val FULL_RES_DECODE = true
  }
}
