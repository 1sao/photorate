import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
        namespace = "isao.photorate.photosinference"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources.enable = true
        // Run commonTest (pure logic: ClipTokenizer) on the JVM host, mirroring
        // galleryComponent.
        withHostTestBuilder {}.configure {}
    }
    // JVM target so the LiteRT provider (photosLiteRT) can run real inference
    // on the host (kmplitert-core-jvm bundles the darwin dylib), and the same
    // iOS targets the iOS-targeted provider modules expose.
    jvm()
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            isStatic = true
        }
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }

        commonMain.dependencies {
            // LandmarkedImage.Point is @Serializable and ClipTokenizer parses the
            // tokenizer.json with kotlinx-serialization-json.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
