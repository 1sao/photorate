package isao.photorate.galleryUi

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import isao.photorate.coreUi.composable.PhotoRatePreview
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val PendingColor = Color(0xFF9E9E9E)
private val DoneColor = Color(0xFF4CAF50)

@Composable
fun ScanStatusCard(statusCounts: GalleryStatusCounts, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.largeIncreased,
    color = MaterialTheme.colorScheme.surfaceBright,
  ) {
    Column(Modifier.padding(20.dp)) {
      Text(
        text = "Scan status",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = "${statusCounts.total} photos total",
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(16.dp))
      Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Counter(
          title = "Pending",
          count = statusCounts.pending,
          color = PendingColor,
          modifier = Modifier.weight(1f),
        )
        Counter(
          title = "Done",
          count = statusCounts.done + statusCounts.failed,
          color = DoneColor,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun Counter(
  title: String,
  count: Long,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      text = title,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    StatusValue(count, color)
  }
}

/**
 * Odometer-style count: each digit slides in/out when the displayed value changes. The next
 * animation only plays when the previous one has finished, so intermediate updates may be skipped.
 */
@Composable
private fun StatusValue(count: Long, color: Color, modifier: Modifier = Modifier) {
  val latest = rememberUpdatedState(count)
  var displayed by remember { mutableLongStateOf(count) }

  LaunchedEffect(Unit) {
    snapshotFlow { latest.value }
      .collect {
        while (true) {
          val target = latest.value
          if (target == displayed) {
            break
          }
          displayed = target
          delay((DEFAULT_TICK_MILLIS).milliseconds)
        }
      }
  }

  Row(
    modifier = modifier.animateContentSize(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    displayed.toString().forEachIndexed { index, digit ->
      AnimatedContent(
        targetState = digit,
        transitionSpec = { tick() },
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

private fun AnimatedContentTransitionScope<Char>.tick(): ContentTransform =
  if (targetState > initialState) {
      (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
    } else {
      (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
    }
    .using(SizeTransform(clip = false))

private const val DEFAULT_TICK_MILLIS = 300

@Preview
@Composable
private fun ScanStatusCardPreview() {
  PhotoRatePreview {
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

    emit(GalleryStatusCounts(pending = totalPhotos))

    while (done + failed < totalPhotos) {
      delay(processingDelay.milliseconds)

      if (Random.nextInt(failureChanceOneIn) == 0) failed++ else done++
      emit(
        GalleryStatusCounts(
          pending = totalPhotos - done - failed,
          done = done,
          failed = failed,
        ),
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
  return flow.collectAsState(initial = GalleryStatusCounts(pending = totalPhotos))
}
