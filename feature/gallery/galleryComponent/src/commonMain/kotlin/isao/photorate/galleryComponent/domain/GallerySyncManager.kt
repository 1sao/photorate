package isao.photorate.galleryComponent.domain

import arrow.core.IorNel
import arrow.core.getOrElse
import co.touchlab.kermit.Logger
import isao.photorate.imageRecognition.ResourceFailure
import kotlin.time.measureTimedValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

@Single
class GallerySyncManager(
  private val populateGallery: PopulateGalleryUseCase,
  private val landmarkPendingImages: LandmarkPendingImagesUseCase,
  private val populateImageEmbeddings: EmbedUnembeddedImagesUseCase,
  private val log: Logger,
) {
  private val syncMutex = Mutex()

  suspend fun run() {
    syncMutex.withLock {
      logResults("PopulatingGallery") { populateGallery() }
      logIorResults("Landmarking") { landmarkPendingImages() }
      logIorResults("Embedding") { populateImageEmbeddings() }
    }
  }

  private inline fun logResults(tag: String, block: () -> Int) {
    val (total, time) = measureTimedValue { block() }
    log.i("Processed $total images in $time", tag = tag)
  }

  private inline fun logIorResults(tag: String, block: () -> IorNel<ResourceFailure, Int>) {
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
