package isao.photorate.galleryComponent.populateGallery

import isao.photorate.gallery.db.GalleryImage

/**
 * Outcome of a system-gallery scan.
 *
 * [isCompleteScan] is true only when [images] covers every image currently visible to the app. A
 * generation/checkpoint-based incremental scan is not complete and must never be used to reconcile
 * stored rows against, since the rows it omits are still valid.
 */
data class GalleryScanResult(
  val images: List<GalleryImage>,
  val checkpoint: Checkpoint,
  val isCompleteScan: Boolean,
)
