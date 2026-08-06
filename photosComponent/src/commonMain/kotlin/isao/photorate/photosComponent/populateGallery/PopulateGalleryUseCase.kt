package isao.photorate.photosComponent.populateGallery

import isao.photorate.galleryRepository.GalleryImageRepository
import org.koin.core.annotation.Factory

@Factory
class PopulateGalleryUseCase(
    private val systemGalleryImageRepository: SystemGalleryImageRepository,
    private val galleryImageRepository: GalleryImageRepository,
) { // TODO NEXT TASK make sure every UseCase runs on Dispatchers.IO for IO and Dispatchers.Default for inference.
    suspend operator fun invoke() {
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
