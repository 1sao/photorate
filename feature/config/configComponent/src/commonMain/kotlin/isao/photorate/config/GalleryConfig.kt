package isao.photorate.config

enum class GallerySorting {
  SCORE,
  DATE,
}

enum class DateHeaderMode {
  DATES,
  MONTHS,
}

/** User-configurable gallery settings. */
data class GalleryConfig(
  val scoreRange: IntRange = 1..5,
  val minHandSizePercent: Int = 1,
  val sorting: GallerySorting = GallerySorting.DATE,
  val dateHeaderMode: DateHeaderMode = DateHeaderMode.DATES,
)
