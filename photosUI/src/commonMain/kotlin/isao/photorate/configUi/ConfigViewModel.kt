package isao.photorate.configUi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isao.photorate.galleryRepository.ConfigRepository
import isao.photorate.galleryRepository.DateHeaderMode
import isao.photorate.galleryRepository.FeatureFlagRepository
import isao.photorate.galleryRepository.GalleryConfig
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.galleryRepository.GallerySorting
import isao.photorate.galleryUi.RescanTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/**
 * Possible user actions on the Config screen.
 */
sealed interface ConfigIntent {
    data class UpdateScoreRange(val range: IntRange) : ConfigIntent
    data class UpdateMinHandSize(val percent: Int) : ConfigIntent
    data class UpdateSorting(val sorting: GallerySorting) : ConfigIntent
    data class UpdateDateHeaderMode(val mode: DateHeaderMode) : ConfigIntent
    data class ToggleDevMode(val enabled: Boolean) : ConfigIntent

    /** Deletes every stored scan and re-runs the full gallery scan from scratch. */
    data object PurgeAndRescan : ConfigIntent
}

@KoinViewModel
class ConfigViewModel(
    private val configRepository: ConfigRepository,
    private val featureFlagRepository: FeatureFlagRepository,
    private val galleryImageRepository: GalleryImageRepository,
    private val rescanTrigger: RescanTrigger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            configRepository.getConfig().collect { config ->
                _uiState.update { it.copy(config = config) }
            }
        }
        viewModelScope.launch {
            featureFlagRepository.devModeEnabled().collect { enabled ->
                _uiState.update { it.copy(devModeEnabled = enabled) }
            }
        }
    }

    fun onIntent(intent: ConfigIntent) {
        viewModelScope.launch {
            when (intent) {
                is ConfigIntent.UpdateScoreRange ->
                    configRepository.updateScoreRange(intent.range)

                is ConfigIntent.UpdateMinHandSize ->
                    configRepository.updateMinHandSizePercent(intent.percent)

                is ConfigIntent.UpdateSorting ->
                    configRepository.updateSorting(intent.sorting)

                is ConfigIntent.UpdateDateHeaderMode ->
                    configRepository.updateDateHeaderMode(intent.mode)

                is ConfigIntent.ToggleDevMode ->
                    featureFlagRepository.setDevModeEnabled(intent.enabled)

                is ConfigIntent.PurgeAndRescan -> {
                    // Deleting the GalleryImage rows cascades to their
                    // DetectedHand and ImageEmbedding rows; the rescan then
                    // re-populates the gallery as PENDING and re-landmarks
                    // everything (needed for the dev-mode filter to apply).
                    galleryImageRepository.deleteAll()
                    rescanTrigger.request()
                }
            }
        }
    }
}

data class ConfigUiState(
    val config: GalleryConfig = GalleryConfig(),
    val sections: List<ConfigSection> = defaultConfigSections,
    val devModeEnabled: Boolean = false,
)
