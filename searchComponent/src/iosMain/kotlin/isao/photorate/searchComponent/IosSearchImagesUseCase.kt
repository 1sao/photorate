package isao.photorate.searchComponent

import isao.photorate.gallery.db.GalleryImage
import org.koin.core.annotation.Factory

/**
 * iOS [SearchImagesUseCase] placeholder — MobileCLIP search is Android-only
 * for now (onnxruntime not yet added for iOS), same gap as [AppClipSearchFactory].
 * Exists so the Koin graph is complete on the iOS target.
 */
@Factory
class IosSearchImagesUseCase : SearchImagesUseCase {

    override suspend fun search(query: String, limit: Int, minSimilarity: Float): List<GalleryImage> =
        error("MobileCLIP search not implemented on iOS yet")

    override fun close() = Unit
}
