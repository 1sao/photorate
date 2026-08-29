package isao.photorate.galleryUi

import isao.photorate.gallery.db.GalleryImageStatus
import isao.photorate.galleryComponent.classify.LandmarkPendingImagesUseCase
import isao.photorate.galleryComponent.gallery.SetUserScoreUseCase
import isao.photorate.galleryComponent.populateGallery.PopulateGalleryUseCase
import isao.photorate.galleryComponent.search.PopulateImageEmbeddingsUseCase
import isao.photorate.galleryRepository.GalleryFilterRepository
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.galleryRepository.GalleryStatusCounts
import isao.photorate.galleryUi.GalleryUiState.ImagesState
import isao.photorate.imageRecognition.classify.Score
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Factory

interface GalleryDelegate {
  val uiState: Flow<GalleryUiState>

  suspend fun rescan()

  /**
   * Applies the active CLIP-search filter to the grid: only images whose uri is in [uris] are
   * shown. Null removes the filter (plain gallery); the empty set means "no matches".
   */
  fun setSearchFilter(uris: Set<String>?)

  suspend fun acceptUncertain(uri: String, score: Int)

  suspend fun deleteUncertain(uri: String)

  /** Deletes every stored scan row (hands + embeddings cascade). */
  suspend fun deleteAllScans()
}

@OptIn(FlowPreview::class)
@Factory
class DefaultGalleryDelegate(
  private val populateGallery: PopulateGalleryUseCase,
  private val galleryImageRepository: GalleryImageRepository,
  private val landmarkPendingImages: LandmarkPendingImagesUseCase,
  private val galleryFilterRepository: GalleryFilterRepository,
  private val populateImageEmbeddings: PopulateImageEmbeddingsUseCase,
  private val setUserScore: SetUserScoreUseCase,
) : GalleryDelegate {

  // TODO gallery should not know about search
  private val searchFilter = MutableStateFlow<Set<String>?>(null)

  override val uiState: Flow<GalleryUiState> =
    combine(
      galleryFilterRepository.selectImagesWithScores(),
      galleryFilterRepository.selectUncertainImages(),
      galleryImageRepository.observeStatusCounts().sample(.1.seconds),
      searchFilter,
    ) { images, uncertainImages, status, filter ->
      GalleryUiState(
        imagesState =
          ImagesState(
            status = status,
            detections =
              images.map { row ->
                GalleryImageItem(
                  uri = row.uri,
                  scores = parseScores(row.scores),
                  scannedAt = row.scannedAt,
                )
              },
            uncertainDetections =
              uncertainImages.map { row ->
                GalleryImageItem(
                  uri = row.uri,
                  scores = listOf(row.bestGuessScore ?: Score.THREE),
                  scannedAt = row.scannedAt,
                )
              },
            searchUris = filter,
          ),
      )
    }

  private val rescanLock = Mutex()

  override suspend fun rescan() {
    rescanLock.withLock {
      populateGallery()
      landmarkPendingImages()
      populateImageEmbeddings()
    }
  }

  override fun setSearchFilter(uris: Set<String>?) {
    searchFilter.value = uris
  }

  override suspend fun acceptUncertain(uri: String, score: Int) {
    val enumScore = Score.entries.firstOrNull { it.score == score } ?: return
    runCatching { setUserScore(uri, enumScore) }
  }

  override suspend fun deleteUncertain(uri: String) {
    runCatching { galleryImageRepository.updateStatus(uri, GalleryImageStatus.IGNORED) }
  }

  override suspend fun deleteAllScans() {
    runCatching { galleryImageRepository.deleteAll() }
  }
}

data class GalleryUiState(
  val imagesState: ImagesState = ImagesState(),
) {
  data class ImagesState(
    val status: GalleryStatusCounts = GalleryStatusCounts(emptyMap()),
    val detections: List<GalleryImageItem> = emptyList(),
    val uncertainDetections: List<GalleryImageItem> = emptyList(),
    val searchUris: Set<String>? = null,
  )
}

data class GalleryImageItem(val uri: String, val scores: List<Score>, val scannedAt: Long? = null)

sealed interface GalleryIntent {
  // TODO
}

private fun parseScores(aggregated: String?): List<Score> =
  aggregated
    ?.split(',')
    .orEmpty()
    .mapNotNull { token ->
      val value = token.trim().toDoubleOrNull()?.roundToInt() ?: return@mapNotNull null
      Score.entries.firstOrNull { it.score == value }
    }
    .distinct()
    .sortedBy { it.score }
