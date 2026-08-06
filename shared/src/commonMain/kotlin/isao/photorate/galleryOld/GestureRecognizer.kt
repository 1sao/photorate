package isao.photorate.galleryOld

import kotlin.math.PI
import kotlin.math.atan2

sealed class GestureResult {
    data class Success(
        val landmarks: List<List<Landmark>>,
        val gestures: List<List<GestureCategory>>,
        val handedness: List<List<GestureCategory>>,
        val thumbRating: Int = 0,
    ) : GestureResult()
    data class Error(val message: String) : GestureResult()
    data object Empty : GestureResult()
}

data class Landmark(val x: Float, val y: Float, val z: Float)

data class GestureCategory(val index: Int, val score: Float, val categoryName: String?, val displayName: String?)

/**
 * Calculates thumb rating (1-5) from hand landmarks.
 * 1 = thumb fully down, 5 = thumb fully up.
 *
 * MediaPipe hand landmarks for thumb:
 * 0: WRIST, 1: THUMB_CMC, 2: THUMB_MCP, 3: THUMB_IP, 4: THUMB_TIP
 */
fun calculateThumbRating(landmarks: List<Landmark>): Int {
    if (landmarks.size < 5) return 0

    val wrist = landmarks[0]
    val thumbTip = landmarks[4]

    // Calculate angle of thumb tip relative to wrist
    // In image coordinates: y increases downward
    val dx = thumbTip.x - wrist.x
    val dy = thumbTip.y - wrist.y

    // atan2 returns angle in radians, convert to degrees
    // Angle from horizontal: 0° = right, 90° = down, -90° = up
    val angleRad = atan2(dy.toDouble(), dx.toDouble())
    val angleDeg = angleRad * 180.0 / PI

    // Map angle to rating 1-5:
    // Thumb down: angle ~90° (pointing down) → rating 1
    // Thumb up: angle ~-90° (pointing up) → rating 5
    // Thumb horizontal: angle ~0° → rating 3

    return when {
        angleDeg > 60 -> 1 // pointing down
        angleDeg > 30 -> 2 // pointing somewhat down
        angleDeg > -30 -> 3 // roughly horizontal
        angleDeg > -60 -> 4 // pointing somewhat up
        else -> 5 // pointing up
    }
}

interface GestureRecognizer {
    fun recognize(imageData: ByteArray, width: Int, height: Int): GestureResult
    fun close()
}
