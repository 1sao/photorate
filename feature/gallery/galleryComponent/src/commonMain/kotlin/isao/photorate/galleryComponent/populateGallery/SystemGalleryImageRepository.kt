package isao.photorate.galleryComponent.populateGallery

import arrow.core.raise.Raise
import isao.photorate.imageRecognition.ResourceFailure

interface SystemGalleryImageRepository {
  /**
   * Returns the gallery images plus the incremental-sync checkpoint that must be saved (via
   * [saveCheckpoint]) only after the images have been persisted.
   */
  suspend fun getAllImagesAfterLastCheckpoint(): GalleryScanResult

  /** Persists the checkpoint captured by the last [getAllImagesAfterLastCheckpoint] call. */
  suspend fun saveCheckpoint(checkpoint: Checkpoint)

  /** Fetches one image's metadata straight from the system gallery (no caching). */
  context(_: Raise<ResourceFailure>)
  suspend fun getImageDetails(uri: String): SystemImageDetails
}
