package isao.photorate.galleryComponent.data

import arrow.core.raise.Raise
import arrow.core.raise.context.raise
import isao.photorate.galleryComponent.domain.Checkpoint
import isao.photorate.galleryComponent.domain.GalleryScanResult
import isao.photorate.galleryComponent.domain.SystemGalleryImageRepository
import isao.photorate.galleryComponent.domain.SystemImageDetails
import isao.photorate.imageRecognition.ResourceFailure
import org.koin.core.annotation.Factory

/**
 * iOS [isao.photorate.galleryComponent.domain.SystemGalleryImageRepository] placeholder — the
 * populate-from-gallery pipeline is Android-only for now.
 */
@Factory
class IosSystemGalleryImageRepository : SystemGalleryImageRepository {
  override suspend fun getAllImagesAfterLastCheckpoint(): GalleryScanResult =
    GalleryScanResult(
      images = emptyList(),
      checkpoint = Checkpoint(),
      isCompleteScan = true,
    )

  override suspend fun saveCheckpoint(checkpoint: Checkpoint) {}

  context(_: Raise<ResourceFailure>)
  override suspend fun getImageDetails(uri: String): SystemImageDetails =
    raise(ResourceFailure.NotFound(uri))
}
