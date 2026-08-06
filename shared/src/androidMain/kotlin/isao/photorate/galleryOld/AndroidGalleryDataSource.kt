package isao.photorate.galleryOld

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.net.toUri
import isao.photorate.inference.classify.LandmarkCandidate
import kotlin.math.min
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidGalleryDataSource(private val context: Context) : GalleryDataSource {

    private val contentResolver: ContentResolver get() = context.contentResolver

    override suspend fun requestPermission(): Boolean {
        // Permission check only - actual prompt is in UI layer
        return true
    }

    override suspend fun fetchAllImageRefs(): List<GalleryImageRef> {
        val refs = mutableListOf<GalleryImageRef>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATA,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateAdded = cursor.getLong(dateColumn)
                val contentUri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString(),
                )
                refs.add(
                    GalleryImageRef(
                        id = id.toString(),
                        uriString = contentUri.toString(),
                        dateAdded = dateAdded,
                    ),
                )
            }
        }
        return refs
    }

    override fun observeNewImages(): Flow<GalleryImageRef> = callbackFlow {
        var lastKnownMaxId = getLastKnownMaxId()

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val newMaxId = getLastKnownMaxId()
                if (newMaxId > lastKnownMaxId) {
                    lastKnownMaxId = newMaxId
                    val id = uri?.lastPathSegment?.toLongOrNull() ?: return
                    val contentUri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString(),
                    )
                    trySend(
                        GalleryImageRef(
                            id = id.toString(),
                            uriString = contentUri.toString(),
                            dateAdded = System.currentTimeMillis() / 1000,
                        ),
                    )
                }
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    override suspend fun loadCandidate(ref: GalleryImageRef): LandmarkCandidate {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, ref.uriString.toUri())
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val originalSize = min(info.size.width, info.size.height)
                val sampleSize = originalSize / PREFERRED_MAX_IMAGE_SIZE
                decoder.setTargetSampleSize(sampleSize)
            }
        } else {
            val uri = ref.uriString.toUri()
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open URI: ${ref.uriString}")
            inputStream.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalArgumentException("Cannot decode image: ${ref.uriString}")
        }

        // The common contract's Android candidate IS the bitmap (MediaPipe
        // wraps it internally); legacy path, so no further conversion.
        return bitmap
    }

    private fun getLastKnownMaxId(): Long {
        var maxId = 0L
        val projection = arrayOf(MediaStore.Images.Media._ID)
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media._ID} DESC LIMIT 1",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                maxId = cursor.getLong(0)
            }
        }
        return maxId
    }
}

// https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker#models
private const val PREFERRED_MAX_IMAGE_SIZE = 224
