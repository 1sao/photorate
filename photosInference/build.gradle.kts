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
    commonMain.dependencies { implementation(libs.kotlinx.serialization.json) }
    commonTest.dependencies { implementation(libs.kotlin.test) }
    getByName("androidHostTest").dependencies { implementation(libs.kotlin.test) }
  }
}
