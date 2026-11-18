import SwiftUI

struct ContentView: View {
    @Environment(FieldConnection.self) private var connection
    @Environment(RTTStream.self) private var rtt
    @Environment(ThreeClient.self) private var threeClient
    @Environment(LiveMetalClient.self) private var liveMetalClient
    @Environment(SpatialInk.self) private var spatialInk
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openImmersiveSpace) private var openImmersiveSpace
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace
    @State private var address = ""
    @State private var addressError = false
    @State private var immersiveOpen = false
    @State private var switchingInk = false
    private var fieldFont = FieldFont.shared
    private var hostLocked: Bool { spatialInk.isOpen || switchingInk || liveMetalClient.immersiveRequested }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("Field host").font(fieldFont.font(size: 18))
                Spacer()
                Text("Port \(connection.server.port.formatted(.number.grouping(.never)))")
                    .font(fieldFont.font(size: 15))
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 10) {
                TextField("Host or URL", text: $address)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .submitLabel(.go)
                    .onSubmit { selectHost(address) }
                    .accessibilityLabel("Field host URL")
                Menu {
                    ForEach(connection.recent, id: \.self) { address in
                        Button(address) { selectHost(address) }
                    }
                    if !connection.recent.contains(ServerConfig.defaultAddress) {
                        Button(ServerConfig.defaultAddress) { selectHost(ServerConfig.defaultAddress) }
                    }
                } label: {
                    Label("Recent hosts", systemImage: "clock.arrow.circlepath").labelStyle(.iconOnly)
                }
                Button("Connect") { selectHost(address) }
            }
            .disabled(hostLocked)

            VStack(alignment: .leading, spacing: 5) {
                Label(statusTitle, systemImage: statusIcon)
                    .font(fieldFont.font(size: 16))
                if address != connection.server.baseURL.absoluteString {
                    Text("Active: \(connection.server.baseURL.absoluteString)")
                        .font(fieldFont.font(size: 13))
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
                if addressError {
                    Text("Enter a hostname or an HTTP(S) URL with an optional port.")
                        .font(.caption)
                } else if case .unavailable(let detail) = connection.status {
                    Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
                if hostLocked {
                    Text("Exit the active immersive head to change hosts.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Divider()
            Text(verbatim: rtt.isRunning ? rtt.status : "RTT receiver · UDP \(RTTStream.resolvedPort)")
                .font(fieldFont.font(size: 13))
                .foregroundStyle(.secondary)
            if threeClient.openVolumes > 0 {
                Text("3D View · \(threeClient.status)").font(fieldFont.font(size: 13))
            }
            if liveMetalClient.immersiveRequested {
                Text("Live Metal · \(liveMetalClient.status)").font(fieldFont.font(size: 13)).lineLimit(2)
            }
            if spatialInk.isOpen {
                Text(spatialInk.status).font(fieldFont.font(size: 13)).lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(26)
        .frame(minWidth: 500, minHeight: 290, alignment: .topLeading)
        .ornament(attachmentAnchor: .scene(.bottom)) { heads }
        .task(id: connection.probeRevision) {
            await connection.monitor(revision: connection.probeRevision)
        }
        .onChange(of: connection.status) { _, status in
            if status == .reachable { fieldFont.load(from: connection.server) }
        }
        .task {
            address = connection.server.baseURL.absoluteString
            threeClient.setServer(connection.server)
            let env = ProcessInfo.processInfo.environment
            if env["FIELD4_THREE_AUTOSTART"] == "1" { openThree() }
            if env["FIELD4_RTT_AUTOSTART"] == "1", !immersiveOpen { await toggleImmersive() }
            if env["FIELD4_LIVE_METAL_AUTOSTART"] == "1", !liveMetalClient.immersiveRequested {
                await toggleLiveMetal()
            }
        }
    }

    private var statusTitle: String {
        switch connection.status {
        case .checking: "Checking Field…"
        case .reachable: "Field reachable"
        case .unavailable: "Field unavailable"
        }
    }

    private var statusIcon: String {
        switch connection.status {
        case .checking: "ellipsis.circle"
        case .reachable: "checkmark.circle"
        case .unavailable: "exclamationmark.circle"
        }
    }

    private func selectHost(_ input: String) {
        guard !hostLocked else { return }
        guard let server = ServerConfig(address: input) else { addressError = true; return }
        addressError = false
        address = server.baseURL.absoluteString
        threeClient.setServer(server)
        connection.select(server)
    }

    private func openThree() {
        threeClient.setServer(connection.server)
        threeClient.start()
        if threeClient.openVolumes == 0 { openWindow(id: Field4VisionApp.threeWindowID) }
    }

    private var heads: some View {
        HStack(spacing: 12) {
            Button(action: openThree) { Label("3D View", systemImage: "cube.transparent") }
            Button { openWindow(id: Field4VisionApp.rttWindowID) } label: {
                Label("Stream Window", systemImage: "play.rectangle")
            }
            Button { Task { await toggleImmersive() } } label: {
                Label(immersiveOpen ? "Exit Immersive Stream" : "Immersive Stream",
                      systemImage: immersiveOpen ? "xmark.circle" : "vision.pro")
            }
            Button { Task { await toggleLiveMetal() } } label: {
                Label(liveMetalClient.immersiveRequested ? "Exit Live Metal" : "Live Metal",
                      systemImage: liveMetalClient.immersiveRequested ? "xmark.circle" : "cube.transparent.fill")
            }
            Button { Task { await toggleSpatialInk() } } label: {
                Label(spatialInk.isOpen ? "Exit Spatial Ink" : "Spatial Ink",
                      systemImage: spatialInk.isOpen ? "xmark.circle" : "scribble.variable")
            }
            .disabled(switchingInk)
            if spatialInk.isOpen {
                Button { spatialInk.arm(!spatialInk.isArmed) } label: {
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
            }
        }
        .labelStyle(.iconOnly)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .fixedSize()
        .glassBackgroundEffect()
    }

    private var rttPort: UInt16 { RTTStream.resolvedPort }

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
        spatialInk.server = connection.server
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
        liveMetalClient.setServer(connection.server)
        liveMetalClient.start()
        switch await openImmersiveSpace(id: Field4VisionApp.liveMetalSpaceID) {
        case .opened:
            break
        default:
            liveMetalClient.stop()
            liveMetalClient.clearImmersiveRequest()
        }
    }

}
