plugins {
  id("photorate.component")
  alias(libs.plugins.sqlDelight)
}

kotlin {
  android { namespace = "isao.photorate.config" }

  sourceSets { commonMain.dependencies { implementation(projects.core) } }
}

sqldelight {
  databases.create("PhotoRateDb") {
    packageName.set("isao.photorate.config.db")
    dialect(libs.sqlDelight.dialect.get().toString())
  }
}
