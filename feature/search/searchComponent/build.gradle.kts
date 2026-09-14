plugins {
  id("photorate.component")
  alias(libs.plugins.sqlDelight)
  alias(libs.plugins.skie)
}

kotlin {
  android { namespace = "isao.photorate.searchcomponent" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.feature.gallery.galleryComponent)
      implementation(projects.core)
      api(projects.feature.imageRecognition.imageRecognitionComponentApi)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}

sqldelight {
  databases.create("PhotoRateDb") {
    packageName.set("isao.photorate.search.db")
    dialect(libs.sqlDelight.dialect.get().toString())
  }
}
