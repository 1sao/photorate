package isao.photorate.config

/** How gallery images are sorted. Stored as the enum name in the `config` table. */
enum class GallerySorting {
  SCORE,
  DATE,
}

/** How gallery date headers are displayed. Stored as the enum name in the `config` table. */
enum class DateHeaderMode {
  DATES,
  MONTHS,
}

/** User-configurable gallery settings (singleton `config` row). */
data class GalleryConfig(
  val scoreRange: IntRange = 1..5,
  val minHandSizePercent: Int = 1,
  val sorting: GallerySorting = GallerySorting.DATE,
  val dateHeaderMode: DateHeaderMode = DateHeaderMode.DATES,
)
