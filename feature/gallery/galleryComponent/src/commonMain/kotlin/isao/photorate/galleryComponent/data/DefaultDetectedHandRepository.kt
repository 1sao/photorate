package isao.photorate.galleryComponent.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.gallery.db.PhotoRateDb
import isao.photorate.galleryComponent.db.DetectedHand
import isao.photorate.galleryComponent.domain.DetectedHandRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class DefaultDetectedHandRepository(@Provided private val db: PhotoRateDb) :
  DetectedHandRepository {

  private val queries
    get() = db.detectedHandQueries

  override fun getHandsForImage(imageUri: String): Flow<List<DetectedHand>> =
    queries.selectHandsForImage(imageUri).asFlow().mapToList(Dispatchers.IO)

  override suspend fun insertHand(hand: DetectedHand) {
    withContext(Dispatchers.IO) {
      queries.insertHand(
        imageUri = hand.imageUri,
        handIndex = hand.handIndex,
        score = hand.score,
        bboxAreaFraction = hand.bboxAreaFraction,
        points = hand.points,
        uncertain = hand.uncertain,
        isUserRated = hand.isUserRated,
      )
    }
  }

  override suspend fun upsertUserRatedHand(hand: DetectedHand) {
    withContext(Dispatchers.IO) {
      queries.upsertUserRatedHand(
        imageUri = hand.imageUri,
        handIndex = hand.handIndex,
        score = hand.score,
        bboxAreaFraction = hand.bboxAreaFraction,
        points = hand.points,
        uncertain = hand.uncertain,
      )
    }
  }

  override suspend fun deleteRealHandsForImage(imageUri: String) {
    withContext(Dispatchers.IO) { queries.deleteRealHandsForImage(imageUri) }
  }

  override suspend fun deleteAll() {
    withContext(Dispatchers.IO) { queries.deleteAllHands() }
  }
}
