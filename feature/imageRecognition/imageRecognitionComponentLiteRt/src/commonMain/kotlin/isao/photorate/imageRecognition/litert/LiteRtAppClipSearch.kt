package isao.photorate.imageRecognition.litert

import isao.photorate.imageRecognition.search.AppClipSearch
import isao.photorate.imageRecognition.search.AppClipSearchFactory
import isao.photorate.imageRecognition.search.ClipTokenizer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The shared LiteRT [AppClipSearch]: the single-graph MobileCLIP-S1 model (vision + text encoders
 * in one tflite, see ml/litert/converted/ CLIP_S1_README.md) plus the CLIP tokenizer.
 *
 * Out 0 is the TEXT embedding, out 1 the IMAGE embedding.
 *
 * in 0 [1, 3, 256, 256] float32 pixel values (NCHW, /255, no mean/std) in 1 [1, 77] int64 token ids
 * out 0 [1, 512] float32 text embedding out 1 [1, 512] float32 image embedding out 2 scalar float32
 * (aux, unused)
 */
class LiteRtAppClipSearch(
  private val engine: LiteRtEngine,
  private val options: AppClipSearchFactory.Options,
  tokenizerJson: String,
) : AppClipSearch {

  private val tokenizer = ClipTokenizer(tokenizerJson)

  // Cached blank inputs (the unused branch's input stays constant).
  private val blankPixels = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)
  private val blankTokenIds = LongArray(options.contextLength)

  // Vision preprocessing: 256x256 center crop, /255 only, NCHW.
  private val imageTensor =
    ImageTensor(
      IMAGE_SIZE,
      IMAGE_SIZE,
      layout = ImageTensor.Layout.NCHW,
      channelOrder = ImageTensor.ChannelOrder.RGB,
    )

  init {
    engine.writeFloatInput(0, blankPixels)
    engine.writeLongInput(1, blankTokenIds)
  }

  override fun embedText(text: String): FloatArray {
    val ids =
      tokenizer.encode(
        text,
        options.contextLength,
      ) // [77] padded
    engine.writeLongInput(
      1,
      LongArray(ids.size) { ids[it].toLong() },
    )
    engine.run()
    return engine.readOutput(0) // text embedding
  }

  override fun embedImage(imageBytes: ByteArray): FloatArray {
    val image = decodeEngineImage(imageBytes)
    val pixels = preprocess(image)
    engine.writeFloatInput(0, pixels)
    engine.run()
    return engine.readOutput(1) // image embedding
  }

  /**
   * Replicates the recipe's /255-only preprocessing: resize shortest edge to 256, center crop
   * 256x256 (no ImageNet mean/std — the recipe measured /255-only at 5/6 vs 0/6 for the normalized
   * variant).
   */
  private fun preprocess(image: EngineImage): FloatArray {
    val w = image.width
    val h = image.height
    val scale = IMAGE_SIZE.toFloat() / min(w, h)
    val resizedW = (w * scale).roundToInt()
    val resizedH = (h * scale).roundToInt()
    val resized = image.resized(resizedW, resizedH)
    val left = (resizedW - IMAGE_SIZE) / 2
    val top = (resizedH - IMAGE_SIZE) / 2
    val cropped =
      resized.cropped(
        left,
        top,
        IMAGE_SIZE,
        IMAGE_SIZE,
      ) ?: resized
    return imageTensor.load(cropped)
  }

  override fun close() {
    engine.close()
    imageTensor.release()
  }

  private companion object {
    const val IMAGE_SIZE = 256 // matches the exported
    // model's image input
  }
}
