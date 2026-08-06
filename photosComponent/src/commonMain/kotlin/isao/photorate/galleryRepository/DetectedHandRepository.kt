package isao.photorate.galleryRepository

import isao.photorate.db.DetectedHand
import kotlinx.coroutines.flow.Flow

interface DetectedHandRepository {
    fun getHandsForImage(imageUri: String): Flow<List<DetectedHand>>
    suspend fun insertHand(hand: DetectedHand)

    /** Replaces the single user-entered (fake) hand for an image, keeping real detections. */
    suspend fun upsertUserRatedHand(hand: DetectedHand)

    /** Removes only the real (detected) hands for an image, keeping user ratings. */
    suspend fun deleteRealHandsForImage(imageUri: String)

    suspend fun deleteAll()
}

// data class DetectedHandRow(
//     val imageUri: String,
//     val handIndex: Long,
//     val score: Double,
//     val bboxMinX: Double,
//     val bboxMinY: Double,
//     val bboxMaxX: Double,
//     val bboxMaxY: Double,
//     val bboxAreaFraction: Double,
//     val centroidX: Double,
//     val centroidY: Double,
//     val points: ByteArray,
// ) {
//     override fun equals(other: Any?): Boolean {
//         // return super.equals(other) //TODO use super.equals(other) + points?
//
//         if (this === other) return true
//         if (other !is DetectedHandRow) return false
//         return imageUri == other.imageUri &&
//             handIndex == other.handIndex &&
//             score == other.score &&
//             bboxMinX == other.bboxMinX &&
//             bboxMinY == other.bboxMinY &&
//             bboxMaxX == other.bboxMaxX &&
//             bboxMaxY == other.bboxMaxY &&
//             bboxAreaFraction == other.bboxAreaFraction &&
//             centroidX == other.centroidX &&
//             centroidY == other.centroidY &&
//             points.contentEquals(other.points)
//     }
//
//     override fun hashCode(): Int {
//         var result = imageUri.hashCode()
//         result = 31 * result + handIndex.hashCode()
//         result = 31 * result + score.hashCode()
//         result = 31 * result + bboxMinX.hashCode()
//         result = 31 * result + bboxMinY.hashCode()
//         result = 31 * result + bboxMaxX.hashCode()
//         result = 31 * result + bboxMaxY.hashCode()
//         result = 31 * result + bboxAreaFraction.hashCode()
//         result = 31 * result + centroidX.hashCode()
//         result = 31 * result + centroidY.hashCode()
//         result = 31 * result + points.contentHashCode()
//         return result
//     }
// }
