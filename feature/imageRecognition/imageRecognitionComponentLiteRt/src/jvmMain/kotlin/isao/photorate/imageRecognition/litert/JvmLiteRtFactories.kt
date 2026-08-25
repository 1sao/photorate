package isao.photorate.imageRecognition.litert

import isao.photorate.imageRecognition.classify.HandLandmarker
import isao.photorate.imageRecognition.classify.HandLandmarkerFactory
import isao.photorate.imageRecognition.classify.HandLandmarkerOptions
import isao.photorate.imageRecognition.search.AppClipSearch
import isao.photorate.imageRecognition.search.AppClipSearchFactory

/**
 * JVM [HandLandmarkerFactory] backed by the shared LiteRT pipeline and kmplitert engines (the JVM
 * jar bundles the native dylib). Models are loaded from [modelsDir] on disk.
 */
class JvmLiteRtHandLandmarkerFactory(private val modelsDir: String) : HandLandmarkerFactory {

  override fun createFromOptions(options: HandLandmarkerOptions): HandLandmarker =
    LiteRtHandLandmarker(
      detector =
        createLiteRtEngine(
          ModelSource.File("$modelsDir/${LiteRtRtmModels.DETECTOR_ASSET}"),
          EngineConfig(LiteRtAccelerator.CPU),
        ),
      pose =
        createLiteRtEngine(
          ModelSource.File("$modelsDir/${LiteRtRtmModels.RTMPOSE_ASSET}"),
          EngineConfig(LiteRtAccelerator.CPU),
        ),
    )
}

/** JVM [AppClipSearchFactory] backed by the shared session + kmplitert engine. */
class JvmLiteRtAppClipSearchFactory(
  private val modelsDir: String,
  private val tokenizerJson: String,
) : AppClipSearchFactory {

  override fun createFromOptions(options: AppClipSearchFactory.Options): AppClipSearch =
    LiteRtAppClipSearch(
      engine =
        createLiteRtEngine(
          ModelSource.File("$modelsDir/${JvmLiteRtAppClipSearchFactory.MODEL_ASSET}"),
          EngineConfig(LiteRtAccelerator.CPU),
        ),
      options = options,
      tokenizerJson = tokenizerJson,
    )

  companion object {
    const val MODEL_ASSET = "clip_s1_combined_f16.tflite"
  }
}
