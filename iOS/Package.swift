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
// │  WebEngage SDK — CocoaPods only (no official SPM distribution).             │
// │  Add to your app's Podfile BEFORE resolving SPM packages:                   │
// │    pod 'WebEngage/Core',   '>= 6.10.0'                                      │
// │    pod 'WEPersonalization'          # for inline slot support               │
// │                                                                             │
// │  Xcode merges CocoaPods framework search paths with SPM build targets,      │
// │  so `import WebEngage` compiles correctly when both are present.            │
// └─────────────────────────────────────────────────────────────────────────────┘

let package = Package(
    name: "DigiaEngageWebEngage",
    platforms: [
        .iOS(.v16),
    ],
    products: [
        .library(
            name: "DigiaEngageWebEngage",
            targets: ["DigiaEngageWebEngage"]
        ),
        // Re-exported so the host app can `import WebEngage` directly
        // without needing CocoaPods.
        .library(name: "WebEngage",          targets: ["WebEngage"]),
        .library(name: "WEPersonalization",  targets: ["WEPersonalization"]),
    ],
    dependencies: [
        // Digia Engage iOS SDK — available via Swift Package Manager.
        .package(
            url: "https://github.com/Digia-Technology-Private-Limited/digia_engage_ios.git",
            from: "1.0.0-beta.1"
        ),
    ],
    targets: [
        // Binary targets bundling the WebEngage CocoaPods xcframeworks so SPM
        // can resolve `import WebEngage` / `import WEPersonalization` at build
        // time without requiring the host app's CocoaPods paths to bleed into
        // the package build context.
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
