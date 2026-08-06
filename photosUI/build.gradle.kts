plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.koin.compiler)
}

// Leaf module. GalleryViewModel's dependencies (use cases, repositories) all
// come from `photosComponent`, which this module depends on, so KOIN-D001
// resolves via that module's generated hints while compileSafety stays ON. The
// authoritative full-graph check runs at the root @KoinApplication in `shared`.
koinCompiler {
    compileSafety = true
}

kotlin {
    jvmToolchain(11)
    android {
        // Unique per-module namespace required by AGP (AndroidManifest merger).
        // Kotlin packages stay `isao.photorate.*`; this only affects the Android
        // R/manifest package of this module.
        namespace = "isao.photorate.photosui"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources.enable = true
    }
    // Shared's iOS targets consume photosUI (GalleryViewModel lives in commonMain),
    // so this module must expose the same iOS targets even though iOS UI stays in Swift.
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            isStatic = true
        }
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }

        commonMain.dependencies {
            implementation(projects.photosComponent)
            implementation(projects.photosInference)
            implementation(libs.koin.core)
            implementation(libs.koin.annotations)
            implementation(libs.koin.viewmodel)
            implementation(libs.coroutines.core)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
        }
        androidMain.dependencies {
            implementation(libs.bundles.app.ui)
            implementation("androidx.compose.ui:ui:${libs.versions.compose.get()}")
            implementation("androidx.compose.ui:ui-tooling-preview:${libs.versions.compose.get()}")
            implementation("androidx.compose.foundation:foundation:${libs.versions.compose.get()}")
            implementation("androidx.compose.animation:animation:${libs.versions.compose.get()}")
            // 1.5.0-alpha24 pulls foundation/ui 1.12.0-beta01 (already what the app
            // resolves at runtime via material3-adaptive-navigation-suite). It is
            // required for the Material 3 Expressive HorizontalFloatingToolbar.
            implementation("androidx.compose.material3:material3:1.5.0-alpha24")
            // Official window size class API (also used by the app module), for
            // the adaptive uncertain-card span (full row on phones, half row on
            // tablets).
            implementation("androidx.compose.material3:material3-window-size-class:1.4.0")
            implementation("androidx.compose.material:material-icons-core:1.6.0")
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.compose)
            implementation("com.google.accompanist:accompanist-permissions:0.37.3")
            implementation(libs.androidx.core)
            implementation("io.coil-kt.coil3:coil-compose:3.5.0")
            implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
        }
    }
}
