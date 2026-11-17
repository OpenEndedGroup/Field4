import Foundation
import Observation

/// The native three.js viewer's connection to Field: it **follows the selection**.
///
/// On connect it sends `follow` on the `__threejs__` channel. Field answers with `three/follow` — the
/// boxes selected in any Field window (the Mac in Virtual Display, typically) and the three views they
/// own — and sends it again whenever that changes. For each view the client sends the same hello the
/// browser iframe does, receives `three/geometry` descriptions, fetches each description's arrays from
/// `/three-buffers`, and publishes the bound `ThreeScene`. Views the selection moved away from are
/// detached, so their updates stop arriving.
@Observable
@MainActor
final class ThreeClient {
    /// The selected boxes, as Field named them.
    private(set) var selected: [ThreeFollowMessage.Box] = []
    /// The views shown: those of the selected boxes, in the server's order. When the selection moves to a
    /// box with no 3D view (the plan beside the model, say) these stay as they were, so editing elsewhere
    /// does not empty the volume. Selecting another box that has views replaces them.
    private(set) var views: [ThreeFollowMessage.View] = []
    /// The name of the box whose views are shown, which is not the selection while they are held.
    private(set) var shownBoxName: String?
    /// Whether the shown views belong to an earlier selection.
    private(set) var isHolding = false
    /// Bound geometry by view uid; a view is absent until its first buffers arrive.
    private(set) var scenes: [String: ThreeScene] = [:]
    /// Which view the volume shows. Follows the selection: the first view unless the person picked another.
    var shownUID: String?
    private(set) var isConnected = false
    private(set) var status = "Idle"
    /// Bumped whenever a scene is replaced, so the view can rebuild without diffing geometry.
    private(set) var revision = 0
    /// The browser viewer's explode and hidden parts, by view uid, mirrored onto the built scene.
    private(set) var viewStates: [String: ThreeViewState] = [:]
    /// Where handles are while another viewer drags them, by view uid then handle id; cleared by the
    /// next geometry of that view, which carries the box's answer.
    private(set) var liveHandles: [String: [String: SIMD3<Double>]] = [:]
    /// How many viewer volumes are on screen: the 3D View button opens one only when there is none.
    var openVolumes = 0

    private let server: ServerConfig
    private let trustDelegate = FieldServerTrustDelegate()
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var running = false
    private var reconnectAttempt = 0
    /// Newest description per uid; a fetch that lands after a newer one arrived is dropped.
    private var latest: [String: String] = [:]
    private var attached: Set<String> = []
    /// Arrays from earlier descriptions by content hash, per view: a description whose buffers are
    /// mostly unchanged fetches only the rest (`/three-buffers?only=`).
    private var held: [String: [String: Data]] = [:]
    /// Part styles that arrived ahead of their geometry (a replay), by view uid.
    private var pendingAppearance: [String: ThreeAppearanceMessage] = [:]
    /// What the last geometry update cost, for the status line.
    private(set) var lastFetch: (fetched: Int, reused: Int) = (0, 0)

    /// Restrict following to one document's selection; `nil` follows whatever was selected last.
    var document: String? = ProcessInfo.processInfo.environment["FIELD4_THREE_DOCUMENT"]

    init(server: ServerConfig = .default) { self.server = server }

    var shownScene: ThreeScene? { shownUID.flatMap { scenes[$0] } }
    var shownView: ThreeFollowMessage.View? { views.first { $0.uid == shownUID } }

    // MARK: - Lifecycle

    func start() {
        guard !running else { return }
        running = true
        connect()
    }

    func stop() {
        running = false
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        session?.invalidateAndCancel()
        session = nil
        isConnected = false
        status = "Stopped"
    }

    private func connect() {
        let session = URLSession(configuration: .default, delegate: trustDelegate, delegateQueue: nil)
        let task = session.webSocketTask(with: server.webSocketURL)
        task.maximumMessageSize = 64 << 20
        self.session = session
        self.task = task
        attached = []
        latest = [:]
        status = "Connecting…"
        task.resume()
        var follow: [String: String] = ["channel": "__threejs__", "debug_uid": "__follow__", "type": "follow"]
        if let document, !document.isEmpty { follow["document"] = document }
        send(follow)
        shellLog.notice("three: follow -> \(self.server.webSocketURL.absoluteString, privacy: .public)")
        receiveNext()
    }

    private func scheduleReconnect() {
        guard running else { return }
        isConnected = false
        reconnectAttempt += 1
        let delay = Swift.min(1.2 * Double(reconnectAttempt), 6.0)
        status = "Reconnecting in \(String(format: "%.1f", delay))s…"
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard let self, self.running else { return }
            self.connect()
        }
    }

    // MARK: - Receive

    private func receiveNext() {
        task?.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.running else { return }
                switch result {
                case .failure(let error):
                    shellLog.error("three ws receive failed: \(error.localizedDescription, privacy: .public)")
                    self.scheduleReconnect()
                case .success(let message):
                    self.reconnectAttempt = 0
                    if !self.isConnected { self.isConnected = true; self.status = "Connected" }
                    switch message {
                    case .string(let text): self.ingest(Data(text.utf8))
                    case .data(let data): self.ingest(data)
                    @unknown default: break
                    }
                    self.receiveNext()
                }
            }
        }
    }

    private static let decoder = JSONDecoder()

    private func ingest(_ data: Data) {
        // Field's other bus traffic arrives on this socket too; only two types are ours.
        guard let type = (try? Self.decoder.decode(ThreeWireEnvelope.self, from: data))?.type else { return }
        switch type {
        case "three/follow":
            if let follow = try? Self.decoder.decode(ThreeFollowMessage.self, from: data) { apply(follow) }
        case "three/view-state":
            if let m = try? Self.decoder.decode(ThreeViewStateMessage.self, from: data), attached.contains(m.uid) {
                viewStates[m.uid] = m.state
            }
        case "three/handle":
            if let m = try? Self.decoder.decode(ThreeHandleMessage.self, from: data), attached.contains(m.uid),
               m.position.count >= 3 {
                liveHandles[m.uid, default: [:]][m.id] = SIMD3(m.position[0], m.position[1], m.position[2])
            }
        case "three/appearance":
            if let m = try? Self.decoder.decode(ThreeAppearanceMessage.self, from: data), attached.contains(m.uid) {
                appearance(m)
            }
        case "three/geometry":
            do {
                let description = try Self.decoder.decode(ThreeWireDescription.self, from: data)
                receive(description)
            } catch {
                status = "Geometry: \(error)"
                shellLog.error("three: undecodable description: \(String(describing: error), privacy: .public)")
            }
        default:
            break
        }
    }

    private func apply(_ follow: ThreeFollowMessage) {
        selected = follow.selected
        if follow.views.isEmpty, !views.isEmpty {
            isHolding = true
            revision += 1
            return
        }
        isHolding = false
        views = follow.views
        let owner = follow.views.first?.box
        shownBoxName = follow.selected.first { $0.box == owner }?.name ?? follow.selected.last?.name
        let wanted = Set(follow.views.map(\.uid))
        for uid in attached.subtracting(wanted) {
            send(["channel": "__threejs__", "debug_uid": uid, "type": "detach"])
            scenes[uid] = nil
            latest[uid] = nil
            viewStates[uid] = nil
            held[uid] = nil
            pendingAppearance[uid] = nil
        }
        for uid in wanted.subtracting(attached) {
            send(["channel": "__threejs__", "debug_uid": uid, "type": "hello", "buffers": "1"])
        }
        attached = wanted
        if shownUID.map({ !wanted.contains($0) }) ?? true { shownUID = follow.views.first?.uid }
        status = follow.selected.isEmpty ? "Nothing selected in Field"
            : (follow.views.isEmpty ? "No 3D view on \(follow.selected.map(\.name).joined(separator: ", "))" : "Connected")
        revision += 1
    }

    private func receive(_ description: ThreeWireDescription) {
        guard attached.contains(description.uid) else { return }
        liveHandles[description.uid] = nil
        let key = description.buffers?.generation ?? description.revision ?? UUID().uuidString
        latest[description.uid] = key
        guard let table = description.buffers else {
            publish(description, bytes: Data())
            return
        }
        // Arrays already held, by hash, and the indices still to fetch.
        let have = held[description.uid] ?? [:]
        var missing: [Int] = []
        for (i, e) in table.entries.enumerated() where e.hash.flatMap({ have[$0] })?.count != e.count * Self.width(e.type) {
            missing.append(i)
        }
        if missing.isEmpty {
            publish(description, bytes: Self.assemble(table, have: have, missing: [], partial: Data()))
            lastFetch = (0, table.entries.count)
            return
        }
        guard let url = bufferURL(uid: description.uid, generation: table.generation,
                                  only: missing.count < table.entries.count ? missing : nil), let session else { return }
        Task { [weak self] in
            do {
                let (bytes, response) = try await session.data(from: url)
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                await MainActor.run {
                    guard let self, self.latest[description.uid] == key else { return }
                    // 404: superseded, and the description that replaced it is on its way.
                    if code == 404 { return }
                    guard code == 200 else { self.status = "Geometry buffers: HTTP \(code)"; return }
                    let full = missing.count < table.entries.count
                        ? Self.assemble(table, have: have, missing: missing, partial: bytes) : bytes
                    self.lastFetch = (bytes.count, table.entries.count - missing.count)
                    self.publish(description, bytes: full)
                }
            } catch {
                await MainActor.run { self?.status = "Geometry buffers: \(error.localizedDescription)" }
            }
        }
    }

    private func publish(_ description: ThreeWireDescription, bytes: Data) {
        do {
            var scene = try ThreeBinding.bind(description, bytes: bytes)
            // Styles a replay sent ahead of this geometry are this geometry's.
            if let early = pendingAppearance.removeValue(forKey: description.uid), early.revision == description.revision {
                scene = scene.styled(early.parts ?? [:], replace: true)
            }
            scenes[description.uid] = scene
            if let table = description.buffers {
                var next: [String: Data] = [:]
                for e in table.entries {
                    guard let h = e.hash else { continue }
                    let n = e.count * Self.width(e.type)
                    next[h] = bytes.subdata(in: e.offset..<(e.offset + n))
                }
                held[description.uid] = next
            }
            revision += 1
        } catch {
            status = "Geometry: \(error)"
        }
    }

    /// `receiveWireMessage` for a `three/appearance` message: merged into the styles (or replacing them)
    /// for the revision on show; one for another revision is stale, and the next geometry brings its own.
    private func appearance(_ m: ThreeAppearanceMessage) {
        if m.beforeGeometry == true { pendingAppearance[m.uid] = m; return }
        guard let scene = scenes[m.uid], scene.revision == m.revision else { return }
        scenes[m.uid] = scene.styled(m.parts ?? [:], replace: m.replace == true)
        revision += 1
    }

    /// Select a part in the browser viewer of the shown view, as a click there would: a pick on the
    /// headset. `nil` clears the selection.
    func select(part id: String?) {
        guard let uid = shownUID else { return }
        let command: [String: Any] = ["select": id ?? NSNull()]
        guard let data = try? JSONSerialization.data(withJSONObject: [
            "channel": "__threejs__", "debug_uid": uid, "type": "three-view-command", "command": command]),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { _ in }
        // Shown at once; the browser's next state confirms it.
        var state = viewStates[uid] ?? ThreeViewState()
        state.selected = id
        viewStates[uid] = state
    }

    /// Tell Field a handle of the shown view has been put at [position] (`three-handle`, as the browser sends).
    func dragHandle(_ id: String, to position: SIMD3<Double>, phase: String) {
        guard let uid = shownUID else { return }
        guard let data = try? JSONSerialization.data(withJSONObject: [
            "channel": "__threejs__", "debug_uid": uid, "type": "three-handle", "id": id,
            "position": [position.x, position.y, position.z], "phase": phase] as [String: Any]),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { _ in }
    }

    private static func width(_ type: String) -> Int {
        ["Float64Array": 8, "Float32Array": 4, "Uint32Array": 4, "Int32Array": 4,
         "Uint16Array": 2, "Int16Array": 2, "Uint8Array": 1, "Int8Array": 1][type] ?? 1
    }

    /// `wireAssemble`: the whole buffer table from held arrays and an `only=` response holding the
    /// missing ones, each padded to eight bytes from its start.
    private static func assemble(_ table: ThreeWireBuffers, have: [String: Data], missing: [Int], partial: Data) -> Data {
        var full = Data(count: table.bytes)
        let missingSet = Set(missing)
        for (i, e) in table.entries.enumerated() where !missingSet.contains(i) {
            if let h = e.hash, let bytes = have[h] { full.replaceSubrange(e.offset..<(e.offset + bytes.count), with: bytes) }
        }
        var at = 0
        for i in missing {
            let e = table.entries[i], n = e.count * width(e.type)
            at = (at + 7) / 8 * 8
            guard at + n <= partial.count else { break }
            full.replaceSubrange(e.offset..<(e.offset + n), with: partial.subdata(in: at..<(at + n)))
            at += n
        }
        return full
    }

    private func bufferURL(uid: String, generation: String, only: [Int]? = nil) -> URL? {
        var comps = URLComponents(url: server.baseURL.appendingPathComponent("three-buffers"), resolvingAgainstBaseURL: false)
        comps?.queryItems = [URLQueryItem(name: "uid", value: uid), URLQueryItem(name: "generation", value: generation)]
        if let only { comps?.queryItems?.append(URLQueryItem(name: "only", value: only.map(String.init).joined(separator: ","))) }
        return comps?.url
    }

    // MARK: - Send

    private func send(_ message: [String: String]) {
        guard let data = try? JSONSerialization.data(withJSONObject: message),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { error in
            if let error { shellLog.error("three ws send failed: \(error.localizedDescription, privacy: .public)") }
        }
    }
}

/// Accepts the Field server's self-signed dev cert for trusted hosts only (`isTrustedFieldDevHost`) —
/// the URLSession equivalent of `WebDock`'s trust override, for the websocket and `/three-buffers` alike.
final class FieldServerTrustDelegate: NSObject, URLSessionDelegate {
    func urlSession(_ session: URLSession,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              isTrustedFieldDevHost(challenge.protectionSpace.host)
        else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}
