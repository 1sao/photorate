import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(11)
    // https://kotlinlang.org/docs/multiplatform-expect-actual.html#expected-and-actual-classes
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        // Unique per-module namespace required by AGP (AndroidManifest merger).
        // Kotlin packages stay `isao.photorate.*`; this only affects the Android
        // R/manifest package of this module.
        namespace = "isao.photorate.photosmediapipe"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources.enable = true
    }
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            isStatic = true
        }
    }

    // The iOS actuals (ImageRecognizer.ios.kt) link against
    // MediaPipeTasksVision via SwiftTasksVision, the same SwiftPM setup
    // photosComponent carried before the module split.
    swiftPMDependencies {
        swiftPackage(
            url = url("https://github.com/jordond/SwiftTasksVision.git"),
            version = branch("main"),
            products = listOf(
                product("MediaPipeTasksVision"),
            ),
        )
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }

        commonMain.dependencies {
            implementation(projects.photosInference)
        }
        androidMain.dependencies {
            implementation("com.google.mediapipe:tasks-vision:0.10.35")
            // `javax.inject.Inject` on the factory matches the ONNX module's
            // factory pattern (used by the app's androidTest dataset tests).
            implementation("javax.inject:javax.inject:1")
        }
    }
}
