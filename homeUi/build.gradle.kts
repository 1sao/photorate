plugins {
  id("photorate.ui")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  android { namespace = "isao.photorate.homeui" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.coreUI)
      implementation(projects.galleryUi)
      implementation(projects.searchUi)
      implementation(projects.configUi)
      implementation(projects.galleryComponent)
      implementation(projects.configComponent)
      implementation(projects.searchComponent)
    }
    androidMain.dependencies {
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    }
  }
}
