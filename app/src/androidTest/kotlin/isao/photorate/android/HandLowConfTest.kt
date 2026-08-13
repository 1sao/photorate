package isao.photorate.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.imageRecognition.classify.HandLandmarkerOptions
import isao.photorate.imageRecognition.mediapipe.AndroidMediaPipeHandLandmarkerFactory
import isao.photorate.imageRecognition.mediapipe.detectFromBitmap
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests whether very low detection confidences (0.2 / 0.15 / 0.1) recover the 3 hands the palm
 * detector misses at conf 0.35, on the known hand-region crops (identified from renders). If the
 * palm detector fires at all, the confidence threshold is the blocker; if not, the model itself
 * cannot see them.
 */
@RunWith(AndroidJUnit4::class)
class HandLowConfTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val assets = InstrumentationRegistry.getInstrumentation().context.assets

  @Test
  fun lowConfOnCrops() {
    for (conf in listOf(0.2f, 0.15f, 0.1f)) {
      val factory = AndroidMediaPipeHandLandmarkerFactory(context)
      val landmarker =
        factory.createFromOptions(
          HandLandmarkerOptions(
            maxNumHands = 2,
            minHandDetectionConfidence = conf,
            minHandPresenceConfidence = conf,
          )
        )
      try {
        for (region in REGIONS) {
          val bytes = assets.open(region.file).use { it.readBytes() }
          val bitmap = decode(bytes) ?: continue
          val cx0 = (region.x0 * bitmap.width).roundToInt()
          val cy0 = (region.y0 * bitmap.height).roundToInt()
          val cx1 = (region.x1 * bitmap.width).roundToInt()
          val cy1 = (region.y1 * bitmap.height).roundToInt()
          val padX = ((cx1 - cx0) * 0.4f).roundToInt()
          val padY = ((cy1 - cy0) * 0.4f).roundToInt()
          val sX = (cx0 - padX).coerceAtLeast(0)
          val sY = (cy0 - padY).coerceAtLeast(0)
          val w = (cx1 + padX - sX).coerceAtMost(bitmap.width - sX).coerceAtLeast(1)
          val h = (cy1 + padY - sY).coerceAtMost(bitmap.height - sY).coerceAtLeast(1)
          val crop =
            Bitmap.createBitmap(
              bitmap,
              sX,
              sY,
              w,
              h,
            )
          val hands = landmarker.detectFromBitmap(crop)?.hands.orEmpty()
          log("conf=$conf ${region.file} hands=${hands.size} crop=${crop.width}x${crop.height}")
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
    android.util.Log.i("LowConf", "LOW $msg")
  }

  private data class Region(
    val file: String,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
  )

  companion object {
    private const val DECODE_MAX_DIM = 1024

    private val REGIONS =
      listOf(
        Region(
          "4/4.jpg",
          0.5f,
          0.28f,
          0.72f,
          0.55f,
        ),
        Region(
          "4/4_also.jpg",
          0.22f,
          0.15f,
          0.62f,
          0.55f,
        ),
        Region(
          "5/5_horizontal.jpg",
          0.0f,
          0.12f,
          0.25f,
          0.6f,
        ),
      )
  }
}
