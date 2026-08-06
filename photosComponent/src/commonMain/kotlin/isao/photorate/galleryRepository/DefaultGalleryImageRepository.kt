package isao.photorate.galleryRepository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import isao.photorate.db.CountsByStatus
import isao.photorate.db.GalleryImage
import isao.photorate.db.GalleryImageStatus
import isao.photorate.db.PhotoRateDb
import isao.photorate.sqldelight.transactionWithContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class DefaultGalleryImageRepository(private val db: PhotoRateDb) : GalleryImageRepository {

    // TODO queries should be constructor-injected instead of the whole db
    private val queries get() = db.galleryImageQueries
    private val embeddingQueries get() = db.imageEmbeddingQueries
    private val detectedHandQueries get() = db.detectedHandQueries

    override fun getImages(): Flow<List<GalleryImage>> = queries.selectAllImages()
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun getImageByUri(uri: String): Flow<GalleryImage?> = queries.selectImageByUri(uri)
        .asFlow()
        .mapToOneOrNull(Dispatchers.IO)

    override fun getUnprocessedImages(): Flow<List<GalleryImage>> = queries.selectUnprocessed()
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun getImagesMissingEmbeddings(): Flow<List<GalleryImage>> = queries.selectImagesMissingEmbeddings()
        .asFlow()
        .mapToList(Dispatchers.IO)

    override fun getStatus(): Flow<CountsByStatus> = queries.countsByStatus()
        .asFlow()
        .mapToOne(Dispatchers.IO)

    override fun observeStatusCounts(): Flow<GalleryStatusCounts> = queries.countsByStatus()
        .asFlow()
        .mapToList(Dispatchers.IO)
        .map { rows ->
            val raw = rows.associate { it.status to it.count }
            // Fill in zeros for statuses with no rows so the UI doesn't have to guess.
            val complete = GalleryImageStatus.entries.associateWith { raw[it] ?: 0L }
            GalleryStatusCounts(complete)
        }

    override suspend fun upsertImage(image: GalleryImage) {
        // The upsert preserves scan results for unchanged images (see the
        // insertOrUpdateImage ON CONFLICT clause); only metadata is refreshed.
        withContext(Dispatchers.IO) {
            queries.insertOrUpdateImage(
                uri = image.uri,
                createdAt = image.createdAt,
                modifiedAt = image.modifiedAt,
                status = image.status,
                scannedAt = image.scannedAt, // TODO save scannedAt
                detectedInMs = image.detectedInMs,
            )
        }
    }

    override suspend fun updateStatus(uri: String, status: GalleryImageStatus) {
        withContext(Dispatchers.IO) {
            queries.updateImageStatus(status, uri)
        }
    }

    override suspend fun markDone(uri: String, detectedInMs: Long) {
        withContext(Dispatchers.IO) {
            queries.updateImageDone(detectedInMs, uri)
        }
    }

    // TODO should be a usecase
    override suspend fun markNoHand(uri: String) {
        // One transaction so a rejection can't leave partial state (e.g. hands
        // gone but status still DONE-with-detections). All three statements
        // live on the same SQLDelight queries object.
        db.transactionWithContext(Dispatchers.IO) {
            detectedHandQueries.deleteRealHandsForImage(uri)
            embeddingQueries.deleteEmbedding(uri)
            queries.updateImageStatus(GalleryImageStatus.DONE, uri)
        }
    }

    override suspend fun reconcileOrphans(currentValidUris: Set<String>) {
        db.transactionWithContext(Dispatchers.IO) {
            val storedUris = queries.selectAllUris().executeAsList().toSet()
            val orphaned = storedUris - currentValidUris

            if (orphaned.isEmpty()) return@transactionWithContext

            queries.deleteByUris(orphaned)
        }
    }

    override suspend fun deleteImage(uri: String) {
        withContext(Dispatchers.IO) {
            queries.deleteImage(uri)
        }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            queries.deleteAllImages()
        }
    }
}
