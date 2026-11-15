// swift-tools-version: 5.7
import PackageDescription

let package = Package(
    name: "TgruPlugins",
    products: [.library(name: "TgruPlugins", targets: ["TgruPlugins"])],
    targets: [
        .target(name: "TgruPlugins", path: "Sources"),
        .testTarget(name: "TgruPluginsTests", dependencies: ["TgruPlugins"], path: "Tests")
    ]
)
