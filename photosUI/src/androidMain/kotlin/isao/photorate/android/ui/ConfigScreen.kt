package isao.photorate.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalFlexBoxApi
import androidx.compose.foundation.layout.FlexBox
import androidx.compose.foundation.layout.FlexDirection
import androidx.compose.foundation.layout.FlexWrap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import isao.photorate.configUi.ConfigIntent
import isao.photorate.configUi.ConfigOption
import isao.photorate.configUi.ConfigUiState
import isao.photorate.configUi.ConfigViewModel
import isao.photorate.configUi.RadioOption
import isao.photorate.configUi.RangeSliderOption
import isao.photorate.configUi.SliderOption
import isao.photorate.galleryRepository.DateHeaderMode
import isao.photorate.galleryRepository.GalleryConfig
import isao.photorate.galleryRepository.GallerySorting

@Composable
fun ConfigScreen(configViewModel: ConfigViewModel, onBack: () -> Unit, appVersionName: String, appVersionCode: Int) {
    val uiState by configViewModel.uiState.collectAsStateWithLifecycle()

    ConfigScreenContent(
        uiState = uiState,
        onBack = onBack,
        onIntent = configViewModel::onIntent,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreenContent(
    uiState: ConfigUiState,
    onBack: () -> Unit,
    onIntent: (ConfigIntent) -> Unit,
    appVersionName: String = "",
    appVersionCode: Int = 0,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(),
    )

    var dialogOption by remember { mutableStateOf<ConfigOption?>(null) }
    var showPurgeDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            uiState.sections.forEach { section ->
                item(key = "section-${section.title}") { SectionTitle(section.title) }
                section.options.forEach { option ->
                    when (option) {
                        is RadioOption -> item(key = "radio-${option.name}") {
                            RadioOptionRow(
                                option = option,
                                config = uiState.config,
                                onIntent = onIntent,
                            )
                        }

                        is SliderOption -> item(key = "slider-${option.name}") {
                            SettingRow(
                                title = option.name,
                                value = option.currentValue(uiState.config),
                                onClick = { dialogOption = option },
                            )
                        }

                        is RangeSliderOption -> item(key = "range-${option.name}") {
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
            item(key = "purge-rescan") {
                PurgeAndRescanRow(onClick = { showPurgeDialog = true })
            }
            item(key = "version-footer") {
                VersionFooter(
                    versionName = appVersionName,
                    versionCode = appVersionCode,
                )
            }
        }
    }

    when (val option = dialogOption) {
        is SliderOption -> SliderDialog(
            option = option,
            config = uiState.config,
            onIntent = onIntent,
            onDismiss = { dialogOption = null },
        )

        is RangeSliderOption -> RangeSliderDialog(
            option = option,
            config = uiState.config,
            onIntent = onIntent,
            onDismiss = { dialogOption = null },
        )

        else -> Unit
    }

    if (showPurgeDialog) {
        AlertDialog(
            onDismissRequest = { showPurgeDialog = false },
            title = { Text("Purge all saved images?") },
            text = {
                Text(
                    "Deletes every stored scan and re-runs the full gallery scan from scratch. Use this after changing dev-mode filters so they apply to the whole gallery.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPurgeDialog = false
                        onIntent(ConfigIntent.PurgeAndRescan)
                    },
                ) { Text("Purge & rescan") }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Preview(
    name = "Settings screen",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
)
@Composable
private fun ConfigScreenPreview() {
    MaterialTheme {
        ConfigScreenContent(
            uiState = ConfigUiState(
                config = GalleryConfig(
                    scoreRange = 2..5,
                    minHandSizePercent = 35,
                    sorting = GallerySorting.SCORE,
                    dateHeaderMode = DateHeaderMode.MONTHS,
                ),
            ),
            onBack = {},
            onIntent = {},
        )
    }
}

/** App version + build number, pinned at the bottom of the settings list. */
@Composable
private fun VersionFooter(versionName: String, versionCode: Int) {
    val label = when {
        versionName.isNotBlank() && versionCode > 0 -> "Version $versionName ($versionCode)"
        versionName.isNotBlank() -> "Version $versionName"
        else -> ""
    }
    if (label.isBlank()) return
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
    )
}

@Composable
private fun PurgeAndRescanRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Purge all saved images & rescan",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "Deletes stored detections and re-scans the whole gallery (needed after changing dev-mode filters).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun DevModeSwitchRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Developer Mode",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Show raw hand landmarks on photo details (for debugging the model).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFlexBoxApi::class)
@Composable
private fun RadioOptionRow(option: RadioOption, config: GalleryConfig, onIntent: (ConfigIntent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = option.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        FlexBox(
            modifier = Modifier.fillMaxWidth(),
            config = {
                direction(FlexDirection.Row)
                wrap(FlexWrap.Wrap)
                gap(12.dp)
            },
        ) {
            option.choices.forEach { choice ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = choice.isSelected(config),
                            onClick = { onIntent(choice.intent()) },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = choice.isSelected(config), onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderDialog(option: SliderOption, config: GalleryConfig, onIntent: (ConfigIntent) -> Unit, onDismiss: () -> Unit) {
    var value by remember(option, config) { mutableStateOf(option.current(config)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(option.name) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = option.formatValue(value),
                    style = MaterialTheme.typography.titleMedium,
                )
                option.description?.let { description ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = option.valueRange,
                    steps = option.steps,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onIntent(option.intentFor(value))
                    onDismiss()
                },
            ) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSliderDialog(option: RangeSliderOption, config: GalleryConfig, onIntent: (ConfigIntent) -> Unit, onDismiss: () -> Unit) {
    var range by remember(option, config) { mutableStateOf(option.current(config)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(option.name) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = option.formatValue(range),
                    style = MaterialTheme.typography.titleMedium,
                )
                option.description?.let { description ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(8.dp))
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = option.valueRange,
                    steps = option.steps,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onIntent(option.intentFor(range))
                    onDismiss()
                },
            ) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
