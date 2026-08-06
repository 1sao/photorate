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
        namespace = "isao.photorate.photoslitert"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources.enable = true
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }

        androidMain.dependencies {
            // LiteRT runtime + CompiledModel API (com.google.ai.edge.litert.*)
            // with the OpenCL/GL GPU accelerator. 2.1.6 is the version the
            // google-ai-edge/litert-samples image_segmentation sample pins.
            implementation("com.google.ai.edge.litert:litert:2.1.6")
        }
    }
}
