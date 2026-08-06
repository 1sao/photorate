package isao.photorate.photosComponent.gallery

import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.photosComponent.populateGallery.AndroidSystemGalleryImageRepository
import org.koin.core.annotation.Factory

@Factory
class ResetGalleryOnVersionChangeUseCase(
    private val systemGalleryRepository: AndroidSystemGalleryImageRepository,
    private val galleryRepository: GalleryImageRepository,
) {
    suspend operator fun invoke(): Boolean {
        if (systemGalleryRepository.currentMediaStoreVersion != systemGalleryRepository.lastMediaStoreVersion) {
            galleryRepository.deleteAll()
            systemGalleryRepository.lastMediaStoreVersion = systemGalleryRepository.currentMediaStoreVersion
            return true
        }
        return false
    }
}
