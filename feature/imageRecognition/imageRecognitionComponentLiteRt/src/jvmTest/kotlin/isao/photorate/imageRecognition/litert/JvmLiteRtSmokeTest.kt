package isao.photorate.imageRecognition.litert

import isao.photorate.imageRecognition.gesture.ThumbSignal
import isao.photorate.imageRecognition.landmark.LandmarkModel
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

  private val modelsDir = File("../../../ml/litert/converted").canonicalFile
  private val samplesDir = File("../../../plans/samples").canonicalFile
  private val recognizers = LiteRtGestureRecognizerProvider().createRecognizers()

  @Test
  fun thumbRatingRunsRealInference() {
    val detector = File(modelsDir, "rtmdet_hand_320_f32.tflite")
    val pose = File(modelsDir, "rtmpose_hand_256_f32.tflite")
    assertTrue(detector.exists(), "detector model missing: $detector")
    assertTrue(pose.exists(), "pose model missing: $pose")

    val factory = JvmLiteRtHandLandmarkerFactory(modelsDir.path)
    val landmarker = factory.createFromOptions(LandmarkModel.LITERT.defaultOptions)
    try {
      val sample = File(samplesDir, "5/5_trope.jpg")
      assertTrue(sample.exists(), "sample missing: $sample")
      val image = ImageIO.read(sample)
      assertNotNull(image, "failed to decode $sample")

      val results = landmarker.detectWithRecognizers(image, recognizers)
      assertTrue(results.isNotEmpty(), "no gesture results on $sample")

      val thumbs = results.filter { it.gesture is ThumbSignal && it.score?.score == 5 }
      println("results=$results")
      assertTrue(thumbs.isNotEmpty(), "expected a THUMBS-5 read on 5_trope, got $results")
    } finally {
      landmarker.close()
    }
  }
}
