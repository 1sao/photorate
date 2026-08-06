@file:OptIn(KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.skie)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.koin.compiler)
    id("org.jetbrains.compose") version "1.11.1"
}

version = "1.2"

kotlin {
    jvmToolchain(11)
    // https://kotlinlang.org/docs/multiplatform-expect-actual.html#expected-and-actual-classes
    // To suppress this warning about usage of expected and actual classes
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        namespace = "isao.photorate"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources.enable = true
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
        }

        lint {
            warningsAsErrors = true
            abortOnError = true
        }
    }
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries {
            executable {
                // TODO revert?
                // disableNativeCache(DisableCacheInKotlinVersion.`2_4_0``, "a")
            }
        }
        it.binaries.framework {
            isStatic = true // TODO revert?
            linkerOpts("-lsqlite3")
            export(libs.touchlab.kermit.simple)
        }
    }
    // iosSimulatorArm64 {
    //     binaries {
    //         executable {
    //             disableNativeCache()
    //         }
    //     }
    // }

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
            languageSettings.enableLanguageFeature("ContextParameters")
        }

        commonMain.dependencies {
            implementation(libs.koin.core)
            implementation(libs.koin.annotations)
            implementation(libs.koin.viewmodel)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.coroutines.core)
            implementation(libs.sqlDelight.coroutinesExt)
            implementation(libs.kotlinx.dateTime)
            implementation(libs.touchlab.skie.annotations)
            api(libs.touchlab.kermit)
            implementation(libs.arrow.core)
            implementation(libs.arrow.fx.coroutines)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(projects.photosComponent)
            implementation(projects.configComponent)
            implementation(projects.photosUI)
            implementation(projects.photosMediaPipe)
        }
        commonTest.dependencies {
            implementation(libs.bundles.shared.commonTest)
        }
        androidMain.dependencies {
            // The LiteRT provider (Android-only — it has no iOS targets) backs
            // the PlatformModule's landmarker + search factories.
            implementation(projects.photosLiteRT)
            implementation(compose.components.resources)
            implementation("androidx.compose.runtime:runtime:${libs.versions.compose.get()}")
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.sqlDelight.android)
            implementation(libs.android.worker)
            implementation(libs.koin.worker)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.bundles.shared.androidTest)
        }
        iosMain.dependencies {
            implementation(libs.sqlDelight.native)
            api(libs.touchlab.kermit.simple)
        }
    }
}
