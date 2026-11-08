// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FamilyCore",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [.library(name: "FamilyCore", targets: ["FamilyCore"])],
    targets: [.target(name: "FamilyCore"), .testTarget(name: "FamilyCoreTests", dependencies: ["FamilyCore"])]
)
