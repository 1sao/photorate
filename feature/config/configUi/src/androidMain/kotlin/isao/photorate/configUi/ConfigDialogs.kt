package isao.photorate.configUi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import isao.photorate.config.GalleryConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderDialog(
  option: SliderOption,
  config: GalleryConfig,
  onIntent: (ConfigIntent) -> Unit,
  onDismiss: () -> Unit,
) {
  var value by remember(option, config) { mutableFloatStateOf(option.current(config)) }

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
      ) {
        Text("Done")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RangeSliderDialog(
  option: RangeSliderOption,
  config: GalleryConfig,
  onIntent: (ConfigIntent) -> Unit,
  onDismiss: () -> Unit,
) {
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
      ) {
        Text("Done")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
internal fun PurgeConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Purge all saved images?") },
    text = {
      Text(
        "Deletes every stored scan and re-runs the full gallery scan from scratch. Use this after changing dev-mode filters so they apply to the whole gallery.",
      )
    },
    confirmButton = { TextButton(onClick = onConfirm) { Text("Purge & rescan") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
