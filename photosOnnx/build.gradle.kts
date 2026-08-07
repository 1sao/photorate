import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  jvmToolchain(11)
  // https://kotlinlang.org/docs/multiplatform-expect-actual.html#expected-and-actual-classes
  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
  android {
    // Unique per-module namespace required by AGP
    // (AndroidManifest merger).
    // Kotlin packages stay `isao.photorate.*`; this only
    // affects the Android
    // R/manifest package of this module.
    namespace = "isao.photorate.photosonnx"
    compileSdk = libs.versions.compileSdk.get().toInt()
    minSdk = libs.versions.minSdk.get().toInt()

    androidResources.enable = true
  }
  listOf(
      iosX64(),
      iosArm64(),
      iosSimulatorArm64(),
    )
    .forEach { it.binaries.framework { isStatic = true } }

  sourceSets {
    all {
      languageSettings.apply {
        optIn("kotlin.RequiresOptIn")
        optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
      }
    }

    commonMain.dependencies { implementation(projects.photosInference) }
    androidMain.dependencies {
      // The Gold-YOLO hand detector
      // + palm-rotation +
      // sparse-landmark models
      // run on-device via ONNX
      // Runtime (same artifact
      // MobileCLIP search uses).
      implementation(libs.onnxruntime.android)
      // `javax.inject.Inject` on the
      // factory mirrors
      // photosMediaPipe's
      // factory pattern; the
      // factories are constructed by
      // the platform's
      // LandmarkerFactoryProvider,
      // so this is only for test
      // construction.
      implementation("javax.inject:javax.inject:1")
    }
  }
}
