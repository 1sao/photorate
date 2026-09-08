package isao.photorate.galleryComponent.populateGallery

import arrow.core.raise.Raise
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.imageRecognition.ResourceFailure

interface SystemGalleryImageRepository {
  suspend fun getAllImages(): List<GalleryImage>

  /** Fetches one image's metadata straight from the system gallery (no caching). */
  context(_: Raise<ResourceFailure>)
  suspend fun getImageDetails(uri: String): SystemImageDetails
}
