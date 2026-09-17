package isao.photorate.galleryComponent.domain

import isao.photorate.galleryComponent.db.DetectedHand
import isao.photorate.imageRecognition.Score
import org.koin.core.annotation.Factory

/**
 * Overlays a user-entered score on top of an image's real detections.
 *
 * Real detections are KEPT (they remain in the DB for statistics); only a previous user rating is
 * replaced. The user rating is stored as one "fake" hand with
 * [isao.photorate.galleryComponent.db.DetectedHand.isUserRated] set, so the UI can display it
 * instead of the real scores and rescans don't confuse it with a detection.
 *
 * The fake hand has an empty landmark list and a full-frame bbox (it has no geometry to display).
 */
@Factory
class SetUserScoreUseCase(private val detectedHandRepository: DetectedHandRepository) {
  suspend operator fun invoke(uri: String, score: Score) {
    // Real upsert (ON CONFLICT(imageUri) WHERE
    // isUserRated = 1): replaces
    // the previous rating in one statement instead of
    // delete + insert.
    detectedHandRepository.upsertUserRatedHand(
      DetectedHand(
        id = -1,
        imageUri = uri,
        handIndex = 0,
        score = score,
        area = 1.0,
        points = emptyList(),
        isUncertain = false,
        isUserRated = true,
      ),
    )
  }
}
