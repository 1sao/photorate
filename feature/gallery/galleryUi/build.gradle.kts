plugins { id("photorate.ui") }

kotlin {
  android { namespace = "isao.photorate.galleryui" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.core.ui)
      implementation(projects.feature.gallery.galleryComponent)
      implementation(projects.feature.config.configComponent)
      implementation(projects.feature.imageRecognition.imageRecognitionComponentApi)
      implementation(libs.touchlab.kermit)
    }
  }
}
