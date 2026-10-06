package isao.photorate.galleryComponent.domain

import arrow.core.raise.Raise
import isao.photorate.imageRecognition.ResourceFailure

interface SystemGalleryImageRepository<Checkpoint : SystemGalleryImageRepository.Checkpoint> {
  /**
   * Returns the gallery images plus the incremental-sync checkpoint that must be saved (via
   * [saveCheckpoint]) only after the images have been persisted.
   *
   * @param requestType [RequestType.FULL_SCAN] returns every image in the gallery;
   *   [RequestType.AFTER_CHECKPOINT_IF_AVAILABLE] returns only images changed since the last saved
   *   checkpoint, or every image if no checkpoint exists.
   */
  suspend fun getImages(requestType: RequestType): GalleryScanResult

  enum class RequestType {
    FULL_SCAN,
    AFTER_CHECKPOINT_IF_AVAILABLE,
  }

  /** Persists the checkpoint captured by the last [getImages] call. */
  suspend fun saveCheckpoint(checkpoint: Checkpoint)

  /** Fetches one image's metadata straight from the system gallery (no caching). */
  context(_: Raise<ResourceFailure>)
  suspend fun getImageDetails(uri: String): SystemImageDetails

  /**
   * A checkpoint for the gallery state, used to retrieve incremental updates after this checkpoint.
   */
  interface Checkpoint
}
