import SwiftUI

/// Field4 visionOS shell — L0/L1 per dev_docs/vision_frontend.md in the field4 repo.
///
/// The whole app is a "dream web browser": a tab manager whose docks are WKWebViews pointed
/// at the existing Field server endpoints (/welcome, /index.html). No Field4 changes needed —
/// the shell just hosts the existing front end and lets docks float in space.
@main
struct Field4VisionApp: App {
    var body: some Scene {
        // Main window: the dock/tab manager.
        WindowGroup {
            ContentView()
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
        // Floating editor panel. NOTE: visionOS's WindowGroup placement API won't reliably pin a
        // "half-width, right-inset" default — relative placements (.leading/.trailing) resolve
        // off-display against the large main window and the window then fails to open. .utilityPanel
        // is the one that reliably opens and floats forward; the user drags it into place.
        .defaultSize(width: 520, height: 820)
        .defaultWindowPlacement { _, _ in
            WindowPlacement(.utilityPanel)
        }
    }
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
