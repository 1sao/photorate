package isao.photorate.searchComponent

import kotlinx.coroutines.flow.Flow

/** History of recent CLIP search queries */
interface SearchHistoryRepository {
  /** Emits the most recent queries, most recent first. */
  fun selectRecentSearches(limit: Long = 20): Flow<List<String>>

  /** Records [query] as the most recent search (deduplicated by upsert). */
  suspend fun upsertRecentSearch(query: String)

  /** Clears the whole history. */
  suspend fun clear()
}
