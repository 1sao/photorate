plugins { id("photorate.component") }

kotlin {
  android {
    namespace = "isao.photorate.core"
    withHostTestBuilder {}.configure {}
  }

  sourceSets {
    commonMain.dependencies {
      // `api` so feature modules that own .sq schemas get the sqldelight runtime (Transacter,
      // EnumColumnAdapter, asFlow/mapToList) without re-declaring it.
      api(libs.sqlDelight.coroutinesExt)
      implementation(libs.touchlab.kermit)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
    getByName("androidHostTest").dependencies {
      implementation(libs.kotlin.test)
      // JDBC SQLite driver so host tests can exercise real schemas.
      implementation(libs.sqlDelight.jvm)
    }
    androidMain.dependencies { api(libs.sqlDelight.android) }
    iosMain.dependencies {
      api(libs.sqlDelight.native)
      api(libs.touchlab.kermit.simple)
    }
  }
}
