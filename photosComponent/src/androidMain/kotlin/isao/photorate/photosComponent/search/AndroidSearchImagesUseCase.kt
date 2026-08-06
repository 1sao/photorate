package isao.photorate.photosComponent.search

import isao.photorate.db.GalleryImage
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.galleryRepository.ImageEmbeddingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Android [SearchImagesUseCase] backed by the MobileCLIP search pipeline.
 *
 * Embeddings are precomputed ahead of time by [PopulateImageEmbeddingsUseCase]
 * and stored in the `ImageEmbedding` table for hand images only, so a search
 * only embeds the query text and ranks the stored vectors by cosine similarity
 * — it never re-embeds gallery images.
 *
 * The CLIP session (LiteRT or ONNX, whichever AppClipSearchFactory the root
 * PlatformModule provides) is created lazily and reused across searches: model
 * load + session creation is expensive, and the `@Factory` instance is
 * ViewModel-scoped so the cache lives exactly as long as the screen.
 */
@Factory
class AndroidSearchImagesUseCase(
    private val galleryImageRepository: GalleryImageRepository,
    private val imageEmbeddingRepository: ImageEmbeddingRepository,
    // The factory is bound by the root PlatformModule (shared) — the leaf
    // module cannot see it (the ONNX/LiteRT search providers live in their own
    // modules), so mark it external like LandmarkerFactoryProvider.
    @Provided private val searchFactory: AppClipSearchFactory,
) : SearchImagesUseCase {

    private val sessionMutex = Any()

    @Volatile
    private var cachedSession: AppClipSearch? = null

    override suspend fun search(
        // TODO search in SQL
        query: String,
        limit: Int,
        minSimilarity: Float,
    ): List<GalleryImage> = withContext(Dispatchers.IO) {
        val embeddings = imageEmbeddingRepository.getEmbeddings().first()
        if (embeddings.isEmpty()) return@withContext emptyList()

        val clip = session()
        val queryEmbedding = clip.embedText(query)
        val imagesByUri = galleryImageRepository.getImages().first().associateBy { it.uri }

        // Embeddings only exist for hand images, so the ranked set is exactly
        // the gallery images in which a hand was detected. Unrelated images are
        // cut off at the similarity threshold instead of shown at the tail.
        embeddings
            .map { it.uri to cosineSimilarity(queryEmbedding, it.embedding) }
            .filter { (_, similarity) -> similarity >= minSimilarity }
            .sortedByDescending { (_, similarity) -> similarity }
            .take(limit)
            .mapNotNull { (uri, _) -> imagesByUri[uri] }
    }

    /** Returns the cached session, creating it exactly once under a lock. */
    private fun session(): AppClipSearch {
        cachedSession?.let { return it }
        synchronized(sessionMutex) {
            return cachedSession ?: searchFactory.createFromOptions(AppClipSearchFactory.Options()).also {
                cachedSession = it
            }
        }
    }

    override fun close() {
        synchronized(sessionMutex) {
            cachedSession?.close()
            cachedSession = null
        }
    }
}
