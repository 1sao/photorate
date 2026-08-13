plugins {
  id("photorate.component")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.sqlDelight)
  alias(libs.plugins.skie)
}

kotlin {
  android {
    namespace = "isao.photorate.gallerycomponent"
    withHostTestBuilder {}.configure {}
  }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.photosInference)
      implementation(projects.configComponent)
      implementation(projects.core)
      implementation(libs.touchlab.kermit)
      implementation(libs.arrow.core)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
    getByName("androidHostTest").dependencies {
      implementation(libs.kotlin.test)
      // JDBC SQLite driver so the host tests can exercise the real schema.
      implementation(libs.sqlDelight.jvm)
    }
    androidMain.dependencies { implementation(libs.androidx.core) }
    iosMain.dependencies { api(libs.touchlab.kermit.simple) }
  }
}

sqldelight {
  databases.create("PhotoRateDb") {
    packageName.set("isao.photorate.gallery.db")
    // Merges the config schema into this database (the gallery queries JOIN
    // the config table). The generated gallery PhotoRateDb therefore also
    // carries the config tables and requires configComponent's adapters.
    dependency(project(":configComponent"))
    // The schema uses UPSERT (INSERT ... ON CONFLICT ... DO UPDATE, SQLite
    // >= 3.24), which the default 3.18 dialect cannot parse. 3.30 is the
    // first published dialect >= 3.24 and matches the bundled SQLite of our
    // minSdk 30 (Android 11).
    dialect(libs.sqlDelight.dialect.get().toString())
  }
}
