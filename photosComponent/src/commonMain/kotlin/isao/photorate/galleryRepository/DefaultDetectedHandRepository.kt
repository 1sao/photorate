package isao.photorate.galleryRepository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.db.DetectedHand
import isao.photorate.db.PhotoRateDb
import isao.photorate.sqldelight.transactionWithContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory

@Factory
class DefaultDetectedHandRepository(private val db: PhotoRateDb) : DetectedHandRepository {

    private val queries get() = db.galleryQueries

    override fun getHandsForImage(imageUri: String): Flow<List<DetectedHand>> = queries.selectHandsForImage(imageUri)
        .asFlow()
        .mapToList(Dispatchers.Default)

    override suspend fun insertHand(hand: DetectedHand) {
        db.transactionWithContext(Dispatchers.Default) {
            // TODO change to IO? Drop transactions when it's not needed?
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
        db.transactionWithContext(Dispatchers.Default) {
            // TODO change to IO? Drop transactions when it's not needed?
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
        db.transactionWithContext(Dispatchers.Default) {
            queries.deleteRealHandsForImage(imageUri)
        }
    }

    override suspend fun deleteAll() {
        db.transactionWithContext(Dispatchers.Default) {
            queries.deleteAllHands()
        }
    }
}
