package isao.photorate.galleryRepository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import isao.photorate.db.Config
import isao.photorate.db.PhotoRateDb
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class DefaultConfigRepository(private val db: PhotoRateDb) : ConfigRepository {

    private val queries get() = db.configQueries

    override fun getConfig(): Flow<GalleryConfig> = queries.getConfig()
        .asFlow()
        .mapToOne(Dispatchers.IO)
        .map { it.toGalleryConfig() }

    override suspend fun updateScoreRange(range: IntRange) {
        withContext(Dispatchers.IO) {
            queries.updateScoreRange(
                min_score = range.first.toDouble(),
                max_score = range.last.toDouble(),
                updated_at = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }

    override suspend fun updateMinHandSizePercent(percent: Int) {
        withContext(Dispatchers.IO) {
            queries.updateMinHandSize(
                min_bboxAreaFraction = percent / 100.0,
                updated_at = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }

    override suspend fun updateSorting(sorting: GallerySorting) {
        withContext(Dispatchers.IO) {
            queries.updateSorting(
                sort_by = sorting,
                updated_at = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }

    override suspend fun updateDateHeaderMode(mode: DateHeaderMode) {
        withContext(Dispatchers.IO) {
            queries.updateDateHeaderMode(
                date_header_mode = mode,
                updated_at = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }

    private fun Config.toGalleryConfig(): GalleryConfig {
        val min = min_score.roundToInt().coerceIn(1, 5)
        val max = max_score.roundToInt().coerceIn(min, 5)
        return GalleryConfig(
            scoreRange = min..max,
            minHandSizePercent = (min_bboxAreaFraction * 100).roundToInt().coerceIn(1, 100),
            sorting = sort_by,
            dateHeaderMode = date_header_mode,
        )
    }
}
