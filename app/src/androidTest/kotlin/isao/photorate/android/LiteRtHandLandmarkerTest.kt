package isao.photorate.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import isao.photorate.imageRecognition.classify.GestureRecognizer
import isao.photorate.imageRecognition.classify.HandLandmarker
import isao.photorate.imageRecognition.classify.HandLandmarkerOptions
import isao.photorate.imageRecognition.litert.AndroidLiteRtHandLandmarkerFactory
import isao.photorate.imageRecognition.litert.LiteRtGestureRecognizerProvider
import org.junit.runner.RunWith

/**
 * Runs the LiteRT hand pipeline (RTMDet + RTMPose on CompiledModel — see
 * [AndroidLiteRtHandLandmarkerFactory]) over the shared bucket dataset via [LandmarkerTest],
 * mirroring [OnnxLandmarkerTest] on identical inputs and expectations.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtHandLandmarkerTest : LandmarkerTest() {

  override val logTag = "LiteRtHandLandmarkerTest"

  override val recognizers: List<GestureRecognizer<*>> =
    LiteRtGestureRecognizerProvider().createRecognizers()

  override fun createLandmarker(): HandLandmarker =
    AndroidLiteRtHandLandmarkerFactory(context)
      .createFromOptions(
        HandLandmarkerOptions(
          maxNumHands = 2,
          minHandDetectionConfidence = 0.25f,
          minHandKpConfidence = 0.3f,
          minHandConfidentKpConfidence = 0.45f,
        ),
      )
}
