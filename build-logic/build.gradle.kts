import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { `kotlin-dsl` }

// AGP/KGP on this classpath require Java 17 variants; pin both Java and
// Kotlin targets so the compiler does not fall back from the host JDK (26)
// to an unaligned Kotlin default.
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

gradlePlugin {
  plugins {
    register("photorate.component") {
      id = "photorate.component"
      implementationClass = "photorate.buildlogic.PhotorateComponentPlugin"
    }
    register("photorate.ui") {
      id = "photorate.ui"
      implementationClass = "photorate.buildlogic.PhotorateUiPlugin"
    }
    register("photorate.app") {
      id = "photorate.app"
      implementationClass = "photorate.buildlogic.PhotorateAppPlugin"
    }
    register("photorate.shared") {
      id = "photorate.shared"
      implementationClass = "photorate.buildlogic.PhotorateSharedPlugin"
    }
  }
}

dependencies {
  implementation(libs.agp)
  implementation(libs.kotlin.gradle.plugin)
  implementation(libs.kotlin.compose.compiler.gradle.plugin)
  implementation(libs.koin.compiler.gradle.plugin)
  // Convention plugins apply these by id (plugins.apply) — they must be on
  // the build-logic classpath or runtime resolution fails / is ambiguous.
  implementation(libs.compose.multiplatform.gradle.plugin)
  implementation(libs.sqldelight.gradle.plugin)
  implementation(libs.skie.gradle.plugin)
}
