package isao.photorate.homeUi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isao.photorate.galleryUi.GalleryDelegate
import isao.photorate.galleryUi.GalleryUiState
import isao.photorate.galleryUi.RescanTrigger
import isao.photorate.searchUi.SearchDelegate
import isao.photorate.searchUi.SearchUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

sealed interface HomeIntent {
    data object Rescan : HomeIntent
    data object ClearSearch : HomeIntent
    data class UpdateSearchQuery(val query: String) : HomeIntent
    data object SubmitSearch : HomeIntent
    data class SelectRecentSearch(val query: String) : HomeIntent
    data class SetMinSimilarity(val value: Float) : HomeIntent
    data class AcceptUncertain(val uri: String, val score: Int) : HomeIntent
    data class DeleteUncertain(val uri: String) : HomeIntent
}

data class HomeScreenUiState(val gallery: GalleryUiState = GalleryUiState(), val search: SearchUiState = SearchUiState())

/**
 * Composes the gallery grid state and the search state into one [StateFlow]
 * and owns the launch-time rescan and the cross-screen rescan trigger.
 * Search submission applies the returned uris as the gallery grid filter.
 * Both delegates are cold-flow producers, so the combined state is collected
 * only while the screen is visible. The search delegate's scope (and the CLIP
 * session behind it) is released when this ViewModel is cleared.
 */
@KoinViewModel
class HomeViewModel(
    private val galleryDelegate: GalleryDelegate,
    private val searchDelegate: SearchDelegate,
    private val rescanTrigger: RescanTrigger,
) : ViewModel() {

    val uiState: StateFlow<HomeScreenUiState> = combine(
        galleryDelegate.uiState,
        searchDelegate.uiState,
    ) { gallery, search -> HomeScreenUiState(gallery, search) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_COLLECTING_TIMEOUT_MS),
            initialValue = HomeScreenUiState(),
        )

    init {
        addCloseable(searchDelegate)
        viewModelScope.launch {
            rescanTrigger.requests.collect { galleryDelegate.rescan() }
        }
        viewModelScope.launch { galleryDelegate.rescan() }
    }

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.Rescan -> viewModelScope.launch { galleryDelegate.rescan() }

            HomeIntent.ClearSearch -> {
                searchDelegate.clearSearch()
                galleryDelegate.setSearchFilter(null)
            }

            is HomeIntent.UpdateSearchQuery -> searchDelegate.updateSearchQuery(intent.query)
            HomeIntent.SubmitSearch -> viewModelScope.launch {
                val uris = searchDelegate.submitSearch()
                galleryDelegate.setSearchFilter(uris.toSet())
            }

            is HomeIntent.SelectRecentSearch -> searchDelegate.selectRecentSearch(intent.query)
            is HomeIntent.SetMinSimilarity -> searchDelegate.setMinSimilarity(intent.value)

            is HomeIntent.AcceptUncertain ->
                viewModelScope.launch { galleryDelegate.acceptUncertain(intent.uri, intent.score) }

            is HomeIntent.DeleteUncertain ->
                viewModelScope.launch { galleryDelegate.deleteUncertain(intent.uri) }
        }
    }

    fun purgeAndRescan() {
        viewModelScope.launch {
            galleryDelegate.deleteAllScans()
            rescanTrigger.request()
        }
    }

    private companion object {
        const val STOP_COLLECTING_TIMEOUT_MS = 5_000L
    }
}
