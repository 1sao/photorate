package isao.photorate.galleryRepository

import isao.photorate.gallery.db.CountsByStatus
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.gallery.db.GalleryImageStatus
import kotlinx.coroutines.flow.Flow

interface GalleryImageRepository {
  fun getImages(): Flow<List<GalleryImage>>

  fun getImageByUri(uri: String): Flow<GalleryImage?>

  fun getUnprocessedImages(): Flow<List<GalleryImage>>

  /** Images with real detected hands that don't have a CLIP embedding yet. */
  fun getImagesMissingEmbeddings(): Flow<List<GalleryImage>>

  suspend fun upsertImage(image: GalleryImage)

  suspend fun updateStatus(uri: String, status: GalleryImageStatus)

  suspend fun markDone(uri: String, detectedInMs: Long)

  /**
   * Atomically rejects an image's detections: removes its real hands and CLIP embedding and marks
   * it DONE, so it behaves like a scanned image with no hand (and drops out of search). Used by the
   * uncertain review's delete.
   */
  suspend fun markNoHand(uri: String)

  suspend fun reconcileOrphans(currentValidUris: Set<String>)

  suspend fun deleteImage(uri: String)

  suspend fun deleteAll()

  fun getStatus(): Flow<CountsByStatus>

  fun observeStatusCounts(): Flow<GalleryStatusCounts>
}
