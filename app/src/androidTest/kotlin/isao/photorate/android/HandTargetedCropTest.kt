package isao.photorate.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.inference.classify.HandGestureClassifier
import isao.photorate.inference.classify.HandLandmarkerOptions
import isao.photorate.photosComponent.classify.LandmarkRaterByThumb
import isao.photorate.photosMediaPipe.AndroidMediaPipeHandLandmarkerFactory
import isao.photorate.photosMediaPipe.detectFromBitmap
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Targeted hand-region crops for the 3 undetected samples. The exact hand
 * bounding boxes were identified from ASCII renders of the images. Each region
 * is cropped from a ~1500px decode, padded generously, upscaled to a fixed
 * target size, and run through the real MediaPipe landmarker. This determines
 * whether the fix is a crop+upscale pipeline (landmarker works on a proper
 * crop) or whether the model itself cannot see these hands at all.
 */
@RunWith(AndroidJUnit4::class)
class HandTargetedCropTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val rater = LandmarkRaterByThumb()

    @Test
    fun targetedCrops() {
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
                log("$file|decoded=${bitmap.width}x${bitmap.height}|region=($x0,$y0)-($x1,$y1)")

                for (pad in listOf(0f, 0.25f, 0.5f)) {
                    val padX = ((x1 - x0) * pad).roundToInt()
                    val padY = ((y1 - y0) * pad).roundToInt()
                    val cx0 = (x0 - padX).coerceAtLeast(0)
                    val cy0 = (y0 - padY).coerceAtLeast(0)
                    val cx1 = (x1 + padX).coerceAtMost(bitmap.width)
                    val cy1 = (y1 + padY).coerceAtMost(bitmap.height)
                    if (cx1 - cx0 < 32 || cy1 - cy0 < 32) continue
                    val crop = Bitmap.createBitmap(bitmap, cx0, cy0, cx1 - cx0, cy1 - cy0)
                    for (target in listOf(384, 512, 768)) {
                        val scaled = if (maxOf(crop.width, crop.height) == target) {
                            crop
                        } else {
                            val s = target.toFloat() / maxOf(crop.width, crop.height)
                            Bitmap.createScaledBitmap(
                                crop,
                                (crop.width * s).roundToInt().coerceAtLeast(1),
                                (crop.height * s).roundToInt().coerceAtLeast(1),
                                true,
                            )
                        }
                        val hands = landmarker.detectFromBitmap(scaled)?.hands.orEmpty()
                        if (hands.isNotEmpty()) {
                            log(
                                "$file|pad=$pad|target=$target|HIT hands=${hands.size} crop=${crop.width}x${crop.height}->${scaled.width}x${scaled.height}",
                            )
                            hands.forEachIndexed { i, hand ->
                                val gesture = HandGestureClassifier.classify(hand)?.gesture
                                val score = rater.rate(hand)?.score
                                log("$file|hand=$i pad=$pad|gesture=$gesture|score=$score|points=${dumpPoints(hand)}")
                            }
                        } else {
                            log("$file|pad=$pad|target=$target|miss")
                        }
                    }
                }
            }
        } finally {
            landmarker.close()
        }
    }

    private fun dumpPoints(hand: isao.photorate.inference.classify.LandmarkedImage.Hand): String =
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
        android.util.Log.i("Targeted", "TARGET $msg")
    }

    /** Source-image normalized regions (fractions of original width/height). */
    private data class Region(val srcW: Int, val srcH: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    companion object {
        private const val DECODE_MAX_DIM = 1024

        private val REGIONS = listOf(
            // 4.jpg (3000x4000): hand in right-middle, from render of (1500,800)-(3000,2000).
            "4/4.jpg" to Region(3000, 4000, 0.5f, 0.28f, 0.72f, 0.55f),
            // 4_also.jpg (3000x4000): hand center-left, from render of (500,500)-(2000,2000).
            "4/4_also.jpg" to Region(3000, 4000, 0.22f, 0.15f, 0.62f, 0.55f),
            // 5_horizontal.jpg (4000x2250): hand at left, from render of (0,200)-(1800,1125).
            "5/5_horizontal.jpg" to Region(4000, 2250, 0.0f, 0.12f, 0.25f, 0.6f),
        )
    }
}
