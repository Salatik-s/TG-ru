// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FamilyTransportCore",
    platforms: [.macOS(.v12), .iOS(.v13)],
    products: [.library(name: "FamilyTransportCore", targets: ["FamilyTransportCore"])],
    targets: [
        .target(name: "FamilyTransportCore", path: "Sources/Core"),
        .testTarget(name: "FamilyTransportCoreTests", dependencies: ["FamilyTransportCore"], path: "Tests")
    ],
    swiftLanguageVersions: [.v5]
)
