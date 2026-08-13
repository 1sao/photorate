package isao.photorate.imageRecognition.mediapipe

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.MediaPipeException
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker as MpHandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import isao.photorate.imageRecognition.classify.HandLandmarker
import isao.photorate.imageRecognition.classify.HandLandmarkerFactory
import isao.photorate.imageRecognition.classify.HandLandmarkerOptions
import isao.photorate.imageRecognition.classify.LandmarkCandidate
import isao.photorate.imageRecognition.classify.LandmarkedImage
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * Android [HandLandmarkerFactory] backed by MediaPipe Tasks (HandLandmarker). The common contract's
 * [LandmarkCandidate] is a Bitmap on Android; MediaPipe's native input is an MPImage, so detect()
 * wraps it here.
 *
 * The MediaPipe pipeline historically returned landmarks already normalized to 0..1; the common
 * scan contract expects image-pixel space (the classifier is tuned on isotropic geometry), so
 * detect() scales them back to pixels. The scan then normalizes them again for storage — an exact
 * round trip.
 */
class AndroidMediaPipeHandLandmarkerFactory @Inject constructor(private val context: Context) :
  HandLandmarkerFactory {
  override fun createFromOptions(options: HandLandmarkerOptions): HandLandmarker {
    val recognizer = createHandLandmarker(options, Delegate.GPU)
    return AndroidMediaPipeHandLandmarker(recognizer)
  }

  private fun createHandLandmarker(
    options: HandLandmarkerOptions,
    delegate: Delegate,
  ): MpHandLandmarker =
    try {
      val mediaPipeOptions =
        MpHandLandmarker.HandLandmarkerOptions.builder()
          .setBaseOptions(
            BaseOptions.builder().setModelAssetPath(MODEL_PATH).setDelegate(delegate).build()
          )
          .setRunningMode(RunningMode.IMAGE)
          .setNumHands(options.maxNumHands)
          .setMinHandDetectionConfidence(options.minHandDetectionConfidence)
          .setMinHandPresenceConfidence(options.minHandPresenceConfidence)
          .build()

      val recognizer: MpHandLandmarker
      measureTimeMillis {
        recognizer = MpHandLandmarker.createFromOptions(context, mediaPipeOptions)
      }
        .also { Log.d("HandLandmarker", "Initialized with $delegate in $it ms") }
      recognizer
    } catch (e: MediaPipeException) {
      if (delegate == Delegate.GPU) {
        // GPU inference needs OpenGL ES 3.1+ (missing on emulators and some
        // devices). Fall back to CPU so scanning still works.
        Log.w("HandLandmarker", "GPU delegate unavailable, retrying with CPU", e)
        createHandLandmarker(options, Delegate.CPU)
      } else {
        throw e
      }
    }

  companion object {
    // The .task model ships in `shared`'s compose resources; the packaged
    // asset path is what MediaPipe's setModelAssetPath resolves.
    const val MODEL_PATH =
      "composeResources/photorate.shared.generated.resources/files/hand_landmarker.task"
  }
}

class AndroidMediaPipeHandLandmarker
internal constructor(private val landmarker: MpHandLandmarker) : HandLandmarker {
  override fun detect(candidate: LandmarkCandidate): LandmarkedImage {
    val result: HandLandmarkerResult
    val detectedIn = measureTimeMillis {
      result = landmarker.detect(BitmapImageBuilder(candidate).build())
    }
    return LandmarkedImage(
      hands =
        result.landmarks().map { handDetections ->
          val width = candidate.width
          val height = candidate.height
          LandmarkedImage.Hand(
            points =
              handDetections.map {
                LandmarkedImage.Point(
                  x = it.x() * width,
                  y = it.y() * height,
                  z = it.z(),
                )
              }
          )
        },
      detectedInMs = detectedIn,
    )
  }

  override fun close() {
    landmarker.close()
  }
}
