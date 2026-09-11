package isao.photorate.galleryComponent.populateGallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.core.raise.context.raise
import isao.photorate.gallery.db.GalleryImage
import isao.photorate.gallery.db.GalleryImageStatus
import isao.photorate.imageRecognition.ResourceFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Factory

@Factory
class AndroidSystemGalleryImageRepository(
  private val context: Context,
) : SystemGalleryImageRepository {
  private val sharedPrefs = context.getSharedPreferences("system-gallery", Context.MODE_PRIVATE)

  var lastMediaStoreVersion
    get() = sharedPrefs.getString(LAST_MEDIA_STORE_VERSION_KEY, null)
    set(value) = sharedPrefs.edit { putString(LAST_MEDIA_STORE_VERSION_KEY, value) }

  val currentMediaStoreVersion = MediaStore.getVersion(context)

  private fun lastCheckpoints(): Map<String, Long> =
    sharedPrefs.getString(CHECKPOINTS_KEY, null)?.let { stored ->
      // TODO A silent failure. Good enough for this case?
      runCatching { Json.decodeFromString<Map<String, Long>>(stored) }.getOrNull()
    } ?: emptyMap()

  fun resetGenerationCheckpoints() {
    sharedPrefs.edit { remove(CHECKPOINTS_KEY) }
  }

  context(_: Raise<ResourceFailure>)
  override suspend fun getImageDetails(uri: String): SystemImageDetails =
    withContext(Dispatchers.IO) {
      // SIZE/WIDTH/HEIGHT are only exposed by MediaStore on API 29+; the rest
      // exist on all supported API levels. getColumnIndex returns -1 for
      // missing columns, so each value degrades to null on old devices.
      val projection =
        arrayOf(
          MediaStore.Images.Media.DISPLAY_NAME,
          MediaStore.Images.Media.DATE_TAKEN,
          MediaStore.Images.Media.DATE_ADDED,
          MediaStore.Images.Media.DATE_MODIFIED,
          MediaStore.Images.Media.SIZE,
          MediaStore.Images.Media.WIDTH,
          MediaStore.Images.Media.HEIGHT,
          MediaStore.Images.Media.MIME_TYPE,
        )
      val cursor =
        catch(
          {
            context.contentResolver.query(
              uri.toUri(),
              projection,
              null,
              null,
              null,
            )
          },
        ) { e: Throwable ->
          when (e) {
            is SecurityException -> raise(ResourceFailure.PermissionDenied(uri))
            else -> throw e
          }
        }
      cursor?.use { cursor ->
        if (!cursor.moveToFirst()) raise(ResourceFailure.NotFound(uri))
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
          dateTakenEpochSeconds =
            longColumn(MediaStore.Images.Media.DATE_TAKEN)?.takeIf { it > 0 }?.div(1000),
          dateAddedEpochSeconds = longColumn(MediaStore.Images.Media.DATE_ADDED),
          dateModifiedEpochSeconds = longColumn(MediaStore.Images.Media.DATE_MODIFIED),
          sizeBytes = longColumn(MediaStore.Images.Media.SIZE),
          width = intColumn(MediaStore.Images.Media.WIDTH),
          height = intColumn(MediaStore.Images.Media.HEIGHT),
          mimeType = textColumn(MediaStore.Images.Media.MIME_TYPE),
        )
      } ?: raise(ResourceFailure.NotFound(uri))
    }

  override suspend fun getAllImagesAfterLastCheckpoint(): GalleryScanResult =
    withContext(Dispatchers.IO) {
      if (getMediaAccess() != MediaAccess.Full) {
        resetGenerationCheckpoints()
        return@withContext GalleryScanResult(
          images = scanCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
          checkpoint = Checkpoint(emptyMap()),
          isCompleteScan = true,
        )
      }

      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return@withContext GalleryScanResult(
          images = scanCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
          checkpoint = Checkpoint(emptyMap()),
          isCompleteScan = true,
        )
      }

      val checkpoints = mutableMapOf<String, Long>()
      val images = mutableListOf<GalleryImage>()
      for (volumeName in MediaStore.getExternalVolumeNames(context)) {
        // Capture the checkpoint before querying to avoid race conditions.
        val generation = MediaStore.getGeneration(context, volumeName)
        images +=
          scanCollection(
            MediaStore.Images.Media.getContentUri(volumeName),
            selection = "${MediaStore.MediaColumns.GENERATION_MODIFIED} >= ?",
            selectionArgs = arrayOf((lastCheckpoints()[volumeName] ?: 0L).toString()),
          )
        checkpoints[volumeName] = generation
      }
      return@withContext GalleryScanResult(
        images = images,
        checkpoint = Checkpoint(checkpoints),
        isCompleteScan = false,
      )
    }

  private fun getMediaAccess(): MediaAccess =
    when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
          PackageManager.PERMISSION_GRANTED -> MediaAccess.Full

      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
          PackageManager.PERMISSION_GRANTED -> MediaAccess.Full

      Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
          PackageManager.PERMISSION_GRANTED -> MediaAccess.Partial

      else -> MediaAccess.None
    }

  private enum class MediaAccess {
    Full,
    Partial,
    None,
  }

  override suspend fun saveCheckpoint(checkpoint: Checkpoint) {
    withContext(Dispatchers.IO) {
      if (checkpoint.generations.isEmpty()) return@withContext
      val old = lastCheckpoints()
      val merged = old.toMutableMap()
      checkpoint.generations.forEach { (volumeName, generation) ->
        if (generation > (old[volumeName] ?: 0L)) merged[volumeName] = generation
      }
      if (merged != old) {
        // TODO Is there a way to synchronize sharedPreference reads AND writes without introducing
        //  more singletons?
        sharedPrefs.edit { putString(CHECKPOINTS_KEY, Json.encodeToString(merged)) }
      }
    }
  }

  private fun scanCollection(
    collection: Uri,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
  ): List<GalleryImage> {
    val projection =
      arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DATE_MODIFIED,
      )

    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

    val images = mutableListOf<GalleryImage>()
    context.contentResolver
      .query(collection, projection, selection, selectionArgs, sortOrder)
      ?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

        while (cursor.moveToNext()) {
          val id = cursor.getLong(idColumn)
          val dateAdded = cursor.getLong(dateAddedColumn)
          val dateModified = cursor.getLong(dateModifiedColumn)

          val contentUri =
            Uri.withAppendedPath(
              MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
              id.toString(),
            )

          images.add(
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
    return images
  }

  private companion object {
    const val CHECKPOINTS_KEY = "lastGenerationCheckpoints"
    const val LAST_MEDIA_STORE_VERSION_KEY = "lastMediaStoreVersion"
  }
}
