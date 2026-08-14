package isao.photorate.galleryUi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isao.photorate.config.FeatureFlagRepository
import isao.photorate.galleryComponent.gallery.SetUserScoreUseCase
import isao.photorate.galleryComponent.populateGallery.SystemGalleryImageRepository
import isao.photorate.galleryComponent.populateGallery.SystemImageDetails
import isao.photorate.galleryRepository.DetectedHandRepository
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.imageRecognition.classify.LandmarkedImage.Point
import isao.photorate.imageRecognition.classify.Score
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

sealed interface ImageDetailsIntent {
  /** Replaces the image's detections with a single hand carrying [score]. */
  data class SetScore(val score: Int) : ImageDetailsIntent

  /** Removes the image from the app (the file stays in the device gallery). */
  data object DeleteImage : ImageDetailsIntent
}

@KoinViewModel
class ImageDetailsViewModel(
  /** The image this screen instance is bound to (injected per navigation). */
  @InjectedParam private val uri: String,
  private val detectedHandRepository: DetectedHandRepository,
  private val systemGalleryImageRepository: SystemGalleryImageRepository,
  private val setUserScore: SetUserScoreUseCase,
  private val featureFlagRepository: FeatureFlagRepository,
  private val galleryImageRepository: GalleryImageRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ImageDetailsUiState(imageUri = uri))
  val uiState: StateFlow<ImageDetailsUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      featureFlagRepository.devModeEnabled().collect { enabled ->
        _uiState.update { it.copy(devModeEnabled = enabled) }
      }
    }
    viewModelScope.launch {
      detectedHandRepository.getHandsForImage(uri).collect { hands ->
        val userRated = hands.filter { hand -> hand.isUserRated }
        // A user rating replaces the real scores for display (the real
        // detections stay in the DB for statistics).
        val displayed = if (userRated.isNotEmpty()) userRated else hands
        _uiState.update {
          it.copy(
            scores =
              displayed.map { hand -> hand.score }.distinct().sortedBy { score -> score.score },
            uncertain =
              userRated.isEmpty() &&
                displayed.isNotEmpty() &&
                displayed.all { hand -> hand.uncertain },
            hasUserRating = userRated.isNotEmpty(),
            // Raw real-detection landmarks for the dev-mode overlay
            // (the user-rated fake hand has no geometry to show).
            landmarkHands =
              hands.filterNot { hand -> hand.isUserRated }.map { hand -> hand.points },
          )
        }
      }
    }
    viewModelScope.launch {
      // Pull straight from the
      // system gallery; never
      // cached. A failure
      // (e.g. file removed) just
      // leaves the metadata fields
      // empty.
      val details = runCatching {
        systemGalleryImageRepository.getImageDetails(uri)
      }
        .getOrNull()
      _uiState.update { it.copy(details = details) }
    }
  }

  fun onIntent(intent: ImageDetailsIntent) {
    when (intent) {
      is ImageDetailsIntent.SetScore -> setScore(intent.score)
      ImageDetailsIntent.DeleteImage -> deleteImage()
    }
  }

  /** Replaces the image's detections with a single hand carrying [score]. */
  private fun setScore(score: Int) {
    val enumScore = Score.entries.firstOrNull { it.score == score } ?: return
    viewModelScope.launch {
      // The screen is popped right after the tap, clearing the entry's
      // ViewModelStore; the write must finish regardless.
      withContext(NonCancellable) {
        runCatching {
          setUserScore(
            uri,
            enumScore,
          )
        }
      }
    }
  }

  /**
   * Removes the image from the app: deletes its DB row (hands and embedding cascade) so it leaves
   * the gallery grid. The file stays in the device gallery — the caller navigates back once this
   * completes.
   */
  // TODO hide instead to track history
  private fun deleteImage() {
    if (uri.isBlank()) return
    viewModelScope.launch {
      // Same as setScore: the caller pops the route right after this intent.
      withContext(NonCancellable) { runCatching { galleryImageRepository.deleteImage(uri) } }
    }
  }
}

data class ImageDetailsUiState(
  val imageUri: String,
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
