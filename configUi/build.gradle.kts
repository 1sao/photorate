plugins { id("photorate.ui") }

kotlin {
  android { namespace = "isao.photorate.configui" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.coreUI)
      implementation(projects.configComponent)
    }
  }
}
