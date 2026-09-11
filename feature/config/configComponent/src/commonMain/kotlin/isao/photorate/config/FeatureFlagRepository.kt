package isao.photorate.config

import kotlinx.coroutines.flow.Flow

interface FeatureFlagRepository {
  fun isDevModeEnabled(): Flow<Boolean>

  suspend fun setDevModeEnabled(enabled: Boolean)
}
