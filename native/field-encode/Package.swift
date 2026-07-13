// swift-tools-version:5.9
import PackageDescription

// field-encode: the Mac (VideoToolbox) sender co-process for the visionOS stereo/flat docks.
// System frameworks (VideoToolbox, CoreVideo, CoreMedia, IOSurface) are auto-linked via
// `import` on macOS — no explicit linkerSettings needed.
let package = Package(
    name: "field-encode",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "field-encode",
            path: "Sources/field-encode"
        )
    ]
)
