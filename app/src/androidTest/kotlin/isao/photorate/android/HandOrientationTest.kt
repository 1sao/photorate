package isao.photorate.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.photosComponent.classify.HandGestureClassifier
import isao.photorate.photosComponent.classify.HandLandmarkerOptions
import isao.photorate.photosComponent.classify.LandmarkRaterByThumb
import isao.photorate.photosMediaPipe.AndroidMediaPipeHandLandmarkerFactory
import isao.photorate.photosMediaPipe.detectFromBitmap
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decisive experiment: does the palm detector see these hands in ANY
 * orientation? Crops the known hand regions (identified from renders), then
 * tries the crop as-is plus mirrored and 90/180/270-degree rotations, each
 * upscaled to 512px. If any orientation is detectable, a multi-orientation
 * pipeline can recover the hands; if none are, the model itself cannot see
 * them (dorsal view) and a different detector model is required.
 */
@RunWith(AndroidJUnit4::class)
class HandOrientationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val rater = LandmarkRaterByThumb()

    @Test
    fun orientations() {
        val factory = AndroidMediaPipeHandLandmarkerFactory(context)
        val landmarker = factory.createFromOptions(
            HandLandmarkerOptions(
                maxNumHands = 2,
                minHandDetectionConfidence = 0.35f,
                minHandPresenceConfidence = 0.35f,
            ),
        )
        try {
            for ((file, region) in REGIONS) {
                val bytes = assets.open(file).use { it.readBytes() }
                val bitmap = decode(bytes, DECODE_MAX_DIM)
                    ?: run {
                        log("$file|DECODE_FAILED")
                        continue
                    }
                val x0 = (region.x0 * bitmap.width).roundToInt()
                val y0 = (region.y0 * bitmap.height).roundToInt()
                val x1 = (region.x1 * bitmap.width).roundToInt()
                val y1 = (region.y1 * bitmap.height).roundToInt()
                // Pad generously so rotation keeps the hand in frame.
                val padX = ((x1 - x0) * 0.4f).roundToInt()
                val padY = ((y1 - y0) * 0.4f).roundToInt()
                val cx0 = (x0 - padX).coerceAtLeast(0)
                val cy0 = (y0 - padY).coerceAtLeast(0)
                val cx1 = (x1 + padX).coerceAtMost(bitmap.width)
                val cy1 = (y1 + padY).coerceAtMost(bitmap.height)
                val crop = Bitmap.createBitmap(bitmap, cx0, cy0, cx1 - cx0, cy1 - cy0)
                log("$file|crop=${crop.width}x${crop.height}")

                val variants = linkedMapOf<String, Bitmap>()
                variants["orig"] = crop
                variants["mirror"] = mirror(crop)
                for (deg in listOf(90, 180, 270)) {
                    variants["rot$deg"] = rotate(crop, deg.toFloat())
                }

                for ((name, v) in variants) {
                    val scaled = if (maxOf(v.width, v.height) == TARGET) {
                        v
                    } else {
                        val s = TARGET.toFloat() / maxOf(v.width, v.height)
                        Bitmap.createScaledBitmap(
                            v,
                            (v.width * s).roundToInt().coerceAtLeast(1),
                            (v.height * s).roundToInt().coerceAtLeast(1),
                            true,
                        )
                    }
                    val hands = landmarker.detectFromBitmap(scaled)?.hands.orEmpty()
                    if (hands.isNotEmpty()) {
                        log("$file|$name|HIT hands=${hands.size}")
                        hands.forEachIndexed { i, hand ->
                            val gesture = HandGestureClassifier.classify(hand)?.gesture
                            val score = rater.rate(hand)?.score
                            log("$file|$name hand=$i|gesture=$gesture|score=$score|points=${dumpPoints(hand)}")
                        }
                    } else {
                        log("$file|$name|miss")
                    }
                }
            }
        } finally {
            landmarker.close()
        }
    }

    private fun mirror(bitmap: Bitmap): Bitmap {
        val m = Matrix().apply {
            setScale(-1f, 1f)
            postTranslate(bitmap.width.toFloat(), 0f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val m = Matrix()
        m.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun dumpPoints(hand: isao.photorate.photosComponent.classify.LandmarkedImage.Hand): String =
        hand.points.joinToString(";") { p -> "${p.x},${p.y},${p.z}" }

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
        android.util.Log.i("Orientation", "ORIENT $msg")
    }

    private data class Region(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    companion object {
        private const val DECODE_MAX_DIM = 1024
        private const val TARGET = 512

        private val REGIONS = listOf(
            // 4.jpg (3000x4000): hand in right-middle.
            "4/4.jpg" to Region(0.5f, 0.28f, 0.72f, 0.55f),
            // 4_also.jpg (3000x4000): hand center-left.
            "4/4_also.jpg" to Region(0.22f, 0.15f, 0.62f, 0.55f),
            // 5_horizontal.jpg (4000x2250): hand at left.
            "5/5_horizontal.jpg" to Region(0.0f, 0.12f, 0.25f, 0.6f),
        )
    }
}
