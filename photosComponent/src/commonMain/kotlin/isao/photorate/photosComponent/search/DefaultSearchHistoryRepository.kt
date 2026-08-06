package isao.photorate.photosComponent.search

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.db.PhotoRateDb
import isao.photorate.sqldelight.transactionWithContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory

/**
 * SQLDelight-backed [SearchHistoryRepository].
 */
@Factory
class DefaultSearchHistoryRepository(private val db: PhotoRateDb) : SearchHistoryRepository {

    private val queries get() = db.galleryQueries

    override fun observeRecentSearches(limit: Long): Flow<List<String>> = queries.recentSearches(limit)
        .asFlow()
        .mapToList(Dispatchers.IO)

    @OptIn(ExperimentalTime::class)
    override suspend fun addRecentSearch(query: String) {
        db.transactionWithContext(Dispatchers.Default) {
            queries.insertSearch(query.trim(), Clock.System.now().toEpochMilliseconds())
        }
    }

    override suspend fun clear() {
        db.transactionWithContext(Dispatchers.Default) {
            queries.clearSearchHistory()
        }
    }
}
