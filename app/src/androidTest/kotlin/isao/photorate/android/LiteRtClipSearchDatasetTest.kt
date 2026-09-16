package isao.photorate.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.android.LiteRtClipSearchDatasetTest.Companion.DECODE_MIN_DIM
import isao.photorate.imageRecognition.litert.AndroidLiteRtAppClipSearchFactory
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import isao.photorate.imageRecognition.search.cosineSimilarity
import java.io.ByteArrayOutputStream
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device MobileCLIP-S1 search verification over the dataset in plans/samples/search (exposed as
 * androidTest assets).
 *
 * 1. Every image must be the TOP-1 match for its own description (what the app's search does when
 *    the user types a query).
 * 2. The scaled-down decode used by AndroidPopulateImageEmbeddingsUseCase must embed almost
 *    identically to a full-resolution decode (the model only sees a 256x256 center crop, so we
 *    never need to decode full-size photos).
 *
 * The S1 combined graph (vision + text in one file) passed the host task check at 5/6 with
 * /255-only preprocessing (ml/litert/converted/CLIP_S1_README.md); the app's two-sample set must
 * rank its own description top on-device.
 *
 * Python mirror: `feature/imageRecognition/scripts/test_clip_search_regression.py` runs the same
 * two checks (top-1 ranking + scaled-decode equivalence) on host via ai-edge-litert.
 *
 * TODO: AI slop. Review and refactor.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtClipSearchDatasetTest {

  private val appContext: Context = ApplicationProvider.getApplicationContext()
  private val assets = InstrumentationRegistry.getInstrumentation().context.assets

  // Search image name (plans/samples/search/<name>.jpg) -> its description.
  private val samples =
    mapOf(
      "turtle" to "Turtle",
      "cat_and_lemons" to "cat and lemons",
    )

  @Test
  fun searchFindsImagesByTheirDescription() {
    AndroidLiteRtAppClipSearchFactory(appContext)
      .createFromOptions(AppClipSearchFactory.Options())
      .use { clip ->
        val imageEmbeddings =
          samples.keys.associateWith { name -> clip.embedImage(loadImageBytes(name)) }
        for ((name, description) in samples) {
          val queryEmbedding = clip.embedText(description)
          val ranked =
            imageEmbeddings.entries
              .sortedByDescending { (_, embedding) ->
                cosineSimilarity(
                  queryEmbedding,
                  embedding,
                )
              }
              .map { it.key }
          log("query=$description ranked=$ranked")
          assertEquals(
            "image '$name' must be the top match for its description '$description'",
            name,
            ranked.first(),
          )
        }
      }
  }

  @Test
  fun scaledDownDecodeMatchesFullDecode() {
    AndroidLiteRtAppClipSearchFactory(appContext)
      .createFromOptions(AppClipSearchFactory.Options())
      .use { clip ->
        for (name in samples.keys) {
          val fullBytes = loadImageBytes(name)
          val fullEmbedding = clip.embedImage(fullBytes)
          val scaledEmbedding = clip.embedImage(decodeScaledJpeg(fullBytes))
          val similarity =
            cosineSimilarity(
              fullEmbedding,
              scaledEmbedding,
            )
          log("$name full-vs-scaled similarity=$similarity")
          assertTrue(
            "scaled decode must embed nearly identically to full decode ($name: $similarity)",
            similarity > SCALED_EQUIVALENCE_THRESHOLD,
          )
        }
      }
  }

  private fun loadImageBytes(name: String): ByteArray =
    assets.open("search/$name.jpg").use { it.readBytes() }

  /**
   * Mirrors the app's scaled decode (sample down to [DECODE_MIN_DIM], then JPEG-encode) so the
   * equivalence check covers the production path.
   */
  private fun decodeScaledJpeg(bytes: ByteArray): ByteArray {
    val bitmap =
      BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
      ) ?: error("Failed to decode image bytes")
    return try {
      val w = bitmap.width
      val h = bitmap.height
      val sampleSize =
        (min(
            w,
            h,
          ) / DECODE_MIN_DIM)
          .coerceAtLeast(1)
      val scaled =
        Bitmap.createScaledBitmap(
          bitmap,
          w / sampleSize,
          h / sampleSize,
          true,
        )
      ByteArrayOutputStream().use { out ->
        scaled.compress(
          Bitmap.CompressFormat.JPEG,
          95,
          out,
        )
        out.toByteArray()
      }
    } finally {
      bitmap.recycle()
    }
  }

  private fun log(msg: String) {
    Log.i("LiteRtClip", msg)
  }

  companion object {
    // Measured in Python on the same models: scaled (640) vs full decode similarity is 0.9998; keep
    // a generous margin for on-device decoders.
    private const val SCALED_EQUIVALENCE_THRESHOLD = 0.99
    private const val DECODE_MIN_DIM = 640
  }
}
