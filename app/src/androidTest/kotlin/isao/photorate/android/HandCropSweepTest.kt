package isao.photorate.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isao.photorate.galleryComponent.classify.LandmarkRaterByThumb
import isao.photorate.imageRecognition.classify.HandGestureClassifier
import isao.photorate.imageRecognition.classify.HandLandmarkerOptions
import isao.photorate.imageRecognition.mediapipe.AndroidMediaPipeHandLandmarkerFactory
import isao.photorate.imageRecognition.mediapipe.detectFromBitmap
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-stage validation for the 3 samples the palm detector misses on the full image (4/4.jpg,
 * 4/4_also.jpg, 5/5_horizontal.jpg):
 *
 * Stage 1 — localize the hand by tiling the image (overlapping grid). The palm detector inside
 * HandLandmarker internally resizes the whole input to ~192x192, so a small hand gets compressed
 * away; a tile makes the hand a large fraction.
 *
 * Stage 2 — for every tile that produced a hand, crop the region from the full-res bitmap, upscale
 * it to ~512px, and re-landmark the crop. Small tiles yield poor landmarks (classifier returns
 * null), so we re-run on a proper crop.
 *
 * Logs landmarks of every stage-1 hit so the gestures can be analyzed offline.
 */
@RunWith(AndroidJUnit4::class)
class HandCropSweepTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val assets = InstrumentationRegistry.getInstrumentation().context.assets

  private val rater = LandmarkRaterByThumb()

  @Test
  fun sweepTilesAndCrops() {
    val factory = AndroidMediaPipeHandLandmarkerFactory(context)
    val landmarker =
      factory.createFromOptions(
        HandLandmarkerOptions(
          maxNumHands = 2,
          minHandDetectionConfidence = 0.35f,
          minHandPresenceConfidence = 0.35f,
        )
      )
    try {
      for (file in
        listOf(
          "4/4.jpg",
          "4/4_also.jpg",
          "5/5_horizontal.jpg",
        )) {
        val bytes = assets.open(file).use { it.readBytes() }
        val bitmap =
          decode(
            bytes,
            MAX_DIM,
          )
            ?: run {
              log("$file|DECODE_FAILED")
              continue
            }
        log("$file|full=${bitmap.width}x${bitmap.height}")
        val fullHands = landmarker.detectFromBitmap(bitmap)?.hands.orEmpty()
        log("$file|full-image hands=${fullHands.size}")

        for (tileDiv in
          listOf(
            3,
            4,
            5,
            6,
          )) {
          val hits = mutableListOf<TileHit>()
          for (tile in
            tiles(
              bitmap,
              tileDiv,
              0.25f,
            )) {
            val label = "$file|tileDiv=$tileDiv|tile=${tile.index}"
            val hands = landmarker.detectFromBitmap(tile.bitmap)?.hands.orEmpty()
            if (hands.isNotEmpty()) {
              log(
                "$label|STAGE1 hands=${hands.size}|tile=${tile.bitmap.width}x${tile.bitmap.height}"
              )
              hands.forEachIndexed { i, hand ->
                log(
                  "$label|stage1 hand=$i|gesture=${
                                        HandGestureClassifier.classify(
                                            hand
                                        )?.gesture
                                    }|score=${rater.rate(hand)?.score}|points=${
                                        dumpPoints(
                                            hand
                                        )
                                    }"
                )
              }
              hits += TileHit(tile)
            } else {
              log("$label|NO_HAND")
            }
          }
          if (hits.isEmpty()) continue

          // Stage 2: crop from the ORIGINAL decoded bitmap (not the tile),
          // pad generously, upscale to ~512px, re-landmark.
          for ((h, hit) in hits.withIndex()) {
            val crop =
              paddedCrop(
                bitmap,
                hit.tile,
                PAD_FRACTION,
              )
            val cropBitmap =
              Bitmap.createScaledBitmap(
                crop.bitmap,
                (crop.bitmap.width * UPSCALE).roundToInt(),
                (crop.bitmap.height * UPSCALE).roundToInt(),
                true,
              )
            val hands2 = landmarker.detectFromBitmap(cropBitmap)?.hands.orEmpty()
            if (hands2.isNotEmpty()) {
              log(
                "$file|STAGE2 hit=$h|tileDiv=$tileDiv|crop=${crop.bitmap.width}x${crop.bitmap.height}->${cropBitmap.width}x${cropBitmap.height}"
              )
              hands2.forEachIndexed { i, hand ->
                log(
                  "$file|stage2 hit=$h hand=$i|gesture=${
                                        HandGestureClassifier.classify(
                                            hand
                                        )?.gesture
                                    }|score=${
                                        rater.rate(
                                            hand
                                        )?.score
                                    }|points=${dumpPoints(hand)}"
                )
              }
            } else {
              log("$file|STAGE2 hit=$h|tileDiv=$tileDiv|NO_HAND in crop")
            }
          }
        }
      }
    } finally {
      landmarker.close()
    }
  }

  private data class TileHit(val tile: Tile)

  /** Overlapping grid of square tiles; keeps tile->bitmap region mapping. */
  private data class Tile(val bitmap: Bitmap, val srcX: Int, val srcY: Int) {
    val index: Int = srcX * 1000 + srcY
  }

  private fun tiles(
    bitmap: Bitmap,
    divisions: Int,
    overlapFraction: Float,
  ): List<Tile> {
    val tileSize =
      min(
        bitmap.width,
        bitmap.height,
      ) / divisions
    val step = (tileSize * (1f - overlapFraction)).toInt().coerceAtLeast(1)
    val result = mutableListOf<Tile>()
    var y = 0
    while (y < bitmap.height) {
      var x = 0
      while (x < bitmap.width) {
        val w =
          min(
            tileSize,
            bitmap.width - x,
          )
        val h =
          min(
            tileSize,
            bitmap.height - y,
          )
        if (w > 0 && h > 0) {
          result +=
            Tile(
              Bitmap.createBitmap(
                bitmap,
                x,
                y,
                w,
                h,
              ),
              x,
              y,
            )
        }
        x += step
      }
      y += step
    }
    return result
  }

  private fun paddedCrop(
    bitmap: Bitmap,
    tile: Tile,
    padFraction: Float,
  ): Tile {
    val padX = (tile.bitmap.width * padFraction).toInt()
    val padY = (tile.bitmap.height * padFraction).toInt()
    val x0 = (tile.srcX - padX).coerceAtLeast(0)
    val y0 = (tile.srcY - padY).coerceAtLeast(0)
    val x1 = (tile.srcX + tile.bitmap.width + padX).coerceAtMost(bitmap.width)
    val y1 = (tile.srcY + tile.bitmap.height + padY).coerceAtMost(bitmap.height)
    return Tile(
      Bitmap.createBitmap(
        bitmap,
        x0,
        y0,
        x1 - x0,
        y1 - y0,
      ),
      x0,
      y0,
    )
  }

  private fun dumpPoints(
    hand: isao.photorate.imageRecognition.classify.LandmarkedImage.Hand
  ): String = hand.points.joinToString(";") { p -> "${p.x},${p.y},${p.z}" }

  private fun decode(bytes: ByteArray, maxDim: Int): Bitmap? =
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val buffer = ByteBuffer.wrap(bytes)
        ImageDecoder.createSource(buffer).let { source ->
          ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val originalSize =
              min(
                info.size.width,
                info.size.height,
              )
            val sampleSize = originalSize / maxDim
            decoder.setTargetSampleSize(sampleSize.coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
          }
        }
      } else {
        null
      }
    } catch (_: Exception) {
      null
    }

  private fun log(msg: String) {
    android.util.Log.i("CropSweep", "CROP $msg")
  }

  companion object {
    private const val MAX_DIM = 1024
    private const val PAD_FRACTION = 0.5f
    private const val UPSCALE = 2.0f
  }
}
