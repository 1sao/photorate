@file:OptIn(KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi

plugins {
  id("photorate.shared")
  id("app.cash.sqldelight")
}

version = "1.2"

kotlin {
  android {
    namespace = "isao.photorate"

    withHostTestBuilder {}.configure { isIncludeAndroidResources = true }

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
    )
    .forEach {
      it.binaries {
        executable {
          // TODO revert?
          // disableNativeCache(DisableCacheInKotlinVersion.`2_4_0``, "a")
        }
      }
      it.binaries.framework {
        isStatic = false
        linkerOpts("-lsqlite3")
        export(libs.touchlab.kermit.simple)
        export(project(":core"))
        if (it.name == "iosArm64" || it.name == "iosSimulatorArm64") {
          // The user's LiteRT C++ dylibs (ml/litert_cpp) feed kmplitert's
          // `-lLiteRt` cinterop linkerOpt. Stage a per-target copy named
          // libLiteRt.dylib (the `-l` convention) so the framework link
          // resolves, then the framework consumer embeds it in the app.
          val variant = if (it.name == "iosArm64") "ios" else "simulator"
          val staged = layout.buildDirectory.dir("litert-dylibs/$variant")
          linkerOpts(
            "-L${staged.get().asFile.absolutePath}",
            "-lLiteRt",
          )
        }
      }
    }
  // Register the dylib staging copies once per target (the framework
  // closures above run per binary, so the task registration must live here).
  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    val variant = if (target.name == "iosArm64") "ios" else "simulator"
    val staged = layout.buildDirectory.dir("litert-dylibs/$variant")
    tasks.register(
      "stageLiteRtDylib_${target.name}",
      Copy::class.java,
    ) {
      from(rootProject.layout.projectDirectory.file("ml/litert_cpp/libLiteRt-$variant.dylib"))
      into(staged)
      rename { "libLiteRt.dylib" }
    }
    target.binaries.configureEach {
      tasks
        .matching { task ->
          task.name == "linkDebugFramework${target.name.replaceFirstChar(Char::uppercase)}" ||
            task.name == "linkReleaseFramework${target.name.replaceFirstChar(Char::uppercase)}"
        }
        .configureEach { dependsOn("stageLiteRtDylib_${target.name}") }
    }
  }
  // iosSimulatorArm64 {
  //     binaries {
  //         executable {
  //             disableNativeCache()
  //         }
  //     }
  // }

  // Can be used to add MediaPipeTasksVision or other swiftPM dependencies
  //  swiftPMDependencies {
  //    swiftPackage(
  //      url = url("https://github.com/jordond/SwiftTasksVision.git"),
  //      version = branch("main"),
  //      products = listOf(product("MediaPipeTasksVision")),
  //    )
  //  }
  sourceSets {
    /** Image recognition backend: 'litert' or 'onnx' (gradle.properties). */
    val backend = providers.gradleProperty("photorate.backend").getOrElse("litert")
    check(backend in setOf("litert", "onnx")) {
      "Unknown photorate.backend '$backend' (expected 'litert' or 'onnx')"
    }
    val backendSrcDir = file("src/backend${backend.replaceFirstChar(Char::uppercase)}Main")
    getByName("androidMain").kotlin.srcDir(backendSrcDir)
    androidMain.dependencies {
      when (backend) {
        "litert" ->
          implementation(projects.feature.imageRecognition.imageRecognitionComponentLiteRt)

        "onnx" -> implementation(projects.feature.imageRecognition.imageRecognitionComponentOnnx)
      }
      implementation(projects.feature.tracking.trackingComponentFirebase)
      implementation(compose.components.resources)
      implementation(libs.compose.runtime)
      implementation(libs.androidx.lifecycle.viewmodel)
      implementation(libs.sqlDelight.android)
      // initKoinAndroid's androidContext() lives in koin-android
      // (workManagerFactory is already here via koin-worker).
      implementation(libs.koin.android)
    }
    iosMain.dependencies {
      implementation(libs.sqlDelight.native)
      api(libs.touchlab.kermit.simple)
      api(project(":core"))
    }
    // LiteRT on iOS: kmplitert-core publishes iosArm64 +
    // iosSimulatorArm64
    // klibs only, so the classpath is added to those leaf
    // source sets (the
    // framework linkerOpts above resolve the dylibs it
    // links against).
    getByName("iosArm64Main").dependencies {
      implementation(projects.feature.imageRecognition.imageRecognitionComponentLiteRt)
    }
    getByName("iosSimulatorArm64Main").dependencies {
      implementation(projects.feature.imageRecognition.imageRecognitionComponentLiteRt)
    }
  }
}

// The main database: owns the FULL merged schema (config + gallery + search
// tables) purely so `PhotoRateDb.Schema` can create the SqlDriver with every
// table present (see the sqldelight multi-module Slack thread). Feature
// modules construct their own generated DB classes over that single driver.
sqldelight {
  databases.create("PhotoRateDb") {
    packageName.set("isao.photorate.app.db")
    dependency(project(":feature:config:configComponent"))
    dependency(project(":feature:gallery:galleryComponent"))
    dependency(project(":feature:search:searchComponent"))
    dialect(libs.sqlDelight.dialect.get().toString())
  }
}
