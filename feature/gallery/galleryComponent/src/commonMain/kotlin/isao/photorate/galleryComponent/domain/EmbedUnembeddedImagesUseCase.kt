package isao.photorate.galleryComponent.domain

import arrow.core.IorNel
import isao.photorate.imageRecognition.ResourceFailure

/**
 * Finds the images that do not have an embedding yet, creates the embeddings, and saves them. After
 * that, the images will be searchable.
 */
interface EmbedUnembeddedImagesUseCase {
  suspend operator fun invoke(): IorNel<ResourceFailure, Int>
}
