package isao.photorate.imageRecognition.classify

import kotlinx.serialization.Serializable

/**
 * Result of a hand-landmark run, in IMAGE-PIXEL space: the isotropic frame the gesture classifier's
 * ratios/angles are tuned on, regardless of the provider (MediaPipe wraps normalized landmarks back
 * to pixels; ONNX works in pixels natively). Storage code normalizes to 0..1 via
 * [LandmarkCandidate] dimensions.
 */
data class LandmarkedImage(val hands: List<Hand>, val detectedInMs: Long) {
  data class Hand(
    val points: List<Point>,
    /**
     * For the ONNX pipeline: the rotation (degrees) of the points relative to image up when the
     * hand was rated. 0 for the MediaPipe pipeline and for image-space (deg 0) hands. Callers that
     * store display geometry can un-rotate by this amount to get true image-space landmarks.
     */
    val rotationDegrees: Float = 0f,
    /**
     * True when this hand was rated at reduced confidence (e.g. the sparse presence gate was below
     * the confident threshold, or the rating came from low-confidence keypoints). Such hands carry
     * a best-guess score that the user should review; the UI surfaces them in the "uncertain"
     * section. Always false for the MediaPipe pipeline.
     */
    val uncertain: Boolean = false,
    /**
     * True when this hand was only found by the ONNX pipeline's edge fallback (a hand mostly out of
     * frame at the left/right image edge — only the thumb is in shot). The off-frame fingers are
     * unreliable, so [HandGestureClassifier] rates these via a thumb-only path instead of the
     * normal gesture gates. Always false for the MediaPipe pipeline.
     */
    val edgeDetected: Boolean = false,
  )

  @Serializable data class Point(val x: Float, val y: Float, val z: Float)
}
