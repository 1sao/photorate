package isao.photorate.galleryComponent.domain

import isao.photorate.galleryComponent.db.DetectedHand
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
