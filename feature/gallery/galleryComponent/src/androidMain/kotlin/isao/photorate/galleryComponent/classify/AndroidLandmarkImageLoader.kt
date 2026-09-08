package isao.photorate.galleryComponent.classify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.core.net.toUri
import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.core.raise.context.raise
import isao.photorate.imageRecognition.ResourceFailure
import isao.photorate.imageRecognition.classify.LandmarkCandidate
import isao.photorate.imageRecognition.classify.LandmarkImageLoader
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min
import org.koin.core.annotation.Factory

/**
 * Decodes a gallery URI to a [Bitmap] whose shortest edge is around 'maxDimension'. 'maxDimension'
 * is ignored if a no-scale fallback is used for lower Android versions.
 */
@Factory
class AndroidLandmarkImageLoader(private val context: Context) : LandmarkImageLoader {
  context(_: Raise<ResourceFailure>)
  override fun load(uri: String, minDimension: Int): LandmarkCandidate =
    catch(
      {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          loadWithScaling(uri, minDimension)
        } else {
          loadWithoutScaling(uri)
        }
      },
      catch = { it.raiseFailureOrThrow(uri) },
    )

  private fun loadWithScaling(uri: String, minDimension: Int): LandmarkCandidate {
    val contentResolver = context.contentResolver
    val source = ImageDecoder.createSource(contentResolver, uri.toUri())
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      val originalSize = min(info.size.width, info.size.height)
      val sampleSize = originalSize / minDimension
      decoder.apply {
        setTargetSampleSize(sampleSize.coerceAtLeast(1))
        allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    }
  }

  context(_: Raise<ResourceFailure>)
  private fun loadWithoutScaling(uri: String): LandmarkCandidate {
    val contentResolver = context.contentResolver
    val inputStream =
      contentResolver.openInputStream(uri.toUri()) ?: raise(ResourceFailure.NotFound(uri))
    val config = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    return inputStream.use { BitmapFactory.decodeStream(it, null, config) }
      ?: raise(ResourceFailure.DecodeFailed(uri))
  }
}

context(_: Raise<ResourceFailure>)
private fun Throwable.raiseFailureOrThrow(uri: String): Nothing =
  when (this) {
    is FileNotFoundException -> raise(ResourceFailure.NotFound(uri))
    is SecurityException -> raise(ResourceFailure.PermissionDenied(uri))
    is IOException -> raise(ResourceFailure.DecodeFailed(uri))
    else -> throw this
  }
