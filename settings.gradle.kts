enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0") }

// Convention plugins (photorate.component / photorate.ui).
includeBuild("build-logic")

include(
  ":app",
  ":shared",
  ":umbrella",
  ":core",
  ":coreUI",
  ":photosInference",
  ":galleryComponent",
  ":configComponent",
  ":searchComponent",
  ":homeUi",
  ":galleryUi",
  ":searchUi",
  ":configUi",
  ":photosOnnx",
  ":photosMediaPipe",
  ":photosLiteRT",
)

rootProject.name = "PhotoRate"
