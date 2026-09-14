package isao.photorate.galleryComponent.domain

import isao.photorate.galleryComponent.db.ImageEmbedding
import kotlinx.coroutines.flow.Flow

interface ImageEmbeddingRepository {
  /**
   * All stored embeddings. Rows exist only for hand images (population filters to images with real
   * detections), so search ranking over these IS a search over images with detected hands.
   */
  fun getEmbeddings(): Flow<List<ImageEmbedding>>

  /** Inserts or replaces the embedding for one gallery image. */
  suspend fun upsert(uri: String, embedding: FloatArray)

  suspend fun deleteAll()
}
