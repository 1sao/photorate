plugins {
  id("photorate.component")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  android {
    namespace = "isao.photorate.photosinference"
    withHostTestBuilder {}.configure {}
  }
  sourceSets {
    commonMain.dependencies {
      api(projects.core)
      implementation(libs.kotlinx.serialization.json)
    }
    androidMain.dependencies { implementation(libs.androidx.core) }
    commonTest.dependencies { implementation(libs.kotlin.test) }
    getByName("androidHostTest").dependencies { implementation(libs.kotlin.test) }
  }
}
