package isao.photorate.galleryRepository

import isao.photorate.gallery.db.GalleryImageStatus

data class GalleryStatusCounts(val counts: Map<GalleryImageStatus, Long>) {
    val total: Long get() = counts.values.sum()
    operator fun get(status: GalleryImageStatus): Long = counts[status] ?: 0L
}
