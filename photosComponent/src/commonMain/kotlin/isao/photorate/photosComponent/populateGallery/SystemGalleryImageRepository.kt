package isao.photorate.photosComponent.populateGallery

import isao.photorate.db.GalleryImage

interface SystemGalleryImageRepository {
    suspend fun getAllImages(): List<GalleryImage>

    /** Fetches one image's metadata straight from the system gallery (no caching). */
    suspend fun getImageDetails(uri: String): SystemImageDetails?
}
