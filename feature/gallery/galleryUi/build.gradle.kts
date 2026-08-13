plugins {
  id("photorate.ui")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  android { namespace = "isao.photorate.galleryui" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.core.ui)
      implementation(projects.feature.gallery.galleryComponent)
      implementation(projects.feature.config.configComponent)
      implementation(projects.feature.imageRecognition.imageRecognitionComponentApi)
    }
    androidMain.dependencies {
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.koin.compose.navigation3)
    }
  }
}
