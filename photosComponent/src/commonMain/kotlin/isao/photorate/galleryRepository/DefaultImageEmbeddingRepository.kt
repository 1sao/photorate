package isao.photorate.galleryRepository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.db.ImageEmbedding
import isao.photorate.db.PhotoRateDb
import isao.photorate.sqldelight.transactionWithContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory

@Factory
class DefaultImageEmbeddingRepository(private val db: PhotoRateDb) : ImageEmbeddingRepository {

    private val queries get() = db.galleryQueries

    override fun getEmbeddings(): Flow<List<ImageEmbedding>> = queries.selectAllEmbeddings()
        .asFlow()
        .mapToList(Dispatchers.Default)

    override suspend fun upsert(uri: String, embedding: FloatArray) {
        db.transactionWithContext(Dispatchers.Default) {
            queries.upsertEmbedding(uri, embedding)
        }
    }

    override suspend fun deleteAll() {
        db.transactionWithContext(Dispatchers.Default) {
            queries.deleteAllEmbeddings()
        }
    }
}
