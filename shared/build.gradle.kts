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
    // photosLiteRT (kmplitert) publishes no iosX64 klib, so the LiteRT
    // classpath + dylib linking are wired per arm64 target only. iosX64 keeps
    // the MediaPipe-only pipeline (KoinIOS defaults to it on every target).
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
            if (it.name == "iosArm64" || it.name == "iosSimulatorArm64") {
                // The user's LiteRT C++ dylibs (ml/litert_cpp) feed kmplitert's
                // `-lLiteRt` cinterop linkerOpt. Stage a per-target copy named
                // libLiteRt.dylib (the `-l` convention) so the framework link
                // resolves, then the framework consumer embeds it in the app.
                val variant = if (it.name == "iosArm64") "ios" else "simulator"
                val staged = layout.buildDirectory.dir("litert-dylibs/$variant")
                linkerOpts("-L${staged.get().asFile.absolutePath}", "-lLiteRt")
            }
        }
    }
    // Register the dylib staging copies once per target (the framework
    // closures above run per binary, so the task registration must live here).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val variant = if (target.name == "iosArm64") "ios" else "simulator"
        val staged = layout.buildDirectory.dir("litert-dylibs/$variant")
        tasks.register("stageLiteRtDylib_${target.name}", Copy::class.java) {
            from(rootProject.layout.projectDirectory.file("ml/litert_cpp/libLiteRt-$variant.dylib"))
            into(staged)
            rename { "libLiteRt.dylib" }
        }
        target.binaries.configureEach {
            tasks.matching { task ->
                task.name == "linkDebugFramework${target.name.replaceFirstChar(Char::uppercase)}" ||
                    task.name == "linkReleaseFramework${target.name.replaceFirstChar(Char::uppercase)}"
            }.configureEach {
                dependsOn("stageLiteRtDylib_${target.name}")
            }
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
            implementation(projects.photosInference)
            implementation(projects.configComponent)
            implementation(projects.photosUI)
            implementation(projects.photosMediaPipe)
        }
        commonTest.dependencies {
            implementation(libs.bundles.shared.commonTest)
        }
        androidMain.dependencies {
            // The LiteRT provider backs the PlatformModule's landmarker + search
            // factories on Android (custom CompiledModel code) and iOS
            // (kmplitert). It has no iosX64 target (kmplitert publishes none),
            // so it stays out of the iosX64-only classpath.
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
        // LiteRT on iOS: kmplitert-core publishes iosArm64 + iosSimulatorArm64
        // klibs only, so the classpath is added to those leaf source sets (the
        // framework linkerOpts above resolve the dylibs it links against).
        getByName("iosArm64Main").dependencies {
            implementation(projects.photosLiteRT)
        }
        getByName("iosSimulatorArm64Main").dependencies {
            implementation(projects.photosLiteRT)
        }
    }
}
