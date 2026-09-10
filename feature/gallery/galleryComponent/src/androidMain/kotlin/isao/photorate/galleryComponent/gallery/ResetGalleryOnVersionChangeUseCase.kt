package isao.photorate.galleryComponent.gallery

import isao.photorate.galleryComponent.populateGallery.AndroidSystemGalleryImageRepository
import isao.photorate.galleryRepository.GalleryImageRepository
import org.koin.core.annotation.Factory

@Factory
class ResetGalleryOnVersionChangeUseCase(
  private val systemGalleryRepository: AndroidSystemGalleryImageRepository,
  private val galleryRepository: GalleryImageRepository,
) {
  suspend operator fun invoke(): Boolean {
    if (
      systemGalleryRepository.currentMediaStoreVersion !=
        systemGalleryRepository.lastMediaStoreVersion
    ) {
      galleryRepository.deleteAll()
      systemGalleryRepository.resetGenerationCheckpoints()
      systemGalleryRepository.lastMediaStoreVersion =
        systemGalleryRepository.currentMediaStoreVersion
      return true
    }
    return false
  }
}
