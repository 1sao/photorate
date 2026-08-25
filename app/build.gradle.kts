plugins {
  id("photorate.app")
  id("com.google.gms.google-services")
  id("com.google.firebase.crashlytics")
}

android {
  // AGP 9 built-in Kotlin toolchain (set here; not exposed via the public DSL).
  kotlin { jvmToolchain(11) }

  // The LiteRT conversions (ml/litert/converted: RTMDet/RTMPose hand models
  // + the MobileCLIP-S1 combined tflite) and the shared CLIP tokenizer ship
  // as app assets; the ONNX source models (ml/original_models, 514 MB) are
  // not shipped while the imageRecognitionComponentOnnx provider is unplugged.
  sourceSets {
    getByName("main") {
      assets.directories.add(
        rootProject.layout.projectDirectory.dir("ml/litert/converted").asFile.path
      )
      assets.directories.add(
        rootProject.layout.projectDirectory.dir("ml/litert/tokenizer").asFile.path,
      )
      // ONNX hand models for the ONNX hand pipeline.
      assets.directories.add(rootProject.layout.projectDirectory.dir("ml/onnx_hand").asFile.path)
    }
    // Instrumented hand-landmarker + search dataset tests
    // read the sample
    // images straight from plans/samples (score dirs 5,
    // 4, 3, 2, 1,
    // no_score, search), keeping a single source of truth
    // for the dataset.
    getByName("androidTest") {
      assets.directories.add(rootProject.layout.projectDirectory.dir("plans/samples").asFile.path)
      // Host-generated reference
      // dumps + GPU op probes for
      // the LiteRT
      // on-device verification.
      assets.directories.add(rootProject.layout.projectDirectory.dir("ml/litert/refs").asFile.path)
      assets.directories.add(
        rootProject.layout.projectDirectory.dir("ml/litert/probes").asFile.path
      )
    }
  }

  androidResources {
    // Keep tflite assets uncompressed so CompiledModel
    // can mmap them.
    noCompress += "tflite"
  }

  androidResources {
    // ml/original_models carries the SDK descriptor jsons
    // + verification
    // renders next to each model; ship only the .onnx
    // binaries + the
    // tokenizer. ignoreAssetsPattern is the aapt2
    // mechanism that filters
    // files out of the packaged assets (colon-separated
    // patterns; files
    // matching are not packaged). NOTE: it REPLACES (does
    // not augment)
    // aapt's default ignore list — fine here because the
    // ml/original_models dir is controlled, with no
    // dotfiles/vcs junk.
    // The verification renders are filtered by EXACT
    // name, not *.jpg — a
    // *.jpg glob would also strip the plans/samples jpgs
    // from the
    // androidTest APK (the dataset tests read those as
    // assets). The
    // original baked end2end.onnx stays on disk as
    // canonical source but
    // is not packaged (only the *_f32clean GPU-ready
    // variants ship).
    ignoreAssetsPattern =
      "deploy.json:detail.json:pipeline.json:" +
        "output_onnxruntime.jpg:output_pytorch.jpg:end2end.onnx:" +
        "*.jpeg:*.png:*.md:.DS_Store:" +
        "*.pth:text_model_fp16.onnx:vision_model_fp16.onnx"
  }
}

composeCompiler {
  stabilityConfigurationFiles =
    listOf(rootProject.layout.projectDirectory.file("app/compose_stability.conf"))
}

dependencies {
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.crashlytics)

  // junit-ktx brings androidx.test.ext:junit + core transitively.
  androidTestImplementation(libs.androidx.test.junit)
  androidTestImplementation(libs.androidx.runner)
  // LiteRT on-device verification + dataset tests: they name
  // CompiledModel/TensorBuffer types and the LiteRT factory classes
  // (imageRecognitionComponentLiteRt keeps its litert dependency `implementation`, so it
  // doesn't leak here). Production code never names LiteRT types.
  androidTestImplementation(projects.feature.imageRecognition.imageRecognitionComponentLiteRt)
  // The ONNX dataset test uses the ONNX hand pipeline.
  androidTestImplementation(projects.feature.imageRecognition.imageRecognitionComponentOnnx)
  // The dataset tests name the inference contracts directly
  // (HandGestureClassifier, AppClipSearchFactory, ...).
  androidTestImplementation(projects.feature.imageRecognition.imageRecognitionComponentApi)
  androidTestImplementation(libs.litert)
}
