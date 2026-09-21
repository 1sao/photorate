plugins {
  id("photorate.app")
  id("com.google.gms.google-services")
  id("com.google.firebase.crashlytics")
}

/** Image recognition backend: 'litert' or 'onnx' (gradle.properties). */
val recognitionBackend = providers.gradleProperty("photorate.backend").getOrElse("litert")

check(recognitionBackend in setOf("litert", "onnx")) {
  "Unknown photorate.backend '$recognitionBackend' (expected 'litert' or 'onnx')"
}

android {
  // AGP 9 built-in Kotlin toolchain (set here; not exposed via the public DSL).
  kotlin { jvmToolchain(11) }
  sourceSets {
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
}

composeCompiler {
  stabilityConfigurationFiles =
    listOf(rootProject.layout.projectDirectory.file("app/compose_stability.conf"))
}

// Model asset staging for the active backend.
abstract class StagePackagedAssetsTask : DefaultTask() {
  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @get:InputFiles abstract val sources: ConfigurableFileCollection

  @get:Input abstract val relativePaths: ListProperty<String>

  @TaskAction
  fun stage() {
    val out = outputDir.get().asFile
    out.deleteRecursively()
    out.mkdirs()
    val files = sources.files.toList()
    val paths = relativePaths.get()
    require(files.size == paths.size) { "sources/relativePaths size mismatch" }
    files.zip(paths).forEach { (src, rel) ->
      val dst = File(out, rel)
      dst.parentFile?.mkdirs()
      src.copyTo(dst, overwrite = true)
    }
  }
}

val backendStaging =
  tasks.register<StagePackagedAssetsTask>("stageBackendPackagedAssets") {
    outputDir.set(layout.buildDirectory.dir("packagedAssets/$recognitionBackend"))
    val root = rootProject.layout.projectDirectory
    when (recognitionBackend) {
      "litert" -> {
        with(root) {
          sources.from(file("ml/litert/converted/rtmdet_hand_320_f32.tflite"))
          sources.from(file("ml/litert/converted/rtmpose_hand_256_f32.tflite"))
          sources.from(file("ml/litert/converted/clip_s1_combined_f16.tflite"))
          sources.from(file("ml/litert/tokenizer/tokenizer.json"))
        }
        relativePaths.set(
          listOf(
            "rtmdet_hand_320_f32.tflite",
            "rtmpose_hand_256_f32.tflite",
            "clip_s1_combined_f16.tflite",
            "tokenizer.json",
          ),
        )
      }

      "onnx" -> {
        val onnxHand = root.dir("ml/onnx_hand").asFile
        val originalModels = root.dir("ml/original_models").asFile
        val handFiles = fileTree(onnxHand) { include("**/*.onnx") }.files.sorted()
        sources.from(handFiles)
        sources.from(root.file("ml/original_models/text_model_fp16/text_model_fp16.onnx"))
        sources.from(root.file("ml/original_models/vision_model_fp16/vision_model_fp16.onnx"))
        sources.from(root.file("ml/original_models/tokenizer.json"))
        relativePaths.set(
          handFiles.map { it.toRelativeString(onnxHand) } +
            listOf(
              "text_model_fp16/text_model_fp16.onnx",
              "vision_model_fp16/vision_model_fp16.onnx",
              "tokenizer.json",
            ),
        )
      }
    }
  }

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(backendStaging) { it.outputDir }
  }
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
