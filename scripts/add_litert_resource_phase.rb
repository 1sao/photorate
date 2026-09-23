require "xcodeproj"

project_path = "ios/PhotoRateiOS.xcodeproj"
project = Xcodeproj::Project.open(project_path)

target = project.targets.find { |t| t.name == "PhotoRateiOS" }
raise "PhotoRateiOS target not found" unless target

existing = target.shell_script_build_phases.find { |p| p.name == "Stage LiteRT Models & Dylib" }
if existing
  puts "Phase already present, nothing to do"
  exit 0
end

script = <<~'SCRIPT'
  set -euo pipefail

  REPO_ROOT="$SRCROOT/.."
  MODELS_DIR="$REPO_ROOT/ml/litert/converted"
  TOKENIZER="$REPO_ROOT/ml/litert/tokenizer/tokenizer.json"
  RESOURCES_DIR="${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}"
  FRAMEWORKS_DIR="${TARGET_BUILD_DIR}/${FRAMEWORKS_FOLDER_PATH}"

  MODEL_FILES=(
    "rtmdet_hand_320_f32.tflite"
    "rtmpose_hand_256_f32.tflite"
    "clip_s1_combined_f16.tflite"
  )

  for f in "${MODEL_FILES[@]}"; do
    if [ ! -f "$MODELS_DIR/$f" ]; then
      echo "error: missing model $MODELS_DIR/$f" >&2
      exit 1
    fi
  done
  if [ ! -f "$TOKENIZER" ]; then
    echo "error: missing tokenizer $TOKENIZER" >&2
    exit 1
  fi

  case "$PLATFORM_NAME" in
    iphoneos) DYLIB_SRC="$REPO_ROOT/ml/litert_cpp/libLiteRt-ios.dylib" ;;
    *)        DYLIB_SRC="$REPO_ROOT/ml/litert_cpp/libLiteRt-simulator.dylib" ;;
  esac
  if [ ! -f "$DYLIB_SRC" ]; then
    echo "error: missing dylib $DYLIB_SRC" >&2
    exit 1
  fi

  mkdir -p "$RESOURCES_DIR" "$FRAMEWORKS_DIR"

  for f in "${MODEL_FILES[@]}"; do
    cp -c "$MODELS_DIR/$f" "$RESOURCES_DIR/$f"
  done
  cp -c "$TOKENIZER" "$RESOURCES_DIR/tokenizer.json"

  cp -c "$DYLIB_SRC" "$FRAMEWORKS_DIR/libLiteRt.dylib"
  if command -v codesign > /dev/null; then
    codesign --force --sign - --timestamp "$FRAMEWORKS_DIR/libLiteRt.dylib" 2>/dev/null || true
  fi

  echo "info: staged LiteRT models + libLiteRt.dylib"
SCRIPT

phase = project.new(Xcodeproj::Project::Object::PBXShellScriptBuildPhase)
phase.name = "Stage LiteRT Models & Dylib"
phase.shell_script = script
phase.shell_path = "/bin/bash"
phase.input_paths = [
  "ml/litert/converted/rtmdet_hand_320_f32.tflite",
  "ml/litert/converted/rtmpose_hand_256_f32.tflite",
  "ml/litert/converted/clip_s1_combined_f16.tflite",
  "ml/litert/tokenizer/tokenizer.json",
]
phase.output_paths = [
  "$(TARGET_BUILD_DIR)/$(UNLOCALIZED_RESOURCES_FOLDER_PATH)/rtmdet_hand_320_f32.tflite",
  "$(TARGET_BUILD_DIR)/$(UNLOCALIZED_RESOURCES_FOLDER_PATH)/rtmpose_hand_256_f32.tflite",
  "$(TARGET_BUILD_DIR)/$(UNLOCALIZED_RESOURCES_FOLDER_PATH)/clip_s1_combined_f16.tflite",
  "$(TARGET_BUILD_DIR)/$(UNLOCALIZED_RESOURCES_FOLDER_PATH)/tokenizer.json",
  "$(TARGET_BUILD_DIR)/$(FRAMEWORKS_FOLDER_PATH)/libLiteRt.dylib",
]

kotlin_phase = target.shell_script_build_phases.find do |p|
  p.shell_script.include?("embedAndSignAppleFrameworkForXcode")
end

if kotlin_phase
  index = target.build_phases.index(kotlin_phase)
  target.build_phases.insert(index + 1, phase)
else
  target.build_phases.insert(target.build_phases.size - 1, phase)
end

project.save
puts "Added 'Stage LiteRT Models & Dylib' phase to #{target.name}"
