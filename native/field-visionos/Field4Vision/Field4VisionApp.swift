import SwiftUI

/// Field4 visionOS shell — L0/L1 per dev_docs/vision_frontend.md in the field4 repo.
///
/// The whole app is a "dream web browser": a tab manager whose docks are WKWebViews pointed
/// at the existing Field server endpoints (/welcome, /index.html). No Field4 changes needed —
/// the shell just hosts the existing front end and lets docks float in space.
@main
struct Field4VisionApp: App {
    // The RTT stream (GPU/immersive half) is app-scoped: the main window drives it (start/stop,
    // status) and the ImmersiveSpace presents it, so both share one instance via the environment.
    @State private var rtt = RTTStream()

    // The native FLine viewer's discovery client is app-scoped too: the main window opens the viewer
    // window and both share this one WebSocket subscription via the environment.
    @State private var flineClient = FLineViewerClient()

    var body: some Scene {
        // Main window: the dock/tab manager.
        WindowGroup {
            ContentView()
                .environment(rtt)
                .environment(flineClient)
        }
        .windowResizability(.contentSize)

        // Undocked docks: opening one gives it its own window floating in space. This is the
        // L1 move — multiple documents/panels arranged spatially — using plain WindowGroups
        // before we reach for RealityKit islands.
        WindowGroup(for: URL.self) { $url in
            if let url {
                UndockedWebDockWindow(url: url)
            }
        }
        // Floating editor panel. We previously forced `.utilityPanel`, which reliably opens but
        // presents *tilted* like a control panel — wrong for a Monaco editor you read head-on.
        // Dropping the explicit placement lets the system use its DEFAULT: an UPRIGHT window, forward,
        // in front of the active window (the canvas the editor edits) — which is what we want. (We
        // deliberately avoid the relative `.leading/.trailing(mainWindow)` placements too: against the
        // large main window they resolve off-display and the window then fails to open. Precise
        // "pinned beside the doc" placement isn't reliably expressible today; the user nudges it the
        // last bit by its bar.)
        .defaultSize(width: 520, height: 820)

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

        // The first native viewer: a fully-3D RealityKit FLine viewer (the native equivalent of the
        // three.js iframe), in its own movable/resizable volume. Cube-ish default — FLine models are
        // arbitrary 3D. Same `in: .meters` requirement as the stream volume.
        WindowGroup(id: Field4VisionApp.flineWindowID) {
            FLineViewerView()
                .environment(flineClient)
        }
        .windowStyle(.volumetric)
        .defaultSize(width: 0.8, height: 0.8, depth: 0.8, in: .meters)
        .windowResizability(.contentSize)

        // The RTT receiver's present half: a dimmed immersive theatre showing the decoded UDP
        // stream (see WIRE_FORMAT.md → "Implementing the receiver"). Mixed style so the dimmed
        // passthrough (preferredSurroundingsEffect in ImmersiveStreamView) stays visible around
        // the world-locked screen.
        ImmersiveSpace(id: Field4VisionApp.rttSpaceID) {
            ImmersiveStreamView()
                .environment(rtt)
        }
        .immersionStyle(selection: .constant(.mixed), in: .mixed)
    }

    static let rttSpaceID = "RTTImmersive"
    static let rttWindowID = "RTTWindow"
    static let flineWindowID = "FLineViewer"
}

/// A free-floating WebDock window (an undocked dock, or a `window.open("_blank")` target). It
/// keeps `window.open` working here too: further `_blank` opens spawn another floating window.
private struct UndockedWebDockWindow: View {
    let url: URL
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        // Standalone floating window: the web view fills it, so its bottom-left is the window's
        // rounded corner — always tuck content clear of it.
        WebDock(url: url, onOpenURL: { openWindow(value: $0) }, cornerInset: shellWindowCornerInset)
            .ignoresSafeArea()
            .onAppear { shellLog.notice("floating window appeared -> \(url.absoluteString, privacy: .public)") }
    }
}
