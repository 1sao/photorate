package isao.photorate.galleryComponent.populateGallery

import isao.photorate.gallery.db.GalleryImage

interface SystemGalleryImageRepository {
    suspend fun getAllImages(): List<GalleryImage>

    /** Fetches one image's metadata straight from the system gallery (no caching). */
    suspend fun getImageDetails(uri: String): SystemImageDetails?
}
