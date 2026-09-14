package isao.photorate.galleryComponent.domain

/** Gallery metadata for one image. */
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
