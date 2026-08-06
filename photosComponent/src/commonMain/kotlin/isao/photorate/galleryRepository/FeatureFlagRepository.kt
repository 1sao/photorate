package isao.photorate.galleryRepository

import kotlinx.coroutines.flow.Flow

/**
 * Developer-only feature flags, persisted across launches (singleton `config`
 * row; see DefaultFeatureFlagRepository).
 */
interface FeatureFlagRepository {
    /** Whether Developer Mode is enabled (raw landmark overlay on photo details). */
    fun devModeEnabled(): Flow<Boolean>

    suspend fun setDevModeEnabled(enabled: Boolean)
}
