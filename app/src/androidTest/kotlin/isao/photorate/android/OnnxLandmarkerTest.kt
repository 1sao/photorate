package isao.photorate.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import isao.photorate.imageRecognition.landmark.HandLandmarker
import isao.photorate.imageRecognition.landmark.HandLandmarkerOptions
import isao.photorate.imageRecognition.onnx.AndroidOnnxHandLandmarkerFactory
import isao.photorate.imageRecognition.onnx.OnnxGestureRecognizerProvider
import isao.photorate.imageRecognition.recognizer.GestureRecognizer
import org.junit.runner.RunWith

/**
 * Runs the ONNX hand pipeline (RTMDet -> multi-rotation RTMPose -> gesture rating — see
 * [AndroidOnnxHandLandmarkerFactory]) over the shared bucket dataset via [LandmarkerTest].
 *
 * TODO: AI slop. Review and refactor.
 */
@RunWith(AndroidJUnit4::class)
class OnnxLandmarkerTest : LandmarkerTest() {

  override val logTag = "OnnxLandmarkerTest"

  override val recognizers: List<GestureRecognizer<*>> =
    OnnxGestureRecognizerProvider().createRecognizers()

  override fun createLandmarker(): HandLandmarker =
    AndroidOnnxHandLandmarkerFactory(context)
      .createFromOptions(
        HandLandmarkerOptions(
          maxNumHands = 2,
          minHandDetectionConfidence = 0.25f,
          minHandKpConfidence = 0.3f,
          minHandConfidentKpConfidence = 0.45f,
        ),
      )
}
