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
import org.koin.core.annotation.Factory

@Factory
class DefaultGalleryImageRepository(private val db: PhotoRateDb) : GalleryImageRepository {

    private val queries get() = db.galleryQueries

    override fun getImages(): Flow<List<GalleryImage>> = queries.selectAllImages()
        .asFlow()
        // TODO NEXT TASK replace Dispatchers.Default with Dispatchers.IO if the
        // only work is IO. Keep/add Dispatchers.Default when working with
        // inference. Don't use transactionWithContext without a real transaction
        // (a single operation).
        .mapToList(Dispatchers.Default)

    override fun getImageByUri(uri: String): Flow<GalleryImage?> = queries.selectImageByUri(uri)
        .asFlow()
        .mapToOneOrNull(Dispatchers.Default)

    override fun getUnprocessedImages(): Flow<List<GalleryImage>> = queries.selectUnprocessed()
        .asFlow()
        .mapToList(Dispatchers.Default)

    override fun getImagesMissingEmbeddings(): Flow<List<GalleryImage>> = queries.selectImagesMissingEmbeddings()
        .asFlow()
        .mapToList(Dispatchers.Default)

    override fun getStatus(): Flow<CountsByStatus> = queries.countsByStatus()
        .asFlow()
        .mapToOne(Dispatchers.Default)

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
        db.transactionWithContext(Dispatchers.Default) {
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
        db.transactionWithContext(Dispatchers.Default) {
            queries.updateImageStatus(status, uri)
        }
    }

    override suspend fun markDone(uri: String, detectedInMs: Long) {
        db.transactionWithContext(Dispatchers.Default) {
            queries.updateImageDone(detectedInMs, uri)
        }
    }

    override suspend fun markNoHand(uri: String) {
        // One transaction so a rejection can't leave partial state (e.g. hands
        // gone but status still DONE-with-detections). All three statements
        // live on the same SQLDelight queries object.
        db.transactionWithContext(Dispatchers.Default) {
            queries.deleteRealHandsForImage(uri)
            queries.deleteEmbedding(uri)
            queries.updateImageStatus(GalleryImageStatus.DONE, uri)
        }
    }

    override suspend fun reconcileOrphans(currentValidUris: Set<String>) {
        db.transactionWithContext(Dispatchers.Default) {
            val storedUris = queries.selectAllUris().executeAsList().toSet()
            val orphaned = storedUris - currentValidUris

            if (orphaned.isEmpty()) return@transactionWithContext

            queries.deleteByUris(orphaned)
        }
    }

    override suspend fun deleteImage(uri: String) {
        db.transactionWithContext(Dispatchers.Default) {
            queries.deleteImage(uri)
        }
    }

    override suspend fun deleteAll() {
        db.transactionWithContext(Dispatchers.Default) {
            queries.deleteAllImages()
        }
    }
}
