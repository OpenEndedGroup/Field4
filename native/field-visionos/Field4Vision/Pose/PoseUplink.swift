import ARKit
import CompositorServices
import Foundation
import OSLog
import simd

/// Publishes this head's pose to the Field backend on `__visionpose__`.
///
/// Deliberately **not** `@MainActor`: it is fed from a Compositor Services render thread, once per
/// frame, and hopping to the main actor to send a pose would put the tracking path behind whatever
/// SwiftUI is doing. `URLSession` is thread-safe, so the socket is driven straight from the caller's
/// thread under one small lock.
///
/// Latest-wins, never queued. A pose is only useful while it is the newest one, so when the socket is
/// still working on the previous frame this one is *dropped* rather than buffered — a queue here would
/// convert network jitter into a growing pose delay, which is exactly the failure that reads as "the
/// tracking feels laggy" and cannot be found by looking at the renderer.
final class PoseUplink: @unchecked Sendable {

    /// A frame's worth of samples, assembled across however many drawables the layout gives us
    /// (`.layered` = one drawable with both views, `.dedicated` = one per eye) and published once.
    private struct Pending {
        var frameIndex: UInt64
        var predictedDisplayTime: Double
        var trackingValid: Bool
        var originFromDevice: simd_float4x4
        var views: [PoseWireView]
        var controllers: [PoseWireController]
    }

    /// Tracked accessories, sampled at the same instant as the pose. See `ControllerTracker`.
    private let controllers = ControllerTracker()

    private let server: ServerConfig
    private let trustDelegate = FieldServerTrustDelegate()
    private let device = UUID().uuidString
    private let log = Logger(subsystem: "com.field4.vision", category: "pose")

    private let lock = NSLock()
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    private var sendInFlight = false
    private var running = false
    private var greeted = false
    private var pending: Pending?

    /// Counters for the log line below — the plan asks for instrumentation before optimisation, and
    /// "how many did we drop" is the first question anyone will ask about a latest-wins sender.
    private var sent: UInt64 = 0
    private var dropped: UInt64 = 0
    private var lastReportFrame: UInt64 = 0

    /// Publish every Nth frame. 1 is the intent; `FIELD4_POSE_DIVISOR` exists so a bandwidth or
    /// battery question can be answered by measurement instead of by argument.
    private let divisor: UInt64

    init(server: ServerConfig = .default) {
        self.server = server
        let raw = ProcessInfo.processInfo.environment["FIELD4_POSE_DIVISOR"].flatMap(UInt64.init) ?? 1
        self.divisor = max(1, raw)
    }

    // MARK: - Lifecycle

    func start(viewCount: Int, refreshHz: Double) {
        lock.lock()
        guard !running else { lock.unlock(); return }
        running = true
        lock.unlock()
        connect()
        greet(viewCount: viewCount, refreshHz: refreshHz)
        controllers.start()
    }

    func stop() {
        lock.lock()
        let wasRunning = running
        running = false
        let task = self.task
        let session = self.session
        self.task = nil
        self.session = nil
        self.pending = nil
        self.greeted = false
        let sent = self.sent, dropped = self.dropped
        lock.unlock()
        controllers.stop()
        guard wasRunning else { return }
        if let task, let text = encode(PoseBye(device: device)) {
            // Best effort: a `bye` that misses just leaves the Mac to time the head out.
            task.send(.string(text)) { _ in task.cancel(with: .goingAway, reason: nil) }
        } else {
            task?.cancel(with: .goingAway, reason: nil)
        }
        session?.finishTasksAndInvalidate()
        log.notice("pose uplink stopped: \(sent, privacy: .public) sent, \(dropped, privacy: .public) dropped")
    }

    private func connect() {
        let session = URLSession(configuration: .default, delegate: trustDelegate, delegateQueue: nil)
        let task = session.webSocketTask(with: server.webSocketURL)
        lock.lock()
        self.session = session
        self.task = task
        self.sendInFlight = false
        lock.unlock()
        task.resume()
        log.notice("pose uplink connecting to \(self.server.webSocketURL.absoluteString, privacy: .public)")
    }

    private func greet(viewCount: Int, refreshHz: Double) {
        let hello = PoseHello(device: device, name: PoseUplink.headName(),
                              viewCount: viewCount, refreshHz: refreshHz)
        guard let text = encode(hello) else { return }
        lock.lock(); let task = self.task; lock.unlock()
        task?.send(.string(text)) { [weak self] error in
            guard let self else { return }
            if let error {
                self.log.error("pose hello failed: \(error.localizedDescription, privacy: .public)")
            } else {
                self.lock.lock(); self.greeted = true; self.lock.unlock()
                self.reportSelfCheck()
            }
        }
    }

    /// Forward the launch-time media-wire parser verdict. This channel carries it because it is the
    /// only one that reaches the Mac: `os.Logger` goes to the unified log, and
    /// `simctl launch --console-pty` captures nothing from this app.
    private func reportSelfCheck() {
        let failures = RTTWireSelfCheck.failures
        let message = PoseSelfCheck(device: device, name: PoseUplink.headName(),
                                    ok: failures.isEmpty,
                                    detail: failures.joined(separator: ", "))
        guard let text = encode(message) else { return }
        lock.lock(); let task = self.task; lock.unlock()
        task?.send(.string(text)) { _ in }
    }

    private static func headName() -> String {
        ProcessInfo.processInfo.environment["FIELD4_POSE_NAME"] ?? ProcessInfo.processInfo.hostName
    }

    // MARK: - Per-frame collection

    /// Start a frame. Call once per compositor frame, before the per-drawable calls.
    func beginFrame(frameIndex: UInt64) {
        lock.lock()
        // Whatever the previous frame had assembled but never flushed is stale by definition.
        pending = nil
        if running, frameIndex % divisor == 0 {
            pending = Pending(frameIndex: frameIndex, predictedDisplayTime: 0,
                              trackingValid: false, originFromDevice: matrix_identity_float4x4,
                              views: [], controllers: [])
        }
        lock.unlock()
    }

    /// Record what one drawable was rendered with. Takes the values the renderer already computed
    /// rather than re-querying ARKit: the pose that goes up the wire must be *the same* pose the
    /// frame was drawn from, or the association the whole milestone rests on is a guess.
    func record(drawable: LayerRenderer.Drawable, deviceAnchor: DeviceAnchor?,
                originFromDevice: simd_float4x4, predictedDisplayTime: Double) {
        lock.lock()
        defer { lock.unlock() }
        guard var frame = pending else { return }
        frame.predictedDisplayTime = predictedDisplayTime
        frame.trackingValid = deviceAnchor != nil
        frame.originFromDevice = originFromDevice
        // Sampled at the pose's own predicted display time, so the input and the image it will act on
        // describe the same instant. Only for the first drawable of a frame — a `.dedicated` layout
        // calls this once per eye and the controllers have not moved between them.
        if frame.controllers.isEmpty {
            frame.controllers = controllers.snapshot(at: predictedDisplayTime)
        }
        for viewIndex in drawable.views.indices {
            frame.views.append(PoseWireView(
                transform: PoseWire.floats(drawable.views[viewIndex].transform),
                projection: PoseWire.floats(drawable.computeProjection(viewIndex: viewIndex))))
        }
        pending = frame
    }

    /// Send the frame assembled by the calls above. Call once, after the drawable loop.
    func endFrame(sentAt: Double) {
        lock.lock()
        guard running, let frame = pending, !frame.views.isEmpty else {
            pending = nil
            lock.unlock()
            return
        }
        pending = nil
        guard !sendInFlight, let task = self.task else {
            dropped += 1
            lock.unlock()
            return
        }
        sendInFlight = true
        let counters = (sent: sent, dropped: dropped, lastReport: lastReportFrame)
        lock.unlock()

        let wire = PoseWireFrame(
            device: device, frameIndex: frame.frameIndex,
            predictedDisplayTime: frame.predictedDisplayTime, sentAt: sentAt,
            trackingValid: frame.trackingValid,
            originFromDevice: PoseWire.floats(frame.originFromDevice),
            views: frame.views,
            controllers: frame.controllers)
        guard let text = encode(wire) else {
            lock.lock(); sendInFlight = false; lock.unlock()
            return
        }

        task.send(.string(text)) { [weak self] error in
            guard let self else { return }
            self.lock.lock()
            self.sendInFlight = false
            if error == nil { self.sent += 1 } else { self.dropped += 1 }
            let shouldReconnect = error != nil && self.running
            self.lock.unlock()
            if let error, shouldReconnect {
                self.log.error("pose send failed: \(error.localizedDescription, privacy: .public)")
                self.reconnect()
            }
        }

        // One line every ~900 frames (ten seconds at 90 Hz): enough to see the rate holding without
        // burning the log.
        if frame.frameIndex - counters.lastReport >= 900 {
            lock.lock(); lastReportFrame = frame.frameIndex; lock.unlock()
            log.notice("pose uplink: \(counters.sent, privacy: .public) sent, \(counters.dropped, privacy: .public) dropped")
        }
    }

    // MARK: - Plumbing

    private func reconnect() {
        lock.lock()
        guard running else { lock.unlock(); return }
        let stale = task
        let staleSession = session
        task = nil
        session = nil
        greeted = false
        lock.unlock()
        stale?.cancel(with: .goingAway, reason: nil)
        staleSession?.invalidateAndCancel()
        // A pose stream has nothing to catch up on, so there is no backoff ladder to climb: wait a
        // beat and try again with a fresh socket.
        DispatchQueue.global().asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self else { return }
            self.lock.lock(); let stillRunning = self.running; self.lock.unlock()
            guard stillRunning else { return }
            self.connect()
            self.greet(viewCount: 2, refreshHz: 90)
        }
    }

    private func encode<T: Encodable>(_ value: T) -> String? {
        guard let data = try? JSONEncoder().encode(value) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
