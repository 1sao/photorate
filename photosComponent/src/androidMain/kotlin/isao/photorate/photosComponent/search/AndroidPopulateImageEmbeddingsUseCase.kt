package isao.photorate.photosComponent.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import isao.photorate.galleryRepository.GalleryImageRepository
import isao.photorate.galleryRepository.ImageEmbeddingRepository
import isao.photorate.inference.search.AppClipSearch
import isao.photorate.inference.search.AppClipSearchFactory
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Android [PopulateImageEmbeddingsUseCase] backed by the MobileCLIP vision
 * encoder ([AppClipSearch]).
 *
 * For every hand image missing an embedding it decodes a scaled-down version of
 * the photo (same sample-size strategy as the ONNX image loader) and embeds it.
 * The vision model only ever sees a 224x224 center crop, so a full-resolution
 * decode is unnecessary: a scaled-down decode embeds almost identically
 * (verified on-device by MobileClipSearchDatasetTest and in Python on the same
 * models — cosine similarity 0.9998 between full and scaled embeddings).
 */
@Factory
class AndroidPopulateImageEmbeddingsUseCase(
    private val context: Context,
    private val galleryImageRepository: GalleryImageRepository,
    private val imageEmbeddingRepository: ImageEmbeddingRepository,
    // External binding from the root PlatformModule (see AndroidSearchImagesUseCase).
    @Provided private val searchFactory: AppClipSearchFactory,
    private val log: Logger,
) : PopulateImageEmbeddingsUseCase {

    override suspend operator fun invoke() = withContext(Dispatchers.IO) {
        val missing = galleryImageRepository.getImagesMissingEmbeddings().first()
        if (missing.isEmpty()) return@withContext
        searchFactory.createFromOptions(AppClipSearchFactory.Options()).use { clip ->
            measureTime {
                log.i { "Embedding ${missing.size} hand images (MobileCLIP)..." }
                missing.forEach { image ->
                    val bitmap = decodeScaled(image.uri)
                    if (bitmap == null) {
                        log.e { "Failed to decode image for embedding: ${image.uri}" }
                        return@forEach
                    }
                    // Round-trip through JPEG because the commonMain interface
                    // takes bytes, not a Bitmap; at 640px the extra decode is
                    // negligible next to the model inference.
                    val embedding = runCatching { clip.embedImage(bitmap.toJpegBytes()) }
                        .getOrElse { error ->
                            log.e { "Failed to embed ${image.uri}. Reason: $error" }
                            return@forEach
                        }
                    imageEmbeddingRepository.upsert(image.uri, embedding)
                }
            }.also {
                log.i { "Image embeddings computed in ${it.inWholeMilliseconds} ms" }
            }
        }
        Unit
    }

    /** Decodes a gallery URI at a moderate size; null on any decode failure. */
    private fun decodeScaled(uri: String): Bitmap? = try {
        val contentResolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri.toUri())
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val originalSize = min(info.size.width, info.size.height)
                val sampleSize = originalSize / DECODE_MIN_DIM
                decoder.apply {
                    setTargetSampleSize(sampleSize.coerceAtLeast(1))
                    allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
        } else {
            val inputStream = contentResolver.openInputStream(uri.toUri())
                ?: return null
            val config = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            inputStream.use { BitmapFactory.decodeStream(it, null, config) }
        }
    } catch (_: Exception) {
        null
    }

    private fun Bitmap.toJpegBytes(): ByteArray = ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        out.toByteArray()
    }

    private companion object {
        // Same decode size as the ONNX pipeline's image loader: large enough
        // that the CLIP 224px resize stays a downscale, small enough to keep
        // embedding fast.
        const val DECODE_MIN_DIM = 640
        const val JPEG_QUALITY = 95
    }
}
