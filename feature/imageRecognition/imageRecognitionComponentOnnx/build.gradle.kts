plugins { id("photorate.component") }

kotlin {
  android { namespace = "isao.photorate.photosonnx" }
  sourceSets {
    commonMain.dependencies {
      implementation(projects.feature.imageRecognition.imageRecognitionComponentApi)
    }
    androidMain.dependencies {
      implementation(libs.onnxruntime.android)
      implementation(libs.javax.inject)
    }
  }
}
