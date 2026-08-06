package isao.photorate.photosComponent.populateGallery

import isao.photorate.galleryRepository.GalleryImageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class PopulateGalleryUseCase(
    private val systemGalleryImageRepository: SystemGalleryImageRepository,
    private val galleryImageRepository: GalleryImageRepository,
) {
    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        val images = systemGalleryImageRepository.getAllImages()
        val validUris = images.map { it.uri }.toSet()

        // TODO uncomment
        // galleryImageRepository.reconcileOrphans(validUris)

        // TODO remove limit .take(100)
        for (image in images) {
            galleryImageRepository.upsertImage(image)
        }
    }
}
