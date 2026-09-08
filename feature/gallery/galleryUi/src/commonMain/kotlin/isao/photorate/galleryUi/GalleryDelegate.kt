package isao.photorate.galleryUi

import arrow.core.IorNel
import arrow.core.getOrElse
import co.touchlab.kermit.Logger
import isao.photorate.gallery.db.GalleryImageStatus
import isao.photorate.galleryComponent.classify.LandmarkPendingImagesUseCase
import isao.photorate.galleryComponent.gallery.SetUserScoreUseCase
import isao.photorate.galleryComponent.populateGallery.PopulateGalleryUseCase
import isao.photorate.galleryComponent.search.EmbedUnembeddedImagesUseCase
import isao.photorate.galleryRepository.GalleryFilterRepository
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.galleryRepository.GalleryStatusCounts
import isao.photorate.galleryUi.GalleryUiState.ImagesState
import isao.photorate.imageRecognition.ResourceFailure
import isao.photorate.imageRecognition.classify.Score
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
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
  private val populateImageEmbeddings: EmbedUnembeddedImagesUseCase,
  private val setUserScore: SetUserScoreUseCase,
  private val log: Logger,
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
    // TODO Move to a worker, consider making it a separate usecase
    rescanLock.withLock {
      populateGallery()
      logResults("Landmarking") { landmarkPendingImages() }
      logResults("Embedding") { populateImageEmbeddings() }

      val embeddingResult = populateImageEmbeddings()
      val total = embeddingResult.getOrElse { 0 }
      val failed = embeddingResult.leftOrNull()?.size ?: 0
      log.i {
        "Tried embedding $total images, out of which ${total - failed} embeddings were successful"
      }
      embeddingResult.leftOrNull()?.let { failures ->
        failures.forEach { log.e("Embedding image failure: $it") }
      }
    }
  }

  override fun setSearchFilter(uris: Set<String>?) {
    searchFilter.value = uris
  }

  override suspend fun acceptUncertain(uri: String, score: Int) {
    val enumScore = Score.entries.firstOrNull { it.score == score } ?: return
    setUserScore(uri, enumScore)
  }

  override suspend fun deleteUncertain(uri: String) {
    galleryImageRepository.updateStatus(uri, GalleryImageStatus.IGNORED)
  }

  override suspend fun deleteAllScans() {
    galleryImageRepository.deleteAll()
  }

  private inline fun logResults(tag: String, block: () -> IorNel<ResourceFailure, Int>) {
    val (result, time) = measureTimedValue { block() }
    val total = result.getOrElse { 0 }
    val failed = result.leftOrNull()?.size ?: 0
    val successful = total - failed
    log.i(
      "Processed $total images in $time, out of which $successful were successful and $failed were failed.",
      tag = tag,
    )
    result.leftOrNull()?.let { failures ->
      failures.forEach { log.e("Embedding image failure: $it") }
    }
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
