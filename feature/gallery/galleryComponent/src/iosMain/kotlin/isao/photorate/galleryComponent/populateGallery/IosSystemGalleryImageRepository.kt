package isao.photorate.galleryComponent.populateGallery

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.imageRecognition.ResourceFailure
import org.koin.core.annotation.Factory

/**
 * iOS [SystemGalleryImageRepository] placeholder — the populate-from-gallery pipeline is
 * Android-only for now.
 */
@Factory
class IosSystemGalleryImageRepository : SystemGalleryImageRepository {
  override suspend fun getAllImagesAfterLastCheckpoint(): Pair<List<GalleryImage>, Checkpoint> =
    emptyList<GalleryImage>() to Checkpoint()

  override suspend fun saveCheckpoint(checkpoint: Checkpoint) {}

  context(_: Raise<ResourceFailure>)
  override suspend fun getImageDetails(uri: String): SystemImageDetails =
    raise(ResourceFailure.NotFound(uri))
}
