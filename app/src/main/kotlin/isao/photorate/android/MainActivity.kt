package isao.photorate.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import isao.photorate.coreUi.theme.PhotoRateTheme
import isao.photorate.homeUi.PhotoRateNavHost

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PhotoRateTheme { PhotoRateNavHost() } }
  }
}
