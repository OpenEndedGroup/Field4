import SwiftUI

@main
struct Field4VisionApp: App {
    // The RTT stream (GPU/immersive half) is app-scoped: the main window drives it (start/stop,
    // status) and the ImmersiveSpace presents it, so both share one instance via the environment.
    @State private var rtt = RTTStream()
    @State private var connection = FieldConnection()
    @State private var spatialInk = SpatialInk()

    // The native three.js viewer's client is app-scoped too: the main window opens the viewer
    // window and both share this one selection-following subscription via the environment.
    @State private var threeClient = ThreeClient()

    // Source, JavaScript and status bridge for the autonomous Compositor Services renderer.
    @State private var liveMetalClient = LiveMetalClient()

    init() {
        ThreeHandleComponent.registerComponent()
        // Lines' eye-space offset material; long before the first scene arrives over the network.
        Task { @MainActor in await ThreeMeshBuilder.loadLineMaterial() }
        // The media wire parser has no XCTest target to live in, so it proves itself at launch: a few
        // microseconds over hand-built v1/v2 datagrams, never fails the app. A wrong header offset
        // produces plausible numbers rather than a crash, so "it compiled" is not evidence.
        // Touching it here runs it; `PoseUplink` reports the verdict to the Mac, since neither the
        // unified log nor `simctl --console-pty` reaches it from here.
        _ = RTTWireSelfCheck.failures
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(rtt)
                .environment(threeClient)
                .environment(liveMetalClient)
                .environment(spatialInk)
                .environment(connection)
        }
        .defaultSize(width: 570, height: 330)

        // The RTT stream, windowed: a movable/resizable volumetric window with system chrome (grabber
        // + resize handles), so the stream is no longer pinned in space like the theatre. Same screen
        // content (RTTScreenContent), just a different home. Volumes REQUIRE the `in: .meters`
        // defaultSize overload — the plain width/height one silently treats the numbers as points.
        WindowGroup(id: Field4VisionApp.rttWindowID) {
            RTTStreamWindow()
                .environment(rtt)
        }
        .windowStyle(.volumetric)
        .defaultSize(width: 1.0, height: 0.6, depth: 0.2, in: .meters)
        .windowResizability(.contentSize)

        // The native three.js viewer: the 3D views of the box selected in Field, in their own
        // movable/resizable volume. Same `in: .meters` requirement as the stream volume.
        WindowGroup(id: Field4VisionApp.threeWindowID) {
            ThreeViewerView()
                .environment(threeClient)
        }
        .windowStyle(.volumetric)
        .defaultSize(width: 0.8, height: 0.8, depth: 0.8, in: .meters)
        .windowResizability(.contentSize)
        // One volume follows the selection; a relaunch should not bring back the last one beside a new one.
        .restorationBehavior(.disabled)

        // The RTT receiver's present half: a dimmed immersive theatre showing the decoded UDP
        // stream (see WIRE_FORMAT.md → "Implementing the receiver"). Mixed style so the dimmed
        // passthrough (preferredSurroundingsEffect in ImmersiveStreamView) stays visible around
        // the world-locked screen.
        ImmersiveSpace(id: Field4VisionApp.rttSpaceID) {
            ImmersiveStreamView()
                .environment(rtt)
        }
        .immersionStyle(selection: .constant(.mixed), in: .mixed)

        ImmersiveSpace(id: Field4VisionApp.spatialInkSpaceID) {
            SpatialInkSpace(ink: spatialInk)
        }
        .immersionStyle(selection: .constant(.mixed), in: .mixed)

        // Field's programmable native graphics head.
        ImmersiveSpace(id: Field4VisionApp.liveMetalSpaceID) {
            LiveMetalImmersiveContent(client: liveMetalClient)
        }
        .immersionStyle(selection: .constant(.mixed), in: .mixed)
    }

    static let spatialInkSpaceID = "SpatialInk"
    static let rttSpaceID = "RTTImmersive"
    static let rttWindowID = "RTTWindow"
    static let threeWindowID = "ThreeViewer"
    static let liveMetalSpaceID = "FieldLiveMetal"
}
