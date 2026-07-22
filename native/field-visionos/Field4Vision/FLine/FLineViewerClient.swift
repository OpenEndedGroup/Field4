import Foundation
import Observation

/// The native FLine viewer's connection to Field. It is a **thin discovery client** on the same
/// `/ws` bus the rest of Field uses: it sends one `subscribe-all` frame and then receives every live
/// three-geometry view's payload (each keyed by `uid`), exactly the data the three.js iframe gets.
/// Outbound clicks/edits go back on the same socket.
///
/// Mirrors the shell's other RPC clients in spirit (see the RTT half): `@Observable @MainActor`,
/// reconnecting, and trusting the Field server's self-signed dev cert via `isTrustedFieldDevHost`.
@Observable
@MainActor
final class FLineViewerClient {
    /// Live views by uid. Rebuilt in place as payloads arrive; the viewer rebuilds meshes on change.
    private(set) var views: [String: FLinePayload] = [:]
    /// Uids in first-seen order — a stable ordering for the multi-view picker.
    private(set) var order: [String] = []
    /// The uid currently shown. Defaults to the first view seen; cleared views fall back to another.
    var selectedUID: String?
    private(set) var isConnected = false
    private(set) var status = "Idle"

    /// A monotonically bumped token so SwiftUI can cheaply detect "the selected payload changed"
    /// without diffing the whole geometry (see `FLineViewerView`).
    private(set) var revision = 0

    private let server: ServerConfig
    private let trustDelegate = FieldServerTrustDelegate()
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var running = false
    private var reconnectAttempt = 0

    init(server: ServerConfig = .default) {
        self.server = server
    }

    var selectedPayload: FLinePayload? {
        guard let uid = selectedUID else { return nil }
        return views[uid]
    }

    /// The uid to stamp on outbound edit/click frames — the shown view.
    private var outboundUID: String { selectedUID ?? FLineWire.discoveryUID }

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
        let config = URLSessionConfiguration.default
        let session = URLSession(configuration: config, delegate: trustDelegate, delegateQueue: nil)
        let task = session.webSocketTask(with: server.webSocketURL)
        self.session = session
        self.task = task
        status = "Connecting…"
        task.resume()
        send(FLineSubscribeAll())
        shellLog.notice("FLine: subscribe-all -> \(self.server.webSocketURL.absoluteString, privacy: .public)")
        receiveNext()
    }

    private func scheduleReconnect() {
        guard running else { return }
        isConnected = false
        reconnectAttempt += 1
        // Backoff like the JS viewer's 1.2s, growing to a few seconds.
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
                    shellLog.error("FLine ws receive failed: \(error.localizedDescription, privacy: .public)")
                    self.scheduleReconnect()
                case .success(let message):
                    self.reconnectAttempt = 0
                    if !self.isConnected {
                        self.isConnected = true
                        self.status = "Connected"
                    }
                    switch message {
                    case .string(let text):
                        self.ingest(Data(text.utf8))
                    case .data(let data):
                        self.ingest(data)
                    @unknown default:
                        break
                    }
                    self.receiveNext()
                }
            }
        }
    }

    private static let decoder = JSONDecoder()

    /// Decode a frame; keep it only if it's a `three/geometry` payload (other bus traffic lacks a
    /// `uid` and fails to decode → silently ignored).
    private func ingest(_ data: Data) {
        guard let payload = try? Self.decoder.decode(FLinePayload.self, from: data),
              payload.type == "three/geometry" else { return }
        if views[payload.uid] == nil { order.append(payload.uid) }
        views[payload.uid] = payload
        if selectedUID == nil { selectedUID = payload.uid }
        revision += 1
    }

    /// Forget a view that no longer publishes (or after a Clear). Keeps the picker honest.
    func removeView(_ uid: String) {
        views[uid] = nil
        order.removeAll { $0 == uid }
        if selectedUID == uid { selectedUID = order.first }
        revision += 1
    }

    // MARK: - Outbound

    private static let encoder = JSONEncoder()

    private func send<T: Encodable>(_ message: T) {
        guard let data = try? Self.encoder.encode(message),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { error in
            if let error { shellLog.error("FLine ws send failed: \(error.localizedDescription, privacy: .public)") }
        }
    }

    func sendClick(callbackId: String, closestVertexIndex: Int, position: SIMD3<Float>) {
        send(FLineClickMessage(debug_uid: outboundUID, callbackId: callbackId,
                               closestVertexIndex: closestVertexIndex,
                               x: Double(position.x), y: Double(position.y), z: Double(position.z)))
    }

    func sendTranslate(sessionId: String, points: [FLinePointRefMsg], delta: SIMD3<Float>) {
        send(FLineTranslateMessage(debug_uid: outboundUID, sessionId: sessionId, points: points,
                                   dx: Double(delta.x), dy: Double(delta.y), dz: Double(delta.z)))
    }

    func sendAddLine(sessionId: String, points: [SIMD3<Float>], closed: Bool = false) {
        let pts = points.map { FLinePoint3Msg(x: Double($0.x), y: Double($0.y), z: Double($0.z)) }
        send(FLineAddLineMessage(debug_uid: outboundUID, sessionId: sessionId, points: pts, closed: closed))
    }

    func sendBeginLine(sessionId: String, lineId: String, point: SIMD3<Float>) {
        send(FLineBeginLineMessage(debug_uid: outboundUID, sessionId: sessionId, lineId: lineId,
                                   point: FLinePoint3Msg(x: Double(point.x), y: Double(point.y), z: Double(point.z))))
    }

    func sendAppendLine(sessionId: String, lineId: String, points: [SIMD3<Float>]) {
        let pts = points.map { FLinePoint3Msg(x: Double($0.x), y: Double($0.y), z: Double($0.z)) }
        send(FLineAppendLineMessage(debug_uid: outboundUID, sessionId: sessionId, lineId: lineId, points: pts))
    }

    func sendEndLine(sessionId: String, lineId: String) {
        send(FLineEndLineMessage(debug_uid: outboundUID, sessionId: sessionId, lineId: lineId))
    }

    func sendClear(sessionId: String) {
        send(FLineClearMessage(debug_uid: outboundUID, sessionId: sessionId))
    }
}

/// Session delegate that accepts the Field server's self-signed dev cert for trusted hosts only
/// (`isTrustedFieldDevHost`) — the WebSocket equivalent of `WebDock`'s trust override. Kept a plain
/// `NSObject` (not the `@Observable @MainActor` client) so URLSession's off-main delegate callbacks
/// have a clean, non-isolated home.
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
