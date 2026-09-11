@file:OptIn(KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi

// TODO: There's a ton of duplicate config from shared module. See if its convention plugin can be
//  used here as well.

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
        export(project(":feature:gallery:galleryComponent"))
        export(project(":feature:config:configComponent"))
        export(project(":feature:search:searchComponent"))
        export(project(":feature:home:homeUi"))
        export(project(":feature:gallery:galleryUi"))
        export(project(":feature:search:searchUi"))
        export(project(":feature:config:configUi"))
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
      api(project(":feature:gallery:galleryComponent"))
      api(project(":feature:config:configComponent"))
      api(project(":feature:search:searchComponent"))
      api(project(":feature:home:homeUi"))
      api(project(":feature:gallery:galleryUi"))
      api(project(":feature:search:searchUi"))
      api(project(":feature:config:configUi"))
    }
    iosMain.dependencies { api(libs.touchlab.kermit.simple) }
  }
}
