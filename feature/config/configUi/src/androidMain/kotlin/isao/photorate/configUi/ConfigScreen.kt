package isao.photorate.configUi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import isao.photorate.config.DateHeaderMode
import isao.photorate.config.GalleryConfig
import isao.photorate.config.GallerySorting
import isao.photorate.coreUi.composable.PhotoRatePreview
import isao.photorate.coreUi.modifier.clipDeviceCorners
import org.koin.androidx.compose.koinViewModel

@Composable
fun ConfigScreen(
  viewModel: ConfigViewModel = koinViewModel(),
  onBack: () -> Unit,
  onPurgeAndRescan: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  ConfigScreenContent(
    uiState = uiState,
    onBack = onBack,
    onIntent = viewModel::onIntent,
    onPurgeAndRescan = onPurgeAndRescan,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreenContent(
  uiState: ConfigUiState,
  onBack: () -> Unit,
  onIntent: (ConfigIntent) -> Unit,
  onPurgeAndRescan: () -> Unit = {},
) {
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

  var dialogOption by remember { mutableStateOf<ConfigOption?>(null) }
  var showPurgeDialog by remember { mutableStateOf(false) }

  Scaffold(
    modifier =
      Modifier.fillMaxSize()
        .nestedScroll(scrollBehavior.nestedScrollConnection)
        .clipDeviceCorners(),
    topBar = {
      Column {
        LargeTopAppBar(
          title = { Text("Settings") },
          navigationIcon = {
            IconButton(onClick = onBack) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
              )
            }
          },
          scrollBehavior = scrollBehavior,
        )
        AnimatedVisibility(
          visible = scrollBehavior.state.collapsedFraction >= 1f,
          enter = fadeIn(),
          exit = fadeOut(),
        ) {
          HorizontalDivider()
        }
      }
    },
  ) { innerPadding ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      uiState.sections.forEach { section ->
        item(key = "section-${section.title}") { SectionTitle(section.title) }
        section.options.forEach { option ->
          when (option) {
            is RadioOption ->
              item(key = "radio-${option.name}") {
                RadioOptionRow(
                  option = option,
                  config = uiState.config,
                  onIntent = onIntent,
                )
              }

            is SliderOption,
            is RangeSliderOption ->
              item(key = "setting-${option.name}") {
                SettingRow(
                  title = option.name,
                  value = option.currentValue(uiState.config),
                  onClick = { dialogOption = option },
                )
              }
          }
        }
      }
      item(key = "section-Developer") { SectionTitle("Developer") }
      item(key = "switch-dev-mode") {
        DevModeSwitchRow(
          enabled = uiState.devModeEnabled,
          onToggle = { onIntent(ConfigIntent.ToggleDevMode(it)) },
        )
      }
      item(key = "purge-rescan") { PurgeAndRescanRow(onClick = { showPurgeDialog = true }) }
      item(key = "version-footer") {
        VersionFooter(
          versionName = uiState.appInfo.versionName,
          versionCode = uiState.appInfo.versionCode,
        )
      }
    }
  }

  when (val option = dialogOption) {
    is SliderOption ->
      SliderDialog(
        option = option,
        config = uiState.config,
        onIntent = onIntent,
        onDismiss = { dialogOption = null },
      )

    is RangeSliderOption ->
      RangeSliderDialog(
        option = option,
        config = uiState.config,
        onIntent = onIntent,
        onDismiss = { dialogOption = null },
      )

    else -> Unit
  }

  if (showPurgeDialog) {
    PurgeConfirmDialog(
      onConfirm = {
        showPurgeDialog = false
        onPurgeAndRescan()
      },
      onDismiss = { showPurgeDialog = false },
    )
  }
}

@Preview
@Composable
private fun ConfigScreenPreview() {
  PhotoRatePreview {
    ConfigScreenContent(
      uiState =
        ConfigUiState(
          config =
            GalleryConfig(
              scoreRange = 2..5,
              minHandSizePercent = 35,
              sorting = GallerySorting.SCORE,
              dateHeaderMode = DateHeaderMode.MONTHS,
            )
        ),
      onBack = {},
      onIntent = {},
    )
  }
}
