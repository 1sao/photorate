package isao.photorate.galleryRepository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.db.ImageEmbedding
import isao.photorate.db.PhotoRateDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class DefaultImageEmbeddingRepository(private val db: PhotoRateDb) : ImageEmbeddingRepository {

    private val queries get() = db.galleryQueries

    override fun getEmbeddings(): Flow<List<ImageEmbedding>> = queries.selectAllEmbeddings()
        .asFlow()
        .mapToList(Dispatchers.IO)

    override suspend fun upsert(uri: String, embedding: FloatArray) {
        withContext(Dispatchers.IO) {
            queries.upsertEmbedding(uri, embedding)
        }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            queries.deleteAllEmbeddings()
        }
    }
}
