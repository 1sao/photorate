package isao.photorate.galleryUi

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/**
 * Floating top bar for the details screen: back and options on opposite corners, over the photo.
 * Fades in once the shared-element transition lands, and hides in full-screen mode.
 */
@Composable
internal fun ImageDetailsTopBar(
  visible: Boolean,
  onBack: () -> Unit,
  onOpenInGallery: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(TOP_BAR_ANIM_MS)),
    exit = fadeOut(tween(TOP_BAR_ANIM_MS / 2)),
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      FloatingTopButton(onClick = onBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Back",
          tint = Color.White,
        )
      }
      Box {
        FloatingTopButton(onClick = { menuExpanded = true }) {
          Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = "Options",
            tint = Color.White,
          )
        }
        DropdownMenu(
          expanded = menuExpanded,
          onDismissRequest = { menuExpanded = false },
        ) {
          DropdownMenuItem(
            text = { Text("Open in gallery") },
            onClick = {
              menuExpanded = false
              onOpenInGallery()
            },
          )
        }
      }
    }
  }
}

/** Circular floating action for the top bar; a dark scrim keeps it visible on any photo. */
@Composable
private fun FloatingTopButton(onClick: () -> Unit, content: @Composable () -> Unit) {
  Surface(
    modifier = Modifier.padding(8.dp),
    shape = CircleShape,
    color = FLOATING_BUTTON_BACKGROUND,
  ) {
    IconButton(onClick = onClick) { content() }
  }
}

/** Opens [uri] (a MediaStore content URI) in the system gallery app. */
// TODO extract as a util
internal fun openImageInGallery(context: Context, uri: String) {
  val intent =
    Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri.toUri(), "image/*")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  runCatching { context.startActivity(intent) }
}

/** 80% black scrim so the floating buttons stay visible over any photo. */
private val FLOATING_BUTTON_BACKGROUND = Color(0xCC000000)

/** Fade-in duration for the floating top bar after the shared transition lands. */
private const val TOP_BAR_ANIM_MS = 220
