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
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Multi-scale crop scan for the 3 undetected samples. The palm detector inside
 * HandLandmarker resizes the whole input to ~192x192, so small hands are lost.
 * Here we slide crops of several sizes across the image and upscale each crop
 * to a fixed TARGET px so the hand fills a large fraction of the detector input.
 * Logs every crop that produces a hand (with landmarks), nothing else.
 */
@RunWith(AndroidJUnit4::class)
class HandMultiScaleSweepTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val rater = LandmarkRaterByThumb()

    @Test
    fun sweepMultiScaleCrops() {
        val factory = AndroidMediaPipeHandLandmarkerFactory(context)
        val landmarker = factory.createFromOptions(
            HandLandmarkerOptions(
                maxNumHands = 2,
                minHandDetectionConfidence = 0.35f,
                minHandPresenceConfidence = 0.35f,
            ),
        )
        try {
            for (file in listOf("4/4.jpg", "4/4_also.jpg", "5/5_horizontal.jpg")) {
                val bytes = assets.open(file).use { it.readBytes() }
                val bitmap = decode(bytes, MAX_DIM)
                    ?: run {
                        log("$file|DECODE_FAILED")
                        continue
                    }
                log("$file|full=${bitmap.width}x${bitmap.height}")

                for (cropSize in CROP_SIZES) {
                    val step = (cropSize * STEP_FRACTION).roundToInt().coerceAtLeast(1)
                    var y = 0
                    while (y < bitmap.height) {
                        var x = 0
                        while (x < bitmap.width) {
                            val w = min(cropSize, bitmap.width - x)
                            val h = min(cropSize, bitmap.height - y)
                            if (w >= MIN_CROP && h >= MIN_CROP) {
                                val crop = Bitmap.createBitmap(bitmap, x, y, w, h)
                                val scaled = if (maxOf(w, h) == TARGET) {
                                    crop
                                } else {
                                    Bitmap.createScaledBitmap(
                                        crop,
                                        (w.toFloat() / maxOf(w, h) * TARGET).roundToInt().coerceAtLeast(1),
                                        (h.toFloat() / maxOf(w, h) * TARGET).roundToInt().coerceAtLeast(1),
                                        true,
                                    )
                                }
                                val hands = landmarker.detectFromBitmap(scaled)?.hands.orEmpty()
                                if (hands.isNotEmpty()) {
                                    log(
                                        "$file|crop=${cropSize}px|at=($x,$y)|crop=${w}x$h->${scaled.width}x${scaled.height}|hands=${hands.size}",
                                    )
                                    hands.forEachIndexed { i, hand ->
                                        val gesture = HandGestureClassifier.classify(hand)?.gesture
                                        val score = rater.rate(hand)?.score
                                        val points = hand.points.joinToString(";") { p -> "${p.x},${p.y},${p.z}" }
                                        log("$file|hand=$i at=($x,$y)|gesture=$gesture|score=$score|points=$points")
                                    }
                                }
                            }
                            x += step
                        }
                        y += step
                    }
                }
            }
        } finally {
            landmarker.close()
        }
    }

    private fun decode(bytes: ByteArray, maxDim: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val buffer = ByteBuffer.wrap(bytes)
            ImageDecoder.createSource(buffer).let { source ->
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val originalSize = min(info.size.width, info.size.height)
                    val sampleSize = originalSize / maxDim
                    decoder.setTargetSampleSize(sampleSize.coerceAtLeast(1))
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
        android.util.Log.i("MultiScale", "MULTI $msg")
    }

    companion object {
        private const val MAX_DIM = 1024
        private const val TARGET = 512
        private const val STEP_FRACTION = 0.5f
        private const val MIN_CROP = 64
        private val CROP_SIZES = listOf(128, 192, 256, 320, 384, 512)
    }
}
