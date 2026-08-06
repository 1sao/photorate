package isao.photorate.db

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import isao.photorate.photosComponent.classify.LandmarkedImage.Point
import isao.photorate.photosComponent.classify.Score
import isao.photorate.sqldelight.adapter.FloatArrayAdapter
import isao.photorate.sqldelight.adapter.PointsAdapter
import isao.photorate.sqldelight.adapter.ScoreAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the real upserts (INSERT ... ON CONFLICT ... DO UPDATE).
 *
 * The old `INSERT OR REPLACE` deleted the conflicting row and re-inserted it,
 * which fired the `ON DELETE CASCADE` foreign keys: re-populating an image
 * wiped its DetectedHand and ImageEmbedding rows on every rescan. The upserts
 * must preserve those children.
 */
class UpsertRegressionTest {

    private fun db(): PhotoRateDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhotoRateDb.Schema.create(driver)
        return PhotoRateDb(
            driver,
            DetectedHandAdapter = DetectedHand.Adapter(
                scoreAdapter = ScoreAdapter,
                pointsAdapter = PointsAdapter,
            ),
            GalleryImageAdapter = GalleryImage.Adapter(EnumColumnAdapter()),
            configAdapter = Config.Adapter(
                sort_byAdapter = EnumColumnAdapter(),
                date_header_modeAdapter = EnumColumnAdapter(),
            ),
            ImageEmbeddingAdapter = ImageEmbedding.Adapter(
                embeddingAdapter = FloatArrayAdapter,
            ),
        )
    }

    private fun PhotoRateDb.insertImage(uri: String, modifiedAt: Long = 1L) {
        galleryQueries.insertOrUpdateImage(
            uri = uri,
            createdAt = 1L,
            modifiedAt = modifiedAt,
            status = GalleryImageStatus.PENDING,
            scannedAt = null,
            detectedInMs = null,
        )
    }

    @Test
    fun upsertingAnImageKeepsItsDetectedHands() {
        val db = db()
        val queries = db.galleryQueries
        db.insertImage("content://media/1")
        queries.insertHand(
            imageUri = "content://media/1",
            handIndex = 0L,
            score = Score.THREE,
            bboxMinX = 0.0,
            bboxMinY = 0.0,
            bboxMaxX = 1.0,
            bboxMaxY = 1.0,
            bboxAreaFraction = 1.0,
            centroidX = 0.5,
            centroidY = 0.5,
            points = listOf(Point(0f, 0f, 0f)),
            uncertain = false,
            isUserRated = false,
        )

        // Second rescan: re-populating the image must not wipe its hands.
        db.insertImage("content://media/1")

        val hands = queries.selectHandsForImage("content://media/1").executeAsList()
        assertEquals(1, hands.size)
        assertEquals(Score.THREE, hands.single().score)
    }

    @Test
    fun upsertingAnImageKeepsItsEmbedding() {
        val db = db()
        val queries = db.galleryQueries
        db.insertImage("content://media/1")
        queries.upsertEmbedding("content://media/1", floatArrayOf(1f, 2f, 3f))

        // Second rescan: re-populating the image must not wipe the embedding.
        db.insertImage("content://media/1")

        val embeddings = queries.selectAllEmbeddings().executeAsList()
        assertEquals(1, embeddings.size)
        assertTrue(embeddings.single().embedding.contentEquals(floatArrayOf(1f, 2f, 3f)))
    }

    @Test
    fun userRatingUpsertReplacesInsteadOfDuplicating() {
        val db = db()
        val queries = db.galleryQueries
        db.insertImage("content://media/1")

        fun rate(score: Score) = queries.upsertUserRatedHand(
            imageUri = "content://media/1",
            handIndex = 0L,
            score = score,
            bboxMinX = 0.0,
            bboxMinY = 0.0,
            bboxMaxX = 1.0,
            bboxMaxY = 1.0,
            bboxAreaFraction = 1.0,
            centroidX = 0.5,
            centroidY = 0.5,
            points = emptyList(),
            uncertain = false,
        )

        rate(Score.ONE)
        rate(Score.FIVE)

        val ratings = queries.selectHandsForImage("content://media/1")
            .executeAsList()
            .filter { it.isUserRated }
        assertEquals(1, ratings.size)
        assertEquals(Score.FIVE, ratings.single().score)
    }

    @Test
    fun upsertingAnUnchangedImageKeepsItsScanResults() {
        val db = db()
        val queries = db.galleryQueries
        db.insertImage("content://media/1")
        queries.updateImageDone(detectedInMs = 10L, uri = "content://media/1")

        // Re-populating with the same modifiedAt must not re-queue the image:
        // this was the bug where every launch flipped DONE images back to
        // PENDING and LandmarkPendingImagesUseCase re-scanned them.
        db.insertImage("content://media/1")

        val image = queries.selectImageByUri("content://media/1").executeAsOne()
        assertEquals(GalleryImageStatus.DONE, image.status)
        assertEquals(10L, image.detectedInMs)
    }

    @Test
    fun upsertingAChangedImageResetsItForRescan() {
        val db = db()
        val queries = db.galleryQueries
        db.insertImage("content://media/1")
        queries.updateImageDone(detectedInMs = 10L, uri = "content://media/1")

        // File changed (new modifiedAt): the image is re-queued so the fresh
        // content gets landmarked instead of keeping the stale DONE results.
        db.insertImage("content://media/1", modifiedAt = 2L)

        val image = queries.selectImageByUri("content://media/1").executeAsOne()
        assertEquals(GalleryImageStatus.PENDING, image.status)
        assertNull(image.detectedInMs)
    }

    @Test
    fun upsertingAnUnchangedImageRecoversAStuckProcessingStatus() {
        val db = db()
        val queries = db.galleryQueries
        db.insertImage("content://media/1")
        queries.updateImageStatus(GalleryImageStatus.PROCESSING, "content://media/1")

        // A scan killed mid-image leaves PROCESSING; re-populating must re-queue
        // it (the landmarker only picks up PENDING rows) instead of leaving it
        // stuck forever.
        db.insertImage("content://media/1")

        val image = queries.selectImageByUri("content://media/1").executeAsOne()
        assertEquals(GalleryImageStatus.PENDING, image.status)
    }

    @Test
    fun deleteRealHandsKeepsUserRating() {
        val db = db()
        val queries = db.galleryQueries
        db.insertImage("content://media/1")
        queries.insertHand(
            imageUri = "content://media/1",
            handIndex = 0L,
            score = Score.FOUR,
            bboxMinX = 0.0,
            bboxMinY = 0.0,
            bboxMaxX = 1.0,
            bboxMaxY = 1.0,
            bboxAreaFraction = 1.0,
            centroidX = 0.5,
            centroidY = 0.5,
            points = listOf(Point(0f, 0f, 0f)),
            uncertain = false,
            isUserRated = false,
        )
        queries.upsertUserRatedHand(
            imageUri = "content://media/1",
            handIndex = 0L,
            score = Score.ONE,
            bboxMinX = 0.0,
            bboxMinY = 0.0,
            bboxMaxX = 1.0,
            bboxMaxY = 1.0,
            bboxAreaFraction = 1.0,
            centroidX = 0.5,
            centroidY = 0.5,
            points = emptyList(),
            uncertain = false,
        )

        // Fresh detection run: only the real hand is dropped, the rating stays.
        queries.deleteRealHandsForImage("content://media/1")

        val hands = queries.selectHandsForImage("content://media/1").executeAsList()
        assertEquals(1, hands.size)
        assertTrue(hands.single().isUserRated)
        assertEquals(Score.ONE, hands.single().score)
    }
}
