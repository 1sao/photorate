package isao.photorate.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.photosComponent.classify.HandGestureClassifier
import isao.photorate.photosComponent.classify.HandLandmarker
import isao.photorate.photosComponent.classify.HandLandmarkerOptions
import isao.photorate.photosComponent.classify.LandmarkRaterByThumb
import isao.photorate.photosMediaPipe.AndroidMediaPipeHandLandmarkerFactory
import isao.photorate.photosMediaPipe.detectFromBitmap
import java.nio.ByteBuffer
import kotlin.math.min
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the tiling hypothesis for the 3 samples the palm detector misses
 * on the full image (4/4.jpg, 4/4_also.jpg, 5/5_horizontal.jpg): the palm
 * detector inside HandLandmarker internally resizes the whole input to ~192x192,
 * so a small hand gets compressed away. Running detection on overlapping crops
 * of the image makes the hand a large fraction of each tile's detector input.
 */
@RunWith(AndroidJUnit4::class)
class HandTilingSweepTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val rater = LandmarkRaterByThumb()

    @Test
    fun sweepTileSizes() {
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
                // Baseline: full image at this resolution.
                detectAndLog(landmarker, bitmap, "$file|full-image")

                // Overlapping grid tiles with a couple of sizes/overlaps.
                for (tileDiv in listOf(2, 3, 4)) {
                    for (overlap in listOf(0f, 0.25f)) {
                        var tileIndex = 0
                        for (tile in tiles(bitmap, tileDiv, overlap)) {
                            val label = "$file|tileDiv=$tileDiv|overlap=$overlap|tile=$tileIndex"
                            detectAndLog(landmarker, tile, label)
                            tileIndex++
                        }
                    }
                }
            }
        } finally {
            landmarker.close()
        }
    }

    private fun detectAndLog(landmarker: HandLandmarker, bitmap: Bitmap, label: String) {
        val detected = landmarker.detectFromBitmap(bitmap)
        val hands = detected?.hands.orEmpty()
        if (hands.isEmpty()) {
            log("$label|NO_HAND")
            return
        }
        log("$label|hands=${hands.size}|${bitmap.width}x${bitmap.height}")
        hands.forEachIndexed { i, hand ->
            val score = rater.rate(hand)?.score
            val gesture = HandGestureClassifier.classify(hand)?.gesture
            log("$label|hand=$i|gesture=$gesture|score=$score")
        }
    }

    /** Overlapping grid of square tiles covering the bitmap. */
    private fun tiles(bitmap: Bitmap, divisions: Int, overlapFraction: Float): List<Bitmap> {
        val tileSize = min(bitmap.width, bitmap.height) / divisions
        val step = (tileSize * (1f - overlapFraction)).toInt().coerceAtLeast(1)
        val result = mutableListOf<Bitmap>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val w = min(tileSize, bitmap.width - x)
                val h = min(tileSize, bitmap.height - y)
                if (w > 0 && h > 0) {
                    result += Bitmap.createBitmap(bitmap, x, y, w, h)
                }
                x += step
            }
            y += step
        }
        return result
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
        android.util.Log.i("TilingSweep", "TILING $msg")
    }

    companion object {
        private const val MAX_DIM = 1024
    }
}
