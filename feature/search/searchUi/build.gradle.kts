plugins { id("photorate.ui") }

kotlin {
  android { namespace = "isao.photorate.searchui" }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.feature.search.searchComponent)
      implementation(projects.feature.config.configComponent)
      implementation(projects.feature.gallery.galleryComponent)
    }
  }
}
