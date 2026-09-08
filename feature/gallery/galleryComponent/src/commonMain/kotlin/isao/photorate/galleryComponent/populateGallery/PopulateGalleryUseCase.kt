package isao.photorate.galleryComponent.populateGallery

import isao.photorate.galleryRepository.GalleryImageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class PopulateGalleryUseCase(
  private val systemGalleryImageRepository: SystemGalleryImageRepository,
  private val galleryImageRepository: GalleryImageRepository,
) {
  suspend operator fun invoke() {
    withContext(Dispatchers.IO) {
      val images = with(systemGalleryImageRepository) { getAllImages() }

      // TODO uncomment
      // galleryImageRepository.reconcileOrphans(images.map { it.uri }.toSet())

      for (image in images) {
        galleryImageRepository.upsertImage(image)
      }
    }
  }
}
