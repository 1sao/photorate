package isao.photorate.config

import kotlinx.coroutines.flow.Flow

interface ConfigRepository {
    fun getConfig(): Flow<GalleryConfig>
    suspend fun updateScoreRange(range: IntRange)
    suspend fun updateMinHandSizePercent(percent: Int)
    suspend fun updateSorting(sorting: GallerySorting)
    suspend fun updateDateHeaderMode(mode: DateHeaderMode)
}
