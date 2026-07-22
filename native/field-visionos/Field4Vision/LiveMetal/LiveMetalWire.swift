import Foundation
import simd

enum LiveMetalWire {
    static let channel = "__visionmetal__"
    static let discoveryUID = "field4-vision-metal"
    static let version = 1
}

struct LiveMetalProgramPayload: Decodable, Sendable {
    let channel: String
    let type: String
    let version: Int
    let uid: String
    let revision: Int
    let shader: String
    let script: String
    let uniforms: [String]
    let vertices: [Float]
    let indices: [UInt32]
}

struct LiveMetalSubscribe: Encodable {
    let channel = LiveMetalWire.channel
    let type = "subscribe-all"
    let version = LiveMetalWire.version
    let debug_uid = LiveMetalWire.discoveryUID
}

struct LiveMetalStatusMessage: Encodable {
    let channel = LiveMetalWire.channel
    let type = "visionmetal/status"
    let version = LiveMetalWire.version
    let debug_uid = LiveMetalWire.discoveryUID
    let uid: String
    let revision: Int
    let phase: String
    let ok: Bool
    let message: String
}

/// A lock-protected latest-value mailbox between URLSession/MainActor and the render actor.
/// Revisions replace older pending work: shader edits never queue behind obsolete compilations.
final class LiveMetalMailbox: @unchecked Sendable {
    private let lock = NSLock()
    private var latest: LiveMetalProgramPayload?
    private var generation = 0

    func put(_ program: LiveMetalProgramPayload) {
        lock.lock()
        latest = program
        generation += 1
        lock.unlock()
    }

    func take(after appliedGeneration: Int) -> (generation: Int, program: LiveMetalProgramPayload)? {
        lock.lock()
        defer { lock.unlock() }
        guard let latest, generation > appliedGeneration else { return nil }
        return (generation, latest)
    }
}

struct LiveMetalVertex {
    var position: SIMD4<Float>
    var normal: SIMD4<Float>
    var color: SIMD4<Float>
}

extension LiveMetalProgramPayload {
    /// Wire vertices are ten floats each: position.xyz, normal.xyz, color.rgba.
    func decodedVertices() -> [LiveMetalVertex] {
        guard vertices.count >= 10, vertices.count % 10 == 0 else {
            return LiveMetalGeometry.defaultVertices
        }
        return stride(from: 0, to: vertices.count, by: 10).map { i in
            LiveMetalVertex(
                position: SIMD4(vertices[i], vertices[i + 1], vertices[i + 2], 1),
                normal: SIMD4(vertices[i + 3], vertices[i + 4], vertices[i + 5], 0),
                color: SIMD4(vertices[i + 6], vertices[i + 7], vertices[i + 8], vertices[i + 9])
            )
        }
    }

    func decodedIndices(vertexCount: Int) -> [UInt32] {
        guard !indices.isEmpty, indices.allSatisfy({ Int($0) < vertexCount }) else {
            return LiveMetalGeometry.defaultIndices
        }
        return indices
    }
}

enum LiveMetalGeometry {
    // A small, correctly wound cube just right of centre and 1.2m in front of the launch origin.
    // Each face has its own vertices so live fragment shaders receive useful normals and colours
    // immediately. Custom geometry is left in the caller's world coordinates.
    static let defaultVertices: [LiveMetalVertex] = {
        let s: Float = 0.18
        let x: Float = 0.45
        let z: Float = -1.2
        let faces: [(SIMD3<Float>, [SIMD3<Float>], SIMD4<Float>)] = [
            (SIMD3(0, 0, 1),  [SIMD3(-s,-s,s), SIMD3(s,-s,s), SIMD3(s,s,s), SIMD3(-s,s,s)], SIMD4(1,0.25,0.12,1)),
            (SIMD3(0, 0,-1),  [SIMD3(s,-s,-s), SIMD3(-s,-s,-s), SIMD3(-s,s,-s), SIMD3(s,s,-s)], SIMD4(0.1,0.7,1,1)),
            (SIMD3(1, 0, 0),  [SIMD3(s,-s,s), SIMD3(s,-s,-s), SIMD3(s,s,-s), SIMD3(s,s,s)], SIMD4(0.9,0.2,0.8,1)),
            (SIMD3(-1, 0, 0), [SIMD3(-s,-s,-s), SIMD3(-s,-s,s), SIMD3(-s,s,s), SIMD3(-s,s,-s)], SIMD4(0.2,1,0.5,1)),
            (SIMD3(0, 1, 0),  [SIMD3(-s,s,s), SIMD3(s,s,s), SIMD3(s,s,-s), SIMD3(-s,s,-s)], SIMD4(1,0.85,0.15,1)),
            (SIMD3(0,-1, 0),  [SIMD3(-s,-s,-s), SIMD3(s,-s,-s), SIMD3(s,-s,s), SIMD3(-s,-s,s)], SIMD4(0.5,0.35,1,1))
        ]
        return faces.flatMap { normal, corners, color in
            corners.map { p in
                LiveMetalVertex(position: SIMD4(p.x + x, p.y, p.z + z, 1),
                                normal: SIMD4(normal.x, normal.y, normal.z, 0), color: color)
            }
        }
    }()

    static let defaultIndices: [UInt32] = (0..<6).flatMap { face -> [UInt32] in
        let b = UInt32(face * 4)
        return [b, b + 1, b + 2, b, b + 2, b + 3]
    }
}
