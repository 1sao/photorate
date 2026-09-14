package isao.photorate.galleryComponent.domain

import arrow.core.IorNel
import isao.photorate.imageRecognition.ResourceFailure

/**
 * Fills the `ImageEmbedding` table for hand images that don't have an embedding yet, so
 * [SearchImagesUseCase] can rank stored vectors.
 */
interface EmbedUnembeddedImagesUseCase {
  suspend operator fun invoke(): IorNel<ResourceFailure, Int>
}
