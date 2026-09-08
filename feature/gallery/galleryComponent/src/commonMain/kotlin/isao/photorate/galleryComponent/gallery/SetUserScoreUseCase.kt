package isao.photorate.galleryComponent.gallery

import isao.photorate.gallery.db.DetectedHand
import isao.photorate.galleryRepository.DetectedHandRepository
import isao.photorate.imageRecognition.classify.Score
import org.koin.core.annotation.Factory

/**
 * Overlays a user-entered score on top of an image's real detections.
 *
 * Real detections are KEPT (they remain in the DB for statistics); only a previous user rating is
 * replaced. The user rating is stored as one "fake" hand with [DetectedHand.isUserRated] set, so
 * the UI can display it instead of the real scores and rescans don't confuse it with a detection.
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
        bboxAreaFraction = 1.0,
        points = emptyList(),
        uncertain = false,
        isUserRated = true,
      )
    )
  }
}
