package isao.photorate.searchComponent

import isao.photorate.gallery.db.GalleryImage

/**
 * Ranks gallery images by how well they match a free-text query, using the MobileCLIP search
 * pipeline ([AppClipSearch]). Android implementation lives in androidMain; iOS is not implemented
 * yet (same gap as [AppClipSearchFactory]).
 */
interface SearchImagesUseCase : AutoCloseable {
  /**
   * Returns the top [limit] gallery images ranked by CLIP similarity to [query], keeping only
   * matches at or above [minSimilarity] so unrelated images are cut off instead of being shown
   * sorted by likeliness.
   */
  suspend fun search(
    query: String,
    limit: Int = 50,
    minSimilarity: Float = DEFAULT_MIN_SIMILARITY,
  ): List<GalleryImage>

  companion object {
    /** Default CLIP-similarity cutoff; the dev-mode search slider tunes it. */
    const val DEFAULT_MIN_SIMILARITY = 0.15f
  }
}
