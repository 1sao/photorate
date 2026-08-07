package isao.photorate.galleryComponent.search

/**
 * Fills the `ImageEmbedding` table for hand images that don't have an embedding
 * yet, so [SearchImagesUseCase] can rank stored vectors instead of re-embedding
 * the whole gallery on every query. Android computes the MobileCLIP embeddings;
 * iOS is a no-op (search is not implemented there yet, same gap as
 * [AppClipSearchFactory]).
 */
interface PopulateImageEmbeddingsUseCase {
    suspend operator fun invoke()
}
