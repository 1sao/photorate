plugins { id("photorate.ui") }

kotlin {
  android { namespace = "isao.photorate.searchui" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.searchComponent)
      implementation(projects.configComponent)
      implementation(projects.galleryComponent)
    }
  }
}
