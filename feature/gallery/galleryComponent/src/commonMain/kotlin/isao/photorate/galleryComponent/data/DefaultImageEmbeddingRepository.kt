package isao.photorate.galleryComponent.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import isao.photorate.galleryComponent.db.ImageEmbedding
import isao.photorate.galleryComponent.db.ImageEmbeddingQueries
import isao.photorate.galleryComponent.domain.ImageEmbeddingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class DefaultImageEmbeddingRepository(
  @Provided private val queries: ImageEmbeddingQueries,
) : ImageEmbeddingRepository {

  override fun getEmbeddings(): Flow<List<ImageEmbedding>> =
    queries.selectAllEmbeddings().asFlow().mapToList(Dispatchers.IO)

  override suspend fun upsert(uri: String, embedding: FloatArray) {
    withContext(Dispatchers.IO) {
      queries.upsertEmbedding(
        uri,
        embedding,
      )
    }
  }

  override suspend fun deleteAll() {
    withContext(Dispatchers.IO) { queries.deleteAllEmbeddings() }
  }
}
