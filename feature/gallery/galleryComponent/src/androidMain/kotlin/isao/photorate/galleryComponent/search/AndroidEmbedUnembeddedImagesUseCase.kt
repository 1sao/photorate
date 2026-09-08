package isao.photorate.galleryComponent.search

import android.graphics.Bitmap
import arrow.core.IorNel
import arrow.core.raise.context.forEachAccumulating
import arrow.core.raise.context.iorNel
import co.touchlab.kermit.Logger
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.galleryRepository.ImageEmbeddingRepository
import isao.photorate.imageRecognition.ResourceFailure
import isao.photorate.imageRecognition.classify.LandmarkImageLoader
import isao.photorate.imageRecognition.search.AppClipSearch
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Android [EmbedUnembeddedImagesUseCase] backed by the MobileCLIP vision encoder ([AppClipSearch]).
 *
 * For every hand image missing an embedding it decodes a scaled-down version of the photo and
 * embeds it. The vision model only ever sees a 224x224 center crop, so a full-resolution decode is
 * unnecessary.
 */
// TODO should be used for both Android and iOS with a few multiplatform adjustments.
@Factory
class AndroidEmbedUnembeddedImagesUseCase(
  private val galleryImageRepository: GalleryImageRepository,
  private val imageEmbeddingRepository: ImageEmbeddingRepository,
  private val imageLoader: LandmarkImageLoader,
  // External binding from the root PlatformModule (see AndroidSearchImagesUseCase).
  @Provided private val searchFactory: AppClipSearchFactory,
  private val log: Logger,
) : EmbedUnembeddedImagesUseCase {
  override suspend operator fun invoke(): IorNel<ResourceFailure, Int> {
    // TODO ior does not match this our case perfectly: it will never return left, only either right
    //  or both. Is it worth extending Arrow.kt or creating our own class for holding results for
    //  such cases?
    return iorNel {
      withContext(Dispatchers.IO) {
        // TODO A failed embedding will be retried every time. Consider implementing smarter logic?
        val missing = galleryImageRepository.getImagesWithMissingEmbeddings().first()
        if (missing.isEmpty()) return@withContext 0
        searchFactory.createFromOptions(AppClipSearchFactory.Options()).use { clip ->
          forEachAccumulating(missing) { image ->
            val bitmap = imageLoader.load(image.uri, DECODE_MIN_DIM)
            val embedding = clip.embedImage(bitmap.toJpegBytes())
            imageEmbeddingRepository.upsert(image.uri, embedding)
            yield()
          }
        }
        return@withContext missing.size
      }
    }
  }

  private fun Bitmap.toJpegBytes(): ByteArray =
    ByteArrayOutputStream().use { out ->
      compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
      out.toByteArray()
    }

  private companion object {
    // TODO try decreasing MIN_DIM. Does this introduce regressions?
    const val DECODE_MIN_DIM = 640
    const val JPEG_QUALITY = 95
  }
}
