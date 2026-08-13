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
  ":core:ui",
  ":feature:home:homeUi",
  ":feature:gallery:galleryComponent",
  ":feature:gallery:galleryUi",
  ":feature:search:searchComponent",
  ":feature:search:searchUi",
  ":feature:config:configComponent",
  ":feature:config:configUi",
  ":feature:imageRecognition:imageRecognitionComponentApi",
  ":feature:imageRecognition:imageRecognitionComponentLiteRt",
  ":feature:imageRecognition:imageRecognitionComponentMediaPipe",
  ":feature:imageRecognition:imageRecognitionComponentOnnx",
)

rootProject.name = "PhotoRate"
