package isao.photorate.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.photosComponent.classify.HandGestureClassifier
import isao.photorate.photosComponent.classify.HandLandmarkerOptions
import isao.photorate.photosComponent.classify.LandmarkRaterByThumb
import isao.photorate.photosComponent.classify.Score
import isao.photorate.photoslitert.AndroidLiteRtHandLandmarkerFactory
import java.nio.ByteBuffer
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the LiteRT hand pipeline (RTMDet + RTMPose on CompiledModel, GPU first
 * with CPU fallback — see [AndroidLiteRtHandLandmarkerFactory]) over the sample
 * dataset in plans/samples, mirroring [HandLandmarkDatasetTest] (MediaPipe)
 * and the ONNX dataset test.
 *
 * The pipeline logic is a port of the verified ONNX pipeline on the same two
 * models (converted to tflite in ml/litert/), so the expectations start from
 * the ONNX on-device results and are re-verified here from real runs — any
 * deviation (e.g. a marginal NMS box or SimCC bin flipping between runtimes)
 * shows up as a failing assertion that must be explained, not silenced.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtHandLandmarkDatasetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val rater = LandmarkRaterByThumb()

    @Test
    fun allSamplesScoreAsExpected() {
        val factory = AndroidLiteRtHandLandmarkerFactory(context)
        val landmarker = factory.createFromOptions(
            HandLandmarkerOptions(
                maxNumHands = 2,
                // Same gates as the ONNX default (LandmarkModel.LITERT.
                // defaultOptions) — the two pipelines share the tuned
                // thresholds from plans/benchmarks/rtmpose_only_v5.py.
                minHandDetectionConfidence = 0.25f,
                minHandKpConfidence = 0.3f,
                minHandConfidentKpConfidence = 0.45f,
            ),
        )
        val detectionGaps = mutableListOf<String>()
        val unratedDetections = mutableListOf<String>()
        val scoreMismatches = mutableListOf<String>()
        val uncertainDetections = mutableListOf<String>()
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
                    val detected = landmarker.detect(bitmap)
                    log("$scoreDir/$file|detectedMs=${detected.detectedInMs}")
                    if (detected.hands.isEmpty()) {
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
                        log(
                            "$scoreDir/$file|hand=${detected.hands.indexOf(hand)}|gesture=$gesture|score=$score|uncertain=${hand.uncertain}",
                        )
                        score
                    }
                    if (scores.isEmpty() && scoreDir != "no_score") {
                        // Hand found but the rater rejected it.
                        unratedDetections += "$scoreDir/$file"
                    }
                    val expected = expectedScore(scoreDir)
                    log("$scoreDir/$file|scores=$scores expected=$expected")
                    if (expected == null) {
                        // no_score must stay filtered.
                        if (scores.isNotEmpty()) scoreMismatches += "$scoreDir/$file: $scores (expected none)"
                    } else {
                        val confidentScores = detected.hands.mapNotNull { hand ->
                            if (hand.uncertain) null else rater.rate(hand)?.score
                        }
                        val allUncertain = detected.hands.isNotEmpty() &&
                            detected.hands.all { it.uncertain }
                        if (allUncertain) {
                            uncertainDetections += "$scoreDir/$file"
                        } else if (confidentScores.isEmpty() || expected !in confidentScores) {
                            scoreMismatches += "$scoreDir/$file: confident=$confidentScores all=$scores (expected $expected)"
                        }
                    }
                }
            }
        } finally {
            landmarker.close()
        }
        log("DETECTION_GAPS (LiteRT finds no hand at app pipeline): $detectionGaps")
        assertEquals(KNOWN_DETECTION_GAPS, detectionGaps)
        log("UNRATED_DETECTIONS (LiteRT detects a hand but rater rejects): $unratedDetections")
        assertEquals(KNOWN_UNRATED_DETECTIONS, unratedDetections)
        log("UNCERTAIN_DETECTIONS (all hands uncertain): $uncertainDetections")
        assertEquals(KNOWN_UNCERTAIN_DETECTIONS, uncertainDetections)
        log("SCORE_MISMATCHES: $scoreMismatches")
        assertEquals("Unexpected scores", KNOWN_SCORE_MISMATCHES, scoreMismatches.map { it.substringBefore(":") })
    }

    companion object {
        // Calibrated from real on-device runs of the LiteRT pipeline (GPU,
        // OpenCL + FP32 — the factory's default) on the Redmi (Adreno 618)
        // device. Mostly identical to the ONNX baseline; the one runtime
        // delta is 2/2_coffee.jpg (see KNOWN_SCORE_MISMATCHES).
        private val KNOWN_DETECTION_GAPS = listOf<String>()

        // Hands the pipeline DOES detect but the real rater (LandmarkRaterByThumb)
        // rejects. 3_open_hand_palm_down (open palm, PEACE/OPEN_PALM dropped)
        // and 2_peanuts (holding — no gesture forms) intentionally no longer
        // rate. 2_coffee is NOT here (unlike ONNX): on the LiteRT GPU one
        // holding hand reads a confident THUMBS-4 (see KNOWN_SCORE_MISMATCHES).
        private val KNOWN_UNRATED_DETECTIONS = listOf(
            "3/3_open_hand_palm_down.jpg",
            "2/2_peanuts.jpg",
        )

        // Samples whose hands are all uncertain-tier (best-guess scores):
        //  5/5_kimbo.jpg — edge thumb-only fallback (thumb in shot, left edge).
        //  4/4_also.jpg — dorsal score-4 hand; ROCK-5 at kp 0.36 < 0.45 gate.
        //  2/2_peanuts.jpg — the detected hands are below the confident gate.
        private val KNOWN_UNCERTAIN_DETECTIONS = listOf(
            "5/5_kimbo.jpg",
            "4/4_also.jpg",
            "2/2_peanuts.jpg",
        )

        // Samples with a CONFIDENT hand that was scored wrong:
        //  2/2_coffee.jpg — NEW for LiteRT GPU: a rotated (180°) read of a
        //    holding hand crosses the THUMBS gates and rates a confident
        //    FOUR (ONNX CPU rejected the same hand). GPU FP32 keypoint drift
        //    flips this marginal classification — the same class of
        //    borderline flip the verification README warns about. Documented
        //    as a finding, not a pass.
        //  4/4.jpg — the thumb reads 36° from image-up (borderline FIVE/4
        //    boundary), same as the ONNX pipeline.
        //  3/3_open_hand_palm_down.jpg — the detected hand forms no gesture
        //    (unrated, listed here because the sample isn't fully uncertain).
        private val KNOWN_SCORE_MISMATCHES = listOf(
            "4/4.jpg",
            "3/3_open_hand_palm_down.jpg",
            "2/2_coffee.jpg",
        )

        // Match the app's image loader decode size.
        private const val DECODE_MIN_DIM = 640
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
        android.util.Log.i("LiteRtDataset", "LITERTDATASET $msg")
    }
}
