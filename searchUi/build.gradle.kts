plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.koin.compiler)
}

koinCompiler {
    compileSafety = true
}

kotlin {
    jvmToolchain(11)
    android {
        // Unique per-module namespace required by AGP (AndroidManifest merger).
        namespace = "isao.photorate.searchui"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources.enable = true
    }
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
            implementation(projects.searchComponent)
            implementation(projects.configComponent)
            implementation(projects.galleryComponent)
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
            implementation("androidx.compose.material3:material3:1.5.0-alpha24")
            implementation("androidx.compose.material:material-icons-core:1.6.0")
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.compose)
            implementation(libs.androidx.core)
        }
    }
}
