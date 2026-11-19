// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "DrawingOnCore",
    platforms: [.macOS(.v13)],
    products: [.library(name: "DrawingOnCore", targets: ["DrawingOnCore"])],
    targets: [
        .target(name: "DrawingOnCore", path: "DrawingOn", exclude: ["AudioCapture.swift", "CaptureStore.swift", "DrawingOnApp.swift", "PencilCanvas.swift", "Resources", "Info.plist"], sources: ["Model.swift", "Journal.swift", "SocketStream.swift", "CaptureLibrary.swift", "FieldReference.swift"]),
        .testTarget(name: "DrawingOnCoreTests", dependencies: ["DrawingOnCore"], path: "Tests")
    ]
)
