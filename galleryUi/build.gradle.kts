plugins { id("photorate.ui") }

kotlin {
  android { namespace = "isao.photorate.galleryui" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.coreUI)
      implementation(projects.galleryComponent)
      implementation(projects.configComponent)
      implementation(projects.photosInference)
    }
  }
}
