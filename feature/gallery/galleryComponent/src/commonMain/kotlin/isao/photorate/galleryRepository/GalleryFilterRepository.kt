package isao.photorate.galleryRepository

import isao.photorate.gallery.db.SelectMatching
import kotlinx.coroutines.flow.Flow

interface GalleryFilterRepository {
  /** Detail view: matching hands with image info */
  fun selectMatching(): Flow<List<SelectMatching>>

  /** Grid view: one row per visible image, including confident and uncertain tiers. */
  fun selectGalleryImages(): Flow<List<GalleryImage>>
}

data class GalleryImage(
  val uri: String,
  val scannedAt: Long?,
  val createdAt: Long,
  val modifiedAt: Long,
  val scores: String?,
  val bestGuessScore: Int?,
  val isUncertain: Boolean,
)
