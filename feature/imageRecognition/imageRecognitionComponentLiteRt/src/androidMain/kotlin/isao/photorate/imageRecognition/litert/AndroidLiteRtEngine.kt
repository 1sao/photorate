package isao.photorate.imageRecognition.litert

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.LiteRtException

/**
 * Android [LiteRtEngine] backed by the custom CompiledModel code. GPU-first (OpenCL, FP32 — the
 * accuracy-safe config from LiteRtOnDeviceVerificationTest) with a CPU fallback when the GPU cannot
 * compile the graph (no OpenCL on emulators/some devices) or fails at runtime, mirroring the
 * MediaPipe factory's GPU->CPU fallback. The scan runs on background threads with no EGL context;
 * OpenCL buffers need none (only the GL backend's textures would).
 */
class AndroidLiteRtEngine
internal constructor(
  private val context: Context,
  private val asset: String,
  options: CompiledModel.Options,
) : LiteRtEngine {

  /** False once the GPU is known-unusable (compile failure at init). */
  private var gpuActive = true

  @Volatile private var runner: CompiledModelRunner = create(options)

  private var demoted = false

  private fun create(options: CompiledModel.Options): CompiledModelRunner =
    try {
      CompiledModelRunner.fromAssets(context, asset, options).also {
        Log.d(TAG, "$asset compiled on GPU (OpenCL, FP32)")
      }
    } catch (e: LiteRtException) {
      gpuActive = false
      Log.w(TAG, "$asset GPU compile failed, falling back to CPU", e)
      CompiledModelRunner.fromAssets(context, asset, CompiledModel.Options(Accelerator.CPU))
    }

  override fun writeFloatInput(index: Int, data: FloatArray) {
    runner.inputBuffers[index].writeFloat(data)
  }

  override fun writeLongInput(index: Int, data: LongArray) {
    runner.inputBuffers[index].writeLong(data)
  }

  /**
   * Runs the current runner; demotes to CPU once when the GPU fails at runtime. Only a GPU runner
   * may demote — a CPU runner (GPU compile already failed at init) that throws is a real error, not
   * a fallback opportunity.
   */
  override fun run() {
    try {
      runner.run()
    } catch (e: LiteRtException) {
      if (!demoted && gpuActive) {
        demoted = true
        Log.w(TAG, "$asset GPU failed at runtime, demoting to CPU", e)
        val cpu =
          CompiledModelRunner.fromAssets(
            context,
            asset,
            CompiledModel.Options(Accelerator.CPU),
          )
        val old = runner
        runner = cpu
        old.close()
        runner.run()
      } else {
        throw e
      }
    }
  }

  override fun readOutput(index: Int): FloatArray = runner.outputBuffers[index].readFloat()

  override fun close() = runner.close()

  companion object {
    private const val TAG = "LiteRtEngine"

    /** Hand-model config: GPU-first, OpenCL FP32 (accuracy-safe). */
    fun gpuFirstOptions() =
      CompiledModel.Options(Accelerator.GPU).apply {
        gpuOptions =
          CompiledModel.GpuOptions(
            backend = CompiledModel.GpuOptions.Backend.OPENCL,
            precision = CompiledModel.GpuOptions.Precision.FP32,
          )
      }

    /**
     * MobileCLIP config: CPU+GPU hybrid (the text head's CAST/ EMBEDDING_LOOKUP have no GPU kernel,
     * so strict GPU cannot compile; the hybrid runs those on CPU and the vision tower on GPU).
     * Falls back to plain CPU when no usable GPU exists (handled by [create]).
     *
     * Using [FP32] precision even for FP16 models as tests show we'd lose some precision otherwise.
     */
    fun hybridOptions() =
      CompiledModel.Options(Accelerator.CPU, Accelerator.GPU).apply {
        gpuOptions =
          CompiledModel.GpuOptions(
            backend = CompiledModel.GpuOptions.Backend.OPENCL,
            precision = CompiledModel.GpuOptions.Precision.FP32,
          )
      }
  }
}

actual fun createLiteRtEngine(source: ModelSource, config: EngineConfig): LiteRtEngine {
  val asset = source as ModelSource.Asset
  // The common seam defaults GPU -> the accuracy-safe OpenCL FP32 config.
  val options =
    when (config.accelerator) {
      LiteRtAccelerator.GPU,
      LiteRtAccelerator.NPU -> AndroidLiteRtEngine.gpuFirstOptions()

      LiteRtAccelerator.CPU -> CompiledModel.Options(Accelerator.CPU)
    }
  return AndroidLiteRtEngine(assetContext, asset.name, options)
}

/**
 * App context for CompiledModel asset loading. Set by the LiteRT factories' constructors (they
 * receive a [Context] from Koin); Android-only — the iOS/JVM kmplitert seam loads models from file
 * paths instead.
 */
internal lateinit var assetContext: Context
  private set

internal fun initAssetContext(context: Context) {
  assetContext = context.applicationContext
}
