package isao.photorate.galleryComponent.populateGallery

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.edit
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.gallery.db.GalleryImageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

@Factory
class AndroidSystemGalleryImageRepository(private val context: Context) : SystemGalleryImageRepository {
    private val sharedPrefs = context.getSharedPreferences("system-gallery", Context.MODE_PRIVATE)
    var lastMediaStoreVersion
        get() = sharedPrefs.getString("lastMediaStoreVersion", null)
        set(value) = sharedPrefs.edit { putString("lastMediaStoreVersion", value) }

    val currentMediaStoreVersion = MediaStore.getVersion(context)

    override suspend fun getImageDetails(uri: String): SystemImageDetails? = withContext(Dispatchers.IO) {
        // SIZE/WIDTH/HEIGHT are only exposed by MediaStore on API 29+; the rest
        // exist on all supported API levels. getColumnIndex returns -1 for
        // missing columns, so each value degrades to null on old devices.
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
        )
        context.contentResolver.query(Uri.parse(uri), projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            fun textColumn(column: String): String? {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            }
            fun longColumn(column: String): Long? {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            }
            fun intColumn(column: String): Int? {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0 && !cursor.isNull(index)) cursor.getInt(index) else null
            }
            SystemImageDetails(
                uri = uri,
                displayName = textColumn(MediaStore.Images.Media.DISPLAY_NAME),
                // DATE_TAKEN is milliseconds since epoch; DATE_ADDED/DATE_MODIFIED are seconds.
                dateTakenEpochSeconds = longColumn(MediaStore.Images.Media.DATE_TAKEN)
                    ?.takeIf { it > 0 }
                    ?.div(1000),
                dateAddedEpochSeconds = longColumn(MediaStore.Images.Media.DATE_ADDED),
                dateModifiedEpochSeconds = longColumn(MediaStore.Images.Media.DATE_MODIFIED),
                sizeBytes = longColumn(MediaStore.Images.Media.SIZE),
                width = intColumn(MediaStore.Images.Media.WIDTH),
                height = intColumn(MediaStore.Images.Media.HEIGHT),
                mimeType = textColumn(MediaStore.Images.Media.MIME_TYPE),
            )
        } ?: null
    }

    override suspend fun getAllImages(): List<GalleryImage> = withContext(Dispatchers.IO) {
        val refs = mutableListOf<GalleryImage>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATA, // Real path to file
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.GENERATION_ADDED,
            MediaStore.Images.Media.GENERATION_MODIFIED,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        // TODO detect updates (https://developer.android.com/training/data-storage/shared/media#detect-updates-media-files)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            // sortOrder, TODO do we need it?
            sortOrder,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val generationAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.GENERATION_ADDED)
            val generationModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.GENERATION_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateAdded = cursor.getLong(dateAddedColumn)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val generationAdded = cursor.getLong(generationAddedColumn)
                val generationModified = cursor.getLong(generationModifiedColumn)

                val contentUri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString(),
                )

                refs.add(
                    GalleryImage(
                        uri = contentUri.toString(),
                        createdAt = dateAdded,
                        modifiedAt = dateModified,
                        status = GalleryImageStatus.PENDING,
                        scannedAt = null,
                        detectedInMs = null,
                    ),
                )
            }
        }
        return@withContext refs
    }
}
