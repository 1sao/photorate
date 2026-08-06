package isao.photorate.android.ui

import android.Manifest
import android.os.Build

val GALLERY_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.READ_MEDIA_IMAGES
} else {
    Manifest.permission.READ_EXTERNAL_STORAGE
}
