plugins {
  id("photorate.ui")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  android { namespace = "isao.photorate.homeui" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.core)
      implementation(projects.core.ui)
      implementation(projects.feature.gallery.galleryUi)
      implementation(projects.feature.search.searchUi)
      implementation(projects.feature.config.configUi)
      implementation(projects.feature.gallery.galleryComponent)
      implementation(projects.feature.config.configComponent)
      implementation(projects.feature.search.searchComponent)
    }
    androidMain.dependencies {
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    }
  }
}
