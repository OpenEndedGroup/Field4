import SwiftUI

/// The dock/tab manager: a rail of open docks on the left, the selected dock's WKWebView on
/// the right. The rail is collapsible (it eats a lot of space in the room), and any dock can be
/// undocked into its own window floating in space.
struct ContentView: View {
    @State private var manager = DockManager()
    @State private var columnVisibility: NavigationSplitViewVisibility = .doubleColumn
    @State private var didAutoOpenEditor = false
    @State private var immersiveOpen = false
    @Environment(\.openWindow) private var openWindow
    @Environment(RTTStream.self) private var rtt
    @Environment(FLineViewerClient.self) private var flineClient
    @Environment(LiveMetalClient.self) private var liveMetalClient
    @Environment(\.openImmersiveSpace) private var openImmersiveSpace
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            List(selection: $manager.selection) {
                Section("Docks") {
                    ForEach(manager.docks) { dock in
                        Label(dock.title, systemImage: icon(for: dock.kind))
                            .tag(dock.id)
                            .contextMenu {
                                Button {
                                    openWindow(value: dock.url)
                                } label: {
                                    Label("Undock into space", systemImage: "rectangle.badge.plus")
                                }
                                Button(role: .destructive) {
                                    manager.close(dock.id)
                                } label: {
                                    Label("Close", systemImage: "xmark")
                                }
                            }
                    }
                }
            }
            .navigationTitle("Field4")
            .navigationSplitViewColumnWidth(min: 180, ideal: 220, max: 300)
        } detail: {
            detailContent
                .toolbar {
                    // Everything lives in one always-visible bottom ornament — it floats outside
                    // the window, so there's no top title bar eating canvas height (visionOS won't
                    // shrink that bar via title modifiers). The toggle stays reachable even when the
                    // rail is collapsed; the doc name shows in the rail.
                    ToolbarItemGroup(placement: .bottomOrnament) {
                        Button {
                            withAnimation {
                                columnVisibility = columnVisibility == .detailOnly ? .doubleColumn : .detailOnly
                            }
                        } label: {
                            Label("Toggle Docks", systemImage: "sidebar.left")
                        }
                        Button {
                            manager.openWelcome()
                        } label: {
                            Label("Welcome", systemImage: "house")
                        }
                        Button {
                            shellLog.notice("tap: canvas")
                            manager.openGlassCanvas()
                        } label: {
                            Label("Canvas", systemImage: "square.on.square")
                        }
                        Button {
                            let u = manager.floatingEditorURL()
                            shellLog.notice("tap: floating-editor -> \(u.absoluteString, privacy: .public)")
                            openWindow(value: u)
                        } label: {
                            Label("Floating Editor", systemImage: "chevron.left.forwardslash.chevron.right")
                        }
                        // The first native viewer: opens the RealityKit FLine viewer window and starts
                        // its discovery subscription (idempotent). Shows any live threeLines() view.
                        Button {
                            shellLog.notice("tap: fline-3d")
                            flineClient.start()
                            openWindow(id: Field4VisionApp.flineWindowID)
                        } label: {
                            Label("FLine 3D", systemImage: "scribble.variable")
                        }
                        Button {
                            shellLog.notice("tap: undock (selected=\(manager.selectedDock?.title ?? "nil", privacy: .public))")
                            if let dock = manager.selectedDock { openWindow(value: dock.url) }
                        } label: {
                            Label("Undock", systemImage: "rectangle.badge.plus")
                        }
                        // The GPU half, windowed: a movable/resizable volumetric window hosting the
                        // stream screen. Opening it acquires the receiver (shared with the theatre).
                        Button {
                            shellLog.notice("tap: stream-window")
                            openWindow(id: Field4VisionApp.rttWindowID)
                        } label: {
                            Label("Stream Window", systemImage: "play.rectangle")
                        }
                        // The GPU/immersive half: start the RTT receiver and open the dimmed
                        // theatre (or tear both down). The stream keeps running independent of the
                        // rail so the receiver's diagnostics stay live.
                        Button {
                            Task { await toggleImmersive() }
                        } label: {
                            Label(immersiveOpen ? "Exit Immersive" : "Immersive",
                                  systemImage: immersiveOpen ? "arrow.down.right.and.arrow.up.left" : "cube.transparent")
                        }
                        Button {
                            Task { await toggleLiveMetal() }
                        } label: {
                            Label(liveMetalClient.immersiveRequested ? "Exit Live Metal" : "Live Metal",
                                  systemImage: liveMetalClient.immersiveRequested ? "xmark.circle" : "cube.transparent.fill")
                        }
                        if rtt.isRunning {
                            Text(rtt.status)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        if liveMetalClient.isConnected || liveMetalClient.immersiveRequested {
                            Text(liveMetalClient.status)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
        }
        .task {
            // Auto-open the floating editor on launch so the shared-selection pair is up immediately.
            if !didAutoOpenEditor {
                didAutoOpenEditor = true
                try? await Task.sleep(for: .seconds(1.2))
                openWindow(value: manager.floatingEditorURL())
            }
            // Headless testing: `FIELD4_RTT_AUTOSTART=1` binds the RTT receiver (and opens the
            // theatre) on launch, so the sim can receive a stream without a tap on the ornament
            // (which simctl can't drive). Pass via `SIMCTL_CHILD_FIELD4_RTT_AUTOSTART=1`.
            if ProcessInfo.processInfo.environment["FIELD4_RTT_AUTOSTART"] == "1", !immersiveOpen {
                await toggleImmersive()
            }
            // Headless testing: `FIELD4_FLINE_AUTOSTART=1` starts the FLine discovery client and opens
            // the native viewer window on launch (simctl can't tap the "FLine 3D" ornament button).
            // Pass via `SIMCTL_CHILD_FIELD4_FLINE_AUTOSTART=1`.
            if ProcessInfo.processInfo.environment["FIELD4_FLINE_AUTOSTART"] == "1" {
                flineClient.start()
                openWindow(id: Field4VisionApp.flineWindowID)
            }
            if ProcessInfo.processInfo.environment["FIELD4_LIVE_METAL_AUTOSTART"] == "1",
               !liveMetalClient.immersiveRequested {
                await toggleLiveMetal()
            }
        }
    }

    /// Receiver port — overridable via `FIELD4_RTT_PORT` (e.g. when Field itself holds the default
    /// 9000 on the shared host stack during same-Mac simulator testing).
    private var rttPort: UInt16 { RTTStream.resolvedPort }

    /// Open/close the RTT theatre. Acquiring the stream starts the receiver (if not already running
    /// for the windowed viewer) before the space so packets are already being reassembled when the
    /// screen appears; releasing stops it only when no other presentation is open.
    private func toggleImmersive() async {
        if immersiveOpen {
            await dismissImmersiveSpace()
            rtt.release()
            immersiveOpen = false
            return
        }
        if liveMetalClient.immersiveRequested {
            await dismissImmersiveSpace()
            liveMetalClient.stop()
            liveMetalClient.clearImmersiveRequest()
        }
        rtt.acquire(port: rttPort)
        switch await openImmersiveSpace(id: Field4VisionApp.rttSpaceID) {
        case .opened:
            immersiveOpen = true
        default:
            rtt.release()
            immersiveOpen = false
        }
    }

    private func toggleLiveMetal() async {
        if liveMetalClient.immersiveRequested {
            await dismissImmersiveSpace()
            liveMetalClient.stop()
            liveMetalClient.clearImmersiveRequest()
            return
        }
        // visionOS permits one immersive space at a time. Close the RTT theatre cleanly if it owns
        // that slot; its independent volumetric stream window is unaffected.
        if immersiveOpen {
            await dismissImmersiveSpace()
            rtt.release()
            immersiveOpen = false
        }
        guard liveMetalClient.claimImmersiveRequest() else { return }
        liveMetalClient.start()
        switch await openImmersiveSpace(id: Field4VisionApp.liveMetalSpaceID) {
        case .opened:
            break
        default:
            liveMetalClient.stop()
            liveMetalClient.clearImmersiveRequest()
        }
    }

    @ViewBuilder
    private var detailContent: some View {
        if let dock = manager.selectedDock {
            WebDock(
                url: dock.url,
                onOpenURL: { openWindow(value: $0) },
                onNavigate: { manager.updateDock(dock.id, to: $0) },
                // Only tuck around the window corner when the rail is collapsed — with it open the
                // sidebar covers the bottom-left corner, so the detail web view is already clear.
                cornerInset: columnVisibility == .detailOnly ? shellWindowCornerInset : 0
            )
                .ignoresSafeArea()
        } else {
            ContentUnavailableView("No dock selected",
                                   systemImage: "square.dashed",
                                   description: Text("Open a dock from the toolbar below."))
        }
    }

    private func icon(for kind: DockKind) -> String {
        switch kind {
        case .welcome: "house"
        case .document: "doc.text"
        }
    }
}

#Preview(windowStyle: .automatic) {
    ContentView()
        .environment(RTTStream())
        .environment(FLineViewerClient())
        .environment(LiveMetalClient())
}
