package isao.photorate.photoslitert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.LiteRtException
import isao.photorate.photosComponent.classify.GestureClassification
import isao.photorate.photosComponent.classify.HandGesture
import isao.photorate.photosComponent.classify.HandGestureClassifier
import isao.photorate.photosComponent.classify.HandLandmarker
import isao.photorate.photosComponent.classify.HandLandmarkerFactory
import isao.photorate.photosComponent.classify.HandLandmarkerOptions
import isao.photorate.photosComponent.classify.LandmarkCandidate
import isao.photorate.photosComponent.classify.LandmarkedImage
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * Android [HandLandmarkerFactory] backed by LiteRT CompiledModel: RTMDet hand
 * detection -> multi-rotation RTMPose search -> gesture rating. This is a port
 * of the verified ONNX pipeline (`photosOnnx.AndroidOnnxHandLandmarker`) that
 * swaps ONNX Runtime sessions for the LiteRT conversions of the same two
 * models (ml/litert/ recipe; assets shipped from ml/litert/converted):
 *
 *  - Detector: `rtmdet_hand_320_f32.tflite` (raw anchors, NMS caller-side).
 *  - Pose: `rtmpose_hand_256_f32.tflite` (raw SimCC, decode caller-side).
 *
 * Inference is GPU-first (OpenCL backend, FP32 precision — the accuracy-safe
 * config verified in LiteRtOnDeviceVerificationTest) with a CPU fallback when
 * the GPU cannot compile the graph or fails at runtime, mirroring the
 * MediaPipe factory's GPU->CPU fallback. The scan runs on background threads
 * with no EGL context; OpenCL buffers need none (only the GL backend's
 * textures would).
 */
class AndroidLiteRtHandLandmarkerFactory @Inject constructor(private val context: Context) : HandLandmarkerFactory {
    override fun createFromOptions(options: HandLandmarkerOptions): HandLandmarker {
        val landmarker: AndroidLiteRtHandLandmarker
        measureTimeMillis {
            landmarker = AndroidLiteRtHandLandmarker(context, options)
        }.also {
            Log.d(TAG, "Initialized LiteRT hand pipeline in $it ms (GPU first, CPU fallback)")
        }
        return landmarker
    }

    companion object {
        private const val TAG = "LiteRtHandLandmarker"
    }
}

/**
 * Android LiteRT pipeline: RTMDet letterbox -> multi-rotation RTMPose search ->
 * gesture classifier (the gate). Same pipeline shape and tuned thresholds as
 * the ONNX provider, so dataset expectations transfer; see
 * `OnnxHandLandmarkDatasetTest` -> `LiteRtHandLandmarkDatasetTest`.
 */
class AndroidLiteRtHandLandmarker internal constructor(context: Context, private val options: HandLandmarkerOptions) : HandLandmarker {

    private val detector = GpuFirstRunner(context, LiteRtRtmModels.DETECTOR_ASSET)
    private val pose = GpuFirstRunner(context, LiteRtRtmModels.RTMPOSE_ASSET)

    // Preprocessing tensors (zero per-frame allocation; see ImageTensor).
    private val detectorTensor = ImageTensor(
        DETECTOR_SIZE,
        DETECTOR_SIZE,
        // mmdet normalization (123.675/58.395 ...) == ImageNet mean/std scaled
        // to 0..1; ImageTensor divides pixels by 255 first, so the equivalence
        // is exact (verified against the ONNX pipeline's bitmapToCHWMeanStd).
        mean = ImageTensor.IMAGENET_MEAN,
        std = ImageTensor.IMAGENET_STD,
        layout = ImageTensor.Layout.NCHW,
        channelOrder = ImageTensor.ChannelOrder.RGB,
    )
    private val poseTensor = ImageTensor(
        RTMPOSE_SIZE,
        RTMPOSE_SIZE,
        mean = ImageTensor.IMAGENET_MEAN,
        std = ImageTensor.IMAGENET_STD,
        layout = ImageTensor.Layout.NCHW,
        channelOrder = ImageTensor.ChannelOrder.BGR,
    )

    override fun detect(candidate: LandmarkCandidate): LandmarkedImage {
        var detectedIn = 0L
        val hands: List<LandmarkedImage.Hand>
        measureTimeMillis {
            hands = runPipeline(candidate)
        }.also { detectedIn = it }
        return LandmarkedImage(hands = hands, detectedInMs = detectedIn)
    }

    // --- Pipeline: RTMDet boxes -> multi-rot RTMPose search -> gesture rate ---

    private fun runPipeline(bitmap: LandmarkCandidate): List<LandmarkedImage.Hand> {
        val boxes = detectBoxes(bitmap) // pixel coords in the original image
        val hands = ArrayList<LandmarkedImage.Hand>()
        for ((box, score) in boxes.take(options.maxNumHands)) {
            if (score < options.minHandDetectionConfidence) continue
            rtmposeRating(bitmap, box)?.let { hands.add(it) }
        }
        // Edge fallback: a hand mostly out of frame at the left/right image
        // edge is invisible to full-frame RTMDet (5_kimbo — the thumb is in
        // shot, the rest off-frame). Probe the edge strips for a confident
        // thumb chain and rate it as a low-certainty THUMBS guess
        // (edgeDetected hands take the classifier's thumb-only path).
        if (hands.isEmpty()) {
            val hint = boxes.firstOrNull()?.second ?: 0f
            hands.addAll(edgeThumbFallback(bitmap, hint).take(options.maxNumHands))
        }
        return hands
    }

    // --- Detection stage: RTMDet letterbox (320x320, top-left pad, mean/std) ---

    private fun detectBoxes(bitmap: Bitmap): List<Pair<IntArray, Float>> {
        val iw = bitmap.width
        val ih = bitmap.height
        val ratio = min(DETECTOR_SIZE / iw.toFloat(), DETECTOR_SIZE / ih.toFloat())
        val nw = max((iw * ratio).toInt(), 1)
        val nh = max((ih * ratio).toInt(), 1)
        val resized = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val padded = Bitmap.createBitmap(DETECTOR_SIZE, DETECTOR_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(padded).drawBitmap(resized, 0f, 0f, Paint())
        // Top-left padded bitmap is already DETECTOR_SIZE x DETECTOR_SIZE, so
        // STRETCH is an identity resize; only the channel split happens here.
        val input = detectorTensor.load(padded)

        // Raw anchors boxes [1,2100,4] + scores [1,2100,1] in 320-space
        // (top-left padded). Post-processing (score filter + NMS) uses the
        // baked export's params via LiteRtRtmModels.
        val (boxesRaw, scoresRaw) = detector.run { runner ->
            runner.writeInput(0, input)
            runner.run()
            runner.readOutput(0) to runner.readOutput(1)
        }
        val keep = LiteRtRtmModels.nmsAnchors(boxesRaw, scoresRaw)
        val boxes = ArrayList<Pair<IntArray, Float>>(keep.size)
        for (i in keep) {
            val o = i * 4
            val score = scoresRaw[i]
            val bx0 = max((boxesRaw[o] / ratio).toInt(), 0)
            val by0 = max((boxesRaw[o + 1] / ratio).toInt(), 0)
            val bx1 = min((boxesRaw[o + 2] / ratio).toInt(), iw)
            val by1 = min((boxesRaw[o + 3] / ratio).toInt(), ih)
            if (bx1 - bx0 < MIN_BOX_SIDE || by1 - by0 < MIN_BOX_SIDE) continue
            boxes.add(intArrayOf(bx0, by0, bx1, by1) to score)
        }
        boxes.sortByDescending { it.second }
        return boxes
    }

    // --- Landmark stage: multi-rotation RTMPose search on the expanded box ---

    /**
     * Runs RTMPose hand on the expanded square box at rotations {0, 90, 180,
     * 270} and returns the best hand. The rotation search prefers a rotation
     * that forms a recognized gesture; if NO rotation does, the highest-
     * confidence hand is still returned so the scan's rater can decide
     * (normally it rejects it; the dev-mode best-guess rater guesses). The
     * only hard gate here is [HandLandmarkerOptions.minHandKpConfidence] —
     * below it a box is not a hand at all (no_score stays filtered).
     *
     * The image-space (deg 0) rating is the ground truth for normally-photographed
     * hands — it correctly rates thumbs-up (5_kenya, 5_trope), thumbs-down
     * (1_coffee) and the OK signs. Rotated crops are only a fallback for hands
     * RTMPose cannot rate upright (dorsal/side views like 4/4, which reads
     * THUMBS-4 at 90°).
     *
     * The crop is tried at two expansions (1.2x and 0.85x): the 0.85x crop
     * isolates the hand when the detected box is tall/thin and mostly
     * background, while the image-space-preference rule keeps the 1.2x
     * ratings that are already correct. Verified on plans/samples (see
     * plans/benchmarks/rtmpose_only_v5.py).
     *
     * The returned hand keeps the points in IMAGE-PIXEL space (crop-space
     * keypoints offset by the crop origin). This is an isometry of the crop
     * frame the classifier's thresholds were tuned in (the Python benchmark
     * classifies raw crop pixels), so the gesture distances/angles are
     * undistorted regardless of image aspect ratio. Storage code un-rotates
     * via [LandmarkedImage.Hand.rotationDegrees] and normalizes to 0..1.
     */
    private fun rtmposeRating(bitmap: Bitmap, box: IntArray): LandmarkedImage.Hand? {
        val iw = bitmap.width
        val ih = bitmap.height
        val candidates = CANDIDATE_ROTATIONS

        var imageSpace: HandWithScore? = null
        var bestRotated: HandWithScore? = null
        // Highest-confidence hand across ALL rotations, including those that
        // form no gesture: the dev-mode best-guess rater still rates those, so
        // the pipeline must not drop them here.
        var bestKp: HandWithScore? = null
        search@ for (factor in FALLBACK_FACTORS) {
            val square = expandSquare(box, iw, ih, factor)
            val cx = (square[0] + square[2]) / 2f
            val cy = (square[1] + square[3]) / 2f
            val side = (square[2] - square[0]).toFloat()
            for (deg in candidates) {
                val (crop, _) = rotateAndCropRectangle(bitmap, cx, cy, side, side, deg) ?: continue
                val kps = rtmposeLandmarks(crop) ?: continue
                val points = ArrayList<LandmarkedImage.Point>(NUM_LANDMARKS)
                var kpSum = 0f
                for (i in 0 until NUM_LANDMARKS) {
                    points.add(
                        LandmarkedImage.Point(
                            // Image-pixel space (crop origin + crop-space
                            // keypoint): isotropic, so the classifier's
                            // distance ratios/angles are undistorted regardless
                            // of the image aspect ratio (see above).
                            x = square[0] + kps[i * 3],
                            y = square[1] + kps[i * 3 + 1],
                            z = kps[i * 3 + 2],
                        ),
                    )
                    kpSum += kps[i * 3 + 2]
                }
                val hand = LandmarkedImage.Hand(points, rotationDegrees = deg)
                val kpMean = kpSum / NUM_LANDMARKS
                val result = HandWithScore(hand, HandGestureClassifier.classify(hand), kpMean)
                if (bestKp == null || result.kpMean > bestKp.kpMean) bestKp = result
                val classification = result.cls ?: continue
                if (deg == 0f) {
                    if (imageSpace == null || result.kpMean > imageSpace.kpMean) imageSpace = result
                    // Early exit: a confident upright read on the standard
                    // 1.2x crop is the guaranteed winner (image-space
                    // preference below), so the rotated sweep and the 0.85x
                    // pass cannot change the outcome — skip them. Verified in
                    // plans/benchmarks/rot_analysis.py: identical winners on
                    // all 33 samples, RTMPose runs 204 -> 123 (-40%). The
                    // genuinely slow images (2_coffee, 2_peanuts, the
                    // no_score_holding_* set) have low deg-0 confidence and
                    // still take the full search.
                    if (factor == BOX_EXPANSION && imageSpace.kpMean >= MIN_RTMPOSE_KP) {
                        break@search
                    }
                    continue
                }
                val better = bestRotated == null ||
                    run {
                        val currentCls = bestRotated.cls ?: return@run true
                        when {
                            // A THUMBS beats any other gesture (the fallback exists to rate
                            // the thumbs-up geometry the rotated crops see correctly).
                            classification.gesture == HandGesture.THUMBS_UP &&
                                currentCls.gesture != HandGesture.THUMBS_UP -> true

                            classification.gesture != HandGesture.THUMBS_UP &&
                                currentCls.gesture == HandGesture.THUMBS_UP -> false
                            // Among THUMBS pick the highest score; tie-break on confidence.
                            classification.gesture == HandGesture.THUMBS_UP ->
                                classification.score.score > currentCls.score.score ||
                                    (classification.score.score == currentCls.score.score && result.kpMean > bestRotated.kpMean)
                            // Non-THUMBS gestures: trust the most confident one.
                            else -> result.kpMean > bestRotated.kpMean
                        }
                    }
                if (better) bestRotated = result
            }
        }
        // Prefer the image-space rating, but only when RTMPose is reasonably
        // confident in those keypoints; a low-confidence upright crop can mis-
        // rate, and the rotated search then has better data to work with. When
        // no rotation forms a gesture at all, fall back to the highest-
        // confidence hand (the dev-mode rater can still guess from it).
        val winner = when {
            imageSpace != null && imageSpace.kpMean >= MIN_RTMPOSE_KP -> imageSpace
            // don't pick the winner that will fail the confidence check
            bestRotated.takeIf { (it?.kpMean ?: 0f) >= options.minHandKpConfidence } != null -> bestRotated
            imageSpace.takeIf { (it?.kpMean ?: 0f) >= options.minHandKpConfidence } != null -> imageSpace
            bestKp.takeIf { (it?.kpMean ?: 0f) >= options.minHandKpConfidence } != null -> bestKp
            else -> null
        } ?: return null

        // Confidence tiers replace the sparse presence gate. Below the floor
        // the box is not a hand at all (no_score stays filtered); ROCK/OK need
        // higher confidence than THUMBS (a curled-finger read at low kp is
        // usually a holding/gripping false positive). Hands with no recognized
        // gesture use the non-THUMBS gate (the dev-mode rater marks its guess
        // uncertain regardless).
        if (winner.kpMean < options.minHandKpConfidence) return null
        val confidentKp = when (winner.cls?.gesture) {
            HandGesture.THUMBS_UP -> options.minHandKpConfidence
            else -> options.minHandConfidentKpConfidence
        }
        return winner.hand.copy(uncertain = winner.kpMean < confidentKp)
    }

    // --- Edge thumb-only fallback (partial hands mostly out of frame) -------

    /**
     * Probes the left and right edge strips for a partial hand whose thumb
     * chain is confidently pointing up. At most one hand per edge. Only runs
     * when the main pass found nothing (see [runPipeline]). The gates live in
     * [HandGestureClassifier] (EDGE_* constants) and are reached by
     * classifying each read with edgeDetected = true — the strip detector must
     * fire above the detection gate and the read above the kp floor.
     */
    private fun edgeThumbFallback(bitmap: Bitmap, fullFrameBest: Float): List<LandmarkedImage.Hand> {
        // The full-frame detector already ran for the main pass; an edge hand
        // still leaves a weak full-frame response (5_kimbo: 0.167), so below
        // the hint threshold the image has no hand signal at all — skip.
        if (fullFrameBest < EDGE_HINT_DET) return emptyList()
        val iw = bitmap.width
        val ih = bitmap.height
        val stripW = max((iw * EDGE_STRIP_FRACTION).toInt(), 1)
        val hands = ArrayList<LandmarkedImage.Hand>()
        for (side in 0..1) {
            val x0 = if (side == 0) 0 else iw - stripW
            for ((fb, ft) in EDGE_BANDS) {
                val y0 = (ih * fb).toInt()
                val y1 = (ih * ft).toInt()
                val strip = padStripToSquare(bitmap, x0, y0, stripW, y1 - y0)
                val boxes = detectBoxes(strip)
                if (boxes.isEmpty() ||
                    boxes.first().second < options.minHandDetectionConfidence
                ) {
                    continue
                }
                val (box, _) = boxes.first()
                val imBox = intArrayOf(x0 + box[0], y0 + box[1], x0 + box[2], y0 + box[3])
                edgeThumbScan(bitmap, imBox)?.let {
                    hands.add(it)
                    break
                }
            }
        }
        return hands
    }

    /**
     * Runs RTMPose on an edge-hugging box at all rotations and returns the
     * best read whose thumb chain qualifies as a partial-hand thumbs-up (the
     * classifier's thumb-only path), or null. The hand is marked uncertain
     * (the off-frame fingers are unreliable — the score is a best guess).
     */
    private fun edgeThumbScan(bitmap: Bitmap, box: IntArray): LandmarkedImage.Hand? {
        val iw = bitmap.width
        val ih = bitmap.height
        val square = expandSquare(box, iw, ih, BOX_EXPANSION)
        val cx = (square[0] + square[2]) / 2f
        val cy = (square[1] + square[3]) / 2f
        val side = (square[2] - square[0]).toFloat()
        var best: LandmarkedImage.Hand? = null
        var bestThumbConf = 0f
        var bestKpMean = 0f
        for (deg in CANDIDATE_ROTATIONS) {
            val (crop, _) = rotateAndCropRectangle(bitmap, cx, cy, side, side, deg) ?: continue
            val kps = rtmposeLandmarks(crop) ?: continue
            val points = ArrayList<LandmarkedImage.Point>(NUM_LANDMARKS)
            var kpSum = 0f
            var thumbSum = 0f
            for (i in 0 until NUM_LANDMARKS) {
                points.add(
                    LandmarkedImage.Point(
                        x = square[0] + kps[i * 3],
                        y = square[1] + kps[i * 3 + 1],
                        z = kps[i * 3 + 2],
                    ),
                )
                kpSum += kps[i * 3 + 2]
                if (i in 1..4) thumbSum += kps[i * 3 + 2]
            }
            val hand = LandmarkedImage.Hand(
                points,
                rotationDegrees = deg,
                edgeDetected = true,
            )
            if (HandGestureClassifier.classify(hand)?.gesture != HandGesture.THUMBS_UP) {
                continue
            }
            val kpMean = kpSum / NUM_LANDMARKS
            val thumbConf = thumbSum / 4f
            // Prefer the most confident thumb chain, then kp mean (mirrors the
            // Python edge_thumb_scan selection).
            if (best == null ||
                thumbConf > bestThumbConf ||
                (thumbConf == bestThumbConf && kpMean > bestKpMean)
            ) {
                best = hand
                bestThumbConf = thumbConf
                bestKpMean = kpMean
            }
            // Deg-0-first acceptance: the upright read is the only
            // gate-qualifying rotation on the edge sample (5_kimbo — deg 0
            // passes the thumb-chain gates at angle 22.4°, 90/180/270 fail),
            // so accept it immediately and skip the rotated sweep. The sweep
            // still runs when deg 0 fails the gates (a sideways thumb can
            // still qualify rotated). Prunes 4 RTMPose runs to 1 per strip.
            if (deg == 0f) break
        }
        return best?.copy(uncertain = true)
    }

    /** Crops [x0, y0, w, h] from the image and pads it to a square with gray. */
    private fun padStripToSquare(image: Bitmap, x0: Int, y0: Int, w: Int, h: Int): Bitmap {
        val crop = Bitmap.createBitmap(image, x0, y0, w, h)
        val side = max(w, h)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(crop, 0f, 0f, Paint())
        return out
    }

    private data class HandWithScore(val hand: LandmarkedImage.Hand, val cls: GestureClassification?, val kpMean: Float)

    /**
     * RTMPose hand (original mmdeploy export): top-down affine to 256x256,
     * BGR-order input normalized with RGB mean/std (matches the verified
     * Python). The converted tflite outputs raw heatmaps simcc_x/simcc_y
     * [1, 21, 512]; decode = argmax / simcc_split_ratio (2.0) mapped back
     * through the inverse affine to crop pixels, with min(peak_x, peak_y) as
     * confidence (LiteRtRtmModels.decodeSimcc).
     */
    private fun rtmposeLandmarks(crop: Bitmap): FloatArray? {
        val w = crop.width
        val h = crop.height
        val warped = topDownAffine(crop)
        val input = poseTensor.load(warped)

        val (simccX, simccY) = pose.run { runner ->
            runner.writeInput(0, input)
            runner.run()
            runner.readOutput(0) to runner.readOutput(1)
        }
        val kps256 = LiteRtRtmModels.decodeSimcc(simccX, simccY)

        val out = FloatArray(NUM_LANDMARKS * 3)
        // Inverse of the top-down affine (see topDownAffine): undo the scale
        // about the crop center to map decoded 256-space bins to crop pixels.
        val bboxW = 1.25f * w
        val bboxH = 1.25f * h
        val scale = RTMPOSE_SIZE / max(bboxH * 0.75f, bboxW)
        val cx = w / 2f
        val cy = h / 2f
        for (i in 0 until NUM_LANDMARKS) {
            out[i * 3] = (kps256[i * 3] - RTMPOSE_SIZE / 2f) / scale + cx
            out[i * 3 + 1] = (kps256[i * 3 + 1] - RTMPOSE_SIZE / 2f) / scale + cy
            // Confidence: min of the two heatmap peaks (the baked decode's
            // score is exactly this).
            out[i * 3 + 2] = kps256[i * 3 + 2]
        }
        return out
    }

    /**
     * Port of the mmpose top-down affine for rotation 0 (see
     * plans/benchmarks/landmark_stage.md): scale the crop about its center so
     * the max(0.75h, 1.25w)-scaled box fills 256x256, black-filled borders.
     */
    private fun topDownAffine(crop: Bitmap): Bitmap {
        val w = crop.width.toFloat()
        val h = crop.height.toFloat()
        val bboxW = 1.25f * w
        val bboxH = 1.25f * h
        val wScaled = max(bboxH * 0.75f, bboxW)
        val scale = RTMPOSE_SIZE / wScaled
        val cx = w / 2f
        val cy = h / 2f
        val matrix = Matrix()
        matrix.postScale(scale, scale, cx, cy)
        matrix.postTranslate(RTMPOSE_SIZE / 2f - cx, RTMPOSE_SIZE / 2f - cy)
        val out = Bitmap.createBitmap(RTMPOSE_SIZE, RTMPOSE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(crop, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Expands a box around its center and makes it square (UNCLAMPED). */
    private fun expandSquare(box: IntArray, iw: Int, ih: Int, factor: Float = BOX_EXPANSION): IntArray {
        val (x1, y1, x2, y2) = box
        val cx = (x1 + x2) / 2f
        val cy = (y1 + y2) / 2f
        val w = (x2 - x1) * factor
        val h = (y2 - y1) * factor
        val side = max(w, h)
        return intArrayOf(
            (cx - side / 2).toInt(),
            (cy - side / 2).toInt(),
            (cx + side / 2).toInt(),
            (cy + side / 2).toInt(),
        )
    }

    // --- Bitmap helpers (port of the verified Python preprocessing) ---

    private fun rotateAndCropRectangle(
        image: Bitmap,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        degree: Float,
    ): Pair<Bitmap, FloatArray>? {
        val ih = image.height
        val iw = image.width
        val size = (sqrt((iw * iw + ih * ih).toDouble()).toInt() + 2) * 2
        val padded = padImage(image, size, size)
        val cxP = cx + abs(size - iw) / 2f
        val cyP = cy + abs(size - ih) / 2f

        val bb = boundingBoxFromRotatedRect(cxP, cyP, width, height, degree)
        val crop = cropRect(padded, bb[0], bb[1], bb[2], bb[3]) ?: return null
        val wDiff = (crop.width - width.toInt() + 1).toFloat()
        val hDiff = (crop.height - height.toInt() + 1).toFloat()

        val rotated = imageRotationWithoutCrop(crop, degree)
        val ccx = rotated.width / 2
        val ccy = rotated.height / 2
        val rw = width.toInt()
        val rh = height.toInt()
        // cropRect expects the CENTER (it subtracts half the size internally), so
        // pass ccx/ccy directly — passing ccx - rw/2 double-subtracted and made
        // every tall hand crop out-of-bounds (y0 < 0), killing all detections.
        val final = cropRect(rotated, ccx, ccy, rw, rh) ?: return null
        return final to floatArrayOf(wDiff, hDiff)
    }

    private fun padImage(image: Bitmap, tw: Int, th: Int): Bitmap {
        val padded = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val startH = th / 2 - image.height / 2
        val startW = tw / 2 - image.width / 2
        Canvas(padded).drawBitmap(image, startW.toFloat(), startH.toFloat(), Paint())
        return padded
    }

    private fun cropRect(image: Bitmap, cx: Int, cy: Int, width: Int, height: Int): Bitmap? {
        val x0 = cx - width / 2
        val y0 = cy - height / 2
        if (x0 < 0 || y0 < 0 || x0 + width > image.width || y0 + height > image.height) return null
        return Bitmap.createBitmap(image, x0, y0, width, height)
    }

    /** Upright bounding box of a rotated rect: [cx, cy, width, height] in pixels. */
    private fun boundingBoxFromRotatedRect(cx: Float, cy: Float, w: Float, h: Float, degree: Float): IntArray {
        val theta = Math.toRadians(degree.toDouble())
        val cosT = cos(theta)
        val sinT = sin(theta)
        val hw = w / 2f
        val hh = h / 2f
        // Four corners of the rotated rect (cv2.boxPoints equivalent).
        val xs = DoubleArray(4)
        val ys = DoubleArray(4)
        xs[0] = cx + hw * cosT - hh * sinT
        ys[0] = cy + hw * sinT + hh * cosT
        xs[1] = cx - hw * cosT - hh * sinT
        ys[1] = cy - hw * sinT + hh * cosT
        xs[2] = cx - hw * cosT + hh * sinT
        ys[2] = cy - hw * sinT - hh * cosT
        xs[3] = cx + hw * cosT + hh * sinT
        ys[3] = cy + hw * sinT - hh * cosT
        val minX = floor(xs.min()).toInt()
        val maxX = floor(xs.max()).toInt() + 1
        val minY = floor(ys.min()).toInt()
        val maxY = floor(ys.max()).toInt() + 1
        val cxx = (minX + maxX) / 2
        val cyy = (minY + maxY) / 2
        return intArrayOf(cxx, cyy, maxX - minX, maxY - minY)
    }

    /**
     * Rotates the image about its center without cropping (expands the canvas).
     */
    private fun imageRotationWithoutCrop(image: Bitmap, degree: Float): Bitmap {
        val w = image.width
        val h = image.height
        val theta = Math.toRadians(degree.toDouble())
        val absCos = abs(cos(theta))
        val absSin = abs(sin(theta))
        val boundW = (h * absSin + w * absCos).toInt()
        val boundH = (h * absCos + w * absSin).toInt()
        val matrix = Matrix()
        matrix.postRotate(-degree, w / 2f, h / 2f)
        matrix.postTranslate(boundW / 2f - w / 2f, boundH / 2f - h / 2f)
        val out = Bitmap.createBitmap(boundW, boundH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(image, matrix, Paint())
        return out
    }

    override fun close() {
        detector.close()
        pose.close()
        detectorTensor.release()
        poseTensor.release()
    }

    /**
     * Owns one CompiledModelRunner that prefers the GPU (OpenCL, FP32 — the
     * accuracy-safe config from LiteRtOnDeviceVerificationTest) and demotes to
     * CPU when the GPU cannot compile the graph (no OpenCL on emulators/some
     * devices) or fails at runtime. Serial calls only (the scan runs one
     * detect() at a time).
     */
    private inner class GpuFirstRunner(private val appContext: Context, private val asset: String) {
        /** False once the GPU is known-unusable (compile failure at init). */
        private var gpuActive = true

        @Volatile
        var runner: CompiledModelRunner = create()
            private set

        private var demoted = false

        private fun create(): CompiledModelRunner = try {
            CompiledModelRunner.fromAssets(appContext, asset, gpuOptions()).also {
                Log.d(TAG, "$asset compiled on GPU (OpenCL, FP32)")
            }
        } catch (e: LiteRtException) {
            gpuActive = false
            Log.w(TAG, "$asset GPU compile failed, falling back to CPU", e)
            CompiledModelRunner.fromAssets(appContext, asset, CompiledModel.Options(Accelerator.CPU))
        }

        private fun gpuOptions() = CompiledModel.Options(Accelerator.GPU).apply {
            gpuOptions = CompiledModel.GpuOptions(
                backend = CompiledModel.GpuOptions.Backend.OPENCL,
                precision = CompiledModel.GpuOptions.Precision.FP32,
            )
        }

        /**
         * Runs [block] on the current runner; demotes to CPU once when the GPU
         * fails at runtime. Only a GPU runner may demote — a CPU runner (GPU
         * compile already failed at init) that throws is a real error, not a
         * fallback opportunity.
         */
        fun <T> run(block: (CompiledModelRunner) -> T): T = try {
            block(runner)
        } catch (e: LiteRtException) {
            if (!demoted && gpuActive) {
                demoted = true
                Log.w(TAG, "$asset GPU failed at runtime, demoting to CPU", e)
                val cpu = CompiledModelRunner.fromAssets(
                    appContext,
                    asset,
                    CompiledModel.Options(Accelerator.CPU),
                )
                val old = runner
                runner = cpu
                old.close()
                block(cpu)
            } else {
                throw e
            }
        }

        fun close() = runner.close()
    }

    private companion object {
        private const val TAG = "LiteRtHandLandmarker"

        // Minimum mean RTMPose keypoint confidence for the deg-0 (image-space)
        // rating to be trusted over the rotated search's best rating.
        const val MIN_RTMPOSE_KP = 0.3f
        const val DETECTOR_SIZE = 320
        const val RTMPOSE_SIZE = 256

        // Detected boxes are expanded 1.2x around the center and made square so
        // the crops include the whole hand (mirrors the verified Python
        // pipeline). The search additionally tries a 0.85x (tighter) crop so
        // tall/thin detection boxes don't drown the hand in background.
        const val BOX_EXPANSION = 1.2f
        val FALLBACK_FACTORS = floatArrayOf(1.2f, 0.85f)

        // Edge thumb-only fallback: a single half-width strip per edge over
        // the lower 60% of the image (tuned so 5_kimbo's left-edge hand is
        // found at det 0.46, thumbC 0.66, while no_score strips fire
        // nothing). Tradeoff: an edge hand in the top 40% would be missed
        // (re-add bands if a sample needs it).
        const val EDGE_STRIP_FRACTION = 0.5f
        val EDGE_BANDS = listOf(0.4f to 1f)

        // Skip the fallback when the full-frame detector's best box is below
        // this hint (5_kimbo leaves a 0.167 full-frame response). Kept at
        // 0.10, not higher: the zoomed strip detection is largely independent
        // of the full-frame response, so a hand even more out of frame could
        // score < 0.12 full-frame yet still read a confident thumb in an edge
        // strip. 0.10 still skips genuine no-hand photos (their detector
        // noise is far lower), making them cost 0 strip detections.
        const val EDGE_HINT_DET = 0.10f
        val CANDIDATE_ROTATIONS = listOf(0f, 90f, 180f, 270f)

        // Minimum side length (px) for a box to be worth landmarking.
        const val MIN_BOX_SIDE = 8
        const val NUM_LANDMARKS = 21
    }
}
