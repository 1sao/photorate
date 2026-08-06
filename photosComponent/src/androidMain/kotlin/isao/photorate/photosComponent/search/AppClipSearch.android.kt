package isao.photorate.photosComponent.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

/**
 * Android factory for the MobileCLIP search pipeline. Mirrors the
 * hand-landmarker architecture ([isao.photorate.photosComponent.classify.HandLandmarkerFactory]):
 * the factory is Android-specific, injected with a [Context], and registered in
 * Koin from the shared module's [isao.photorate.PlatformModule].
 */
class AndroidAppClipSearchFactory @Inject constructor(private val context: Context) : AppClipSearchFactory {

    override fun createFromOptions(options: AppClipSearchFactory.Options): AppClipSearch {
        val session: AndroidAppClipSearch
        measureTimeMillis {
            session = AndroidAppClipSearch(context, options)
        }.also {
            Log.d(TAG, "Initialized MobileCLIP search in $it ms")
        }
        return session
    }

    companion object {
        private const val TAG = "AndroidAppClipSearchFactory"

        // Confirmed-source Xenova exports (ml/original_models), alongside the
        // tokenizer at the asset root.
        const val TEXT_MODEL_ASSET = "text_model_fp16/text_model_fp16.onnx"
        const val VISION_MODEL_ASSET = "vision_model_fp16/vision_model_fp16.onnx"
        const val TOKENIZER_ASSET = "tokenizer.json"
    }
}

/**
 * Android MobileCLIP session: two ONNX Runtime sessions (text + vision
 * encoders) plus the CLIP tokenizer, all loading their assets from the app.
 * Models are copied from assets to filesDir on first use so ORT can mmap them.
 */
class AndroidAppClipSearch internal constructor(private val context: Context, private val options: AppClipSearchFactory.Options) :
    AppClipSearch {

    private val ortEnv = OrtEnvironment.getEnvironment()

    private val tokenizer: ClipTokenizer
    private val textSession: OrtSession
    private val visionSession: OrtSession

    init {
        // Load the tokenizer once; the JSON is small enough to read as a string.
        val tokenizerJson = context.assets.open(AndroidAppClipSearchFactory.TOKENIZER_ASSET)
            .bufferedReader().use { it.readText() }
        tokenizer = ClipTokenizer(tokenizerJson)

        // ORT needs a real file path; copy the (large) models to filesDir once.
        val textModelFile = copyAssetToFile(AndroidAppClipSearchFactory.TEXT_MODEL_ASSET)
        val visionModelFile = copyAssetToFile(AndroidAppClipSearchFactory.VISION_MODEL_ASSET)

        textSession = ortEnv.createSession(textModelFile.absolutePath)
        visionSession = ortEnv.createSession(visionModelFile.absolutePath)
    }

    override fun embedText(text: String): FloatArray {
        val inputIds = tokenizer.encode(text, options.contextLength) // [1, 77] padded
        val shape = longArrayOf(1, options.contextLength.toLong())
        val buffer = LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray())

        OnnxTensor.createTensor(ortEnv, buffer, shape).use { tensor ->
            textSession.run(mapOf("input_ids" to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                return output.floatBuffer.takeFloatArray()
            }
        }
    }

    override fun embedImage(imageBytes: ByteArray): FloatArray {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: error("Failed to decode image")
        val pixels = preprocess(bitmap) // [1, 3, 224, 224] float32
        val shape = longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())

        OnnxTensor.createTensor(ortEnv, pixels, shape).use { tensor ->
            visionSession.run(mapOf("pixel_values" to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                return output.floatBuffer.takeFloatArray()
            }
        }
    }

    /**
     * Replicates CLIPFeatureExtractor: resize shortest edge to 224, center
     * crop 224x224, rescale by 1/255 (no ImageNet normalization for this
     * model). Returns a CHW float32 buffer matching `pixel_values`.
     */
    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val scale = IMAGE_SIZE.toFloat() / min(w, h)
        val resizedW = (w * scale).roundToInt()
        val resizedH = (h * scale).roundToInt()
        val resized = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        val left = (resizedW - IMAGE_SIZE) / 2
        val top = (resizedH - IMAGE_SIZE) / 2
        val cropped = Bitmap.createBitmap(resized, left, top, IMAGE_SIZE, IMAGE_SIZE)

        val stride = IMAGE_SIZE * IMAGE_SIZE
        val pixels = IntArray(stride)
        cropped.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        val buffer = FloatBuffer.allocate(3 * stride)

        // Write in CHW order (all R, then all G, then all B) — the model expects
        // [3, 224, 224] channels-first, matching the Python script's transpose.
        val r = FloatArray(stride)
        val g = FloatArray(stride)
        val b = FloatArray(stride)
        for (i in 0 until stride) {
            val pixel = pixels[i]
            r[i] = (pixel shr 16 and 0xFF) / 255f
            g[i] = (pixel shr 8 and 0xFF) / 255f
            b[i] = (pixel and 0xFF) / 255f
        }
        buffer.put(r)
        buffer.put(g)
        buffer.put(b)
        buffer.rewind()
        return buffer
    }

    private fun copyAssetToFile(assetPath: String): File {
        val target = File(context.filesDir, assetPath)
        if (target.exists() && target.length() > 0) return target
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    override fun close() {
        textSession.close()
        visionSession.close()
    }

    private companion object {
        const val IMAGE_SIZE = 224 // matches the exported vision model's input
    }
}

private fun java.nio.FloatBuffer.takeFloatArray(): FloatArray {
    val array = FloatArray(remaining())
    get(array)
    return array
}
