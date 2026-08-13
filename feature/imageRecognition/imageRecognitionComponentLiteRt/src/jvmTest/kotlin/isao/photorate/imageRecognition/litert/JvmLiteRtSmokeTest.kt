package isao.photorate.imageRecognition.litert

import isao.photorate.imageRecognition.classify.HandGesture
import isao.photorate.imageRecognition.classify.HandGestureClassifier
import isao.photorate.imageRecognition.classify.LandmarkModel
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-inference smoke test: the full LiteRT hand pipeline runs on the JVM via kmplitert (its JVM
 * jar bundles the LiteRT dylib) over the converted models in ml/litert/converted and the sample
 * photos in plans/samples. This proves the shared pipeline + JVM engine actually execute — the same
 * code path the iOS and Android factories use — instead of just type-checking.
 */
class JvmLiteRtSmokeTest {

  // jvmTest's working dir is the module dir
  // (feature/imageRecognition/imageRecognitionComponentLiteRt); the models/samples live at root.
  private val modelsDir = File("../../../ml/litert/converted").canonicalFile
  private val samplesDir = File("../../../plans/samples").canonicalFile

  @Test
  fun thumbRatingRunsRealInference() {
    val detector =
      File(
        modelsDir,
        "rtmdet_hand_320_f32.tflite",
      )
    val pose =
      File(
        modelsDir,
        "rtmpose_hand_256_f32.tflite",
      )
    assertTrue(
      detector.exists(),
      "detector model missing: $detector",
    )
    assertTrue(pose.exists(), "pose model missing: $pose")

    val factory = JvmLiteRtHandLandmarkerFactory(modelsDir.path)
    // Same tuned gates the app uses on-device
    // (LandmarkModel.LITERT).
    val landmarker = factory.createFromOptions(LandmarkModel.LITERT.defaultOptions)
    try {
      // A known thumbs-up sample (5/
      // = score 5 in the app's
      // dataset).
      val sample =
        File(
          samplesDir,
          "5/5_trope.jpg",
        )
      assertTrue(
        sample.exists(),
        "sample missing: $sample",
      )
      val image = ImageIO.read(sample)
      assertNotNull(
        image,
        "failed to decode $sample",
      )

      val detected = landmarker.detect(image)
      assertTrue(
        detected.hands.isNotEmpty(),
        "no hand detected on $sample",
      )

      val classifications = detected.hands.map { HandGestureClassifier.classify(it) }
      val thumbs = classifications.filter { it?.gesture == HandGesture.THUMBS_UP }
      println(
        "hands=${detected.hands.size} gestures=$classifications " +
          "detectedInMs=${detected.detectedInMs}"
      )
      assertTrue(
        thumbs.isNotEmpty(),
        "expected a THUMBS_UP read on 5_trope, got $classifications",
      )
    } finally {
      landmarker.close()
    }
  }
}
