package isao.photorate.searchComponent

import kotlinx.coroutines.flow.Flow

/**
 * Persists recent search queries (backed by the `SearchHistory` SQLDelight
 * table) so the search UI can offer one-tap re-runs.
 */
interface SearchHistoryRepository {
    /** Emits the most recent queries, most recent first. */
    fun observeRecentSearches(limit: Long = 10): Flow<List<String>>

    /** Records [query] as the most recent search (deduplicated by upsert). */
    suspend fun addRecentSearch(query: String)

    /** Clears the whole history. */
    suspend fun clear()
}
