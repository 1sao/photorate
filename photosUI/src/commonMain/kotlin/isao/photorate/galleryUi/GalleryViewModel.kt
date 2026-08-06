package isao.photorate.galleryUi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/**
 * Possible user actions on the gallery screen. One funnel ([onIntent]) keeps
 * the UI free of ViewModel method knowledge; each intent is dispatched to the
 * owning delegate.
 */
sealed interface GalleryIntent {
    /** Re-runs gallery population + landmarking + embedding population. */
    data object Rescan : GalleryIntent

    /** Removes the active query filter so the gallery shows everything again. */
    data object ClearSearch : GalleryIntent

    data class UpdateSearchQuery(val query: String) : GalleryIntent
    data object SubmitSearch : GalleryIntent
    data class SelectRecentSearch(val query: String) : GalleryIntent
    data class SetMinSimilarity(val value: Float) : GalleryIntent

    /** Accepts an uncertain best-guess as a confident user rating. */
    data class AcceptUncertain(val uri: String, val score: Int) : GalleryIntent

    /** Rejects an uncertain guess, marking the image as no-hand. */
    data class DeleteUncertain(val uri: String) : GalleryIntent
}

/**
 * The whole gallery screen state: the gallery grid ([GalleryDelegate]) and the
 * CLIP search ([SearchDelegate]) combined, so the screen collects a single
 * flow. Both halves keep their own data classes so the grid can still pass
 * granular values down and skip recomposition where nothing changed.
 */
data class GalleryScreenUiState(val gallery: GalleryUiState = GalleryUiState(), val search: SearchUiState = SearchUiState())

/**
 * Thin coordinator over the two screen delegates: [GalleryDelegate] owns the
 * gallery grid state (scan status, detections, uncertain review, rescan) and
 * [SearchDelegate] owns the CLIP search state. Both delegates expose cold flows
 * of their states, which this ViewModel combines into [uiState] via [stateIn];
 * collection stops when the screen is not visible (WhileSubscribed).
 */
@KoinViewModel
class GalleryViewModel(
    private val galleryDelegate: GalleryDelegate,
    private val searchDelegate: SearchDelegate,
    private val rescanTrigger: RescanTrigger,
) : ViewModel() {

    val uiState: StateFlow<GalleryScreenUiState> = combine(
        galleryDelegate.uiState,
        searchDelegate.uiState,
    ) { gallery, search -> GalleryScreenUiState(gallery, search) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_COLLECTING_TIMEOUT_MS),
            initialValue = GalleryScreenUiState(),
        )

    init {
        // Releases the ONNX search session with the ViewModel instead of
        // overriding onCleared manually. Explicit object: SAM conversion for
        // AutoCloseable only exists on the JVM (native has no SAM for
        // non-fun interfaces).
        addCloseable(object : AutoCloseable {
            override fun close() = searchDelegate.close()
        })
        // Other screens (e.g. Settings after purging all data) can request a
        // full rescan through the shared trigger. The delegate's mutex queues
        // behind any in-flight scan so a purge-triggered rescan starts from the
        // consistent post-purge state.
        viewModelScope.launch {
            rescanTrigger.requests.collect { galleryDelegate.rescan() }
        }
        // Launch-time rescan: populate + landmark + embed (idempotent, so the
        // permission-granted re-run from the UI adds nothing at startup).
        viewModelScope.launch { galleryDelegate.rescan() }
    }

    fun onIntent(intent: GalleryIntent) {
        when (intent) {
            GalleryIntent.Rescan -> viewModelScope.launch { galleryDelegate.rescan() }

            // --- Search (delegated) ---
            GalleryIntent.ClearSearch -> searchDelegate.clearSearch()
            is GalleryIntent.UpdateSearchQuery -> searchDelegate.updateSearchQuery(intent.query)
            GalleryIntent.SubmitSearch -> searchDelegate.submitSearch()
            is GalleryIntent.SelectRecentSearch -> searchDelegate.selectRecentSearch(intent.query)
            is GalleryIntent.SetMinSimilarity -> searchDelegate.setMinSimilarity(intent.value)

            // --- Uncertain best-guess review (delegated) ---
            is GalleryIntent.AcceptUncertain ->
                viewModelScope.launch { galleryDelegate.acceptUncertain(intent.uri, intent.score) }

            is GalleryIntent.DeleteUncertain ->
                viewModelScope.launch { galleryDelegate.deleteUncertain(intent.uri) }
        }
    }

    private companion object {
        const val STOP_COLLECTING_TIMEOUT_MS = 5_000L
    }
}
