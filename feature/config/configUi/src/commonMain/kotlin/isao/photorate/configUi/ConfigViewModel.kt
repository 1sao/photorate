package isao.photorate.configUi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isao.photorate.config.AppVersionInfo
import isao.photorate.config.ConfigRepository
import isao.photorate.config.DateHeaderMode
import isao.photorate.config.FeatureFlagRepository
import isao.photorate.config.GalleryConfig
import isao.photorate.config.GallerySorting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

sealed interface ConfigIntent {
  data class UpdateScoreRange(val range: IntRange) : ConfigIntent

  data class UpdateMinHandSize(val percent: Int) : ConfigIntent

  data class UpdateSorting(val sorting: GallerySorting) : ConfigIntent

  data class UpdateDateHeaderMode(val mode: DateHeaderMode) : ConfigIntent

  data class ToggleDevMode(val enabled: Boolean) : ConfigIntent
}

@KoinViewModel
class ConfigViewModel(
  private val configRepository: ConfigRepository,
  private val featureFlagRepository: FeatureFlagRepository,
  @Provided val appVersionInfo: AppVersionInfo,
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
        is ConfigIntent.UpdateScoreRange -> configRepository.updateScoreRange(intent.range)

        is ConfigIntent.UpdateMinHandSize ->
          configRepository.updateMinHandSizePercent(intent.percent)

        is ConfigIntent.UpdateSorting -> configRepository.updateSorting(intent.sorting)

        is ConfigIntent.UpdateDateHeaderMode -> configRepository.updateDateHeaderMode(intent.mode)

        is ConfigIntent.ToggleDevMode -> featureFlagRepository.setDevModeEnabled(intent.enabled)
      }
    }
  }
}

data class ConfigUiState(
  val config: GalleryConfig = GalleryConfig(),
  val sections: List<ConfigSection> = defaultConfigSections,
  val devModeEnabled: Boolean = false,
)
