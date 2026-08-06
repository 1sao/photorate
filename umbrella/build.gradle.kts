@file:OptIn(KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.skie)
}

kotlin {
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "umbrella"
            isStatic = true
            linkerOpts("-lsqlite3")
            export(project(":shared"))
            export(project(":photosComponent"))
            export(project(":configComponent"))
            export(project(":photosUI"))
            export(libs.touchlab.kermit.simple)
        }
    }

    // The umbrella framework is what Xcode links, so it must carry the same
    // SwiftPM dependency (SwiftTasksVision / MediaPipeTasksVision) that
    // `shared`'s iOS code links against.
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
            api(project(":shared"))
            api(project(":photosComponent"))
            api(project(":configComponent"))
            api(project(":photosUI"))
        }
        iosMain.dependencies {
            api(libs.touchlab.kermit.simple)
        }
    }
}
