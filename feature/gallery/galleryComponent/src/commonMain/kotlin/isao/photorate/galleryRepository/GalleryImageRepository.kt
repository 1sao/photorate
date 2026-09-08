package isao.photorate.galleryRepository

import isao.photorate.gallery.db.CountsByStatus
import isao.photorate.gallery.db.DetectedHand
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.gallery.db.GalleryImageStatus
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

interface GalleryImageRepository {
  fun getImages(): Flow<List<GalleryImage>>

  fun getImageByUri(uri: String): Flow<GalleryImage?>

  fun getUnprocessedImages(): Flow<List<GalleryImage>>

  /** Images with real detected hands that don't have a CLIP embedding yet. */
  fun getImagesWithMissingEmbeddings(): Flow<List<GalleryImage>>

  suspend fun upsertImage(image: GalleryImage)

  suspend fun updateStatus(uri: String, status: GalleryImageStatus)

  suspend fun markDone(uri: String, detectedInMs: Long)

  suspend fun reconcileOrphans(currentValidUris: Set<String>)

  suspend fun deleteAll()

  fun getStatus(): Flow<CountsByStatus>

  fun observeStatusCounts(): Flow<GalleryStatusCounts>

  suspend fun setScanFailed(uri: String)

  suspend fun setScanStarted(uri: String)

  suspend fun setScanSuccessful(
    uri: String,
    scanDuration: Duration,
    scannedAt: Instant,
    hands: List<DetectedHand>,
  )
}
