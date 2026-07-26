import Foundation
import Observation

/// Native WebSocket client for the live Metal runtime. WKWebView is intentionally not involved:
/// Field source reaches the autonomous renderer directly over `/ws`.
@Observable
@MainActor
final class LiveMetalClient {
    let mailbox = LiveMetalMailbox()

    private(set) var isConnected = false
    private(set) var status = "Idle"
    private(set) var activeUID = ""
    private(set) var activeRevision = 0
    private(set) var immersiveRequested = false

    private let server: ServerConfig
    private let trustDelegate = FieldServerTrustDelegate()
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var running = false
    private var reconnectAttempt = 0

    init(server: ServerConfig = .default) {
        self.server = server
    }

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
        send(LiveMetalSubscribe())
        receiveNext()
    }

    private func receiveNext() {
        task?.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.running else { return }
                switch result {
                case .failure(let error):
                    self.status = error.localizedDescription
                    self.scheduleReconnect()
                case .success(let message):
                    self.isConnected = true
                    self.reconnectAttempt = 0
                    let data: Data
                    switch message {
                    case .string(let text): data = Data(text.utf8)
                    case .data(let bytes): data = bytes
                    @unknown default: self.receiveNext(); return
                    }
                    self.accept(data)
                    self.receiveNext()
                }
            }
        }
    }

    private func accept(_ data: Data) {
        guard let payload = try? JSONDecoder().decode(LiveMetalProgramPayload.self, from: data),
              payload.channel == LiveMetalWire.channel,
              payload.type == "visionmetal/program",
              payload.version == LiveMetalWire.version else { return }
        activeUID = payload.uid
        activeRevision = payload.revision
        // Name the parts that actually arrived: an explicit shader reload should read as one, not as
        // an indistinguishable "received program".
        var parts: [String] = []
        if payload.shader != nil { parts.append("msl") }
        if payload.script != nil { parts.append("script") }
        if payload.uniforms != nil { parts.append("uniforms") }
        if payload.geometry != nil { parts.append("geometry") }
        status = "Received \(payload.uid) r\(payload.revision) [\(parts.joined(separator: "+"))]"
        mailbox.put(payload)
    }

    private func scheduleReconnect() {
        guard running else { return }
        isConnected = false
        reconnectAttempt += 1
        let delay = Swift.min(1.2 * Double(reconnectAttempt), 6.0)
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard let self, self.running else { return }
            self.connect()
        }
    }

    private func send<T: Encodable>(_ value: T) {
        guard let data = try? JSONEncoder().encode(value),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor in self?.status = error.localizedDescription }
        }
    }

    func report(uid: String, revision: Int, phase: String, ok: Bool, message: String, line: Int = 0) {
        status = ok ? message : "\(phase): \(message)"
        let where_ = line > 0 ? ":\(line)" : ""
        if ok {
            shellLog.notice("LiveMetal \(uid, privacy: .public) r\(revision) \(phase, privacy: .public)\(where_, privacy: .public): \(message, privacy: .public)")
        } else {
            shellLog.error("LiveMetal \(uid, privacy: .public) r\(revision) \(phase, privacy: .public)\(where_, privacy: .public): \(message, privacy: .public)")
        }
        send(LiveMetalStatusMessage(uid: uid, revision: revision, phase: phase, ok: ok, message: message, line: line))
    }

    /// App windows share this client. Only one of their `.task` hooks may request the immersive
    /// space when simulator/device restoration recreates several windows at once.
    func claimImmersiveRequest() -> Bool {
        guard !immersiveRequested else { return false }
        immersiveRequested = true
        return true
    }

    func clearImmersiveRequest() {
        immersiveRequested = false
    }
}
