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
      // The merged schema compiles gallery's AND config's .sq files, so
      // their Kotlin types must be on this module's compile classpath.
      implementation(projects.feature.config.configComponent) // TODO remove?
      api(projects.feature.imageRecognition.imageRecognitionComponentApi)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
  }
}

sqldelight {
  databases.create("PhotoRateDb") {
    packageName.set("isao.photorate.search.db")
    // Merges the gallery + config schemas into this database. The search
    // DB therefore owns the full schema; shared/iosMain uses its Schema
    // value to create the native driver with every table present.
    dependency(project(":feature:gallery:galleryComponent"))
    dialect(libs.sqlDelight.dialect.get().toString())
  }
}
