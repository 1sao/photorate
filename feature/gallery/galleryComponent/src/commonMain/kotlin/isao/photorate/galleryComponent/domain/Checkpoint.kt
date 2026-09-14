package isao.photorate.galleryComponent.domain

/**
 * Incremental-sync position captured by
 * [SystemGalleryImageRepository.getAllImagesAfterLastCheckpoint].
 */
data class Checkpoint(val generations: Map<String, Long> = emptyMap())
