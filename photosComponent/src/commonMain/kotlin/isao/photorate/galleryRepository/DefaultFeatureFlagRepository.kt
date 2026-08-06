package isao.photorate.galleryRepository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import isao.photorate.db.PhotoRateDb
import isao.photorate.sqldelight.transactionWithContext
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory

@Factory
class DefaultFeatureFlagRepository(private val db: PhotoRateDb) : FeatureFlagRepository {

    private val queries get() = db.galleryQueries

    override fun devModeEnabled(): Flow<Boolean> = queries.getDevMode()
        .asFlow()
        .mapToOne(Dispatchers.Default)

    override suspend fun setDevModeEnabled(enabled: Boolean) {
        db.transactionWithContext(Dispatchers.Default) {
            queries.updateDevMode(
                dev_mode = enabled,
                updated_at = Clock.System.now().toEpochMilliseconds(),
            )
        }
    }
}
