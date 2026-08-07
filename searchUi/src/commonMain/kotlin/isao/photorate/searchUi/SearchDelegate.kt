package isao.photorate.searchUi

import isao.photorate.config.FeatureFlagRepository
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.searchComponent.SearchHistoryRepository
import isao.photorate.searchComponent.SearchImagesUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.Factory

interface SearchDelegate : AutoCloseable {
  val uiState: Flow<SearchUiState>

  fun clearSearch()

  fun updateSearchQuery(query: String)

  /**
   * Runs the CLIP search for the current query and returns the ranked result uris; the Home screen
   * applies them as a gallery grid filter. The full ranked results also stay in [SearchUiState] for
   * the results header and leftover-match rendering.
   */
  suspend fun submitSearch(): List<String>

  fun selectRecentSearch(query: String)

  fun setMinSimilarity(value: Float)

  /**
   * Cancels in-flight search jobs and releases the underlying [SearchImagesUseCase] (CLIP session).
   */
  override fun close()
}

@Factory
class DefaultSearchDelegate(
  private val searchImages: SearchImagesUseCase,
  private val searchHistoryRepository: SearchHistoryRepository,
  private val featureFlagRepository: FeatureFlagRepository,
) : SearchDelegate {

  private data class Session(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<GalleryImage> = emptyList(),
    val minSimilarity: Float = SearchImagesUseCase.DEFAULT_MIN_SIMILARITY,
  )

  private val session = MutableStateFlow(Session())

  override val uiState: Flow<SearchUiState> =
    combine(
      session,
      searchHistoryRepository.observeRecentSearches(),
      featureFlagRepository.devModeEnabled(),
    ) { session, recent, devMode ->
      SearchUiState(
        query = session.query,
        isSearching = session.isSearching,
        results = session.results,
        recentSearches = recent,
        minSimilarity = session.minSimilarity,
        devModeEnabled = devMode,
      )
    }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private var searchJob: Job? = null

  private var thresholdRerunJob: Job? = null

  override fun clearSearch() = session.update {
    it.copy(
      query = "",
      results = emptyList(),
      isSearching = false,
    )
  }

  override fun updateSearchQuery(query: String) = session.update { it.copy(query = query) }

  // The CLIP session is single-use and not safe for concurrent use: re-submits
  // while a search is in flight are dropped, and a re-run waits for the
  // in-flight search before starting.
  override suspend fun submitSearch(): List<String> {
    if (session.value.isSearching) return emptyList()
    val query = session.value.query.trim()
    if (query.isEmpty()) return emptyList()
    session.update { it.copy(isSearching = true) }
    val results = runCatching {
      searchImages.search(
        query,
        minSimilarity = session.value.minSimilarity,
      )
    }
      .getOrDefault(emptyList())
    session.update {
      it.copy(
        isSearching = false,
        results = results,
      )
    }
    runCatching { searchHistoryRepository.addRecentSearch(query) }
    return results.map { it.uri }
  }

  override fun selectRecentSearch(query: String) {
    session.update { it.copy(query = query) }
    scope.launch { submitSearch() }
  }

  // Slider drags fire many changes, so re-runs are debounced and wait for any
  // in-flight search (the CLIP session is single-use) instead of being
  // dropped.
  override fun setMinSimilarity(value: Float) {
    session.update { it.copy(minSimilarity = value) }
    if (session.value.query.isBlank()) return
    thresholdRerunJob?.cancel()
    thresholdRerunJob = scope.launch {
      delay(SearchUiState.THRESHOLD_RERUN_DEBOUNCE_MS)
      searchJob?.join()
      if (session.value.query.isNotBlank()) submitSearch()
    }
  }

  override fun close() {
    scope.cancel()
    searchImages.close()
  }
}

data class SearchUiState(
  val query: String = "",
  val isSearching: Boolean = false,
  val results: List<GalleryImage> = emptyList(),
  val recentSearches: List<String> = emptyList(),
  val minSimilarity: Float = SearchImagesUseCase.DEFAULT_MIN_SIMILARITY,
  val devModeEnabled: Boolean = false,
) {
  companion object {
    const val THRESHOLD_RERUN_DEBOUNCE_MS = 250L
  }
}
