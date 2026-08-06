// swift-tools-version: 5.9
import PackageDescription
let package = Package(
  name: "KotlinMultiplatformLinkedPackage",
  platforms: [
    .iOS("15.0")
  ],
  products: [
    .library(
      name: "KotlinMultiplatformLinkedPackage",
      type: .none,
      targets: ["KotlinMultiplatformLinkedPackage"]
    )
  ],
  dependencies: [
    .package(
      url: "https://github.com/jordond/SwiftTasksVision.git",
      branch: "main"
    ),
    .package(path: "subpackages/KotlinMultiplatformLinkedPackageDylib")
  ],
  targets: [
    .target(
      name: "KotlinMultiplatformLinkedPackage",
      dependencies: [
        .product(name: "KotlinMultiplatformLinkedPackageDylib", package: "KotlinMultiplatformLinkedPackageDylib")
      ]
    )
  ]
)
