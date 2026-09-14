package isao.photorate.galleryComponent.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class PopulateGalleryUseCase(
  private val systemGalleryImageRepository: SystemGalleryImageRepository,
  private val galleryImageRepository: GalleryImageRepository,
) {
  suspend operator fun invoke(): Int {
    return withContext(Dispatchers.IO) {
      val galleryScanResult = systemGalleryImageRepository.getAllImagesAfterLastCheckpoint()

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
