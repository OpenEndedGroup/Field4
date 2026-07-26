import Foundation
import simd

enum LiveMetalWire {
    static let channel = "__visionmetal__"
    static let discoveryUID = "field4-vision-metal"

    /// Version 2 splits the program into independently revisioned parts — shader, script, uniforms,
    /// geometry — so reloading one leaves the others resident. A version-1 server cannot drive this
    /// build, and vice versa; the two ship together from the field4 repository.
    static let version = 2

    /// Floats per wire vertex, matching `MemoryLayout<LiveMetalVertex>.stride / 4`. The wire array is
    /// copied straight into the vertex buffer.
    static let vertexFloats = 16

    static let maxUniforms = 16
}

struct LiveMetalShaderPart: Decodable, Sendable {
    let revision: Int
    let source: String
}

struct LiveMetalScriptPart: Decodable, Sendable {
    let revision: Int
    let source: String
    let launchable: Bool
}

struct LiveMetalUniformsPart: Decodable, Sendable {
    let revision: Int
    let names: [String]
}

/// Who owns the resident vertex buffer.
enum LiveMetalGeometryOrigin: String, Decodable, Sendable {
    /// The `vertices`/`indices` arrays in this payload.
    case wire
    /// This head's built-in cube — an explicit request, not an empty-geometry fallback.
    case builtin
    /// Whatever the box's Kotlin/JS `geometry { … }` builder uploads across the JS bridge.
    case script
}

struct LiveMetalGeometryPart: Decodable, Sendable {
    let revision: Int
    let origin: LiveMetalGeometryOrigin
    let vertices: [Float]
    let indices: [UInt32]

    enum CodingKeys: String, CodingKey { case revision, origin, vertices, indices }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        revision = try container.decode(Int.self, forKey: .revision)
        origin = try container.decode(LiveMetalGeometryOrigin.self, forKey: .origin)
        vertices = try container.decodeIfPresent([Float].self, forKey: .vertices) ?? []
        indices = try container.decodeIfPresent([UInt32].self, forKey: .indices) ?? []
    }
}

/// One program update. Absent parts are unchanged; a shader reload carries `shader` and nothing else.
struct LiveMetalProgramPayload: Decodable, Sendable {
    let channel: String
    let type: String
    let version: Int
    let uid: String
    let revision: Int
    let shader: LiveMetalShaderPart?
    let script: LiveMetalScriptPart?
    let uniforms: LiveMetalUniformsPart?
    let geometry: LiveMetalGeometryPart?
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
    /// One-based line within the part named by `phase`, when one could be recovered. Metal reports
    /// user-relative lines because the shader preamble ends with `#line 1`.
    let line: Int
}

/// Which part generations the render loop has already applied.
struct LiveMetalAppliedParts: Sendable {
    var shader = 0
    var script = 0
    var uniforms = 0
    var geometry = 0
}

/// The parts newer than what the renderer holds, plus the program identity they arrived under.
struct LiveMetalPendingParts: Sendable {
    var uid: String
    var revision: Int
    var applied: LiveMetalAppliedParts
    var shader: LiveMetalShaderPart?
    var script: LiveMetalScriptPart?
    var uniforms: LiveMetalUniformsPart?
    var geometry: LiveMetalGeometryPart?

    var isEmpty: Bool { shader == nil && script == nil && uniforms == nil && geometry == nil }
}

/// A lock-protected latest-value mailbox between URLSession/MainActor and the render actor.
///
/// Each part is stored separately under its own generation, so a shader-only edit never discards the
/// resident script or geometry, and a burst of shader edits collapses to the newest one rather than
/// queueing obsolete compilations behind it.
final class LiveMetalMailbox: @unchecked Sendable {
    private let lock = NSLock()
    private var generation = 0
    private var uid = ""
    private var revision = 0
    private var shader: (generation: Int, value: LiveMetalShaderPart)?
    private var script: (generation: Int, value: LiveMetalScriptPart)?
    private var uniforms: (generation: Int, value: LiveMetalUniformsPart)?
    private var geometry: (generation: Int, value: LiveMetalGeometryPart)?

    func put(_ payload: LiveMetalProgramPayload) {
        lock.lock()
        defer { lock.unlock() }
        // A different program replaces every part: parts left over from the previous box would
        // otherwise be silently mixed into it.
        if payload.uid != uid {
            uid = payload.uid
            shader = nil
            script = nil
            uniforms = nil
            geometry = nil
        }
        revision = payload.revision
        if let part = payload.shader {
            generation += 1
            shader = (generation, part)
        }
        if let part = payload.script {
            generation += 1
            script = (generation, part)
        }
        if let part = payload.uniforms {
            generation += 1
            uniforms = (generation, part)
        }
        if let part = payload.geometry {
            generation += 1
            geometry = (generation, part)
        }
    }

    func take(after applied: LiveMetalAppliedParts) -> LiveMetalPendingParts? {
        lock.lock()
        defer { lock.unlock() }
        var next = LiveMetalPendingParts(uid: uid, revision: revision, applied: applied)
        if let shader, shader.generation > applied.shader {
            next.shader = shader.value
            next.applied.shader = shader.generation
        }
        if let script, script.generation > applied.script {
            next.script = script.value
            next.applied.script = script.generation
        }
        if let uniforms, uniforms.generation > applied.uniforms {
            next.uniforms = uniforms.value
            next.applied.uniforms = uniforms.generation
        }
        if let geometry, geometry.generation > applied.geometry {
            next.geometry = geometry.value
            next.applied.geometry = geometry.generation
        }
        return next.isEmpty ? nil : next
    }
}

/// Four `float4`s: position, normal, colour, then uv plus two free per-vertex channels. The wire
/// layout is identical, so decoding is a copy.
struct LiveMetalVertex {
    var position: SIMD4<Float>
    var normal: SIMD4<Float>
    var color: SIMD4<Float>
    var uv: SIMD4<Float>
}

enum LiveMetalGeometryDecoding {
    /// Reinterpret a packed wire array. Returns nil when the array is not a whole number of
    /// vertices, so the caller can report it rather than draw garbage.
    static func vertices(from floats: [Float]) -> [LiveMetalVertex]? {
        guard !floats.isEmpty, floats.count % LiveMetalWire.vertexFloats == 0 else { return nil }
        return stride(from: 0, to: floats.count, by: LiveMetalWire.vertexFloats).map { i in
            LiveMetalVertex(
                position: SIMD4(floats[i], floats[i + 1], floats[i + 2], floats[i + 3]),
                normal: SIMD4(floats[i + 4], floats[i + 5], floats[i + 6], floats[i + 7]),
                color: SIMD4(floats[i + 8], floats[i + 9], floats[i + 10], floats[i + 11]),
                uv: SIMD4(floats[i + 12], floats[i + 13], floats[i + 14], floats[i + 15])
            )
        }
    }

    /// Reject out-of-range or partial-triangle index arrays before they reach the GPU.
    static func indices(from indices: [UInt32], vertexCount: Int) -> [UInt32]? {
        guard !indices.isEmpty, indices.count % 3 == 0 else { return nil }
        guard indices.allSatisfy({ Int($0) < vertexCount }) else { return nil }
        return indices
    }
}

enum LiveMetalGeometry {
    // A small, correctly wound cube 1.2m in front of the launch origin and slightly to the right.
    // Each face has its own vertices so live shaders receive useful normals and colours immediately.
    // Requested explicitly via `geometry = BUILTIN`; it is no longer an empty-geometry fallback.
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
        let corners: [SIMD2<Float>] = [SIMD2(0, 0), SIMD2(1, 0), SIMD2(1, 1), SIMD2(0, 1)]
        return faces.flatMap { normal, cornerPositions, color in
            cornerPositions.enumerated().map { index, p in
                LiveMetalVertex(position: SIMD4(p.x + x, p.y, p.z + z, 1),
                                normal: SIMD4(normal.x, normal.y, normal.z, 0),
                                color: color,
                                uv: SIMD4(corners[index].x, corners[index].y, 0, 0))
            }
        }
    }()

    static let defaultIndices: [UInt32] = (0..<6).flatMap { face -> [UInt32] in
        let b = UInt32(face * 4)
        return [b, b + 1, b + 2, b, b + 2, b + 3]
    }
}
