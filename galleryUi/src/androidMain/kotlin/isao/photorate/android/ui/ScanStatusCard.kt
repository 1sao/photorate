package isao.photorate.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import isao.photorate.gallery.db.GalleryImageStatus
import isao.photorate.gallery.db.GalleryImageStatus.DONE
import isao.photorate.gallery.db.GalleryImageStatus.FAILED
import isao.photorate.gallery.db.GalleryImageStatus.PENDING
import isao.photorate.gallery.db.GalleryImageStatus.PROCESSING
import isao.photorate.galleryRepository.GalleryStatusCounts
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Scan status rendered as a full-width grid item (instead of a dialog): one counter (label above
 * animated count) per status. Pending, Processing and Done share a single row of three; the Failed
 * counter is hidden while there are no errors and, once it appears, the four counters split into
 * two rows of two.
 */
@Composable
fun ScanStatusCard(statusCounts: GalleryStatusCounts, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(Modifier.padding(20.dp).animateContentSize()) {
      Text(
        text = "Scan status",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = "${statusCounts.total} photos total",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(16.dp))
      val counters =
        listOf(PENDING, PROCESSING, DONE) +
          if (statusCounts[FAILED] > 0L) listOf(FAILED) else emptyList()
      if (counters.size == 3) {
        CounterRow(counters, statusCounts)
      } else {
        counters.chunked(2).forEach { rowStatuses -> CounterRow(rowStatuses, statusCounts) }
      }
    }
  }
}

/** One row of counters; every column shares the row width equally. */
@Composable
private fun CounterRow(statuses: List<GalleryImageStatus>, statusCounts: GalleryStatusCounts) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    statuses.forEach { status ->
      Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        StatusLabel(status)
        StatusValue(statusCounts[status], status.color)
      }
    }
  }
}

@Composable
private fun StatusLabel(status: GalleryImageStatus, modifier: Modifier = Modifier) {
  Text(
    text = status.title,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier,
  )
}

/**
 * Odometer-style count: each digit slides in/out when the displayed value changes. Changes are
 * processed sequentially — an incoming value never cancels or overrides the in-flight slide, it is
 * picked up once the slide finishes. A change by one ticks at the default speed; a jump by more
 * than one ticks at double speed regardless of magnitude; when the value settles (no pending change
 * after a tick), the text plays a short bouncy settling spring.
 */
@Composable
private fun StatusValue(count: Long, color: Color, modifier: Modifier = Modifier) {
  val latest = rememberUpdatedState(count)
  var displayed by remember { mutableLongStateOf(count) }
  val speed = remember { DigitSpeedFlag() }
  val scale = remember { Animatable(1f) }

  LaunchedEffect(Unit) {
    snapshotFlow { latest.value }
      .collect {
        var hasTick = false
        while (true) {
          val target = latest.value
          if (target == displayed) {
            if (hasTick) {
              // Settled: land with a small overshoot and let the
              // spring bounce it to rest.
              scale.snapTo(1.12f)
              scale.animateTo(
                targetValue = 1f,
                animationSpec =
                  spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                  ),
              )
            }
            break
          }
          val tickMillis =
            if (abs(target - displayed) > 1L) {
              FAST_TICK_MILLIS
            } else {
              DEFAULT_TICK_MILLIS
            }
          speed.fast = tickMillis == FAST_TICK_MILLIS
          displayed = target
          hasTick = true
          // The slide takes ~tickMillis; wait a beat so the next tick
          // never interrupts the current transition.
          delay((tickMillis + TICK_FINISH_PAD_MILLIS).milliseconds)
        }
      }
  }

  val digitTick: AnimatedContentTransitionScope<Char>.() -> ContentTransform = {
    if (speed.fast) fastTick() else defaultTick()
  }

  Row(
    modifier =
      modifier.animateContentSize().graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
      },
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    displayed.toString().forEachIndexed { index, digit ->
      AnimatedContent(
        targetState = digit,
        transitionSpec = digitTick,
        label = "digit-$index",
      ) { animatedDigit ->
        Text(
          text = animatedDigit.toString(),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = color,
        )
      }
    }
  }
}

/** The original slide from GalleryStatusDialog, at normal speed. */
private fun AnimatedContentTransitionScope<Char>.defaultTick(): ContentTransform =
  if (targetState > initialState) {
      (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
    } else {
      (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
    }
    .using(SizeTransform(clip = false))

/** Same slide as [defaultTick] but at double speed. */
private fun AnimatedContentTransitionScope<Char>.fastTick(): ContentTransform {
  val slide = tween<IntOffset>(FAST_TICK_MILLIS)
  val fade = tween<Float>(FAST_TICK_MILLIS)
  return if (targetState > initialState) {
      (slideInVertically(slide) { -it } + fadeIn(fade)) togetherWith
        (slideOutVertically(slide) { it } + fadeOut(fade))
    } else {
      (slideInVertically(slide) { it } + fadeIn(fade)) togetherWith
        (slideOutVertically(slide) { -it } + fadeOut(fade))
    }
    .using(SizeTransform(clip = false))
}

/** Plain holder, not snapshot state: a mid-flight slide keeps its speed spec. */
private class DigitSpeedFlag {
  var fast: Boolean = false
}

private const val DEFAULT_TICK_MILLIS = 300
private const val FAST_TICK_MILLIS = 150
private const val TICK_FINISH_PAD_MILLIS = 50L

private val GalleryImageStatus.color
  get() =
    when (this) {
      PENDING -> Color(0xFF9E9E9E)
      FAILED -> Color(0xFFF44336)
      PROCESSING -> Color(0xFF2196F3)
      DONE -> Color(0xFF4CAF50)
    }

private val GalleryImageStatus.title
  get() =
    when (this) {
      PENDING -> "Pending"
      FAILED -> "Failed"
      PROCESSING -> "Processing"
      DONE -> "Done"
    }

@Preview(showBackground = true, widthDp = 411)
@Composable
private fun ScanStatusCardPreview() {
  MaterialTheme {
    val counts by rememberPreviewGalleryStatusCounts()
    ScanStatusCard(counts)
  }
}

/**
 * Emits a simulated stream of [GalleryStatusCounts] as if [totalPhotos] images were being scanned
 * one at a time. Runs forever, restarting the batch from scratch once every photo is done/failed.
 */
private fun previewGalleryStatusFlow(
  totalPhotos: Long = 120L,
  processingDelay: Long = 520L,
  gapDelay: Long = 100L,
  restartDelay: Duration = 3.seconds,
  failureChanceOneIn: Int = 12,
): Flow<GalleryStatusCounts> = flow {
  while (true) {
    var done = 0L
    var failed = 0L

    emit(
      GalleryStatusCounts(
        counts =
          mapOf(
            PENDING to totalPhotos,
            PROCESSING to 0,
            DONE to 0,
            FAILED to 0,
          )
      )
    )

    while (done + failed < totalPhotos) {
      emit(
        GalleryStatusCounts(
          counts =
            mapOf(
              PENDING to totalPhotos - done - failed - 1,
              PROCESSING to 1,
              DONE to done,
              FAILED to failed,
            )
        )
      )
      delay(processingDelay.milliseconds)

      if (Random.nextInt(failureChanceOneIn) == 0) failed++ else done++
      emit(
        GalleryStatusCounts(
          counts =
            mapOf(
              PENDING to totalPhotos - done - failed,
              PROCESSING to 0,
              DONE to done,
              FAILED to failed,
            )
        )
      )
      delay(gapDelay.milliseconds)
    }

    delay(restartDelay)
  }
}

@Composable
private fun rememberPreviewGalleryStatusCounts(
  totalPhotos: Long = 120L
): State<GalleryStatusCounts> {
  val flow = remember(totalPhotos) { previewGalleryStatusFlow(totalPhotos) }
  return flow.collectAsState(
    initial =
      GalleryStatusCounts(
        counts =
          mapOf(
            PENDING to totalPhotos,
            PROCESSING to 0,
            DONE to 0,
            FAILED to 0,
          )
      )
  )
}
