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

    private let server: ServerConfig
    private let trustDelegate = FieldServerTrustDelegate()
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var running = false
    private var reconnectAttempt = 0
    /// Newest description per uid; a fetch that lands after a newer one arrived is dropped.
    private var latest: [String: String] = [:]
    private var attached: Set<String> = []

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
        let key = description.buffers?.generation ?? description.revision ?? UUID().uuidString
        latest[description.uid] = key
        guard let table = description.buffers else {
            publish(description, bytes: Data())
            return
        }
        guard let url = bufferURL(uid: description.uid, generation: table.generation), let session else { return }
        Task { [weak self] in
            do {
                let (bytes, response) = try await session.data(from: url)
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                await MainActor.run {
                    guard let self, self.latest[description.uid] == key else { return }
                    // 404: superseded, and the description that replaced it is on its way.
                    if code == 404 { return }
                    guard code == 200 else { self.status = "Geometry buffers: HTTP \(code)"; return }
                    self.publish(description, bytes: bytes)
                }
            } catch {
                await MainActor.run { self?.status = "Geometry buffers: \(error.localizedDescription)" }
            }
        }
    }

    private func publish(_ description: ThreeWireDescription, bytes: Data) {
        do {
            scenes[description.uid] = try ThreeBinding.bind(description, bytes: bytes)
            revision += 1
        } catch {
            status = "Geometry: \(error)"
        }
    }

    private func bufferURL(uid: String, generation: String) -> URL? {
        var comps = URLComponents(url: server.baseURL.appendingPathComponent("three-buffers"), resolvingAgainstBaseURL: false)
        comps?.queryItems = [URLQueryItem(name: "uid", value: uid), URLQueryItem(name: "generation", value: generation)]
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
