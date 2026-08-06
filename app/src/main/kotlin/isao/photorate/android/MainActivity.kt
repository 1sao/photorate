package isao.photorate.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import isao.photorate.android.ui.theme.PhotoRateTheme
import isao.photorate.configUi.ConfigViewModel
import isao.photorate.galleryUi.GalleryViewModel
import isao.photorate.galleryUi.ImageDetailsViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.component.KoinComponent

class MainActivity :
    ComponentActivity(),
    KoinComponent {

    private val galleryViewModel: GalleryViewModel by viewModel()
    private val imageDetailsViewModel: ImageDetailsViewModel by viewModel()
    private val configViewModel: ConfigViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotoRateTheme {
                PhotoRateNavHost(
                    galleryViewModel = galleryViewModel,
                    imageDetailsViewModel = imageDetailsViewModel,
                    configViewModel = configViewModel,
                    onOpenAppSettings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null),
                            ),
                        )
                    },
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                )
            }
        }
    }
}
