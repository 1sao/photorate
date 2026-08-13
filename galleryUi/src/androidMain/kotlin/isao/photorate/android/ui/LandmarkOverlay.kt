package isao.photorate.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import isao.photorate.inference.classify.LandmarkedImage.Point

/** Developer Mode overlay: draws the raw detected 21-point hand landmarks on top of the photo. */
@Composable
internal fun LandmarkOverlay(hands: List<List<Point>>, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    hands.forEach { hand ->
      val screen = hand.map { point -> Offset(point.x * size.width, point.y * size.height) }
      // Skeleton edges (MediaPipe hand connections, index-based).
      HAND_SKELETON.forEach { (from, to) ->
        if (from < screen.size && to < screen.size) {
          drawLine(
            color = DEV_LANDMARK_COLOR,
            start = screen[from],
            end = screen[to],
            strokeWidth = DEV_SKELETON_WIDTH.toPx(),
          )
        }
      }
      screen.forEach { offset ->
        drawCircle(
          color = DEV_LANDMARK_COLOR,
          radius = DEV_JOINT_RADIUS.toPx(),
          center = offset,
        )
      }
    }
  }
}

/** High-contrast cyan for the dev-mode landmark overlay (visible on any photo). */
private val DEV_LANDMARK_COLOR = Color(0xFF00E5FF)
private val DEV_SKELETON_WIDTH = 3.dp
private val DEV_JOINT_RADIUS = 6.dp

/** 21-point hand skeleton edges (0..20). */
private val HAND_SKELETON =
  listOf(
    0 to 1,
    1 to 2,
    2 to 3,
    3 to 4, // thumb
    0 to 5,
    5 to 6,
    6 to 7,
    7 to 8, // index
    5 to 9,
    9 to 10,
    10 to 11,
    11 to 12, // middle
    9 to 13,
    13 to 14,
    14 to 15,
    15 to 16, // ring
    13 to 17,
    17 to 18,
    18 to 19,
    19 to 20, // pinky
    0 to 17, // palm base
  )
