package isao.photorate.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import isao.photorate.imageRecognition.landmark.HandLandmarker
import isao.photorate.imageRecognition.landmark.HandLandmarkerOptions
import isao.photorate.imageRecognition.litert.AndroidLiteRtHandLandmarkerFactory
import isao.photorate.imageRecognition.litert.LiteRtGestureRecognizerProvider
import isao.photorate.imageRecognition.recognizer.GestureRecognizer
import org.junit.runner.RunWith

/**
 * Runs the LiteRT hand pipeline (RTMDet + RTMPose on CompiledModel — see
 * [AndroidLiteRtHandLandmarkerFactory]) over the shared bucket dataset via [LandmarkerTest],
 * mirroring [OnnxLandmarkerTest] on identical inputs and expectations.
 *
 * Python mirror: `feature/imageRecognition/scripts/test_landmarks_regression.py` runs the same
 * bucket dataset with the same expectations on host (`--backend tflite` = this test's models,
 * `--backend onnx` = rtmlib ground-truth baseline). Diff both runs with
 * `feature/imageRecognition/scripts/compare_device_log.py`.
 *
 * TODO: AI slop. Review and refactor.
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
