package isao.photorate.photoslitert

/**
 * The only per-platform seam in this module. The whole vision pipeline
 * (detection, multi-rotation RTMPose search, gesture rating, MobileCLIP
 * embedding) is shared and drives engines through this interface; each target
 * supplies its own [createLiteRtEngine] implementation:
 *
 *  - Android keeps the custom CompiledModel code (GPU-first with CPU fallback).
 *  - iOS + JVM use kmplitert's simplified syntax (`LiteRTCompiler`).
 */
interface LiteRtEngine : AutoCloseable {
    /** Writes a float input tensor (pixels). */
    fun writeFloatInput(index: Int, data: FloatArray)

    /** Writes a long input tensor (CLIP token ids). */
    fun writeLongInput(index: Int, data: LongArray)

    /** Runs one inference pass on the pre-written inputs. */
    fun run()

    /** Reads a float output tensor (post-run). */
    fun readOutput(index: Int): FloatArray
}

/** Hardware accelerator requested from the engine (mapped per platform). */
enum class LiteRtAccelerator { CPU, GPU, NPU }

/** Compile-time options passed to [createLiteRtEngine]. */
data class EngineConfig(val accelerator: LiteRtAccelerator = LiteRtAccelerator.GPU)

/** Where a model file lives. Android ships them as assets; iOS/JVM on disk. */
sealed interface ModelSource {
    /** Android assets name (e.g. `rtmdet_hand_320_f32.tflite`). */
    data class Asset(val name: String) : ModelSource

    /** Absolute filesystem path (iOS bundle resource, JVM test models dir). */
    data class File(val path: String) : ModelSource
}

/**
 * Creates a compiled engine for [source] with the platform's native LiteRT
 * runtime. Android: `CompiledModel` via [ModelSource.Asset]. iOS/JVM:
 * kmplitert `LiteRTCompiler` via [ModelSource.File].
 */
expect fun createLiteRtEngine(source: ModelSource, config: EngineConfig): LiteRtEngine
