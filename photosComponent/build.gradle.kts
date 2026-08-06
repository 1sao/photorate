@file:OptIn(KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqlDelight)
    alias(libs.plugins.skie)
    alias(libs.plugins.koin.compiler)
}

// Leaf module. All bindings its components need (Logger, SqlDriver, PhotoRateDb)
// are defined in this module itself (see `di` package), so KOIN-D001 resolves
// locally while compileSafety stays ON. The authoritative full-graph check runs
// at the root @KoinApplication in `shared`.
koinCompiler {
    compileSafety = true
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
        namespace = "isao.photorate.photoscomponent"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources.enable = true
        // Run commonTest (pure logic: ClipTokenizer, cosineSimilarity) on the
        // JVM host via `:photosComponent:testAndroidHostTest`, mirroring shared.
        withHostTestBuilder {}.configure {}
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

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
            languageSettings.enableLanguageFeature("ContextParameters")
        }

        commonMain.dependencies {
            // `api` because this module's public Koin metadata (`PhotosComponentModule`
            // includes `CoreModule`) forces consumers to resolve core's module
            // classes at compile time — `implementation` would hide them from
            // consumers' compile classpath and break KOIN-D001 resolution.
            api(projects.core)
            implementation(libs.koin.core)
            implementation(libs.koin.annotations)
            implementation(libs.coroutines.core)
            implementation(libs.sqlDelight.coroutinesExt)
            implementation(libs.touchlab.kermit)
            implementation(libs.arrow.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            // JDBC SQLite driver so the host tests can exercise the real schema
            // (e.g. the upsert regression tests in db/).
            implementation(libs.sqlDelight.jvm)
        }
        androidMain.dependencies {
            implementation(libs.sqlDelight.android)
            implementation(libs.androidx.core)
            // No onnxruntime here: the ONNX hand-landmark and MobileCLIP search
            // implementations live in the photosOnnx provider module (unplugged
            // from the app for now); the app's inference runs on photosLiteRT.
        }
        iosMain.dependencies {
            implementation(libs.sqlDelight.native)
            api(libs.touchlab.kermit.simple)
        }
    }
}

sqldelight {
    databases.create("PhotoRateDb") {
        packageName.set("isao.photorate.db")
        // The schema uses UPSERT (INSERT ... ON CONFLICT ... DO UPDATE, SQLite
        // >= 3.24), which the default 3.18 dialect cannot parse. 3.30 is the
        // first published dialect >= 3.24 and matches the bundled SQLite of our
        // minSdk 30 (Android 11).
        dialect("app.cash.sqldelight:sqlite-3-30-dialect:${libs.versions.sqlDelight.get()}")
    }
}
