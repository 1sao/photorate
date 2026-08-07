package isao.photorate.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** One fast-scroll group: a run of grid items starting at [startIndex]. */
data class IndexScrollSection(
  val startIndex: Int,
  /** Section is the uncertain review tier: shows an extra "?" pill while in it. */
  val isUncertain: Boolean = false,
)

/**
 * Google-Photos-style fast scrollbar for a [LazyGridState].
 *
 * The bar fades in when the grid starts scrolling and fades out shortly after it stops. Touching or
 * dragging it scrolls the grid to the touched position (top of the bar = first grid item, so the
 * very top shows everything above the photos, e.g. the scan status card).
 *
 * While engaged a label pill is drawn to the start (left) of the bar showing [labelAt] of the group
 * under the thumb; when the text changes it slides vertically in the direction of travel and fires
 * haptic feedback. When the thumb is inside an [IndexScrollSection.isUncertain] section, an extra
 * "?" pill is drawn before (start of) the label pill. Touching the bar reveals smaller pills
 * marking where each section starts (the uncertain section is a slightly larger "?" marker);
 * tapping one jumps straight to that section. The pills stay tappable until the bar fades out. Scan
 * status is never part of the sections, so it never appears in any pill.
 *
 * The scrollbar is fully reusable: any screen can feed it a [LazyGridState], an ordered list of
 * [IndexScrollSection]s and a [labelAt] resolver.
 */
@Composable
fun IndexScrollbar(
  gridState: LazyGridState,
  sections: List<IndexScrollSection>,
  labelAt: (Int) -> String,
  modifier: Modifier = Modifier,
) {
  if (sections.isEmpty()) return

  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current
  val density = LocalDensity.current
  // The gesture/scroll coroutines outlive recompositions, so resolve the
  // label resolver through state and always read the latest instance.
  val currentLabelAt by rememberUpdatedState(labelAt)
  val touchWidthPx = with(density) { TOUCH_WIDTH.roundToPx() }
  val thumbHeightPx = with(density) { THUMB_HEIGHT.roundToPx() }
  val chipHeightPx = with(density) { CHIP_HEIGHT.roundToPx() }
  val chipGapPx = with(density) { CHIP_GAP.roundToPx() }
  val pillGapPx = with(density) { PILL_GAP.roundToPx() }
  // Widest pill width, to reserve a clear gap between the track's touch
  // strip and the label pill (see the pill/chip x offsets below).
  val pillReservedWidthPx = with(density) { UNCERTAIN_MARKER_SIZE.roundToPx() }

  var visible by remember { mutableStateOf(false) }
  var showPills by remember { mutableStateOf(false) }
  var keepAliveTick by remember { mutableIntStateOf(0) }
  var dragging by remember { mutableStateOf(false) }
  var thumbFraction by remember { mutableFloatStateOf(0f) }
  var trackHeight by remember { mutableIntStateOf(0) }
  var chipText by remember { mutableStateOf("") }
  var chipSlideUp by remember { mutableStateOf(true) }
  var dragIndex by remember { mutableIntStateOf(0) }
  var lastLabel by remember { mutableStateOf<String?>(null) }
  var lastLabelIndex by remember { mutableIntStateOf(-1) }

  // Visible while the grid scrolls or the thumb is held; fades out shortly
  // after both stop (the delay is cancelled when the user re-engages).
  var scrollActive by remember { mutableStateOf(false) }
  LaunchedEffect(gridState) {
    snapshotFlow { gridState.isScrollInProgress }
      .distinctUntilChanged()
      .collect { active -> scrollActive = active }
  }
  val engaged = scrollActive || dragging
  LaunchedEffect(engaged, keepAliveTick) {
    if (engaged) {
      visible = true
    } else {
      delay(HIDE_DELAY_MS)
      visible = false
    }
  }
  // Group pills show only after the bar was touched and stay tappable until
  // the bar fades out (plain scrolling alone never reveals them).
  LaunchedEffect(visible) { if (!visible) showPills = false }
  val alpha by
    animateFloatAsState(
      targetValue = if (visible) 1f else 0f,
      animationSpec = tween(FADE_MS),
      label = "index-scrollbar-fade",
    )

  fun totalItems(): Int = gridState.layoutInfo.totalItemsCount

  /** Maps a thumb fraction to a grid item index (top = 0, bottom = last). */
  fun indexForFraction(fraction: Float): Int {
    val total = totalItems()
    return if (total <= 1) 0 else (fraction * (total - 1)).roundToInt().coerceIn(0, total - 1)
  }

  /** Maps a grid item index to a thumb fraction (top = 0, bottom = last). */
  fun fractionFor(index: Int): Float {
    val total = totalItems()
    return if (total <= 1) 0f else index.toFloat() / (total - 1)
  }

  /** Updates the label pill; slides + haptics only when the label changes. */
  fun updateLabel(index: Int) {
    val label = currentLabelAt(index)
    if (label == lastLabel) return
    if (lastLabel != null) {
      chipSlideUp = index >= lastLabelIndex
      haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    lastLabel = label
    lastLabelIndex = index
    chipText = label
  }

  // Thumb + label follow the grid while it scrolls; the drag path drives
  // them directly, so this effect pauses while the thumb is held.
  LaunchedEffect(gridState, dragging) {
    if (dragging) return@LaunchedEffect
    snapshotFlow { gridState.firstVisibleItemIndex }
      .distinctUntilChanged()
      .collect { first ->
        thumbFraction = fractionFor(first)
        updateLabel(first)
      }
  }

  // Scrolls the grid to the dragged index: a single effect restarted per
  // index change instead of launching a coroutine per pointer event.
  LaunchedEffect(dragIndex, dragging) { if (dragging) gridState.scrollToItem(dragIndex) }

  val currentIndex = if (dragging) dragIndex else gridState.firstVisibleItemIndex
  val inUncertainSection =
    remember(sections, currentIndex) {
      sections.lastOrNull { it.startIndex <= currentIndex }?.isUncertain == true
    }

  /** Jumps to a section from its pill and keeps the bar alive for more taps. */
  fun onPillTap(section: IndexScrollSection) {
    // A pill is only visible while the bar is, so bumping the keep-alive
    // tick is enough to hold it for another hide delay.
    keepAliveTick++
    thumbFraction = fractionFor(section.startIndex)
    dragIndex = section.startIndex
    updateLabel(section.startIndex)
    scope.launch { gridState.scrollToItem(section.startIndex) }
  }

  Box(modifier) {
    // --- Track + thumb (the only touch target; inactive while hidden so
    // the grid keeps receiving swipes on the right edge) ---
    Box(
      Modifier.align(Alignment.CenterEnd)
        .fillMaxHeight()
        .width(TOUCH_WIDTH)
        .alpha(alpha)
        .onSizeChanged { trackHeight = it.height }
        .semantics { contentDescription = "Index scrollbar" }
        .pointerInput(visible) {
          if (!visible) return@pointerInput
          awaitEachGesture {
            val down = awaitFirstDown()
            // Own the gesture: consume the down so the grid's own
            // scrollable below never claims the touch.
            down.consume()
            dragging = true
            showPills = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            var dragY = 0f
            fun handleDrag() {
              if (trackHeight <= 0) return
              val fraction = ((down.position.y + dragY) / trackHeight).coerceIn(0f, 1f)
              thumbFraction = fraction
              dragIndex = indexForFraction(fraction)
              updateLabel(dragIndex)
            }
            // Jump to the press position immediately, then track
            // the finger through raw pointer events (the `drag`
            // gesture helper is not part of this Compose version's
            // public gestures API surface).
            handleDrag()
            var lastY = down.position.y
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) break
              dragY += change.position.y - lastY
              lastY = change.position.y
              change.consume()
              handleDrag()
            }
            dragging = false
          }
        }
    ) {
      // Static track line.
      Box(
        Modifier.align(Alignment.Center)
          .width(TRACK_WIDTH)
          .fillMaxHeight()
          .clip(RoundedCornerShape(TRACK_WIDTH / 2))
          .background(MaterialTheme.colorScheme.surfaceVariant)
      )
      // Thumb knob.
      val thumbTop =
        if (trackHeight > 0) {
          (thumbFraction * trackHeight - thumbHeightPx / 2f)
            .roundToInt()
            .coerceIn(0, (trackHeight - thumbHeightPx).coerceAtLeast(0))
        } else {
          0
        }
      Box(
        Modifier.align(Alignment.TopCenter)
          .offset { IntOffset(0, thumbTop) }
          .width(TRACK_WIDTH)
          .height(THUMB_HEIGHT)
          .clip(RoundedCornerShape(THUMB_HEIGHT / 2))
          .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
      )
    }

    // --- Group-start pills; visible only after the bar was touched, and
    // tappable to jump straight to that section ---
    if (showPills && trackHeight > 0) {
      val total = totalItems()
      sections.forEach { section ->
        val pillWidth = if (section.isUncertain) UNCERTAIN_MARKER_SIZE else PILL_WIDTH
        val pillHeight = if (section.isUncertain) UNCERTAIN_MARKER_SIZE else PILL_HEIGHT
        val pillHeightPx = with(density) { pillHeight.roundToPx() }
        val pillTop =
          if (total <= 1) {
            0
          } else {
            (section.startIndex.toFloat() / (total - 1) * trackHeight).roundToInt() -
              pillHeightPx / 2
          }
        Box(
          Modifier.align(Alignment.CenterEnd)
            .offset {
              IntOffset(
                // Fully left of the track's touch strip so a
                // press meant to drag the bar never lands on a
                // pill (and a pill tap never starts a drag).
                x = -(touchWidthPx + pillGapPx),
                y = pillTop.coerceIn(0, (trackHeight - pillHeightPx).coerceAtLeast(0)),
              )
            }
            .pointerInput(section) { detectTapGestures(onTap = { onPillTap(section) }) }
            .width(pillWidth)
            .height(pillHeight)
            .clip(RoundedCornerShape(pillHeight / 2))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
        ) {
          if (section.isUncertain) {
            Text(
              text = "?",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.align(Alignment.Center),
            )
          }
        }
      }
    }

    // --- Label pill (and "?" pill while in the uncertain section), to
    // the start (left) of the bar, tracking the thumb vertically ---
    val chipTop =
      if (trackHeight > 0) {
        (thumbFraction * trackHeight - chipHeightPx / 2f)
          .roundToInt()
          .coerceIn(0, (trackHeight - chipHeightPx).coerceAtLeast(0))
      } else {
        0
      }
    Row(
      Modifier.align(Alignment.TopEnd)
        .offset {
          IntOffset(
            x = -(touchWidthPx + pillGapPx + pillReservedWidthPx + chipGapPx),
            y = chipTop,
          )
        }
        .alpha(alpha),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (inUncertainSection) {
        Box(
          Modifier.width(UNCERTAIN_PILL_SIZE)
            .height(UNCERTAIN_PILL_SIZE)
            .clip(RoundedCornerShape(UNCERTAIN_PILL_SIZE / 2))
            .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "?",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Box(
        Modifier.clip(RoundedCornerShape(CHIP_HEIGHT / 2))
          .background(MaterialTheme.colorScheme.inverseSurface)
      ) {
        AnimatedContent(
          targetState = chipText,
          transitionSpec = {
            if (chipSlideUp) {
              (slideInVertically { it } + fadeIn()) togetherWith
                (slideOutVertically { -it } + fadeOut())
            } else {
              (slideInVertically { -it } + fadeIn()) togetherWith
                (slideOutVertically { it } + fadeOut())
            }
          },
          label = "index-scrollbar-label",
        ) { label ->
          Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
          )
        }
      }
    }
  }
}

private val TOUCH_WIDTH = 24.dp
private val TRACK_WIDTH = 4.dp
private val THUMB_HEIGHT = 48.dp
private val PILL_WIDTH = 12.dp
private val PILL_HEIGHT = 20.dp
private val UNCERTAIN_MARKER_SIZE = 22.dp
private val UNCERTAIN_PILL_SIZE = 28.dp
private val CHIP_GAP = 8.dp
private val PILL_GAP = 4.dp
private val CHIP_HEIGHT = 34.dp

private const val HIDE_DELAY_MS = 900L
private const val FADE_MS = 200
