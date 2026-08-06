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
import isao.photorate.photosOnnx.AndroidOnnxHandLandmarkerFactory
import java.nio.ByteBuffer
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the ONNX RTMPose-only hand pipeline (RTMDet detector + multi-rotation
 * RTMPose search + gesture classifier gate, see [AndroidOnnxHandLandmarkerFactory])
 * over the sample dataset in plans/samples, mirroring [HandLandmarkDatasetTest]
 * for the MediaPipe pipeline.
 *
 * The ONNX pipeline lives in its own module (`photosOnnx`) as a separate
 * implementation from MediaPipe; this test documents — from real on-device
 * runs — which samples it detects AND rates, so the two pipelines can be
 * compared honestly instead of silently skipping assertions.
 *
 * The RTMPose-only pipeline (see plans/benchmarks/rtmpose_only_v5.py) dropped
 * the sparse landmark model and the palm rotation model: the tightened gesture
 * set (THUMBS / ROCK / camera-facing OK circle) is the gate itself, so every
 * no_score sample is filtered and the scored samples rate. 2_coffee and
 * 3_open_hand_palm_down intentionally no longer rate (PEACE/OPEN_PALM were
 * dropped as false positives). 5_kimbo is handled by the edge thumb-only
 * fallback: its hand is mostly out of frame (only the thumb is in shot, left
 * edge), so full-frame RTMDet never fires; the edge-strip probe finds the
 * thumb chain and rates a low-certainty THUMBS guess (see
 * KNOWN_UNCERTAIN_DETECTIONS). 1_tuna rates THUMBS-1 after the score
 * direction switched to the IP->TIP segment (see HandGestureClassifier).
 */
@RunWith(AndroidJUnit4::class)
class OnnxHandLandmarkDatasetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val rater = LandmarkRaterByThumb()

    @Test
    fun allSamplesScoreAsExpected() {
        val nnapiFlags = AndroidOnnxHandLandmarkerFactory.parseNnapiFlags(
            InstrumentationRegistry.getArguments().getString("nnapiFlags"),
        )
        log("provider=${nnapiFlags?.let { "NNAPI${if (it.isEmpty()) " (default)" else "$it"}" } ?: "CPU"}")
        val factory = AndroidOnnxHandLandmarkerFactory(context, nnapiFlags)
        val landmarker = factory.createFromOptions(
            HandLandmarkerOptions(
                maxNumHands = 2,
                // 0.25: the lowest on-dataset RTMDet box is 1_pills at 0.287
                // (missed by the 0.3 gate); the gesture classifier + kp gates
                // filter the rest (see plans/benchmarks/rtmpose_only_v5.py).
                minHandDetectionConfidence = 0.25f,
                minHandKpConfidence = 0.3f,
                // ROCK/OK below 0.45 mean-keypoint confidence are uncertain
                // (best guess) — the app's ONNX default (LandmarkModel.ONNX.
                // defaultOptions). KNOWN_UNCERTAIN_DETECTIONS is calibrated
                // to this gate (4_also at kp 0.36, 2_coffee / 2_peanuts).
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
                        val unc = if (hand.uncertain) " UNCERTAIN" else ""
                        log(
                            "$scoreDir/$file|hand=${
                                detected.hands.indexOf(
                                    hand,
                                )
                            }|gesture=$gesture|score=$score|uncertain=${hand.uncertain}",
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
                        // At least one CONFIDENT hand must carry the expected
                        // score. Uncertain hands are best-guess scores (their
                        // whole point is that they may be wrong) and are
                        // tracked separately, so they don't count here.
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
        // The RTMDet detector cannot find these hands at the app pipeline
        // (observed on-device). Assert the exact gap set so the test fails
        // loudly if a pipeline change makes them detectable.
        log("DETECTION_GAPS (ONNX finds no hand at app pipeline): $detectionGaps")
        assertEquals(KNOWN_DETECTION_GAPS, detectionGaps)
        // Hands the pipeline DOES detect but the rater rejects (should be
        // empty now that RTMPose fills in the thumbs-up geometry).
        log("UNRATED_DETECTIONS (ONNX detects a hand but rater rejects): $unratedDetections")
        assertEquals(KNOWN_UNRATED_DETECTIONS, unratedDetections)
        // Samples detected but rated only at the uncertain tier (best-guess
        // scores that may be wrong — the UI shows them in the uncertain section).
        log("UNCERTAIN_DETECTIONS (all hands uncertain): $uncertainDetections")
        assertEquals(KNOWN_UNCERTAIN_DETECTIONS, uncertainDetections)
        // Samples with a confident hand that was scored wrong (empty = all correct).
        log("SCORE_MISMATCHES: $scoreMismatches")
        assertEquals("Unexpected scores", KNOWN_SCORE_MISMATCHES, scoreMismatches.map { it.substringBefore(":") })
    }

    companion object {
        // Real on-device gaps in the RTMPose-only pipeline (no sparse model,
        // no palm model). The rotation search now passes through hands that
        // form no gesture (so the dev-mode best-guess rater can still store
        // them for inspection); a sample is a gap only when RTMDet finds no
        // box above the 0.25 detection gate AND the edge thumb-only fallback
        // finds no qualifying thumb chain. Currently empty: every scored
        // sample is at least detected (5_kimbo is a low-certainty edge guess,
        // see KNOWN_UNCERTAIN_DETECTIONS).
        private val KNOWN_DETECTION_GAPS = listOf<String>(
            // Order matches the test's directory iteration (5, 4, 3, 2, 1, no_score).
        )

        // Hands the pipeline DOES detect but the real rater (LandmarkRaterByThumb)
        // rejects — they only rate in dev mode via the best-guess rater
        // (DebugLandmarkRater). 3_open_hand_palm_down (open palm, PEACE/OPEN_PALM
        // dropped), 2_coffee and 2_peanuts (holding — no gesture forms)
        // intentionally no longer rate.
        private val KNOWN_UNRATED_DETECTIONS = listOf(
            "3/3_open_hand_palm_down.jpg",
            "2/2_coffee.jpg",
            "2/2_peanuts.jpg",
        )

        // Samples whose hands are all uncertain-tier (best-guess scores):
        //  5/5_kimbo.jpg — edge thumb-only fallback: the hand is mostly out
        //    of frame (thumb in shot, left edge); the confident upright thumb
        //    chain rates a THUMBS-5 guess with unreliable off-frame fingers.
        //  4/4_also.jpg — dorsal score-4 hand; RTMPose rates ROCK-5 at 180°
        //    with kp 0.36, below the 0.45 confident gate for ROCK/OK.
        //  2/2_coffee.jpg — both holding hands detected but below the floor.
        //  2/2_peanuts.jpg — the one detected hand is also below the gate.
        private val KNOWN_UNCERTAIN_DETECTIONS = listOf(
            "5/5_kimbo.jpg",
            "4/4_also.jpg",
            "2/2_coffee.jpg",
            "2/2_peanuts.jpg",
        )

        // Samples with a CONFIDENT hand that was scored wrong (compared by
        // sample path only — the log line carries the score detail):
        //  4/4.jpg — the model reads the thumb at 36° from image-up in pixel
        //    space (just under the 40° FIVE boundary), so it rates THUMBS-5
        //    while the sample's label is 4. The old hybrid's FOUR came from
        //    the anisotropic image-space normalization (x/iw vs y/ih)
        //    distorting the angle — the pixel-space read matches the Python
        //    benchmark. Flagged for dev-mode inspection
        //    (FeatureFlagRepository.devModeEnabled); the borderline angle is
        //    right where a score threshold sits.
        //  3/3_open_hand_palm_down.jpg — the detected hand forms no gesture
        //    (unrated, listed here because the sample isn't fully uncertain);
        //    it rates only in dev mode via the best-guess rater.
        //  (1/1_tuna.jpg used to be here — a THUMBS-2 vs its label 1 — but the
        //  thumb score direction now uses the IP->TIP segment, which reads the
        //  borderline 147.7/153 deg angle as ONE, matching the label.)
        private val KNOWN_SCORE_MISMATCHES = listOf(
            "4/4.jpg",
            "3/3_open_hand_palm_down.jpg",
        )

        // Match the app's ONNX image loader decode size.
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
        android.util.Log.i("OnnxDataset", "ONNXDATASET $msg")
    }
}
