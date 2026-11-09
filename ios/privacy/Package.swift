// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "FamilyPrivacy", platforms: [.iOS(.v15), .macOS(.v12)],
    products: [.library(name: "FamilyPrivacy", targets: ["FamilyPrivacy"])],
    targets: [.target(name: "FamilyPrivacy"), .testTarget(name: "FamilyPrivacyTests", dependencies: ["FamilyPrivacy"])])
