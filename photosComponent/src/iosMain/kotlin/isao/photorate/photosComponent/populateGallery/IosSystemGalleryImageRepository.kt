package isao.photorate.photosComponent.populateGallery

import isao.photorate.db.GalleryImage
import org.koin.core.annotation.Factory

/**
 * iOS [SystemGalleryImageRepository] placeholder — the populate-from-gallery
 * pipeline is Android-only for now (PHAsset population not implemented).
 * Returns empty so the Koin graph is complete on the iOS target (KOIN-D001).
 */
@Factory
class IosSystemGalleryImageRepository : SystemGalleryImageRepository {
    override suspend fun getAllImages(): List<GalleryImage> = emptyList()

    override suspend fun getImageDetails(uri: String): SystemImageDetails? = null
}
