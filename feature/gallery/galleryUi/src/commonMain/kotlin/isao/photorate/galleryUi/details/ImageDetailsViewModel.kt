package isao.photorate.galleryUi.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.core.raise.either
import co.touchlab.kermit.Logger
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
  private val log: Logger,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ImageDetailsUiState(imageUri = uri))
  val uiState: StateFlow<ImageDetailsUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      featureFlagRepository.isDevModeEnabled().collect { enabled ->
        _uiState.update { it.copy(devModeEnabled = enabled) }
      }
    }
    viewModelScope.launch {
      detectedHandRepository.getHandsForImage(uri).collect { hands ->
        val userRated = hands.filter { hand -> hand.isUserRated }
        // A user rating replaces the real scores for display (the real
        // detections stay in the DB as they might be useful later).
        val displayedScores = userRated.ifEmpty { hands }
        _uiState.update {
          it.copy(
            scores =
              displayedScores
                .map { hand -> hand.score }
                .distinct()
                .sortedBy { score -> score.score },
            hasUserRating = userRated.isNotEmpty(),
            landmarkHands = hands.map { hand -> hand.points },
          )
        }
      }
    }
    viewModelScope.launch {
      val details = either {
        systemGalleryImageRepository.getImageDetails(uri)
      }
        .onLeft {
          // TODO this should happen practically never. Is it worth handling properly?
          log.e { "Unable to open image details: $it" }
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

  private fun setScore(score: Int) {
    val enumScore = Score.entries.firstOrNull { it.score == score } ?: return
    viewModelScope.launch { withContext(NonCancellable) { setUserScore(uri, enumScore) } }
  }

  private fun deleteImage() {
    viewModelScope.launch { withContext(NonCancellable) { galleryImageRepository.setIgnored(uri) } }
  }
}

data class ImageDetailsUiState(
  val imageUri: String,
  /** Distinct scores shown (user rating when present, otherwise detected). */
  val scores: List<Score> = emptyList(),
  /** True when the shown scores are the user's own rating. */
  val hasUserRating: Boolean = false,
  /** System gallery metadata; null while loading or when unavailable. */
  val details: SystemImageDetails? = null,
  /** Developer Mode: raw real-detection landmarks per hand (overlay on the photo). */
  val landmarkHands: List<List<Point>> = emptyList(),
  /** Developer Mode: whether the raw-landmark overlay should be shown. */
  val devModeEnabled: Boolean = false,
)
