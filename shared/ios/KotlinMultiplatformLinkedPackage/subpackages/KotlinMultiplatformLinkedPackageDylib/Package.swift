// swift-tools-version: 5.9
import PackageDescription
let package = Package(
  name: "KotlinMultiplatformLinkedPackageDylib",
  platforms: [
    .iOS("15.0")
  ],
  products: [
    .library(
      name: "KotlinMultiplatformLinkedPackageDylib",
      type: .dynamic,
      targets: ["KotlinMultiplatformLinkedPackageDylib"]
    )
  ],
  dependencies: [
    .package(
      url: "https://github.com/jordond/SwiftTasksVision.git",
      branch: "main"
    )
  ],
  targets: [
    .target(
      name: "KotlinMultiplatformLinkedPackageDylib",
      dependencies: [
        .product(
          name: "MediaPipeTasksVision",
          package: "SwiftTasksVision"
        )
      ]
    )
  ]
)
