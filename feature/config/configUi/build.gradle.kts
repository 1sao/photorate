plugins { id("photorate.ui") }

kotlin {
  android { namespace = "isao.photorate.configui" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.core)
      implementation(projects.core.ui)
      implementation(projects.feature.config.configComponent)
    }
  }
}
