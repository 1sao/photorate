package isao.photorate.galleryComponent.domain

import isao.photorate.galleryComponent.data.AndroidSystemGalleryImageRepository
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
