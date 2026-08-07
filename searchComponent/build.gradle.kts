@file:OptIn(KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.sqlDelight)
  alias(libs.plugins.skie)
  alias(libs.plugins.koin.compiler)
}

koinCompiler { compileSafety = true }

kotlin {
  jvmToolchain(11)
  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
  android {
    // Unique per-module namespace required by AGP
    // (AndroidManifest merger).
    namespace = "isao.photorate.searchcomponent"
    compileSdk = libs.versions.compileSdk.get().toInt()
    minSdk = libs.versions.minSdk.get().toInt()

    androidResources.enable = true
  }
  listOf(
      iosX64(),
      iosArm64(),
      iosSimulatorArm64(),
    )
    .forEach { it.binaries.framework { isStatic = true } }

  sourceSets {
    all {
      languageSettings.apply {
        optIn("kotlin.RequiresOptIn")
        optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
      }
    }

    commonMain.dependencies {
      implementation(projects.galleryComponent)
      implementation(projects.core)
      // The merged schema compiles
      // gallery's AND config's .sq
      // files, so
      // their Kotlin types must be
      // on this module's compile
      // classpath.
      implementation(projects.configComponent)
      implementation(projects.photosInference)
      implementation(libs.koin.core)
      implementation(libs.koin.annotations)
      implementation(libs.coroutines.core)
    }
  }
}

sqldelight {
  databases.create("PhotoRateDb") {
    packageName.set("isao.photorate.search.db")
    // Merges the gallery + config schemas into this
    // database. The search
    // DB therefore owns the full schema; shared/iosMain
    // uses its Schema
    // value to create the native driver with every table
    // present.
    dependency(project(":galleryComponent"))
    dialect("app.cash.sqldelight:sqlite-3-30-dialect:${libs.versions.sqlDelight.get()}")
  }
}
