package isao.photorate.config

import kotlinx.coroutines.flow.Flow

/**
 * Developer-only feature flags, persisted across launches (singleton `config` row; see
 * DefaultFeatureFlagRepository).
 */
interface FeatureFlagRepository {
  /** Whether Developer Mode is enabled (raw landmark overlay on photo details). */
  fun isDevModeEnabled(): Flow<Boolean>

  suspend fun setDevModeEnabled(enabled: Boolean)
}
