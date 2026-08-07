package isao.photorate.galleryRepository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.gallery.db.DetectedHand
import isao.photorate.gallery.db.PhotoRateDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class DefaultDetectedHandRepository(private val db: PhotoRateDb) : DetectedHandRepository {

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
        bboxMinX = hand.bboxMinX,
        bboxMinY = hand.bboxMinY,
        bboxMaxX = hand.bboxMaxX,
        bboxMaxY = hand.bboxMaxY,
        bboxAreaFraction = hand.bboxAreaFraction,
        centroidX = hand.centroidX,
        centroidY = hand.centroidY,
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
        bboxMinX = hand.bboxMinX,
        bboxMinY = hand.bboxMinY,
        bboxMaxX = hand.bboxMaxX,
        bboxMaxY = hand.bboxMaxY,
        bboxAreaFraction = hand.bboxAreaFraction,
        centroidX = hand.centroidX,
        centroidY = hand.centroidY,
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
