plugins {
  id("photorate.ui")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  android { namespace = "isao.photorate.configui" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.core)
      implementation(projects.core.ui)
      implementation(projects.feature.config.configComponent)
    }
    androidMain.dependencies {
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.koin.compose.navigation3)
    }
  }
}
