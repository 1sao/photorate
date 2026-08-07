package isao.photorate.android

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.inference.classify.HandLandmarkerOptions
import isao.photorate.photosMediaPipe.AndroidMediaPipeHandLandmarkerFactory
import isao.photorate.photosMediaPipe.detectFromBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter

/**
 * Runs the EXTRACTED hand landmark model (hand_landmarks_detector.tflite, the same model MediaPipe
 * runs AFTER its palm detector) DIRECTLY on hand crops, bypassing the palm-detector stage entirely.
 *
 * The landmark model outputs [63 landmarks, presence score, handedness, world landmarks]. If its
 * presence score fires on the exact hand regions that the palm detector misses (4/4.jpg,
 * 4/4_also.jpg, 5/5_horizontal.jpg), then the landmark model CAN see these hands and the fix is a
 * better hand-finder (e.g. a different detector), not a different landmark model.
 *
 * Control: the app pipeline detects 5_vertical's hand, so we crop that detected hand's tight bbox
 * (from its 21 landmarks) and expect HIGH presence through this direct path. Problem regions are
 * swept over several padding factors so a loose region can't cause a false miss.
 */
@RunWith(AndroidJUnit4::class)
class HandLandmarkDirectTest {

  private val assets = InstrumentationRegistry.getInstrumentation().context.assets

  @Test
  fun landmarkModelDirectlyOnCrops() {
    val modelBytes = assets.open("hand_landmarks_detector.tflite").use { it.readBytes() }
    // TFLite requires a DIRECT byte buffer for the model
    // (heap buffers throw).
    val modelBuffer =
      ByteBuffer.allocateDirect(modelBytes.size)
        .order(ByteOrder.nativeOrder())
        .put(modelBytes)
        .apply { rewind() }
    val interpreter = Interpreter(modelBuffer)
    try {
      runControl(interpreter)
      for (region in PROBLEM_REGIONS) {
        val bytes = assets.open(region.file).use { it.readBytes() }
        val bitmap = decode(bytes)
        if (bitmap == null) {
          log("problem-${region.label}|DECODE_FAILED")
          continue
        }
        val cx0 = (region.x0 * bitmap.width).roundToInt()
        val cy0 = (region.y0 * bitmap.height).roundToInt()
        val cx1 = (region.x1 * bitmap.width).roundToInt()
        val cy1 = (region.y1 * bitmap.height).roundToInt()
        for (pad in PADS) {
          runDirect(
            region.file,
            "problem-${region.label}",
            cx0,
            cy0,
            cx1,
            cy1,
            interpreter,
            pad,
          )
        }
      }
    } finally {
      interpreter.close()
    }
  }

  /**
   * Control: run the FULL app pipeline on 5_vertical (palm detector works here), take the detected
   * hand's tight landmark bbox, and feed that exact region to the landmark model directly. High
   * presence proves the direct-crop preprocessing is faithful; low presence would mean our
   * crop/rotation/normalization is wrong and the experiment is invalid.
   */
  private fun runControl(interpreter: Interpreter) {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val factory = AndroidMediaPipeHandLandmarkerFactory(context)
    val landmarker =
      factory.createFromOptions(
        HandLandmarkerOptions(
          maxNumHands = 2,
          minHandDetectionConfidence = 0.35f,
          minHandPresenceConfidence = 0.35f,
        )
      )
    try {
      val bytes = assets.open("5/5_vertical.jpg").use { it.readBytes() }
      val bitmap =
        decode(bytes)
          ?: run {
            log("control|DECODE_FAILED")
            return
          }
      val detected = landmarker.detectFromBitmap(bitmap)
      if (detected == null || detected.hands.isEmpty()) {
        log("control|no hand detected by full pipeline (control invalid!)")
        return
      }
      val hand = detected.hands.first()
      var lx = Float.MAX_VALUE
      var ly = Float.MAX_VALUE
      var hx = -Float.MAX_VALUE
      var hy = -Float.MAX_VALUE
      for (p in hand.points) {
        lx =
          min(
            lx,
            p.x,
          )
        ly =
          min(
            ly,
            p.y,
          )
        hx =
          max(
            hx,
            p.x,
          )
        hy =
          max(
            hy,
            p.y,
          )
      }
      // Normalized landmark coords
      // -> pixel bbox.
      val x0 = (lx * bitmap.width).roundToInt()
      val y0 = (ly * bitmap.height).roundToInt()
      val x1 = (hx * bitmap.width).roundToInt()
      val y1 = (hy * bitmap.height).roundToInt()
      for (pad in PADS) {
        runDirect(
          "5/5_vertical.jpg",
          "control-5_vertical",
          x0,
          y0,
          x1,
          y1,
          interpreter,
          pad,
        )
      }
    } finally {
      landmarker.close()
    }
  }

  private fun runDirect(
    assetPath: String,
    label: String,
    cx0: Int,
    cy0: Int,
    cx1: Int,
    cy1: Int,
    interpreter: Interpreter,
    padFraction: Float,
  ) {
    val bytes = assets.open(assetPath).use { it.readBytes() }
    val bitmap =
      decode(bytes)
        ?: run {
          log("$label|DECODE_FAILED")
          return
        }
    val padX = ((cx1 - cx0) * padFraction).roundToInt()
    val padY = ((cy1 - cy0) * padFraction).roundToInt()
    val sX = (cx0 - padX).coerceAtLeast(0)
    val sY = (cy0 - padY).coerceAtLeast(0)
    val w = (cx1 + padX - sX).coerceAtMost(bitmap.width - sX).coerceAtLeast(1)
    val h = (cy1 + padY - sY).coerceAtMost(bitmap.height - sY).coerceAtLeast(1)
    val crop = Bitmap.createBitmap(bitmap, sX, sY, w, h)
    val scaled =
      Bitmap.createScaledBitmap(
        crop,
        224,
        224,
        true,
      )
    val pixels = IntArray(224 * 224)
    scaled.getPixels(pixels, 0, 224, 0, 0, 224, 224)

    for (normalization in listOf("neg1to1", "0to1")) {
      val input =
        toInputBuffer(
          pixels,
          normalization,
        )
      val landmarks = Array(1) { FloatArray(63) }
      val presence = Array(1) { FloatArray(1) }
      val handedness = Array(1) { FloatArray(1) }
      val world = Array(1) { FloatArray(63) }
      interpreter.runForMultipleInputsOutputs(
        arrayOf(input),
        mapOf(
          0 to landmarks,
          1 to presence,
          2 to handedness,
          3 to world,
        ),
      )

      val score = presence[0][0]
      val pts = landmarks[0]
      var lx = Float.MAX_VALUE
      var ly = Float.MAX_VALUE
      var hx = -Float.MAX_VALUE
      var hy = -Float.MAX_VALUE
      for (i in 0 until 21) {
        lx =
          min(
            lx,
            pts[i * 3],
          )
        ly =
          min(
            ly,
            pts[i * 3 + 1],
          )
        hx =
          max(
            hx,
            pts[i * 3],
          )
        hy =
          max(
            hy,
            pts[i * 3 + 1],
          )
      }
      val bw = hx - lx
      val bh = hy - ly
      log(
        "$label|$assetPath|pad=$padFraction|norm=$normalization|presence=%.3f|bbox=%.2fx%.2f|aspect=%.2f|handed=%.2f"
          .format(
            score,
            bw,
            bh,
            if (bh > 0f) bw / bh else 0f,
            handedness[0][0],
          )
      )
    }
  }

  private fun toInputBuffer(
    pixels: IntArray,
    normalization: String,
  ): ByteBuffer {
    val input = ByteBuffer.allocateDirect(1 * 224 * 224 * 3 * 4).order(ByteOrder.nativeOrder())
    for (p in pixels) {
      val r = ((p shr 16) and 0xFF).toFloat()
      val g = ((p shr 8) and 0xFF).toFloat()
      val b = (p and 0xFF).toFloat()
      when (normalization) {
        "neg1to1" -> {
          input.putFloat(r / 127.5f - 1f)
          input.putFloat(g / 127.5f - 1f)
          input.putFloat(b / 127.5f - 1f)
        }

        else -> {
          input.putFloat(r / 255f)
          input.putFloat(g / 255f)
          input.putFloat(b / 255f)
        }
      }
    }
    input.rewind()
    return input
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
    android.util.Log.i("LandmarkDirect", "DIRECT $msg")
  }

  private data class Region(
    val file: String,
    val label: String,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
  )

  companion object {
    private const val DECODE_MAX_DIM = 1024
    private val PADS = listOf(0.2f, 0.5f)

    // The three hands the palm detector misses (regions
    // from renders).
    private val PROBLEM_REGIONS =
      listOf(
        Region(
          "4/4.jpg",
          "4jpg",
          0.5f,
          0.28f,
          0.72f,
          0.55f,
        ),
        Region(
          "4/4_also.jpg",
          "4also",
          0.22f,
          0.15f,
          0.62f,
          0.55f,
        ),
        Region(
          "5/5_horizontal.jpg",
          "5horiz",
          0.0f,
          0.12f,
          0.25f,
          0.6f,
        ),
      )
  }
}
