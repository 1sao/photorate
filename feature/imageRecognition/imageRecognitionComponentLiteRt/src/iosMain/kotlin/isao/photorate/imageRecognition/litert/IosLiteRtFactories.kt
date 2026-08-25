package isao.photorate.imageRecognition.litert

import isao.photorate.imageRecognition.classify.HandLandmarker
import isao.photorate.imageRecognition.classify.HandLandmarkerFactory
import isao.photorate.imageRecognition.classify.HandLandmarkerOptions
import isao.photorate.imageRecognition.search.AppClipSearch
import isao.photorate.imageRecognition.search.AppClipSearchFactory

/**
 * iOS [HandLandmarkerFactory] backed by the shared LiteRT pipeline and kmplitert engines. Models
 * ship as bundle resources; the app passes the directory containing the converted tflite files.
 */
class IosLiteRtHandLandmarkerFactory(private val modelsDir: String) : HandLandmarkerFactory {

  override fun createFromOptions(options: HandLandmarkerOptions): HandLandmarker =
    LiteRtHandLandmarker(
      detector =
        createLiteRtEngine(
          ModelSource.File("$modelsDir/${LiteRtRtmModels.DETECTOR_ASSET}"),
          EngineConfig(LiteRtAccelerator.GPU),
        ),
      pose =
        createLiteRtEngine(
          ModelSource.File("$modelsDir/${LiteRtRtmModels.RTMPOSE_ASSET}"),
          EngineConfig(LiteRtAccelerator.GPU),
        ),
    )
}

/** iOS [AppClipSearchFactory] backed by the shared session + kmplitert engine. */
class IosLiteRtAppClipSearchFactory(
  private val modelsDir: String,
  private val tokenizerJson: String,
) : AppClipSearchFactory {

  override fun createFromOptions(options: AppClipSearchFactory.Options): AppClipSearch =
    LiteRtAppClipSearch(
      engine =
        createLiteRtEngine(
          ModelSource.File("$modelsDir/${IosLiteRtAppClipSearchFactory.MODEL_ASSET}"),
          EngineConfig(LiteRtAccelerator.GPU),
        ),
      options = options,
      tokenizerJson = tokenizerJson,
    )

  companion object {
    const val MODEL_ASSET = "clip_s1_combined_f16.tflite"
  }
}
