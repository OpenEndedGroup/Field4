import SwiftUI

/// The dock/tab manager: a rail of open docks on the left, the selected dock's WKWebView on
/// the right. The rail is collapsible (it eats a lot of space in the room), and any dock can be
/// undocked into its own window floating in space.
struct ContentView: View {
    @State private var manager = DockManager()
    /// Rail shown/hidden. This was a `NavigationSplitViewVisibility` driving a `NavigationSplitView`,
    /// which aborted the app on visionOS 26: any change to the window's bounds — including the
    /// geometry restore that happens ~0.7s into every relaunch — runs
    /// `_UISplitViewControllerAdaptiveImpl _prepareTransitionToLayout:` *inside* a UIView animation
    /// block, which updates the ornament's glass buttons and reaches
    /// `-[UIVisualEffectView setEffect:]` mid-animation. That throws
    /// NSInternalInconsistencyException ("cannot animate the effects property alongside
    /// backgroundEffects or contentEffects") and SIGABRTs. A plain HStack has no adaptive layout
    /// transition, so the animated-effect update never happens. See the two crash reports at
    /// 2026-07-25 11:44:40 and 13:23:54.
    @State private var railVisible = true
    /// Compact rail: 13pt rows fit a middle-truncated document name in 150pt.
    private let railWidth: CGFloat = 150
    private var fieldFont = FieldFont.shared
    @State private var didAutoOpenEditor = false
    @State private var immersiveOpen = false
    @Environment(\.openWindow) private var openWindow
    @Environment(RTTStream.self) private var rtt
    @Environment(SpatialInk.self) private var spatialInk
    @State private var switchingInk = false
    @Environment(ThreeClient.self) private var threeClient
    @Environment(LiveMetalClient.self) private var liveMetalClient
    @Environment(\.openImmersiveSpace) private var openImmersiveSpace
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace

    var body: some View {
        HStack(spacing: 0) {
            if railVisible {
                railList
                    .frame(width: railWidth)
                    .transition(.move(edge: .leading).combined(with: .opacity))
            }
            detailContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .shellWindowCornerSafe()
        // Everything lives in one always-visible bottom ornament — it floats outside the window,
        // so there's no top title bar eating canvas height (visionOS won't shrink that bar via
        // title modifiers). The toggle stays reachable even when the rail is collapsed; the doc
        // name shows in the rail. This is `.ornament` rather than a `ToolbarItemGroup` because the
        // toolbar came with the `NavigationSplitView` that had to go — see `railVisible`.
        .ornament(attachmentAnchor: .scene(.bottom)) { controlBar }
        .task {
            fieldFont.load(from: manager.server)
            // The floating editor is OPT-IN now. It used to open itself ~1.2s into every launch so
            // the shared-selection pair was up immediately, but in practice a second window that
            // steals keyboard focus costs more than the pairing saves — you spend the session
            // moving focus back and forth. The `</>` ornament button still opens it on demand, and
            // shared selection works the moment it does. Set FIELD4_AUTO_EDITOR=1 to restore the
            // old behaviour (useful for headless/paired-client testing, where nothing can tap).
            if !didAutoOpenEditor,
               ProcessInfo.processInfo.environment["FIELD4_AUTO_EDITOR"] == "1" {
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
            // Headless testing: `FIELD4_THREE_AUTOSTART=1` starts the selection-following three.js
            // client and opens its volume on launch (simctl can't tap the "3D View" ornament button).
            // Pass via `SIMCTL_CHILD_FIELD4_THREE_AUTOSTART=1`.
            if ProcessInfo.processInfo.environment["FIELD4_THREE_AUTOSTART"] == "1" {
                threeClient.start()
                if threeClient.openVolumes == 0 { openWindow(id: Field4VisionApp.threeWindowID) }
            }
            if ProcessInfo.processInfo.environment["FIELD4_LIVE_METAL_AUTOSTART"] == "1",
               !liveMetalClient.immersiveRequested {
                await toggleLiveMetal()
            }
        }
    }

    /// The dock rail. Previously the `NavigationSplitView` sidebar; now a plain column.
    private var railList: some View {
        // Plain rows, not a `List`: visionOS list rows are tall glass capsules with a section
        // header, which made two or three docks fill the rail like toy buttons. No header ("Docks"
        // said nothing) and no tooltip (it repeated the label). Names truncate in the MIDDLE so a
        // numeric suffix ("some fline00001") stays visible.
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(manager.docks) { dock in
                    let selected = dock.id == manager.selection
                    Button {
                        manager.selection = dock.id
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: icon(for: dock.kind))
                                .font(.system(size: 11))
                                .foregroundStyle(.secondary)
                            Text(dock.title)
                                .font(fieldFont.font(size: 13))
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(selected ? Color.white.opacity(0.14) : .clear,
                                    in: RoundedRectangle(cornerRadius: 6))
                        .contentShape(.hoverEffect, RoundedRectangle(cornerRadius: 6))
                        .hoverEffect()
                    }
                    .buttonStyle(.plain)
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
            .padding(.top, 4)
            .padding(.trailing, 8)
        }
    }

    /// The single bottom ornament carrying every control.
    private var controlBar: some View {
        HStack(spacing: 12) {
            Button {
                withAnimation { railVisible.toggle() }
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
            // Opens the native three.js viewer and starts following Field's selection (idempotent).
            Button {
                shellLog.notice("tap: three-3d")
                threeClient.start()
                if threeClient.openVolumes == 0 { openWindow(id: Field4VisionApp.threeWindowID) }
            } label: {
                Label("3D View", systemImage: "cube.transparent")
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
            Button {
                Task { await toggleSpatialInk() }
            } label: {
                Label(spatialInk.isOpen ? "Exit Spatial Ink" : "Spatial Ink",
                      systemImage: spatialInk.isOpen ? "xmark.circle" : "scribble.variable")
            }
            .disabled(switchingInk)
            if spatialInk.isOpen {
                Button {
                    spatialInk.arm(!spatialInk.isArmed)
                } label: {
                    Label(spatialInk.isArmed ? "Pause Drawing" : "Arm Draw",
                          systemImage: spatialInk.isArmed ? "pause.circle.fill" : "pencil.tip")
                }
                .disabled(!spatialInk.canDraw)
                Button { spatialInk.undo() } label: {
                    Label("Undo Stroke", systemImage: "arrow.uturn.backward")
                }
                .disabled(!spatialInk.canUndo)
                if spatialInk.syncFailed {
                    Button { spatialInk.retry() } label: {
                        Label("Retry Field Sync", systemImage: "arrow.clockwise")
                    }
                }
                Text(spatialInk.status).font(.caption).lineLimit(1)
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
        // `ToolbarItemGroup` rendered these Labels icon-only for free; a plain HStack renders icon
        // AND title, which made the ornament wide and tall enough to cover the window's bottom
        // resize affordances. Ask for icon-only explicitly. (visionOS still surfaces the titles as
        // hover tooltips, so nothing is lost.)
        .labelStyle(.iconOnly)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .fixedSize()               // size to content so labels never wrap
        .glassBackgroundEffect()
    }

    /// Receiver port — overridable via `FIELD4_RTT_PORT` (e.g. when Field itself holds the default
    /// 9000 on the shared host stack during same-Mac simulator testing).
    private var rttPort: UInt16 { RTTStream.resolvedPort }

    /// Open/close the RTT theatre. Acquiring the stream starts the receiver (if not already running
    /// for the windowed viewer) before the space so packets are already being reassembled when the
    /// screen appears; releasing stops it only when no other presentation is open.
    private func toggleSpatialInk() async {
        guard !switchingInk else { return }
        switchingInk = true
        defer { switchingInk = false }
        if spatialInk.isOpen {
            await dismissImmersiveSpace()
            return
        }
        if immersiveOpen { await toggleImmersive() }
        if liveMetalClient.immersiveRequested { await toggleLiveMetal() }
        _ = await openImmersiveSpace(id: Field4VisionApp.spatialInkSpaceID)
    }

    private func toggleImmersive() async {
        if spatialInk.isOpen { await dismissImmersiveSpace() }
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
        if spatialInk.isOpen { await dismissImmersiveSpace() }
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
                // The content is now inset and re-rounded by `shellWindowCornerSafe`, so what a
                // toast has to clear is the content's own radius, not the system's much larger one.
                cornerInset: railVisible ? 0 : shellContentCornerRadius
            )
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
        .environment(ThreeClient())
        .environment(LiveMetalClient())
        .environment(SpatialInk())
}
