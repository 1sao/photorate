package photorate.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin for the `:app` Android application module. Applies the
 * android/compose/koin/serialization/sqldelight plugins, the SDK/defaultConfig/buildTypes/signing
 * boilerplate and the stable dependency set; the module keeps its assets/aapt config, sqldelight
 * merged-DB block and app-specific (firebase, worker, test) dependencies.
 */
class PhotorateAppPlugin : Plugin<Project> {
  override fun apply(project: Project) =
    with(project) {
      plugins.apply("com.android.application")
      plugins.apply("org.jetbrains.kotlin.plugin.compose")
      plugins.apply("io.insert-koin.compiler.plugin")
      plugins.apply("org.jetbrains.kotlin.plugin.serialization")

      extensions.configure<ApplicationExtension>("android") {
        namespace = "isao.photorate.android"
        compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()
        defaultConfig {
          applicationId = "isao.photorate"
          minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
          targetSdk = libs.findVersion("targetSdk").get().requiredVersion.toInt()
          versionCode = 1
          versionName = "1.0"
          testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        signingConfigs {
          getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
          }
        }

        buildTypes {
          // AGP's debug buildType already uses the "debug"
          // signingConfig by default.
          getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
              getDefaultProguardFile("proguard-android-optimize.txt"),
              "proguard-rules.pro",
            )
          }
        }
        compileOptions { isCoreLibraryDesugaringEnabled = true }
        lint {
          warningsAsErrors = false
          abortOnError = true
        }

        buildFeatures {
          compose = true
          buildConfig = true
        }
      }

      dependencies {
        add("implementation", project(":shared"))
        add("implementation", project(":core:ui"))
        add("implementation", project(":feature:home:homeUi"))
        // The merged main database now lives in :shared (shared/androidMain
        // AndroidDatabaseModule); these implementation deps stay because the
        // manifest references BootSystemGalleryVersionChecker (galleryComponent)
        // and the instrumented tests use the generated feature types.
        add("implementation", project(":feature:gallery:galleryComponent"))
        add("implementation", project(":feature:config:configComponent"))
        add("implementation", project(":feature:search:searchComponent"))
        libs.requireBundle("app-ui").forEach { add("implementation", it) }
        add("implementation", libs.requireLibrary("kotlinx-dateTime"))
        add("coreLibraryDesugaring", libs.requireLibrary("android-desugaring"))
        add("implementation", libs.requireLibrary("koin-core"))
        add("implementation", libs.requireLibrary("koin-android"))
        add("implementation", libs.requireLibrary("koin-annotations"))
        add("implementation", libs.requireLibrary("android-worker"))
        add("implementation", libs.requireLibrary("koin-worker"))
        add("implementation", libs.requireLibrary("sqlDelight-android"))
        add("implementation", libs.requireLibrary("compose-material3"))
        add("implementation", libs.requireLibrary("compose-material3-window-size"))
        add("implementation", libs.requireLibrary("compose-material3-adaptive-navigation-suite"))
        add("implementation", libs.requireLibrary("accompanist-permissions"))
        // Navigation3 (type-safe, stable) hosts the gallery / details / config
        // destinations; routes are @Serializable sealed types.
        add("implementation", libs.requireLibrary("navigation3-ui"))
        add("implementation", libs.requireLibrary("kotlinx-serialization-json"))
      }
    }
}
