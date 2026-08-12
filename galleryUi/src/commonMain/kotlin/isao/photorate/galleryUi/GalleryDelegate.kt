package isao.photorate.galleryUi

import isao.photorate.config.ConfigRepository
import isao.photorate.config.GallerySorting
import isao.photorate.gallery.db.SelectUncertainImagesWithScore
import isao.photorate.galleryComponent.classify.LandmarkPendingImagesUseCase
import isao.photorate.galleryComponent.gallery.SetUserScoreUseCase
import isao.photorate.galleryComponent.populateGallery.PopulateGalleryUseCase
import isao.photorate.galleryComponent.search.PopulateImageEmbeddingsUseCase
import isao.photorate.galleryRepository.GalleryFilterRepository
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.galleryRepository.GalleryStatusCounts
import isao.photorate.galleryUi.GalleryUiState.ImageSorting
import isao.photorate.galleryUi.GalleryUiState.ImagesState
import isao.photorate.galleryUi.GalleryUiState.PermissionState
import isao.photorate.inference.classify.Score
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
  private val configRepository: ConfigRepository,
) : GalleryDelegate {

  private val searchFilter = MutableStateFlow<Set<String>?>(null)

  override val uiState: Flow<GalleryUiState> =
    combine(
      galleryFilterRepository.selectImagesWithScores(),
      galleryFilterRepository.selectUncertainImages(),
      galleryImageRepository.observeStatusCounts().sample(.1.seconds),
      configRepository.getConfig(),
      searchFilter,
    ) { images, uncertain, status, config, filter ->
      GalleryUiState(
        permissionState = PermissionState.Denied,
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
            uncertainDetections = uncertain,
            sorting = config.sorting.toImageSorting(),
            searchUris = filter,
          ),
      )
    }

  // Serialized with a mutex: rescans share the singleton landmarker and
  // overlapping scans caused native crashes, so a scan that's already
  // running is never overlapped — callers queue instead.
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
    runCatching { galleryImageRepository.markNoHand(uri) }
  }

  override suspend fun deleteAllScans() {
    runCatching { galleryImageRepository.deleteAll() }
  }
}

data class GalleryUiState(
  val permissionState: PermissionState = PermissionState.Denied,
  val imagesState: ImagesState = ImagesState(),
) {
  // TODO remove
  sealed interface PermissionState {
    data object Granted : PermissionState

    data object GrantedPartially : PermissionState

    data object Denied : PermissionState
  }

  data class ImagesState(
    val status: GalleryStatusCounts = GalleryStatusCounts(emptyMap()),
    val detections: List<GalleryImageItem> = emptyList(),
    val uncertainDetections: List<SelectUncertainImagesWithScore> = emptyList(),
    val sorting: ImageSorting = ImageSorting.Date(isAscending = false),
    val searchUris: Set<String>? = null,
  )

  sealed interface ImageSorting {
    val isAscending: Boolean

    data class Date(override val isAscending: Boolean) : ImageSorting

    data class Score(override val isAscending: Boolean) : ImageSorting
  }
}

data class GalleryImageItem(val uri: String, val scores: List<Score>, val scannedAt: Long? = null)

sealed interface GalleryIntent {
  // TODO
}

private fun GallerySorting.toImageSorting(): ImageSorting =
  when (this) {
    GallerySorting.SCORE -> ImageSorting.Score(isAscending = false)
    GallerySorting.DATE -> ImageSorting.Date(isAscending = false)
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
