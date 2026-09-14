package isao.photorate.galleryUi

import isao.photorate.gallery.db.GalleryImageStatus
import isao.photorate.galleryComponent.gallery.SetUserScoreUseCase
import isao.photorate.galleryRepository.GalleryFilterRepository
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.galleryUi.GalleryUiState.ImagesState
import isao.photorate.imageRecognition.classify.Score
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import org.koin.core.annotation.Factory

interface GalleryDelegate {
  val uiState: Flow<GalleryUiState>

  suspend fun onIntent(intent: GalleryIntent)
}

@OptIn(FlowPreview::class)
@Factory
class DefaultGalleryDelegate(
  private val galleryImageRepository: GalleryImageRepository,
  private val galleryFilterRepository: GalleryFilterRepository,
  private val setUserScore: SetUserScoreUseCase,
) : GalleryDelegate {

  override val uiState: Flow<GalleryUiState> =
    combine(
      galleryFilterRepository.selectImagesWithScores(),
      galleryFilterRepository.selectUncertainImages(),
      selectStatusCounts().sample(.1.seconds),
    ) { images, uncertainImages, status ->
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
          ),
      )
    }

  override suspend fun onIntent(intent: GalleryIntent) {
    when (intent) {
      is GalleryIntent.AcceptUncertain -> acceptUncertain(intent.uri, intent.score)
      is GalleryIntent.DeleteUncertain -> deleteUncertain(intent.uri)
    }
  }

  private suspend fun acceptUncertain(uri: String, score: Int) {
    val enumScore = Score.entries.firstOrNull { it.score == score } ?: return
    setUserScore(uri, enumScore)
  }

  private suspend fun deleteUncertain(uri: String) {
    galleryImageRepository.setIgnored(uri)
  }

  private fun selectStatusCounts(): Flow<GalleryStatusCounts> =
    combine(
      galleryImageRepository.selectCount(GalleryImageStatus.PENDING),
      galleryImageRepository.selectCount(GalleryImageStatus.DONE),
      galleryImageRepository.selectCount(GalleryImageStatus.FAILED),
    ) { pending, done, failed ->
      GalleryStatusCounts(
        pending = pending,
        done = done,
        failed = failed,
      )
    }
}

data class GalleryUiState(
  val imagesState: ImagesState = ImagesState(),
) {
  data class ImagesState(
    val status: GalleryStatusCounts = GalleryStatusCounts(),
    val detections: List<GalleryImageItem> = emptyList(),
    val uncertainDetections: List<GalleryImageItem> = emptyList(),
  )
}

data class GalleryStatusCounts(
  val pending: Long = 0L,
  val done: Long = 0L,
  val failed: Long = 0L,
) {
  val total: Long
    get() = pending + done + failed
}

data class GalleryImageItem(val uri: String, val scores: List<Score>, val scannedAt: Long? = null)

sealed interface GalleryIntent {
  data class AcceptUncertain(val uri: String, val score: Int) : GalleryIntent

  data class DeleteUncertain(val uri: String) : GalleryIntent
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
