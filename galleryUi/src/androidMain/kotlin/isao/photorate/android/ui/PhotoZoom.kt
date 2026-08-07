package isao.photorate.android.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import isao.photorate.android.ui.PhotoZoomState.Companion.DOUBLE_TAP_SCALE
import kotlinx.coroutines.launch

/**
 * Reusable photo-viewer interaction state: a scale + pan pair that powers the [Modifier.panAndZoom]
 * modifier. Gestures write [scale]/[offset] directly (snappy, finger-tracked); programmatic changes
 * (double-tap zoom, reset) go through [animateTo] for a smooth spring.
 *
 * Pan is clamped so a zoomed image never reveals empty space around it: the clamp uses
 * [viewportSize] (the modifier's bounds) and [contentSize] (the drawn image bounds, i.e. the fit
 * rect), both fed by the caller.
 */
@Stable
class PhotoZoomState(val maxScale: Float = MAX_SCALE) {
  var scale by mutableFloatStateOf(1f)
    private set

  var offset by mutableStateOf(Offset.Zero)
    private set

  /** Bounds of the viewport (the modifier's node), set via onSizeChanged. */
  var viewportSize by mutableStateOf(IntSize.Zero)
    private set

  /** Bounds of the drawn content (the fitted image), for pan clamping. */
  var contentSize by mutableStateOf(IntSize.Zero)
    private set

  fun updateViewportSize(size: IntSize) {
    viewportSize = size
  }

  fun updateContentSize(size: IntSize) {
    contentSize = size
  }

  /**
   * Applies a pinch-zoom gesture, keeping the focal [centroid] (in viewport coordinates) pinned
   * under the fingers.
   */
  fun onTransform(centroid: Offset, pan: Offset, zoom: Float) {
    val newScale = (scale * zoom).coerceIn(1f, maxScale)
    val k = newScale / scale
    // Content point under the centroid stays under it: (c - o)/s fixed.
    val rawOffset = (centroid - (centroid - offset) * k) + pan
    scale = newScale
    offset = clampOffset(rawOffset)
  }

  /** True when the image is zoomed past 1x. */
  fun isZoomed(): Boolean = scale > 1f

  /**
   * Smoothly animates to [targetScale] with [targetOffset]. Used by double-tap zoom (keep the tap
   * point pinned) and reset.
   */
  suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
    val startScale = scale
    val startOffset = offset
    if (startScale == targetScale && startOffset == targetOffset) return
    animate(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioMediumBouncy,
          stiffness = Spring.StiffnessLow,
        ),
    ) { fraction, _ ->
      scale = lerp(startScale, targetScale, fraction)
      offset = clampOffset(lerp(startOffset, targetOffset, fraction))
    }
  }

  /** The offset that keeps the viewport point [focal] pinned at [targetScale]. */
  private fun offsetPinning(focal: Offset, targetScale: Float): Offset {
    val k = targetScale / scale
    return focal - (focal - offset) * k
  }

  /** Double-tap toggles between 1x and [DOUBLE_TAP_SCALE], pinning [focal]. */
  suspend fun toggleDoubleTap(focal: Offset) {
    if (scale > 1f) {
      animateTo(targetScale = 1f, targetOffset = Offset.Zero)
    } else {
      animateTo(
        targetScale = DOUBLE_TAP_SCALE,
        targetOffset = offsetPinning(focal, DOUBLE_TAP_SCALE),
      )
    }
  }

  private fun clampOffset(raw: Offset): Offset {
    if (scale <= 1f) return Offset.Zero
    val maxX = ((contentSize.width * scale - viewportSize.width) / 2f).coerceAtLeast(0f)
    val maxY = ((contentSize.height * scale - viewportSize.height) / 2f).coerceAtLeast(0f)
    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
  }

  companion object {
    const val MAX_SCALE = 5f
    const val DOUBLE_TAP_SCALE = 3f
  }
}

@Composable
fun rememberPhotoZoomState(maxScale: Float = PhotoZoomState.MAX_SCALE): PhotoZoomState = remember {
  PhotoZoomState(maxScale)
}

/**
 * Reusable pan/zoom modifier for photo viewers:
 * - pinch to zoom between 1x and [PhotoZoomState.maxScale],
 * - drag to pan once zoomed (clamped to the image bounds),
 * - double-tap to toggle zoom around the tapped point,
 * - [onTap] for a single tap (fires only when not zoomed; zoomed taps reset the zoom instead, like
 *   a photo gallery).
 *
 * The gesture handlers never consume drag events beyond the transform, so parent scrollables keep
 * working at 1x zoom.
 *
 * Requires [PhotoZoomState.updateContentSize] to be fed the drawn image size (the fit rect) for
 * correct pan clamping.
 */
@Composable
fun Modifier.panAndZoom(state: PhotoZoomState, onTap: (() -> Unit)? = null): Modifier {
  val scope = rememberCoroutineScope()
  val currentOnTap by rememberUpdatedState(onTap)
  return this.onSizeChanged { state.updateViewportSize(it) }
    .pointerInput(state) {
      detectTransformGestures { centroid, pan, zoom, _ -> state.onTransform(centroid, pan, zoom) }
    }
    .pointerInput(state) {
      detectTapGestures(
        onDoubleTap = { focal -> scope.launch { state.toggleDoubleTap(focal) } },
        onTap = { currentOnTap?.invoke() },
      )
    }
    // Top-left transform origin so the gesture math (content = (viewport -
    // offset) / scale) matches the rendered transform; the default center
    // origin would let the image drift under the fingers while zooming.
    .graphicsLayer {
      transformOrigin = TransformOrigin(0f, 0f)
      scaleX = state.scale
      scaleY = state.scale
      translationX = state.offset.x
      translationY = state.offset.y
    }
}
