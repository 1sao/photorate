package isao.photorate.searchUi

import isao.photorate.config.FeatureFlagRepository
import isao.photorate.searchComponent.SearchHistoryRepository
import isao.photorate.searchComponent.SearchImagesUseCase
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.Factory

interface SearchDelegate {
  val uiState: Flow<SearchUiState>

  suspend fun onIntent(intent: SearchIntent)
}

@Factory
class DefaultSearchDelegate(
  private val searchImages: SearchImagesUseCase,
  private val searchHistoryRepository: SearchHistoryRepository,
  featureFlagRepository: FeatureFlagRepository,
) : SearchDelegate {

  private var searchJob: Job? = null
  private var debounceSimilarityThresholdJob: Job? = null

  private val queryResults = MutableStateFlow<QueryWithResult?>(null)

  private val pendingQuery = MutableStateFlow(Query.INITIAL)

  override val uiState: Flow<SearchUiState> =
    combine(
      queryResults,
      pendingQuery,
      searchHistoryRepository.observeRecentSearches(),
      featureFlagRepository.isDevModeEnabled(),
    ) { queryResults, pendingQuery, recentSearches, isInDevMode ->
      SearchUiState(
        queryResults = queryResults,
        pendingQuery = pendingQuery,
        recentSearches = recentSearches,
        isInDevMode = isInDevMode,
      )
    }

  override suspend fun onIntent(intent: SearchIntent) {
    when (intent) {
      SearchIntent.ClearSearch -> clearSearch()
      is SearchIntent.SelectRecentSearch -> selectRecentSearch(intent.query)
      is SearchIntent.SetMinSimilarity -> setMinSimilarity(intent.value)
      SearchIntent.SubmitSearch -> submitSearch(pendingQuery.value)
      is SearchIntent.UpdateSearchQuery -> updateSearchQuery(intent.query)
    }
  }

  fun clearSearch() {
    searchJob?.cancel()
    queryResults.value = null
    pendingQuery.update { it.copy(value = "", isInProgress = false) }
  }

  fun updateSearchQuery(query: String) {
    if (query == "") {
      clearSearch()
      return
    }
    pendingQuery.update { it.copy(value = query) }
  }

  suspend fun submitSearch(query: Query) = coroutineScope {
    searchJob?.cancel()

    val trimmedQueryValue = query.value.trim()
    if (trimmedQueryValue == "") return@coroutineScope null

    searchJob = launch {
      pendingQuery.update {
        it.copy(
          value = query.value,
          minSimilarity = query.minSimilarity,
          isInProgress = true,
        )
      }
      val result =
        try {
          searchImages(
            query = trimmedQueryValue,
            limit = SEARCH_LIMIT,
            minSimilarity = query.minSimilarity,
          )
        } finally {
          pendingQuery.update { it.copy(isInProgress = false) }
          searchHistoryRepository.addRecentSearch(trimmedQueryValue)
        }

      queryResults.value =
        QueryWithResult(
          value = query.value,
          minSimilarity = query.minSimilarity,
          result = result,
        )
    }
  }

  suspend fun selectRecentSearch(query: String) {
    submitSearch(pendingQuery.value.copy(value = query))
  }

  suspend fun setMinSimilarity(value: Float) = coroutineScope {
    pendingQuery.update { it.copy(minSimilarity = value) }

    // TODO Consider removing: current UX requires the user to submit the query to view results
    //  anyway. This search runs but won't be visible until enter is pressed.
    //  Alternatively, adjust UX to utilize this logic.
    debounceSimilarityThresholdJob?.cancel()
    debounceSimilarityThresholdJob = launch {
      delay(SIMILARITY_THRESHOLD_DEBOUNCE_MS.milliseconds)
      submitSearch(pendingQuery.value)
    }
  }
}

data class SearchUiState(
  val queryResults: QueryWithResult? = null,
  val pendingQuery: Query = Query.INITIAL,
  val recentSearches: List<String> = emptyList(),
  val isInDevMode: Boolean = false,
)

data class QueryWithResult(
  val value: String,
  val minSimilarity: Float,
  val result: List<String>,
)

data class Query(
  val value: String,
  val minSimilarity: Float,
  val isInProgress: Boolean,
) {
  companion object {
    val INITIAL
      get() =
        Query(
          value = "",
          minSimilarity = .2f,
          isInProgress = false,
        )
  }
}

sealed interface SearchIntent {
  data object ClearSearch : SearchIntent

  data class UpdateSearchQuery(val query: String) : SearchIntent

  data object SubmitSearch : SearchIntent

  data class SelectRecentSearch(val query: String) : SearchIntent

  data class SetMinSimilarity(val value: Float) : SearchIntent
}

private const val SEARCH_LIMIT = 50
private const val SIMILARITY_THRESHOLD_DEBOUNCE_MS = 150
