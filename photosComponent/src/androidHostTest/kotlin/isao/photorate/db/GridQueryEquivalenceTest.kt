package isao.photorate.db

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import isao.photorate.photosComponent.classify.Score
import isao.photorate.sqldelight.adapter.FloatArrayAdapter
import isao.photorate.sqldelight.adapter.PointsAdapter
import isao.photorate.sqldelight.adapter.ScoreAdapter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The join-based [selectImagesWithScores] must be a drop-in replacement for the
 * original correlated-subquery version (kept as [selectImagesWithScoresOld]):
 * same images, same scores, for every rating scenario.
 */
class GridQueryEquivalenceTest {

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

    private fun PhotoRateDb.insertImage(uri: String) {
        galleryQueries.insertOrUpdateImage(
            uri = uri,
            createdAt = 1L,
            modifiedAt = 1L,
            status = GalleryImageStatus.PENDING,
            scannedAt = null,
            detectedInMs = null,
        )
    }

    private fun PhotoRateDb.insertHand(uri: String, index: Long, score: Score, uncertain: Boolean = false, userRated: Boolean = false) {
        galleryQueries.insertHand(
            imageUri = uri,
            handIndex = index,
            score = score,
            bboxMinX = 0.0,
            bboxMinY = 0.0,
            bboxMaxX = 1.0,
            bboxMaxY = 1.0,
            bboxAreaFraction = 1.0,
            centroidX = 0.5,
            centroidY = 0.5,
            points = emptyList(),
            uncertain = uncertain,
            isUserRated = userRated,
        )
    }

    @Test
    fun joinBasedGridQueryMatchesSubqueryVersion() {
        val db = db()
        val queries = db.galleryImageQueries

        // User rating only.
        db.insertImage("content://media/rated")
        db.insertHand("content://media/rated", 0, Score.FOUR, userRated = true)
        // Real confident hands only (multiple).
        db.insertImage("content://media/real")
        db.insertHand("content://media/real", 0, Score.THREE)
        db.insertHand("content://media/real", 1, Score.FIVE)
        // Rating + real hands: the rating must win.
        db.insertImage("content://media/both")
        db.insertHand("content://media/both", 0, Score.TWO)
        db.insertHand("content://media/both", 1, Score.THREE, userRated = true)
        // Only uncertain hands: excluded.
        db.insertImage("content://media/uncertain")
        db.insertHand("content://media/uncertain", 0, Score.ONE, uncertain = true)
        // No hands at all: excluded.
        db.insertImage("content://media/empty")

        val old = queries.selectImagesWithScoresOld().executeAsList()
        val fresh = queries.selectImagesWithScores().executeAsList()

        assertEquals(old.map { it.uri }, fresh.map { it.uri })
        old.zip(fresh).forEach { (a, b) ->
            assertEquals(parseScores(a.scores), parseScores(b.scores), "scores differ for ${a.uri}")
        }

        // Sanity: the images that should show, and only those.
        assertEquals(
            setOf("content://media/rated", "content://media/real", "content://media/both"),
            fresh.map { it.uri }.toSet(),
        )
        // And the rating wins for "both".
        assertEquals(listOf(3.0), parseScores(fresh.single { it.uri == "content://media/both" }.scores))
    }

    @Test
    fun joinBasedQueryAppliesConfigFilterToRealHands() {
        val db = db()
        val queries = db.galleryImageQueries

        // Real hands below the (tightened) min_score: excluded unless a rating exists.
        db.insertImage("content://media/below-min")
        db.insertHand("content://media/below-min", 0, Score.TWO)

        db.insertImage("content://media/below-min-rated")
        db.insertHand("content://media/below-min-rated", 0, Score.TWO)
        db.insertHand("content://media/below-min-rated", 1, Score.FIVE, userRated = true)

        // Rating next to only-uncertain real hands: rating still shows.
        db.insertImage("content://media/rated-uncertain")
        db.insertHand("content://media/rated-uncertain", 0, Score.ONE, uncertain = true)
        db.insertHand("content://media/rated-uncertain", 1, Score.FIVE, userRated = true)

        // Tighten the filter so the Score.TWO real hands fall below min_score.
        queries.updateScoreRange(4.0, 5.0, 0L)

        val old = queries.selectImagesWithScoresOld().executeAsList()
        val fresh = queries.selectImagesWithScores().executeAsList()

        assertEquals(old.map { it.uri }, fresh.map { it.uri })
        old.zip(fresh).forEach { (a, b) ->
            assertEquals(parseScores(a.scores), parseScores(b.scores), "scores differ for ${a.uri}")
        }

        // The below-min real hand is filtered out; ratings are exempt and show.
        assertEquals(
            setOf("content://media/below-min-rated", "content://media/rated-uncertain"),
            fresh.map { it.uri }.toSet(),
        )
    }

    private fun parseScores(scores: String?): List<Double> = scores
        ?.split(',')
        ?.map { it.trim().toDouble() }
        ?.sorted()
        ?: emptyList()
}
