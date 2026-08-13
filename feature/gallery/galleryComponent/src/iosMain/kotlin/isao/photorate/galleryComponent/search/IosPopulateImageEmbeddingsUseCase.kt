package isao.photorate.galleryComponent.search

import org.koin.core.annotation.Factory

/**
 * iOS [PopulateImageEmbeddingsUseCase] placeholder — MobileCLIP embedding is Android-only for now
 * (onnxruntime not yet added for iOS), same gap as [AppClipSearchFactory]. No-op so the Koin graph
 * is complete on the iOS target.
 */
@Factory
class IosPopulateImageEmbeddingsUseCase : PopulateImageEmbeddingsUseCase {

  override suspend operator fun invoke() = Unit
}
