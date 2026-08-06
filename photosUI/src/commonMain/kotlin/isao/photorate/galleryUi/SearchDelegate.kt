package isao.photorate.galleryUi

import isao.photorate.db.GalleryImage
import isao.photorate.galleryRepository.FeatureFlagRepository
import isao.photorate.photosComponent.search.SearchHistoryRepository
import isao.photorate.photosComponent.search.SearchImagesUseCase
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

/**
 * Owns the CLIP search UI state ([SearchUiState]) and the search lifecycle for
 * the gallery screen: query text, in-flight flag, results, recent searches and
 * the dev-mode similarity threshold. Extracted from [GalleryViewModel] so the
 * gallery grid state and the search state evolve (and recompose) independently.
 *
 * User-input state (query, results, threshold) lives in a small [MutableStateFlow]
 * session; the repository-driven parts (recent searches, dev-mode flag) are
 * combined with it into the exposed cold [uiState] flow, which the ViewModel
 * collects. The ONNX session and search jobs run on the delegate's own scope,
 * released via [close] (called from [GalleryViewModel]'s [androidx.lifecycle.ViewModel.addCloseable]).
 */
interface SearchDelegate {
    /** Cold flow of the search state, derived from the session + repository flows. */
    val uiState: Flow<SearchUiState>

    fun clearSearch()
    fun updateSearchQuery(query: String)
    fun submitSearch()
    fun selectRecentSearch(query: String)
    fun setMinSimilarity(value: Float)

    /** Cancels in-flight search jobs and releases the underlying [SearchImagesUseCase] (ONNX session). */
    fun close()
}

@Factory
class DefaultSearchDelegate(
    private val searchImages: SearchImagesUseCase,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val featureFlagRepository: FeatureFlagRepository,
) : SearchDelegate {

    /** User-driven search state, kept across recompositions; the seed for [uiState]. */
    private data class Session(
        val query: String = "",
        val isSearching: Boolean = false,
        val results: List<GalleryImage> = emptyList(),
        /** CLIP-similarity cutoff for search results (dev-mode tunable). */
        val minSimilarity: Float = SearchImagesUseCase.DEFAULT_MIN_SIMILARITY,
    )

    private val session = MutableStateFlow(Session())

    override val uiState: Flow<SearchUiState> = combine(
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

    /** Search jobs run here; cancelled by [close] alongside the ONNX session. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The in-flight search, if any. The ONNX session is single-use, so a
     *  re-run (e.g. from the dev-mode slider) waits for it before starting. */
    private var searchJob: Job? = null

    /** Debounced re-run triggered by [setMinSimilarity]; only the last slider
     *  position is applied once the in-flight search finishes. */
    private var thresholdRerunJob: Job? = null

    /** Removes the active query filter so the gallery shows everything again. */
    override fun clearSearch() = session.update {
        it.copy(query = "", results = emptyList(), isSearching = false)
    }

    override fun updateSearchQuery(query: String) = session.update { it.copy(query = query) }

    /** The gallery grid shows [SearchUiState.results] when [SearchUiState.query] is non-blank. */
    override fun submitSearch() {
        // Ignore re-submits while a search is in flight — the ONNX session is
        // not safe for concurrent use. The flag is set synchronously so two
        // quick submits can't both pass the guard before the coroutine starts.
        if (session.value.isSearching) return
        val query = session.value.query.trim()
        if (query.isEmpty()) return
        session.update { it.copy(isSearching = true) }
        searchJob = scope.launch {
            // Recover from failures (e.g. model assets missing) instead of
            // leaving the UI stuck in the searching state. Clear the flag
            // before the (fallible) history write so it can't wedge the UI.
            val results = runCatching {
                searchImages.search(query, minSimilarity = session.value.minSimilarity)
            }.getOrDefault(emptyList())
            session.update { it.copy(isSearching = false, results = results) }
            runCatching { searchHistoryRepository.addRecentSearch(query) }
        }
    }

    override fun selectRecentSearch(query: String) {
        session.update { it.copy(query = query) }
        submitSearch()
    }

    /**
     * Sets the CLIP-similarity cutoff and re-runs the active query so the
     * filtered gallery reflects the new threshold immediately. Slider drags
     * fire many changes, so re-runs are debounced and wait for any in-flight
     * search (the ONNX session is single-use) instead of being dropped.
     */
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
    /** CLIP-similarity cutoff for search results (dev-mode tunable). */
    val minSimilarity: Float = SearchImagesUseCase.DEFAULT_MIN_SIMILARITY,
    /** Developer Mode: whether the threshold slider shows on the search screen. */
    val devModeEnabled: Boolean = false,
) {
    companion object {
        const val THRESHOLD_RERUN_DEBOUNCE_MS = 250L
    }
}
