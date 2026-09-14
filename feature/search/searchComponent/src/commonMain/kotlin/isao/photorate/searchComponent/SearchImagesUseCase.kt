package isao.photorate.searchComponent

import isao.photorate.galleryComponent.domain.ImageEmbeddingRepository
import isao.photorate.imageRecognition.search.cosineSimilarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * Ranks gallery images by how well they match a free-text query, using the MobileCLIP search
 * pipeline.
 */
@Single
class SearchImagesUseCase(
  private val imageEmbeddingRepository: ImageEmbeddingRepository,
  private val searchSessionHolder: SearchSessionHolder,
) {
  suspend operator fun invoke(
    // TODO consider searching in SQL
    query: String,
    limit: Int,
    minSimilarity: Float,
  ): List<String> =
    withContext(Dispatchers.IO) {
      val embeddings = imageEmbeddingRepository.getEmbeddings().first()
      if (embeddings.isEmpty()) return@withContext emptyList()

      val queryEmbedding = searchSessionHolder.use { session -> session.embedText(query) }

      embeddings
        .asSequence()
        .map { (uri, embedding) -> uri to cosineSimilarity(queryEmbedding, embedding) }
        .filter { (_, similarity) -> similarity >= minSimilarity }
        .sortedByDescending { (_, similarity) -> similarity }
        .take(limit)
        .map { (uri, _) -> uri }
        .toList()
    }
}
