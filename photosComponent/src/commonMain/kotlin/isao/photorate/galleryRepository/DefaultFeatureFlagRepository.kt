package isao.photorate.galleryRepository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import isao.photorate.db.PhotoRateDb
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class DefaultFeatureFlagRepository(private val db: PhotoRateDb) : FeatureFlagRepository {

    private val queries get() = db.configQueries

    override fun devModeEnabled(): Flow<Boolean> = queries.getDevMode()
        .asFlow()
        .mapToOne(Dispatchers.IO)

    override suspend fun setDevModeEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            queries.updateDevMode(
                dev_mode = enabled,
                updated_at = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }
}
