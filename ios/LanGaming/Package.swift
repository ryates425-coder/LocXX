// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "LanGaming",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "LanGaming", targets: ["LanGaming"]),
    ],
    targets: [
        .target(name: "LanGaming", dependencies: []),
        .testTarget(name: "LanGamingTests", dependencies: ["LanGaming"]),
    ]
)
