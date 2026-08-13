package isao.photorate.configUi

import isao.photorate.config.DateHeaderMode
import isao.photorate.config.GalleryConfig
import isao.photorate.config.GallerySorting

/**
 * One group of related settings on the Config screen. The setup is static and shared between the
 * Android Compose UI and the (future) iOS UI; only the option values derive from the current
 * [GalleryConfig].
 */
data class ConfigSection(val title: String, val options: List<ConfigOption>)

/**
 * A single user-adjustable option. Every option knows its own [name] and can render its current
 * value from a [GalleryConfig].
 */
sealed interface ConfigOption {
  val name: String

  /** Current value of this option for [config], formatted for display. */
  fun currentValue(config: GalleryConfig): String
}

/**
 * A mutually-exclusive choice, rendered as radio buttons that wrap into a row when they fit on
 * screen.
 */
data class RadioOption(override val name: String, val choices: List<RadioChoice>) : ConfigOption {
  override fun currentValue(config: GalleryConfig): String =
    choices.firstOrNull { it.isSelected(config) }?.label.orEmpty()

  data class RadioChoice(
    val label: String,
    val isSelected: (GalleryConfig) -> Boolean,
    val intent: () -> ConfigIntent,
  )
}

/** A single-thumb slider, edited in a dialog. [description], when set, is shown in the dialog. */
data class SliderOption(
  override val name: String,
  val steps: Int,
  val description: String? = null,
  val valueRange: ClosedFloatingPointRange<Float>,
  val current: (GalleryConfig) -> Float,
  val formatValue: (Float) -> String,
  val intentFor: (Float) -> ConfigIntent,
) : ConfigOption {
  override fun currentValue(config: GalleryConfig): String = formatValue(current(config))
}

/**
 * A two-thumb range slider, edited in a dialog. [description], when set, is shown in the dialog.
 */
data class RangeSliderOption(
  override val name: String,
  val steps: Int,
  val description: String? = null,
  val valueRange: ClosedFloatingPointRange<Float>,
  val current: (GalleryConfig) -> ClosedFloatingPointRange<Float>,
  val formatValue: (ClosedFloatingPointRange<Float>) -> String,
  val intentFor: (ClosedFloatingPointRange<Float>) -> ConfigIntent,
) : ConfigOption {
  override fun currentValue(config: GalleryConfig): String = formatValue(current(config))
}

/** The static screen setup, shared between Android and iOS. */
val defaultConfigSections: List<ConfigSection> =
  listOf(
    ConfigSection(
      title = "Gallery filters",
      options =
        listOf(
          RangeSliderOption(
            name = "Score",
            steps = 3,
            description = "Only show photos rated within this range.",
            valueRange = 1f..5f,
            current = { config ->
              config.scoreRange.first.toFloat()..config.scoreRange.last.toFloat()
            },
            formatValue = { range ->
              if (range.start == range.endInclusive) {
                "${range.start.toInt()}"
              } else {
                "${range.start.toInt()} – ${range.endInclusive.toInt()}"
              }
            },
            intentFor = { range ->
              ConfigIntent.UpdateScoreRange(range.start.toInt()..range.endInclusive.toInt())
            },
          ),
          SliderOption(
            name = "Minimum hand size",
            steps = 98,
            description =
              "Only show photos where the hand covers at least this percentage of the image.",
            valueRange = 1f..100f,
            current = { config -> config.minHandSizePercent.toFloat() },
            formatValue = { value -> "${value.toInt()}%" },
            intentFor = { value -> ConfigIntent.UpdateMinHandSize(value.toInt()) },
          ),
        ),
    ),
    ConfigSection(
      title = "Gallery sorting",
      options =
        listOf(
          RadioOption(
            name = "Sort by",
            choices =
              listOf(
                RadioOption.RadioChoice(
                  label = "By score",
                  isSelected = { config -> config.sorting == GallerySorting.SCORE },
                  intent = { ConfigIntent.UpdateSorting(GallerySorting.SCORE) },
                ),
                RadioOption.RadioChoice(
                  label = "By date added",
                  isSelected = { config -> config.sorting == GallerySorting.DATE },
                  intent = { ConfigIntent.UpdateSorting(GallerySorting.DATE) },
                ),
              ),
          )
        ),
    ),
    ConfigSection(
      title = "Date headers",
      options =
        listOf(
          RadioOption(
            name = "Date header",
            choices =
              listOf(
                RadioOption.RadioChoice(
                  label = "Show dates",
                  isSelected = { config -> config.dateHeaderMode == DateHeaderMode.DATES },
                  intent = { ConfigIntent.UpdateDateHeaderMode(DateHeaderMode.DATES) },
                ),
                RadioOption.RadioChoice(
                  label = "Show months",
                  isSelected = { config -> config.dateHeaderMode == DateHeaderMode.MONTHS },
                  intent = { ConfigIntent.UpdateDateHeaderMode(DateHeaderMode.MONTHS) },
                ),
              ),
          )
        ),
    ),
  )
