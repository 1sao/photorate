plugins { id("photorate.component") }

kotlin {
  android { namespace = "isao.photorate.photosmediapipe" }

  swiftPMDependencies {
    swiftPackage(
      url = url("https://github.com/jordond/SwiftTasksVision.git"),
      version = branch("main"),
      products = listOf(product("MediaPipeTasksVision")),
    )
  }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.feature.imageRecognition.imageRecognitionComponentApi)
    }
    androidMain.dependencies {
      implementation(libs.mediapipe.tasks.vision)
      implementation(libs.javax.inject)
    }
  }
}
