package isao.photorate.photosComponent.classify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.core.net.toUri
import kotlin.math.min
import org.koin.core.annotation.Factory

/**
 * Android [LandmarkImageLoader]: decodes a gallery URI to a [Bitmap] whose
 * shortest edge is around [maxDimension]. The old MediaPipe and ONNX loaders
 * differed only in that constant (224 vs 640), so they were merged here.
 */
@Factory
class AndroidLandmarkImageLoader(private val context: Context) : LandmarkImageLoader {

    override fun load(uri: String, maxDimension: Int): LandmarkCandidate? = try {
        val contentResolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri.toUri())
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val originalSize = min(info.size.width, info.size.height)
                val sampleSize = originalSize / maxDimension
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
}
