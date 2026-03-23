// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "LocXXRules",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "LocXXRules", targets: ["LocXXRules"]),
    ],
    targets: [
        .target(name: "LocXXRules", dependencies: []),
        .testTarget(name: "LocXXRulesTests", dependencies: ["LocXXRules"]),
    ]
)
