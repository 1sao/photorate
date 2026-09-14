package isao.photorate.galleryComponent.domain

import isao.photorate.galleryComponent.db.DetectedHand
import isao.photorate.galleryComponent.db.GalleryImage
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

  suspend fun markDone(uri: String, detectedInMs: Long)

  suspend fun reconcileOrphans(currentValidUris: Set<String>)

  suspend fun deleteAll()

  fun selectCount(status: GalleryImageStatus): Flow<Long>

  suspend fun setScanFailed(uri: String)

  suspend fun setScanSuccessful(
    uri: String,
    scanDuration: Duration,
    scannedAt: Instant,
    hands: List<DetectedHand>,
  )

  suspend fun setIgnored(uri: String)
}
