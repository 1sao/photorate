package isao.photorate.galleryUi

import isao.photorate.galleryComponent.domain.GalleryImageRepository
import isao.photorate.galleryComponent.domain.GalleryImageStatus
import isao.photorate.galleryComponent.domain.SetUserScoreUseCase
import isao.photorate.galleryUi.GalleryUiState.ImagesState
import isao.photorate.imageRecognition.Score
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
  private val setUserScore: SetUserScoreUseCase,
) : GalleryDelegate {

  override val uiState: Flow<GalleryUiState> =
    combine(
      galleryImageRepository.selectScoredImages(),
      selectStatusCounts().sample(.1.seconds),
    ) { scoredImages, status ->
      val (certain, uncertain) = scoredImages.partition { it.isCertain }
      GalleryUiState(
        imagesState =
          ImagesState(
            status = status,
            detections =
              certain.map { row ->
                GalleryImageItem(
                  uri = row.uri,
                  score = row.bestScore,
                  scannedAt = row.scannedAt,
                )
              },
            uncertainDetections =
              uncertain.map { row ->
                GalleryImageItem(
                  uri = row.uri,
                  score = row.bestScore,
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
      galleryImageRepository.selectImagesWithDetectionsCount(),
    ) { pending, done, failed, detected ->
      GalleryStatusCounts(
        pending = pending,
        done = done,
        failed = failed,
        detected = detected,
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
  val detected: Long = 0L,
) {
  val total: Long
    get() = pending + done + failed
}

data class GalleryImageItem(val uri: String, val score: Score, val scannedAt: Long? = null)

sealed interface GalleryIntent {
  data class AcceptUncertain(val uri: String, val score: Int) : GalleryIntent

  data class DeleteUncertain(val uri: String) : GalleryIntent
}
