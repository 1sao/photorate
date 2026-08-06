package isao.photorate.photoslitert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.LiteRtException
import isao.photorate.photosComponent.search.AppClipSearch
import isao.photorate.photosComponent.search.AppClipSearchFactory
import isao.photorate.photosComponent.search.ClipTokenizer
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

/**
 * Android [AppClipSearchFactory] backed by LiteRT: the single-graph
 * MobileCLIP-S1 model (vision + text encoders in one tflite, see
 * ml/litert/converted/CLIP_S1_README.md).
 *
 * I/O contract (trust the behavior, not the tensor names — out 0 is the TEXT
 * embedding, out 1 the IMAGE embedding, swapped relative to graph order):
 *
 *   in 0  [1, 3, 256, 256] float32 pixel values (NCHW, /255, no mean/std)
 *   in 1  [1, 77]          int64   token ids
 *   out 0 [1, 512] float32 text embedding
 *   out 1 [1, 512] float32 image embedding
 *   out 2 scalar   float32 (aux, unused)
 *
 * The text path starts with CAST (int64->int32) + EMBEDDING_LOOKUP, which the
 * ClGl accelerator cannot compile (probed on-device), so the graph runs in a
 * CPU+GPU hybrid — text on CPU (once per query), vision on GPU — falling back
 * to plain CPU when no GPU is available. This is the config the recipe
 * recommends for the app's usage; see the CLIP_S1_README GPU section.
 */
class AndroidLiteRtAppClipSearchFactory @Inject constructor(private val context: Context) : AppClipSearchFactory {

    override fun createFromOptions(options: AppClipSearchFactory.Options): AppClipSearch {
        val session: AndroidLiteRtAppClipSearch
        measureTimeMillis {
            session = AndroidLiteRtAppClipSearch(context, options)
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

/**
 * LiteRT MobileCLIP session: one CompiledModel (vision + text) plus the CLIP
 * tokenizer, all loading their assets from the app. The two graph inputs are
 * pre-allocated with the runner and only the relevant one is written per call
 * (the other stays blank — the text embedding is constant under image change
 * and vice versa, verified in the recipe).
 */
class AndroidLiteRtAppClipSearch internal constructor(
    context: Context,
    private val options: AppClipSearchFactory.Options,
) : AppClipSearch {

    private val runner = createRunner(context)

    private val tokenizer: ClipTokenizer = context.assets.open(AndroidLiteRtAppClipSearchFactory.TOKENIZER_ASSET)
        .bufferedReader().use { it.readText() }
        .let { ClipTokenizer(it) }

    // Cached blank inputs (the unused branch's input stays constant).
    private val blankPixels = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)
    private val blankTokenIds = LongArray(options.contextLength)

    // Vision preprocessing: 256x256 center crop, /255 only, NCHW.
    private val imageTensor = ImageTensor(
        IMAGE_SIZE, IMAGE_SIZE,
        layout = ImageTensor.Layout.NCHW,
        channelOrder = ImageTensor.ChannelOrder.RGB,
    )

    init {
        runner.inputBuffers[0].writeFloat(blankPixels)
        runner.inputBuffers[1].writeLong(blankTokenIds)
    }

    private fun createRunner(context: Context): CompiledModelRunner {
        // Hybrid CPU+GPU: the text head's CAST/EMBEDDING_LOOKUP have no GPU
        // kernel, so strict GPU cannot compile; the hybrid runs those on CPU
        // and the rest (vision tower) on GPU. Fall back to plain CPU when the
        // device has no usable GPU.
        val hybrid = CompiledModel.Options(Accelerator.CPU, Accelerator.GPU).apply {
            gpuOptions = CompiledModel.GpuOptions(
                backend = CompiledModel.GpuOptions.Backend.OPENCL,
                precision = CompiledModel.GpuOptions.Precision.FP32,
            )
        }
        return try {
            CompiledModelRunner.fromAssets(context, AndroidLiteRtAppClipSearchFactory.MODEL_ASSET, hybrid).also {
                Log.d(TAG, "S1 compiled on CPU+GPU hybrid")
            }
        } catch (e: LiteRtException) {
            Log.w(TAG, "S1 GPU unavailable, using CPU", e)
            CompiledModelRunner.fromAssets(
                context, AndroidLiteRtAppClipSearchFactory.MODEL_ASSET,
                CompiledModel.Options(Accelerator.CPU),
            )
        }
    }

    override fun embedText(text: String): FloatArray {
        val ids = tokenizer.encode(text, options.contextLength) // [77] padded
        runner.inputBuffers[1].writeLong(LongArray(ids.size) { ids[it].toLong() })
        runner.run()
        return runner.readOutput(0) // text embedding
    }

    override fun embedImage(imageBytes: ByteArray): FloatArray {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: error("Failed to decode image")
        val pixels = preprocess(bitmap)
        runner.inputBuffers[0].writeFloat(pixels)
        runner.run()
        return runner.readOutput(1) // image embedding
    }

    /**
     * Replicates the recipe's /255-only preprocessing: resize shortest edge to
     * 256, center crop 256x256 (no ImageNet mean/std — the recipe measured
     * /255-only at 5/6 vs 0/6 for the normalized variant).
     */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val scale = IMAGE_SIZE.toFloat() / min(w, h)
        val resizedW = (w * scale).roundToInt()
        val resizedH = (h * scale).roundToInt()
        val resized = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        val left = (resizedW - IMAGE_SIZE) / 2
        val top = (resizedH - IMAGE_SIZE) / 2
        val cropped = Bitmap.createBitmap(resized, left, top, IMAGE_SIZE, IMAGE_SIZE)
        return imageTensor.load(cropped)
    }

    override fun close() {
        runner.close()
        imageTensor.release()
    }

    private companion object {
        private const val TAG = "LiteRtAppClipSearch"
        const val IMAGE_SIZE = 256 // matches the exported model's image input
    }
}
