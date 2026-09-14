package isao.photorate.galleryComponent.data

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import isao.photorate.gallery.db.PhotoRateDb
import isao.photorate.galleryComponent.db.DetectedHand
import isao.photorate.galleryComponent.db.GalleryImage
import isao.photorate.galleryComponent.domain.GalleryImageRepository
import isao.photorate.galleryComponent.domain.GalleryImageStatus
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class DefaultGalleryImageRepository(
  @Provided private val db: PhotoRateDb,
  @Provided private val transacter: Transacter,
) : GalleryImageRepository {

  // TODO queries should be constructor-injected instead of the whole db
  private val galleryImageQueries = db.galleryImageQueries
  private val detectedHandQueries = db.detectedHandQueries

  override fun getImages(): Flow<List<GalleryImage>> =
    galleryImageQueries.selectAllImages().asFlow().mapToList(Dispatchers.IO)

  override fun getImageByUri(uri: String): Flow<GalleryImage?> =
    galleryImageQueries.selectImageByUri(uri).asFlow().mapToOneOrNull(Dispatchers.IO)

  override fun getUnprocessedImages(): Flow<List<GalleryImage>> =
    galleryImageQueries.selectUnprocessed().asFlow().mapToList(Dispatchers.IO)

  override fun getImagesWithMissingEmbeddings(): Flow<List<GalleryImage>> =
    galleryImageQueries.selectImagesMissingEmbeddings().asFlow().mapToList(Dispatchers.IO)

  override fun selectCount(status: GalleryImageStatus): Flow<Long> =
    galleryImageQueries.selectCount(status).asFlow().mapToOne(Dispatchers.IO)

  override suspend fun upsertImage(image: GalleryImage) {
    withContext(Dispatchers.IO) {
      galleryImageQueries.insertOrUpdateImage(
        uri = image.uri,
        createdAt = image.createdAt,
        modifiedAt = image.modifiedAt,
        status = image.status,
        scannedAt = image.scannedAt,
        detectedInMs = image.detectedInMs,
      )
    }
  }

  override suspend fun markDone(uri: String, detectedInMs: Long) {
    withContext(Dispatchers.IO) {
      galleryImageQueries.updateImageDone(
        detectedInMs,
        Clock.System.now().toEpochMilliseconds(),
        uri,
      )
    }
  }

  override suspend fun setScanFailed(uri: String) {
    withContext(Dispatchers.IO) {
      galleryImageQueries.updateImageStatus(
        status = GalleryImageStatus.FAILED,
        uri,
      )
    }
  }

  override suspend fun setScanSuccessful(
    uri: String,
    scanDuration: Duration,
    scannedAt: Instant,
    hands: List<DetectedHand>,
  ) {
    withContext(Dispatchers.IO) {
      transacter.transaction {
        galleryImageQueries.updateImageDone(
          detectedInMs = scanDuration.inWholeMilliseconds,
          scannedAt = scannedAt.toEpochMilliseconds(),
          uri = uri,
        )

        detectedHandQueries.deleteRealHandsForImage(uri)

        hands.forEach { hand ->
          detectedHandQueries.insertHand(
            imageUri = hand.imageUri,
            handIndex = hand.handIndex,
            score = hand.score,
            bboxAreaFraction = hand.bboxAreaFraction,
            points = hand.points,
            uncertain = hand.uncertain,
            isUserRated = hand.isUserRated,
          )
        }
      }
    }
  }

  override suspend fun setIgnored(uri: String) {
    withContext(Dispatchers.IO) {
      galleryImageQueries.updateImageStatus(
        status = GalleryImageStatus.IGNORED,
        uri,
      )
    }
  }

  override suspend fun reconcileOrphans(currentValidUris: Set<String>) {
    withContext(Dispatchers.IO) {
      transacter.transaction {
        val storedUris = galleryImageQueries.selectAllUris().executeAsList().toSet()
        val orphaned = storedUris - currentValidUris

        if (orphaned.isEmpty()) return@transaction

        orphaned.chunked(DELETE_BATCH_SIZE).forEach { galleryImageQueries.deleteByUris(it) }
      }
    }
  }

  override suspend fun deleteAll() {
    withContext(Dispatchers.IO) { galleryImageQueries.deleteAllImages() }
  }

  private companion object {
    const val DELETE_BATCH_SIZE = 900
  }
}
