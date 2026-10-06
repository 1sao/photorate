package isao.photorate.galleryComponent.domain

import isao.photorate.galleryComponent.domain.SystemGalleryImageRepository.Checkpoint
import isao.photorate.galleryComponent.domain.SystemGalleryImageRepository.RequestType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class PopulateGalleryUseCase(
  private val systemGalleryImageRepository: SystemGalleryImageRepository<Checkpoint>,
  private val galleryImageRepository: GalleryImageRepository,
) {
  suspend operator fun invoke(
    requestType: RequestType = RequestType.AFTER_CHECKPOINT_IF_AVAILABLE
  ): Int {
    return withContext(Dispatchers.IO) {
      val galleryScanResult = systemGalleryImageRepository.getImages(requestType)

      if (galleryScanResult.isCompleteScan) {
        galleryImageRepository.reconcileOrphans(galleryScanResult.images.map { it.uri }.toSet())
      }

      for (image in galleryScanResult.images) {
        galleryImageRepository.upsertImage(image)
      }

      systemGalleryImageRepository.saveCheckpoint(galleryScanResult.checkpoint)

      return@withContext galleryScanResult.images.size
    }
  }
}
