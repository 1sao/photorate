package isao.photorate.photoslitert

import android.content.Context
import android.util.Log
import isao.photorate.inference.classify.HandLandmarker
import isao.photorate.inference.classify.HandLandmarkerFactory
import isao.photorate.inference.classify.HandLandmarkerOptions
import isao.photorate.inference.search.AppClipSearch
import isao.photorate.inference.search.AppClipSearchFactory
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * Android [HandLandmarkerFactory] backed by the shared LiteRT pipeline and the
 * custom CompiledModel engine (GPU-first, CPU fallback). The pipeline itself
 * lives in commonMain — this factory only wires the two engines + options.
 */
class AndroidLiteRtHandLandmarkerFactory @Inject constructor(private val context: Context) : HandLandmarkerFactory {

    override fun createFromOptions(options: HandLandmarkerOptions): HandLandmarker {
        initAssetContext(context)
        val landmarker: LiteRtHandLandmarker
        measureTimeMillis {
            landmarker = LiteRtHandLandmarker(
                detector = createLiteRtEngine(
                    ModelSource.Asset(LiteRtRtmModels.DETECTOR_ASSET),
                    EngineConfig(LiteRtAccelerator.GPU),
                ),
                pose = createLiteRtEngine(
                    ModelSource.Asset(LiteRtRtmModels.RTMPOSE_ASSET),
                    EngineConfig(LiteRtAccelerator.GPU),
                ),
                options = options,
            )
        }.also {
            Log.d(TAG, "Initialized LiteRT hand pipeline in $it ms (GPU first, CPU fallback)")
        }
        return landmarker
    }

    companion object {
        private const val TAG = "LiteRtHandLandmarker"
    }
}

/**
 * Android [AppClipSearchFactory] backed by the shared LiteRT session and the
 * custom CompiledModel engine (CPU+GPU hybrid, CPU fallback). The session
 * itself lives in commonMain — this factory only wires the engine + tokenizer.
 */
class AndroidLiteRtAppClipSearchFactory @Inject constructor(private val context: Context) : AppClipSearchFactory {

    override fun createFromOptions(options: AppClipSearchFactory.Options): AppClipSearch {
        initAssetContext(context)
        val session: LiteRtAppClipSearch
        measureTimeMillis {
            val engine = AndroidLiteRtEngine(
                context,
                AndroidLiteRtAppClipSearchFactory.MODEL_ASSET,
                AndroidLiteRtEngine.hybridOptions(),
            )
            val tokenizerJson = context.assets.open(TOKENIZER_ASSET)
                .bufferedReader().use { it.readText() }
            session = LiteRtAppClipSearch(engine, options, tokenizerJson)
        }.also {
            Log.d(TAG, "Initialized LiteRT MobileCLIP search in $it ms")
        }
        return session
    }

    companion object {
        private const val TAG = "LiteRtAppClipSearch"

        // fp16 variant of the combined S1 graph: bit-parity with fp32 on the
        // host task check, half the size (CLIP_S1_README.md quality row).
        const val MODEL_ASSET = "clip_s1_combined_f16.tflite"
        const val TOKENIZER_ASSET = "tokenizer.json"
    }
}
