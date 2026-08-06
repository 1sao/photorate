package isao.photorate.galleryUi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isao.photorate.galleryRepository.DetectedHandRepository
import isao.photorate.galleryRepository.FeatureFlagRepository
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.photosComponent.classify.LandmarkedImage.Point
import isao.photorate.photosComponent.classify.Score
import isao.photorate.photosComponent.gallery.SetUserScoreUseCase
import isao.photorate.photosComponent.populateGallery.SystemGalleryImageRepository
import isao.photorate.photosComponent.populateGallery.SystemImageDetails
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/** Possible user actions on the image-details screen, funneled through [ImageDetailsViewModel.onIntent]. */
sealed interface ImageDetailsIntent {
    /** Opens the details for [uri]; resets state when the image changes. */
    data class Load(val uri: String) : ImageDetailsIntent

    /** Replaces the image's detections with a single hand carrying [score]. */
    data class SetScore(val score: Int) : ImageDetailsIntent

    /** Removes the image from the app (the file stays in the device gallery). */
    data object DeleteImage : ImageDetailsIntent
}

@KoinViewModel
class ImageDetailsViewModel(
    private val detectedHandRepository: DetectedHandRepository,
    private val systemGalleryImageRepository: SystemGalleryImageRepository,
    private val setUserScore: SetUserScoreUseCase,
    private val featureFlagRepository: FeatureFlagRepository,
    private val galleryImageRepository: GalleryImageRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageDetailsUiState())
    val uiState: StateFlow<ImageDetailsUiState> = _uiState.asStateFlow()

    // Tracked so a new image's load cancels the previous one's collectors:
    // SQLDelight flows re-emit on ANY write to DetectedHand, so a stale
    // collector from image A would otherwise overwrite image B's display.
    private var handsJob: Job? = null
    private var detailsJob: Job? = null

    init {
        viewModelScope.launch {
            featureFlagRepository.devModeEnabled().collect { enabled ->
                _uiState.update { it.copy(devModeEnabled = enabled) }
            }
        }
    }

    fun onIntent(intent: ImageDetailsIntent) {
        when (intent) {
            is ImageDetailsIntent.Load -> load(intent.uri)
            is ImageDetailsIntent.SetScore -> setScore(intent.score)
            ImageDetailsIntent.DeleteImage -> deleteImage()
        }
    }

    /** Opens the details for [uri]; resets state when the image changes. */
    private fun load(uri: String) {
        if (_uiState.value.uri == uri) return
        handsJob?.cancel()
        detailsJob?.cancel()
        // Keep app-level state (dev mode) across image switches: the SQLDelight
        // flag flow only re-emits on DB writes, so a full reset here would
        // permanently lose the value the init collector already delivered.
        _uiState.value = ImageDetailsUiState(
            uri = uri,
            devModeEnabled = _uiState.value.devModeEnabled,
        )
        handsJob = viewModelScope.launch {
            detectedHandRepository.getHandsForImage(uri).collect { hands ->
                val userRated = hands.filter { hand -> hand.isUserRated }
                // A user rating replaces the real scores for display (the real
                // detections stay in the DB for statistics).
                val displayed = if (userRated.isNotEmpty()) userRated else hands
                _uiState.update {
                    it.copy(
                        scores = displayed.map { hand -> hand.score }
                            .distinct()
                            .sortedBy { score -> score.score },
                        uncertain = userRated.isEmpty() &&
                            displayed.isNotEmpty() &&
                            displayed.all { hand -> hand.uncertain },
                        hasUserRating = userRated.isNotEmpty(),
                        // Raw real-detection landmarks for the dev-mode overlay
                        // (the user-rated fake hand has no geometry to show).
                        landmarkHands = hands
                            .filterNot { hand -> hand.isUserRated }
                            .map { hand -> hand.points },
                    )
                }
            }
        }
        detailsJob = viewModelScope.launch {
            // Pull straight from the system gallery; never cached. A failure
            // (e.g. file removed) just leaves the metadata fields empty.
            val details = runCatching { systemGalleryImageRepository.getImageDetails(uri) }.getOrNull()
            _uiState.update { it.copy(details = details) }
        }
    }

    /** Replaces the image's detections with a single hand carrying [score]. */
    private fun setScore(score: Int) {
        val uri = _uiState.value.uri
        val enumScore = Score.entries.firstOrNull { it.score == score } ?: return
        viewModelScope.launch {
            runCatching { setUserScore(uri, enumScore) }
        }
    }

    /**
     * Removes the image from the app: deletes its DB row (hands and embedding
     * cascade) so it leaves the gallery grid. The file stays in the device
     * gallery — the caller navigates back once this completes.
     */
    // TODO hide instead to track history
    private fun deleteImage() {
        val uri = _uiState.value.uri
        if (uri.isBlank()) return
        viewModelScope.launch {
            runCatching { galleryImageRepository.deleteImage(uri) }
        }
    }
}

data class ImageDetailsUiState(
    val uri: String = "",
    /** Distinct scores shown (user rating when present, otherwise detected). */
    val scores: List<Score> = emptyList(),
    /** True when the shown scores are the user's own rating. */
    val hasUserRating: Boolean = false,
    /** True when every hand is a low-confidence guess (uncertain tier). */
    val uncertain: Boolean = false,
    /** System gallery metadata; null while loading or when unavailable. */
    val details: SystemImageDetails? = null,
    /** Developer Mode: raw real-detection landmarks per hand (overlay on the photo). */
    val landmarkHands: List<List<Point>> = emptyList(),
    /** Developer Mode: whether the raw-landmark overlay should be shown. */
    val devModeEnabled: Boolean = false,
)
