package isao.photorate.galleryComponent.search

import org.koin.core.annotation.Factory

/**
 * iOS [EmbedUnembeddedImagesUseCase] placeholder — MobileCLIP embedding is Android-only for now.
 */
@Factory
class IosEmbedUnembeddedImagesUseCase : EmbedUnembeddedImagesUseCase {

  override suspend operator fun invoke() = Unit
}
