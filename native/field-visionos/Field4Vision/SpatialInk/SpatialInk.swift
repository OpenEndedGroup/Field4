import ARKit
import Observation
import RealityKit
import SwiftUI

private struct InkPoint: Codable, Equatable {
    let x: Float
    let y: Float
    let z: Float

    init(_ position: SIMD3<Float>) {
        x = position.x
        y = position.y
        z = position.z
    }

    var position: SIMD3<Float> { SIMD3(x, y, z) }
}

private struct InkStroke: Codable, Equatable {
    let id: String
    let points: [InkPoint]
}

private struct InkSnapshot: Encodable {
    let revision: Int
    let strokes: [InkStroke]
}

private struct InkReply: Decodable {
    let revision: Int
    let edges: [InkStroke]
}

@MainActor
@Observable
final class SpatialInk {
    private(set) var isOpen = false
    private(set) var isArmed = false
    private(set) var canDraw = false
    private(set) var syncFailed = false
    private(set) var canUndo = false
    private(set) var status = "Open Spatial Ink to draw"
    let root = Entity()

    @ObservationIgnored private var session: ARKitSession?
    @ObservationIgnored private var network: URLSession?
    @ObservationIgnored private var socket: URLSessionWebSocketTask?
    @ObservationIgnored private var exchange: Task<Void, Never>?
    @ObservationIgnored private var strokes: [InkStroke] = []
    @ObservationIgnored private var fitted: [String: InkStroke] = [:]
    @ObservationIgnored private var entities: [String: Entity] = [:]
    @ObservationIgnored private var current: [InkPoint] = []
    @ObservationIgnored private var preview = Entity()
    @ObservationIgnored private var revision = 0
    @ObservationIgnored private var acknowledged = 0
    @ObservationIgnored private var waitForRelease = true
    @ObservationIgnored private var trackingTimeout: Task<Void, Never>?

    func track() async {
        isOpen = true
        guard HandTrackingProvider.isSupported else {
            status = "Hand tracking needs a Vision Pro"
            return
        }
        let provider = HandTrackingProvider()
        let tracking = ARKitSession()
        session = tracking
        do {
            try await tracking.run([provider])
            guard !Task.isCancelled, session === tracking else { return }
            canDraw = true
            status = "Arm Draw, then pinch to make a stroke"
            for await update in provider.anchorUpdates {
                guard !Task.isCancelled, session === tracking else { break }
                let hand = update.anchor
                guard hand.chirality == .right else { continue }
                guard update.event != .removed, hand.isTracked, let skeleton = hand.handSkeleton else {
                    loseTracking()
                    continue
                }
                let index = skeleton.joint(.indexFingerTip)
                let thumb = skeleton.joint(.thumbTip)
                guard index.isTracked, thumb.isTracked else { loseTracking(); continue }
                let tip = translation(hand.originFromAnchorTransform * index.anchorFromJointTransform)
                let opposingTip = translation(hand.originFromAnchorTransform * thumb.anchorFromJointTransform)
                input((tip + opposingTip) * 0.5, separation: simd_distance(tip, opposingTip))
            }
        } catch {
            if !Task.isCancelled, session === tracking {
                status = "Hand tracking unavailable: \(error.localizedDescription)"
            }
        }
        if session === tracking {
            arm(false)
            canDraw = false
        }
    }

    func arm(_ active: Bool) {
        guard isOpen, canDraw else { return }
        isArmed = active
        waitForRelease = true
        if !active { discardPreview() }
    }

    func undo() {
        discardPreview()
        waitForRelease = true
        guard let stroke = strokes.popLast() else { return }
        entities.removeValue(forKey: stroke.id)?.removeFromParent()
        fitted.removeValue(forKey: stroke.id)
        canUndo = !strokes.isEmpty
        submit()
    }

    func retry() {
        guard isOpen, syncFailed else { return }
        submit()
    }

    func close() {
        isOpen = false
        isArmed = false
        canDraw = false
        syncFailed = false
        canUndo = false
        session?.stop()
        session = nil
        disconnect()
        discardPreview()
        for entity in entities.values { entity.removeFromParent() }
        entities.removeAll()
        fitted.removeAll()
        strokes.removeAll()
        revision = 0
        acknowledged = 0
        waitForRelease = true
    }

    private func input(_ point: SIMD3<Float>, separation: Float) {
        guard isArmed else { return }
        if separation >= 0.027 {
            waitForRelease = false
            finishStroke()
            return
        }
        guard !waitForRelease, separation <= 0.018 || !current.isEmpty else { return }
        if current.isEmpty && strokes.count == 96 {
            waitForRelease = true
            status = "Session is full. Undo or reopen Spatial Ink."
            return
        }
        trackingTimeout?.cancel()
        trackingTimeout = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(220))
            guard !Task.isCancelled else { return }
            self?.loseTracking()
        }
        if let last = current.last {
            let distance = simd_distance(last.position, point)
            if distance > 0.09 { loseTracking(); return }
            guard distance >= 0.002 else { return }
            preview.addChild(segment(last.position, point, color: .lightGray))
        } else {
            root.addChild(preview)
        }
        current.append(InkPoint(point))
        if current.count == 384 {
            waitForRelease = true
            finishStroke()
            status = "Stroke length limit reached. Release to draw again."
        }
    }

    private func finishStroke() {
        guard current.count >= 2 else { discardPreview(); return }
        trackingTimeout?.cancel()
        trackingTimeout = nil
        let stroke = InkStroke(id: UUID().uuidString, points: current)
        strokes.append(stroke)
        entities[stroke.id] = preview
        preview = Entity()
        current.removeAll(keepingCapacity: true)
        canUndo = true
        submit()
    }

    private func loseTracking() {
        waitForRelease = true
        discardPreview()
    }

    private func discardPreview() {
        trackingTimeout?.cancel()
        trackingTimeout = nil
        preview.removeFromParent()
        preview = Entity()
        current.removeAll(keepingCapacity: true)
    }

    private func submit() {
        revision += 1
        syncFailed = false
        guard exchange == nil else { return }
        exchange = Task { [weak self] in await self?.sendSnapshots() }
    }

    private func sendSnapshots() async {
        guard isOpen, !Task.isCancelled else { return }
        defer { if !Task.isCancelled { exchange = nil } }
        do {
            let task = try connect()
            while acknowledged < revision, !Task.isCancelled {
                let snapshot = InkSnapshot(revision: revision, strokes: strokes)
                status = "Cleaning up on Field…"
                let data = try JSONEncoder().encode(snapshot)
                try await task.send(.string(String(decoding: data, as: UTF8.self)))
                guard case let .string(text) = try await task.receive() else { throw URLError(.cannotParseResponse) }
                try Task.checkCancellation()
                let reply = try JSONDecoder().decode(InkReply.self, from: Data(text.utf8))
                guard reply.revision == snapshot.revision else { throw URLError(.cannotParseResponse) }
                acknowledged = reply.revision
                if acknowledged == revision {
                    for edge in reply.edges where entities[edge.id] != nil && fitted[edge.id] != edge {
                        fitted[edge.id] = edge
                        entities[edge.id]?.removeFromParent()
                        let cleaned = Entity()
                        for (a, b) in zip(edge.points, edge.points.dropFirst()) {
                            cleaned.addChild(segment(a.position, b.position, color: .white))
                        }
                        root.addChild(cleaned)
                        entities[edge.id] = cleaned
                    }
                    status = "Saved on Field"
                }
            }
        } catch {
            guard !Task.isCancelled else { return }
            socket?.cancel(with: .goingAway, reason: nil)
            socket = nil
            network?.invalidateAndCancel()
            network = nil
            syncFailed = true
            status = "Field unavailable. Ink is still here."
        }
    }

    private func connect() throws -> URLSessionWebSocketTask {
        if let socket { return socket }
        var address = URLComponents(url: ServerConfig.default.webSocketURL, resolvingAgainstBaseURL: false)
        address?.path = "/spatial-ink/ws"
        guard let url = address?.url else { throw URLError(.badURL) }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        let connection = URLSession(configuration: configuration, delegate: FieldServerTrustDelegate(), delegateQueue: nil)
        let task = connection.webSocketTask(with: url)
        network = connection
        socket = task
        task.resume()
        return task
    }

    private func disconnect() {
        exchange?.cancel()
        exchange = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        network?.invalidateAndCancel()
        network = nil
    }

    private func translation(_ transform: simd_float4x4) -> SIMD3<Float> {
        let p = transform.columns.3
        return SIMD3(p.x, p.y, p.z)
    }

    private static let tube = MeshResource.generateCylinder(height: 1, radius: 0.0017)

    private func segment(_ a: SIMD3<Float>, _ b: SIMD3<Float>, color: UIColor) -> Entity {
        let delta = b - a
        let length = simd_length(delta)
        guard length > 1e-6 else { return Entity() }
        let model = ModelEntity(mesh: Self.tube, materials: [UnlitMaterial(color: color)])
        model.position = (a + b) * 0.5
        model.scale.y = length
        model.orientation = simd_quatf(from: SIMD3(0, 1, 0), to: delta / length)
        return model
    }
}

struct SpatialInkSpace: View {
    let ink: SpatialInk

    var body: some View {
        RealityView { content in content.add(ink.root) }
            .upperLimbVisibility(.visible)
            .task { await ink.track() }
            .onDisappear { ink.close() }
    }
}
