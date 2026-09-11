package isao.photorate.configUi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalFlexBoxApi
import androidx.compose.foundation.layout.FlexBox
import androidx.compose.foundation.layout.FlexDirection
import androidx.compose.foundation.layout.FlexWrap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import isao.photorate.config.GalleryConfig

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  )
}

@Composable
internal fun SettingRow(
  title: String,
  value: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
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
internal fun RadioOptionRow(
  option: RadioOption,
  config: GalleryConfig,
  onIntent: (ConfigIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
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
          modifier =
            Modifier.selectable(
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

@Composable
internal fun DevModeSwitchRow(
  enabled: Boolean,
  onToggle: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        text = "Developer Mode",
        style = MaterialTheme.typography.bodyLarge,
      )
      Text(
        text = "Show raw hand landmarks on photo details and tweak search confidence.",
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
internal fun PurgeAndRescanRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Surface(
    modifier =
      modifier
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
        text =
          "Deletes stored detections and re-scans the whole gallery (needed after changing dev-mode filters).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
    }
  }
}

@Composable
internal fun VersionFooter(
  versionName: String,
  versionCode: Int,
  modifier: Modifier = Modifier,
) {
  val label =
    when {
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
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
  )
}
