package isao.photorate.galleryComponent.populateGallery

/**
 * Gallery metadata for one image, read straight from the system gallery on
 * demand. Never cached — the details screen re-queries every time it opens,
 * so the data is always current.
 *
 * Dates are epoch seconds (matching [isao.photorate.gallery.db.GalleryImage]); the UI
 * formats them for display.
 */
data class SystemImageDetails(
    val uri: String,
    val displayName: String?,
    val dateTakenEpochSeconds: Long?,
    val dateAddedEpochSeconds: Long?,
    val dateModifiedEpochSeconds: Long?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val mimeType: String?,
)
