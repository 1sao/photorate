package isao.photorate.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.galleryComponent.classify.LandmarkRaterByThumb
import isao.photorate.inference.classify.HandGestureClassifier
import isao.photorate.inference.classify.HandLandmarkerOptions
import isao.photorate.inference.classify.Score
import isao.photorate.photosMediaPipe.AndroidMediaPipeHandLandmarkerFactory
import isao.photorate.photosMediaPipe.detectFromBitmap
import java.nio.ByteBuffer
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real MediaPipe hand landmarker (same model, factory and image
 * pipeline as the app — the ORIGINAL pipeline, kept alongside the ONNX one)
 * over the sample dataset in plans/samples and documents — from real on-device
 * runs — which samples it detects AND rates.
 *
 * The ONNX hybrid pipeline ([OnnxHandLandmarkDatasetTest]) rates every sample
 * except 4/4_also and 2/2_peanuts; the old MediaPipe pipeline has no RTMPose
 * fallback, so it additionally cannot rate the newly added rotated/thumb-down
 * gestures (5_trope, 2_coffee's secondary hand, ...). Those honest gaps are
 * asserted below so the test fails loudly if a pipeline change ever shifts
 * them.
 */
@RunWith(AndroidJUnit4::class)
class HandLandmarkDatasetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val rater = LandmarkRaterByThumb()

    @Test
    fun allSamplesScoreAsExpected() {
        val factory = AndroidMediaPipeHandLandmarkerFactory(context)
        val landmarker = factory.createFromOptions(
            HandLandmarkerOptions(
                maxNumHands = 2,
                minHandDetectionConfidence = 0.35f,
                minHandPresenceConfidence = 0.35f,
            ),
        )
        val detectionGaps = mutableListOf<String>()
        val unratedDetections = mutableListOf<String>()
        val scoreMismatches = mutableListOf<String>()
        try {
            for (scoreDir in listOf("5", "4", "3", "2", "1", "no_score")) {
                val files = assets.list(scoreDir)
                    ?.filter { it.endsWith(".jpg") }
                    ?.sorted()
                    .orEmpty()
                for (file in files) {
                    val bytes = assets.open("$scoreDir/$file").use { it.readBytes() }
                    val bitmap = decode(bytes)
                    if (bitmap == null) {
                        log("$scoreDir/$file|DECODE_FAILED")
                        continue
                    }
                    val detected = landmarker.detectFromBitmap(bitmap)
                    if (detected == null || detected.hands.isEmpty()) {
                        // Palm detector found nothing at the app pipeline.
                        if (scoreDir == "no_score") {
                            log("$scoreDir/$file|no hand detected (ok for no_score)")
                        } else {
                            detectionGaps += "$scoreDir/$file"
                            log("$scoreDir/$file|DETECTION_GAP: no hand found")
                        }
                        continue
                    }
                    val scores = detected.hands.mapNotNull { hand ->
                        val score = rater.rate(hand)?.score
                        val gesture = HandGestureClassifier.classify(hand)?.gesture
                        log("$scoreDir/$file|hand=${detected.hands.indexOf(hand)}|gesture=$gesture|score=$score")
                        score
                    }
                    if (scores.isEmpty() && scoreDir != "no_score") {
                        unratedDetections += "$scoreDir/$file"
                    }
                    val expected = expectedScore(scoreDir)
                    log("$scoreDir/$file|scores=$scores expected=$expected")
                    if (expected == null) {
                        // no_score must stay filtered.
                        if (scores.isNotEmpty()) scoreMismatches += "$scoreDir/$file: $scores (expected none)"
                    } else {
                        // Unrated scored samples are already recorded in
                        // unratedDetections; only hands that rate the WRONG
                        // score are mismatches here.
                        if (scores.isNotEmpty() && expected !in scores) {
                            scoreMismatches += "$scoreDir/$file: $scores (expected $expected)"
                        }
                    }
                }
            }
        } finally {
            landmarker.close()
        }
        log("DETECTION_GAPS (palm detector finds no hand at app pipeline): $detectionGaps")
        assertEquals(KNOWN_DETECTION_GAPS, detectionGaps)
        log("UNRATED_DETECTIONS (palm detects a hand but rater rejects): $unratedDetections")
        assertEquals(KNOWN_UNRATED_DETECTIONS, unratedDetections)
        log("SCORE_MISMATCHES: $scoreMismatches")
        assertEquals(emptyList<String>(), scoreMismatches)
    }

    companion object {
        // Real on-device gaps for the ORIGINAL MediaPipe pipeline on the full
        // dataset. The palm detector misses these (4/4 and 5_horizontal were
        // already gaps; the ONNX RTMDet replaced the palm detector), and
        // 2_coffee's two hands stay below the presence gate.
        private val KNOWN_DETECTION_GAPS = listOf(
            "5/5_horizontal.jpg",
            "4/4.jpg",
            "4/4_also.jpg",
            "2/2_coffee.jpg",
            "2/2_peanuts.jpg",
        )

        // Hands the palm detector DOES find but the rater rejects: 5_trope's
        // MediaPipe sparse geometry never satisfies the gesture classifier
        // (this old pipeline has no RTMPose fallback).
        private val KNOWN_UNRATED_DETECTIONS = listOf(
            "5/5_trope.jpg",
        )

        // Match the app's MediaPipe image loader decode size.
        private const val DECODE_MIN_DIM = 224
    }

    private fun expectedScore(scoreDir: String): Score? = when (scoreDir) {
        "5" -> Score.FIVE
        "4" -> Score.FOUR
        "3" -> Score.THREE
        "2" -> Score.TWO
        "1" -> Score.ONE
        else -> null
    }

    private fun decode(bytes: ByteArray): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
        }
    } catch (_: Exception) {
        null
    }

    private fun log(msg: String) {
        android.util.Log.i("HandDataset", "DATASET $msg")
    }
}
