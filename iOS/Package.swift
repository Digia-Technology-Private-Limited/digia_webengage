// swift-tools-version: 6.0
import PackageDescription

// ┌─────────────────────────────────────────────────────────────────────────────┐
// │  DigiaEngageWebEngage – iOS native CEP plugin for WebEngage                 │
// │                                                                             │
// │  SPM dependency (this package):                                             │
// │    .package(                                                                │
// │        url: "…/digia_engage_webengage.git",                                 │
// │        from: "0.1.0"                                                        │
// │    )                                                                        │
// │                                                                             │
// │  WebEngage SDK is fetched automatically as remote SPM binary targets.       │
// │  No CocoaPods setup is required in the host app.                            │
// └─────────────────────────────────────────────────────────────────────────────┘

let package = Package(
    name: "DigiaEngageWebEngage",
    platforms: [
        .iOS(.v16),
    ],
    products: [
        // Only DigiaEngageWebEngage is exposed. WebEngage and WEPersonalization
        // are internal dependencies — the host app must NOT add WebEngage
        // separately or it will cause duplicate symbol linker errors.
        .library(
            name: "DigiaEngageWebEngage",
            targets: ["DigiaEngageWebEngage"]
        ),
    ],
    dependencies: [
        // Digia Engage iOS SDK — available via Swift Package Manager.
        .package(
            url: "https://github.com/Digia-Technology-Private-Limited/digia_engage_ios.git",
            from: "1.0.0-beta.1"
        ),
    ],
    targets: [
        // WebEngage xcframeworks bundled locally.
        .binaryTarget(
            name: "WebEngage",
            path: "Frameworks/WebEngage.xcframework"
        ),
        .binaryTarget(
            name: "WEPersonalization",
            path: "Frameworks/WEPersonalization.xcframework"
        ),
        .target(
            name: "DigiaEngageWebEngage",
            dependencies: [
                .product(name: "DigiaEngage", package: "digia_engage_ios"),
                .target(name: "WebEngage"),
                .target(name: "WEPersonalization"),
            ],
            path: "Sources"
        ),
        // .testTarget(
        //     name: "DigiaEngageWebEngageTests",
        //     dependencies: ["DigiaEngageWebEngage"],
        //     path: "Tests"
        // ),
    ]
)
