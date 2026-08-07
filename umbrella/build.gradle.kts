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
    )
    .forEach {
      it.binaries.framework {
        baseName = "umbrella"
        isStatic = true
        linkerOpts("-lsqlite3")
        export(project(":shared"))
        export(project(":core"))
        export(project(":galleryComponent"))
        export(project(":configComponent"))
        export(project(":searchComponent"))
        export(project(":homeUi"))
        export(project(":galleryUi"))
        export(project(":searchUi"))
        export(project(":configUi"))
        export(libs.touchlab.kermit.simple)
        if (it.name == "iosArm64" || it.name == "iosSimulatorArm64") {
          // shared links kmplitert (photosLiteRT) on the arm64 iOS targets;
          // the umbrella framework (what Xcode links) must resolve the same
          // LiteRT dylib, so mirror shared's staged copy + linkerOpt.
          val variant = if (it.name == "iosArm64") "ios" else "simulator"
          val staged = layout.buildDirectory.dir("litert-dylibs/$variant")
          linkerOpts(
            "-L${staged.get().asFile.absolutePath}",
            "-lLiteRt",
          )
        }
      }
    }
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

  // The umbrella framework is what Xcode links, so it must carry the same
  // SwiftPM dependency (SwiftTasksVision / MediaPipeTasksVision) that
  // `shared`'s iOS code links against.
  swiftPMDependencies {
    swiftPackage(
      url = url("https://github.com/jordond/SwiftTasksVision.git"),
      version = branch("main"),
      products = listOf(product("MediaPipeTasksVision")),
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
      api(project(":core"))
      api(project(":galleryComponent"))
      api(project(":configComponent"))
      api(project(":searchComponent"))
      api(project(":homeUi"))
      api(project(":galleryUi"))
      api(project(":searchUi"))
      api(project(":configUi"))
    }
    iosMain.dependencies { api(libs.touchlab.kermit.simple) }
  }
}
