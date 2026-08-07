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
        namespace = "isao.photorate.gallerycomponent"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources.enable = true
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
            implementation(projects.photosInference)
            implementation(projects.configComponent)
            implementation(projects.core)
            implementation(libs.koin.core)
            implementation(libs.koin.annotations)
            implementation(libs.coroutines.core)
            implementation(libs.touchlab.kermit)
            implementation(libs.arrow.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            // JDBC SQLite driver so the host tests can exercise the real schema.
            implementation(libs.sqlDelight.jvm)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core)
        }
        iosMain.dependencies {
            api(libs.touchlab.kermit.simple)
        }
    }
}

sqldelight {
    databases.create("PhotoRateDb") {
        packageName.set("isao.photorate.gallery.db")
        // Merges the config schema into this database (the gallery queries JOIN
        // the config table). The generated gallery PhotoRateDb therefore also
        // carries the config tables and requires configComponent's adapters.
        dependency(project(":configComponent"))
        // The schema uses UPSERT (INSERT ... ON CONFLICT ... DO UPDATE, SQLite
        // >= 3.24), which the default 3.18 dialect cannot parse. 3.30 is the
        // first published dialect >= 3.24 and matches the bundled SQLite of our
        // minSdk 30 (Android 11).
        dialect("app.cash.sqldelight:sqlite-3-30-dialect:${libs.versions.sqlDelight.get()}")
    }
}
