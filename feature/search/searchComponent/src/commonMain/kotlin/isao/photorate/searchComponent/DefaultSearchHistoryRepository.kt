package isao.photorate.searchComponent

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.search.db.SearchHistoryQueries
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class DefaultSearchHistoryRepository(@Provided private val queries: SearchHistoryQueries) :
  SearchHistoryRepository {

  override fun selectRecentSearches(limit: Long): Flow<List<String>> =
    queries.recentSearches(limit).asFlow().mapToList(Dispatchers.IO)

  @OptIn(ExperimentalTime::class)
  override suspend fun upsertRecentSearch(query: String) {
    withContext(Dispatchers.IO) {
      // TODO clocks should be constructor-injected
      queries.insertSearch(
        query.trim(),
        Clock.System.now().toEpochMilliseconds(),
      )
    }
  }

  override suspend fun clear() { // TODO implement manual clearing
    withContext(Dispatchers.IO) { queries.clearSearchHistory() }
  }
}
