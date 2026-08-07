@file:OptIn(KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.library)
  alias(libs.plugins.koin.compiler)
}

// Leaf module. Hosts the cross-cutting DB infrastructure — the sqldelight
// driver libraries (exposed via `api` so schema-owning feature modules inherit
// the runtime + drivers), adapters and coroutine helpers — plus the Kermit
// logger provider. No databases are generated here.
koinCompiler { compileSafety = true }

kotlin {
  jvmToolchain(11)

  android {
    // Unique per-module namespace required by AGP
    // (AndroidManifest merger).
    // Kotlin packages stay `isao.photorate.*`; this only
    // affects the Android
    // R/manifest package of this module.
    namespace = "isao.photorate.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    minSdk = libs.versions.minSdk.get().toInt()

    androidResources.enable = true
    // Run commonTest (pure logic: FloatArrayAdapter) on
    // the JVM host,
    // mirroring galleryComponent.
    withHostTestBuilder {}.configure {}
  }
  // Core is consumed by iOS-targeted modules (galleryComponent etc.), so it
  // must expose the same iOS targets.
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
      implementation(libs.koin.core)
      implementation(libs.koin.annotations)
      implementation(libs.coroutines.core)
      // `api` so feature modules
      // that own .sq schemas get the
      // sqldelight
      // runtime (Transacter,
      // EnumColumnAdapter,
      // asFlow/mapToList) without
      // re-declaring it.
      api(libs.sqlDelight.coroutinesExt)
      implementation(libs.touchlab.kermit)
    }
    commonTest.dependencies { implementation(libs.kotlin.test) }
    getByName("androidHostTest").dependencies {
      implementation(libs.kotlin.test)
      // JDBC SQLite driver so host
      // tests can exercise real
      // schemas.
      implementation(libs.sqlDelight.jvm)
    }
    androidMain.dependencies { api(libs.sqlDelight.android) }
    iosMain.dependencies {
      api(libs.sqlDelight.native)
      api(libs.touchlab.kermit.simple)
    }
  }
}
