package isao.photorate.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.galleryComponent.classify.LandmarkRaterByThumb
import isao.photorate.inference.classify.HandGestureClassifier
import isao.photorate.inference.classify.HandLandmarkerOptions
import isao.photorate.photosMediaPipe.AndroidMediaPipeHandLandmarkerFactory
import isao.photorate.photosMediaPipe.detectFromBitmap
import java.nio.ByteBuffer
import kotlin.math.min
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-image detection at very low confidence (0.1 / 0.05) for every sample. The earlier sweep only
 * went down to 0.25; if 4.jpg / 4_also respond at all, the fix is simply a lower threshold (with
 * the gesture classifier rejecting the false positives). Landmarks are dumped so real hands can be
 * told apart from palm-detector hallucinations.
 */
@RunWith(AndroidJUnit4::class)
class HandUltraLowConfTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val assets = InstrumentationRegistry.getInstrumentation().context.assets
  private val rater = LandmarkRaterByThumb()

  @Test
  fun ultraLowConf() {
    for (conf in listOf(0.1f, 0.05f)) {
      val factory = AndroidMediaPipeHandLandmarkerFactory(context)
      val landmarker =
        factory.createFromOptions(
          HandLandmarkerOptions(
            maxNumHands = 4,
            minHandDetectionConfidence = conf,
            minHandPresenceConfidence = conf,
          )
        )
      try {
        for (scoreDir in
          listOf(
            "5",
            "4",
            "3",
            "2",
            "1",
            "no_score",
          )) {
          val files = assets.list(scoreDir)?.filter { it.endsWith(".jpg") }?.sorted().orEmpty()
          for (file in files) {
            val bytes = assets.open("$scoreDir/$file").use { it.readBytes() }
            val bitmap = decode(bytes) ?: continue
            val hands = landmarker.detectFromBitmap(bitmap)?.hands.orEmpty()
            if (hands.isNotEmpty()) {
              log("conf=$conf $scoreDir/$file hands=${hands.size} ${bitmap.width}x${bitmap.height}")
              hands.forEachIndexed { i, hand ->
                val gesture = HandGestureClassifier.classify(hand)?.gesture
                val score = rater.rate(hand)?.score
                val points = hand.points.joinToString(";") { p -> "${p.x},${p.y},${p.z}" }
                log(
                  "conf=$conf $scoreDir/$file hand=$i gesture=$gesture score=$score points=$points"
                )
              }
            } else {
              log("conf=$conf $scoreDir/$file hands=0")
            }
          }
        }
      } finally {
        landmarker.close()
      }
    }
  }

  private fun decode(bytes: ByteArray): Bitmap? =
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val buffer = ByteBuffer.wrap(bytes)
        ImageDecoder.createSource(buffer).let { source ->
          ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val s =
              min(
                info.size.width,
                info.size.height,
              ) / DECODE_MAX_DIM
            decoder.setTargetSampleSize(s.coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
          }
        }
      } else {
        null
      }
    } catch (_: Exception) {
      null
    }

  private fun log(msg: String) {
    android.util.Log.i("UltraLow", "ULOW $msg")
  }

  companion object {
    private const val DECODE_MAX_DIM = 1024
  }
}
