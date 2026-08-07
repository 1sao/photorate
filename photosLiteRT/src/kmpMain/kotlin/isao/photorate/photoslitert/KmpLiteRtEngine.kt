package isao.photorate.photoslitert

import io.github.leitingzi.kmplitert.core.LiteRTAccelerator
import io.github.leitingzi.kmplitert.core.LiteRTCompiler
import io.github.leitingzi.kmplitert.core.TFBuffer
import kotlinx.coroutines.runBlocking

/**
 * kmplitert-backed [LiteRtEngine] for the iOS + JVM targets. The kmplitert API
 * is suspend-based (`LiteRTCompiler`), so this adapter bridges it to the
 * synchronous pipeline with [runBlocking] — inference itself is blocking C
 * calls either way.
 *
 * The input/output buffer lists are fetched once at init and reused for every
 * run, mirroring the Android CompiledModel engine's pre-allocated buffers.
 */
class KmpLiteRtEngine private constructor(
    private val compiler: LiteRTCompiler,
    private val inputs: List<TFBuffer>,
    private val outputs: List<TFBuffer>,
) : LiteRtEngine {

    override fun writeFloatInput(index: Int, data: FloatArray) {
        inputs[index].writeFloat(data)
    }

    override fun writeLongInput(index: Int, data: LongArray) {
        inputs[index].writeLong(data)
    }

    override fun run() {
        runBlocking { compiler.run(inputs, outputs) }
    }

    override fun readOutput(index: Int): FloatArray = runBlocking { outputs[index].readFloat() }

    override fun close() {
        runBlocking { compiler.close() }
    }

    companion object {
        suspend fun create(filePath: String, accelerator: LiteRTAccelerator): KmpLiteRtEngine {
            val compiler = LiteRTCompiler(filePath, accelerator)
            compiler.init()
            return KmpLiteRtEngine(
                compiler = compiler,
                inputs = compiler.getInputBuffers(),
                outputs = compiler.getOutputBuffers(),
            )
        }
    }
}

/** Maps the common accelerator enum onto kmplitert's. */
internal fun LiteRtAccelerator.toKmp(): io.github.leitingzi.kmplitert.core.LiteRTAccelerator = when (this) {
    LiteRtAccelerator.CPU -> io.github.leitingzi.kmplitert.core.LiteRTAccelerator.CPU
    LiteRtAccelerator.GPU -> io.github.leitingzi.kmplitert.core.LiteRTAccelerator.GPU
    LiteRtAccelerator.NPU -> io.github.leitingzi.kmplitert.core.LiteRTAccelerator.NPU
}

actual fun createLiteRtEngine(source: ModelSource, config: EngineConfig): LiteRtEngine {
    val file = source as ModelSource.File
    return runBlocking { KmpLiteRtEngine.create(file.path, config.accelerator.toKmp()) }
}
