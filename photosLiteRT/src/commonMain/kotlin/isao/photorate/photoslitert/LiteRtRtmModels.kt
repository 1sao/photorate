package isao.photorate.photoslitert

import isao.photorate.photoslitert.LiteRtRtmModels.NUM_LANDMARKS
import isao.photorate.photoslitert.LiteRtRtmModels.SIMCC_SPLIT_RATIO
import kotlin.math.min

/**
 * Model contract for the LiteRT-converted RTM hand models (see `ml/litert/` recipe):
 *
 * - Detector: RTMDet-nano hand, `end2end_f32clean.onnx` -> `rtmdet_hand_320_f32.tflite`.
 *   float32 NCHW input `input` [1,3,320,320]; raw anchor outputs boxes [1,2100,4] +
 *   scores [1,2100,1] in 320-space (the baked NMS + sort tail were cut caller-side —
 *   the app's `AndroidOnnxHandLandmarker.detectBoxes()` NMS params are the original
 *   export's: iou=0.6, score_thr=0.05, max_out=200).
 * - RTMPose: RTMPose-m hand SimCC, `end2end_f32clean.onnx` ->
 *   `rtmpose_hand_256_f32.tflite`. float32 NCHW input `input` [1,3,256,256]; raw
 *   SimCC outputs simcc_x/simcc_y [1,21,512] (split ratio 2.0, decoded caller-side).
 *
 * Verification of the conversions lives in `LiteRtOnDeviceVerificationTest` (app
 * androidTest) against reference dumps in `ml/litert/refs` (host-generated, see
 * `ml/litert/scripts/dump_refs.py`).
 */
object LiteRtRtmModels {
    const val DETECTOR_ASSET = "rtmdet_hand_320_f32.tflite"
    const val RTMPOSE_ASSET = "rtmpose_hand_256_f32.tflite"

    // Raw anchor output shapes (NCHW; outputs are transposed to NHWC on device).
    const val DETECTOR_ANCHORS = 2100
    const val NUM_LANDMARKS = 21
    const val SIMCC_SIZE = 512
    const val SIMCC_SPLIT_RATIO = 2f
    const val RTMPOSE_SIZE = 256
    const val DETECTOR_SIZE = 320

    // Caller-side NMS must match the original baked export's params exactly.
    const val NMS_IOU_THR = 0.6f
    const val NMS_SCORE_THR = 0.05f
    const val NMS_MAX_OUT = 200

    /**
     * Decodes raw SimCC outputs into 21 keypoints in 256-space, mirroring the app's
     * `AndroidOnnxHandLandmarker.rtmposeLandmarks()` decode: per-axis argmax over 512
     * bins divided by [SIMCC_SPLIT_RATIO], confidence = min of the two peaks.
     * Returns [NUM_LANDMARKS] * 3 floats: x, y, conf.
     */
    fun decodeSimcc(simccX: FloatArray, simccY: FloatArray): FloatArray {
        require(simccX.size == NUM_LANDMARKS * SIMCC_SIZE && simccY.size == simccX.size) {
            "unexpected SimCC size ${simccX.size}"
        }
        val out = FloatArray(NUM_LANDMARKS * 3)
        for (i in 0 until NUM_LANDMARKS) {
            var xi = 0
            var yi = 0
            var xv = simccX[i * SIMCC_SIZE]
            var yv = simccY[i * SIMCC_SIZE]
            for (b in 1 until SIMCC_SIZE) {
                val vx = simccX[i * SIMCC_SIZE + b]
                val vy = simccY[i * SIMCC_SIZE + b]
                if (vx > xv) {
                    xv = vx
                    xi = b
                }
                if (vy > yv) {
                    yv = vy
                    yi = b
                }
            }
            out[i * 3] = xi / SIMCC_SPLIT_RATIO
            out[i * 3 + 1] = yi / SIMCC_SPLIT_RATIO
            out[i * 3 + 2] = min(xv, yv)
        }
        return out
    }

    /**
     * Caller-side NMS over the raw anchors (iou=0.6 / thr=0.05 / max 200, exactly the
     * baked export's params). Returns kept indices in descending-score order.
     */
    fun nmsAnchors(boxes: FloatArray, scores: FloatArray): IntArray {
        val kept = MathOps.nms(boxes, scores, NMS_IOU_THR, NMS_SCORE_THR)
        return kept.take(NMS_MAX_OUT).toIntArray()
    }
}
