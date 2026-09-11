@file:OptIn(ExperimentalPermissionsApi::class)

package isao.photorate.coreUi.permission

import android.Manifest
import android.os.Build
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted
import isao.photorate.coreUi.permission.GalleryPermissions.FULL

val MultiplePermissionsState.hasFullGalleryAccess
  get() = permissions.any { it.permission == FULL && it.status.isGranted }

val MultiplePermissionsState.hasOnlyPartialGalleryAccess: Boolean
  get() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      return false
    }

    return permissions.any {
      it.permission == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && it.status.isGranted
    }
  }

val MultiplePermissionsState.hasAnyGalleryAccess
  get() = hasFullGalleryAccess || hasOnlyPartialGalleryAccess

object GalleryPermissions {
  val FULL =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Manifest.permission.READ_MEDIA_IMAGES
    } else {
      Manifest.permission.READ_EXTERNAL_STORAGE
    }

  val ALL_GALLERY_PERMISSIONS: List<String> = buildList {
    add(FULL)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }
  }
}
