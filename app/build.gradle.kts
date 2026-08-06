plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.koin.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "isao.photorate.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "isao.photorate"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Override AGP's default debug keystore with a project-local one so AGP never
        // touches ~/.android/debug.keystore, which macOS TCC/sandbox restrictions
        // block ("Operation not permitted").
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // AGP's debug buildType already uses the "debug" signingConfig by default.
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    kotlin {
        jvmToolchain(11)
    }

    // Hand-landmark (RTMDet + RTMPose) and MobileCLIP models + tokenizer live
    // in the repo's ml/original_models folder (confirmed-source exports).
    // Expose them as app assets so the on-device pipelines can load them like
    // any other asset.
    sourceSets {
        getByName("main") {
            // TEMPORARY (old-device LiteRT test): ml/original_models (514 MB of
            // MobileCLIP + RTM onnx sources) excluded from assets — the LiteRT
            // verification harness only reads ml/litert/converted. Restore by
            // uncommenting the line below.
            // assets.directories.add(rootProject.layout.projectDirectory.dir("ml/original_models").asFile.path)
            // LiteRT-converted RTM models (see ml/litert/ recipe) ship as assets
            // for the CompiledModel on-device verification harness.
            assets.directories.add(rootProject.layout.projectDirectory.dir("ml/litert/converted").asFile.path)
        }
        // Instrumented hand-landmarker dataset tests read the sample images
        // straight from plans/samples (score dirs 5, 4, 3, 2, 1, no_score),
        // keeping a single source of truth for the dataset.
        getByName("androidTest") {
            // TEMPORARY (old-device LiteRT test): plans/samples (107 MB of
            // dataset photos) excluded — only used by the dataset tests, not
            // by LiteRtOnDeviceVerificationTest. Restore by uncommenting.
            // assets.directories.add(rootProject.layout.projectDirectory.dir("plans/samples").asFile.path)
            // Host-generated reference dumps + GPU op probes for the LiteRT
            // on-device verification.
            assets.directories.add(rootProject.layout.projectDirectory.dir("ml/litert/refs").asFile.path)
            assets.directories.add(rootProject.layout.projectDirectory.dir("ml/litert/probes").asFile.path)
        }
    }

    androidResources {
        // Keep tflite assets uncompressed so CompiledModel can mmap them.
        noCompress += "tflite"
    }

    androidResources {
        // ml/original_models carries the SDK descriptor jsons + verification
        // renders next to each model; ship only the .onnx binaries + the
        // tokenizer. ignoreAssetsPattern is the aapt2 mechanism that filters
        // files out of the packaged assets (colon-separated patterns; files
        // matching are not packaged). NOTE: it REPLACES (does not augment)
        // aapt's default ignore list — fine here because the
        // ml/original_models dir is controlled, with no dotfiles/vcs junk.
        // The verification renders are filtered by EXACT name, not *.jpg — a
        // *.jpg glob would also strip the plans/samples jpgs from the
        // androidTest APK (the dataset tests read those as assets). The
        // original baked end2end.onnx stays on disk as canonical source but
        // is not packaged (only the *_f32clean GPU-ready variants ship).
        ignoreAssetsPattern = "deploy.json:detail.json:pipeline.json:" +
            "output_onnxruntime.jpg:output_pytorch.jpg:end2end.onnx:" +
            "*.jpeg:*.png:*.md:.DS_Store"
    }
}

composeCompiler {
    stabilityConfigurationFiles = listOf(
        rootProject.layout.projectDirectory.file("app/compose_stability.conf"),
    )
}

dependencies {
    implementation(projects.shared) // TODO duplicate?
    implementation(projects.photosUI)
    implementation(projects.photosComponent)
    implementation(projects.photosOnnx)
    implementation(projects.photosMediaPipe)
    // androidTest-only: the NNAPI benchmark reads the `nnapiFlags`
    // instrumentation argument and needs the NNAPIFlags type on the test
    // compile classpath (photosOnnx keeps its ORT dependency `implementation`,
    // so it doesn't leak here). Production code never names ORT types.
    androidTestImplementation(libs.onnxruntime.android)
    // LiteRT on-device verification: the test names CompiledModel/TensorBuffer
    // types (photosLiteRT keeps its litert dependency `implementation`, so it
    // doesn't leak here). Production code never names LiteRT types.
    androidTestImplementation(projects.photosLiteRT)
    androidTestImplementation("com.google.ai.edge.litert:litert:2.1.6")
    implementation(libs.bundles.app.ui)
    implementation(libs.kotlinx.dateTime)
    coreLibraryDesugaring(libs.android.desugaring)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.annotations)
    implementation(libs.android.worker)
    implementation(libs.koin.worker)
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material3:material3-window-size-class:1.4.0")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-alpha24")
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")
    // Navigation3 (type-safe, stable) hosts the gallery / details / config
    // destinations; routes are @Serializable sealed types.
    implementation(libs.navigation3.ui)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")

    // junit-ktx brings androidx.test.ext:junit + core transitively.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.runner)
    // Raw TFLite interpreter for running the extracted hand landmark model
    // directly on crops (bypassing the palm-detector stage) in detection-gap
    // experiments. Test-APK-only; the app itself uses MediaPipe Tasks.
    // 2.17.0 ships 16KB-page-aligned native libs (2.16.x fails to dlopen on
    // modern arm64 emulators with 16KB pages); it is also the newest version
    // published on Maven Central.
    androidTestImplementation("org.tensorflow:tensorflow-lite:2.17.0")
    // Classic TFLite GPU delegate (GLES 3.1 compute) — feasibility probe for
    // the on-device GPU verification, since the litert CompiledModel GPU path
    // fails on OpenCL-less devices (see LiteRtOnDeviceVerificationTest).
    // The Delegate/DelegateFactory/RuntimeFlavor classes live in
    // tensorflow-lite-api; the -gpu-api carries the GpuDelegateFactory; the
    // -gpu AAR carries only the native delegate implementation.
    androidTestImplementation("org.tensorflow:tensorflow-lite-api:2.17.0")
    androidTestImplementation("org.tensorflow:tensorflow-lite-gpu-api:2.17.0")
    androidTestImplementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
}
