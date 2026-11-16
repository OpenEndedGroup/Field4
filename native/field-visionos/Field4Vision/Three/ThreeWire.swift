import Foundation
import simd

// The native mirror of Field's three.js wire (`src/jvmMain/kotlin/afield/graphics/three/ThreeWire.kt`
// and `ThreeScene.kt`). A `three/geometry` message is a *description*: every numeric array in it is
// `{array: [], buffer: i}`, and `buffers` says where entry `i` sits in one binary response from
// `/three-buffers?uid=&generation=`. `ThreeBinding` turns a description plus those bytes into
// `ThreeScene`, which is all the mesh builder reads.
//
// Only what the viewer draws is decoded; unknown keys are ignored, so the server can grow the wire
// without breaking this client.

// MARK: - Description

struct ThreeWireAttribute: Decodable {
    let itemSize: Int
    let type: String?
    let buffer: Int?
    /// Inline values, when the server sent them rather than a buffer reference (small arrays).
    let array: [Float]?
}

struct ThreeWireIndex: Decodable {
    let type: String?
    let buffer: Int?
    let array: [UInt32]?
}

struct ThreeWireGeometry: Decodable {
    let attributes: [String: ThreeWireAttribute]
    let index: ThreeWireIndex?
}

struct ThreeWireBounds: Decodable {
    let min: [Float]
    let max: [Float]
}

struct ThreeWireMaterial: Decodable {
    let color: [Float]?
    let roughness: Float?
    let metalness: Float?
    let emissive: [Float]?
    let emissiveIntensity: Float?
}

struct ThreeWireStyle: Decodable {
    /// `"surface"`, `"lines"` or `"points"`.
    let kind: String
    let doubleSided: Bool?
    let opacity: Float?
    let unlit: Bool?
    let flatShading: Bool?
    let material: ThreeWireMaterial?
}

struct ThreeWireDrawable: Decodable {
    let geometry: ThreeWireGeometry
    let style: ThreeWireStyle
    let label: String?
    let id: String?
    let group: Int?
    /// 16 floats, column-major. Absent is the identity.
    let transform: [Float]?
    let panel: Int?
    let callbackId: String?
    let pickable: Bool?
    let layer: [String]?
    let hidden: Bool?
}

/// The inspector block: assembly groups, how to explode them, and where each part sits in the Parts tree.
struct ThreeWireInspector: Decodable {
    struct Explode: Decodable {
        let amount: Double?
        /// `"radial"` (from the bounds centre) or `"axis"` (ranked along [axis]).
        let mode: String?
        let axis: [Float]?
        let sliderMax: Double?
    }
    let enabled: Bool?
    let groups: [String]?
    let explode: Explode?
    let units: String?
    /// Part id or group name → folder path, outermost first.
    let partPaths: [String: [String]]?
}

struct ThreeWireBufferEntry: Decodable {
    let type: String
    let offset: Int
    let count: Int
    let hash: String?
}

struct ThreeWireBuffers: Decodable {
    let generation: String
    let bytes: Int
    let entries: [ThreeWireBufferEntry]
}

struct ThreeWireDescription: Decodable {
    let type: String
    let uid: String
    let revision: String?
    let sourceBox: String?
    /// The FLine half: a scene's lines as segment pairs, fills as triangles, points.
    let lines: ThreeWireGeometry?
    let fills: ThreeWireGeometry?
    let points: ThreeWireGeometry?
    let bounds: ThreeWireBounds?
    let drawables: [ThreeWireDrawable]?
    let inspector: ThreeWireInspector?
    let buffers: ThreeWireBuffers?
}

// MARK: - Follow

/// `three/follow`: what the box selected in Field shows. Sent on `follow`, and again whenever the
/// selection or the set of the selected boxes' views changes.
struct ThreeFollowMessage: Decodable {
    struct Box: Decodable, Hashable { let box: String; let name: String }
    struct View: Decodable, Hashable { let uid: String; let box: String; let name: String }
    let type: String
    let selected: [Box]
    let views: [View]
}

/// `three/view-state`: what the browser viewer of this view in Field shows — its explode amount and
/// the drawable ids hidden in its Parts list. The headset mirrors it.
struct ThreeViewState: Decodable, Equatable {
    var explode: Float = 0
    var hidden: [String] = []
}

struct ThreeViewStateMessage: Decodable {
    let uid: String
    let state: ThreeViewState
}

/// Just enough to route a frame by its `type`.
struct ThreeWireEnvelope: Decodable { let type: String? }

// MARK: - Bound scene

/// One geometry with its arrays read out of the buffer response. Colours are RGB in `[0, 1]`.
struct ThreeGeometry {
    var positions: [SIMD3<Float>] = []
    var normals: [SIMD3<Float>]?
    var colors: [SIMD3<Float>]?
    var alpha: [Float]?
    /// Per-vertex explode offsets: at explode amount `t`, a vertex sits at `position + t * explode`.
    var explode: [SIMD3<Float>]?
    var indices: [UInt32]?
    var isEmpty: Bool { positions.isEmpty }
}

struct ThreeDrawable {
    enum Kind { case surface, lines, points }
    var kind: Kind
    var geometry: ThreeGeometry
    var style: ThreeWireStyle
    var id: String
    var label: String?
    var group: Int
    var transform: simd_float4x4
    var layer: [String]
    var hidden: Bool
    var callbackId: String
    var pickable: Bool
}

struct ThreeScene {
    let uid: String
    let revision: String?
    let generation: String?
    var drawables: [ThreeDrawable]
    var bounds: (min: SIMD3<Float>, max: SIMD3<Float>)?
    /// Present when the view has an inspector, which is what turns explode on.
    var inspector: ThreeWireInspector?
}

enum ThreeWireError: Error, CustomStringConvertible {
    case sizeMismatch(got: Int, expected: Int)
    case badEntry(Int)
    var description: String {
        switch self {
        case .sizeMismatch(let got, let expected): "geometry buffers are \(got) bytes; the description says \(expected)"
        case .badEntry(let i): "geometry buffer \(i) is out of range or of an unknown type"
        }
    }
}

// MARK: - Binding

/// `bindWireBuffers` in `threejs_viewer.html`, for Swift: point a description's references at the bytes.
enum ThreeBinding {
    static func bind(_ d: ThreeWireDescription, bytes: Data) throws -> ThreeScene {
        if let table = d.buffers, bytes.count != table.bytes {
            throw ThreeWireError.sizeMismatch(got: bytes.count, expected: table.bytes)
        }
        let reader = Reader(bytes: bytes, entries: d.buffers?.entries ?? [])

        var drawables: [ThreeDrawable] = []
        // The FLine half first, drawn as the web viewer draws it: under the drawables.
        if let g = d.fills, let geometry = try reader.geometry(g), !geometry.isEmpty {
            drawables.append(flineHalf(.surface, geometry, id: "__fills"))
        }
        if let g = d.lines, let geometry = try reader.geometry(g), !geometry.isEmpty {
            drawables.append(flineHalf(.lines, geometry, id: "__lines"))
        }
        if let g = d.points, let geometry = try reader.geometry(g), !geometry.isEmpty {
            drawables.append(flineHalf(.points, geometry, id: "__points"))
        }
        for (ordinal, w) in (d.drawables ?? []).enumerated() {
            guard let geometry = try reader.geometry(w.geometry), !geometry.isEmpty else { continue }
            let kind: ThreeDrawable.Kind = switch w.style.kind {
            case "lines": .lines
            case "points": .points
            default: .surface
            }
            drawables.append(ThreeDrawable(
                kind: kind, geometry: geometry, style: w.style,
                id: (w.id?.isEmpty == false ? w.id! : "drawable-\(ordinal)"),
                label: w.label, group: w.group ?? -1,
                transform: matrix(w.transform), layer: w.layer ?? [], hidden: w.hidden ?? false,
                callbackId: w.callbackId ?? "", pickable: w.pickable ?? false))
        }

        var bounds: (min: SIMD3<Float>, max: SIMD3<Float>)?
        if let b = d.bounds, b.min.count >= 3, b.max.count >= 3 {
            bounds = (SIMD3(b.min[0], b.min[1], b.min[2]), SIMD3(b.max[0], b.max[1], b.max[2]))
        } else {
            bounds = measuredBounds(drawables)
        }
        return ThreeScene(uid: d.uid, revision: d.revision, generation: d.buffers?.generation,
                          drawables: drawables, bounds: bounds,
                          inspector: d.inspector?.enabled == false ? nil : d.inspector)
    }

    private static func flineHalf(_ kind: ThreeDrawable.Kind, _ g: ThreeGeometry, id: String) -> ThreeDrawable {
        // FLine fills are translucent sheets with no inside; lines and points are unlit data colours.
        let style = ThreeWireStyle(kind: kind == .surface ? "surface" : (kind == .lines ? "lines" : "points"),
                                   doubleSided: true, opacity: 1, unlit: true, flatShading: false, material: nil)
        return ThreeDrawable(kind: kind, geometry: g, style: style, id: id, label: nil, group: -1,
                             transform: matrix_identity_float4x4, layer: [], hidden: false,
                             callbackId: "", pickable: false)
    }

    static func measuredBounds(_ drawables: [ThreeDrawable]) -> (min: SIMD3<Float>, max: SIMD3<Float>)? {
        var lo = SIMD3<Float>(repeating: .greatestFiniteMagnitude), hi = -lo
        var any = false
        for d in drawables {
            for p in d.geometry.positions {
                let w = d.transform * SIMD4(p, 1)
                let q = SIMD3(w.x, w.y, w.z)
                lo = simd_min(lo, q); hi = simd_max(hi, q); any = true
            }
        }
        return any ? (lo, hi) : nil
    }

    private static func matrix(_ m: [Float]?) -> simd_float4x4 {
        guard let m, m.count == 16 else { return matrix_identity_float4x4 }
        return simd_float4x4(columns: (SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                                       SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15])))
    }

    /// Reads typed little-endian arrays out of one `/three-buffers` response.
    private struct Reader {
        let bytes: Data
        let entries: [ThreeWireBufferEntry]

        func geometry(_ g: ThreeWireGeometry) throws -> ThreeGeometry? {
            guard let position = g.attributes["position"] else { return nil }
            var out = ThreeGeometry()
            out.positions = vec3(try floats(position))
            if let a = g.attributes["normal"] { out.normals = vec3(try floats(a)) }
            if let a = g.attributes["color"] {
                let values = try floats(a)
                // itemSize 3 or 4; normalised byte colours arrive already as 0…255 floats.
                let scale: Float = (a.type == "Uint8Array") ? 1 / 255 : 1
                let n = a.itemSize
                if n >= 3 {
                    out.colors = stride(from: 0, to: values.count - (n - 1), by: n).map {
                        SIMD3(values[$0], values[$0 + 1], values[$0 + 2]) * scale
                    }
                    if n == 4 { out.alpha = stride(from: 3, to: values.count, by: 4).map { values[$0] * scale } }
                }
            }
            if let a = g.attributes["alpha"] { out.alpha = try floats(a) }
            if let a = g.attributes["explode"] { out.explode = vec3(try floats(a)) }
            if let index = g.index {
                if let i = index.buffer { out.indices = try uints(i) } else { out.indices = index.array }
            }
            return out
        }

        private func vec3(_ v: [Float]) -> [SIMD3<Float>] {
            stride(from: 0, to: v.count - 2, by: 3).map { SIMD3(v[$0], v[$0 + 1], v[$0 + 2]) }
        }

        private func floats(_ a: ThreeWireAttribute) throws -> [Float] {
            guard let i = a.buffer else { return a.array ?? [] }
            let e = try entry(i)
            return try bytes.withUnsafeBytes { raw -> [Float] in
                let base = raw.baseAddress!.advanced(by: e.offset)
                switch e.type {
                case "Float32Array": return Array(UnsafeBufferPointer(start: base.assumingMemoryBound(to: Float.self), count: e.count))
                case "Float64Array": return UnsafeBufferPointer(start: base.assumingMemoryBound(to: Double.self), count: e.count).map(Float.init)
                case "Uint8Array": return UnsafeBufferPointer(start: base.assumingMemoryBound(to: UInt8.self), count: e.count).map(Float.init)
                case "Int8Array": return UnsafeBufferPointer(start: base.assumingMemoryBound(to: Int8.self), count: e.count).map(Float.init)
                case "Uint16Array": return UnsafeBufferPointer(start: base.assumingMemoryBound(to: UInt16.self), count: e.count).map(Float.init)
                case "Int16Array": return UnsafeBufferPointer(start: base.assumingMemoryBound(to: Int16.self), count: e.count).map(Float.init)
                case "Uint32Array": return UnsafeBufferPointer(start: base.assumingMemoryBound(to: UInt32.self), count: e.count).map(Float.init)
                case "Int32Array": return UnsafeBufferPointer(start: base.assumingMemoryBound(to: Int32.self), count: e.count).map(Float.init)
                default: throw ThreeWireError.badEntry(i)
                }
            }
        }

        private func uints(_ i: Int) throws -> [UInt32] {
            let e = try entry(i)
            return try bytes.withUnsafeBytes { raw -> [UInt32] in
                let base = raw.baseAddress!.advanced(by: e.offset)
                switch e.type {
                case "Uint32Array", "Int32Array": return Array(UnsafeBufferPointer(start: base.assumingMemoryBound(to: UInt32.self), count: e.count))
                case "Uint16Array": return UnsafeBufferPointer(start: base.assumingMemoryBound(to: UInt16.self), count: e.count).map(UInt32.init)
                case "Uint8Array": return UnsafeBufferPointer(start: base.assumingMemoryBound(to: UInt8.self), count: e.count).map(UInt32.init)
                default: throw ThreeWireError.badEntry(i)
                }
            }
        }

        private func entry(_ i: Int) throws -> ThreeWireBufferEntry {
            guard i >= 0, i < entries.count else { throw ThreeWireError.badEntry(i) }
            let e = entries[i]
            let width = ["Float64Array": 8, "Float32Array": 4, "Uint32Array": 4, "Int32Array": 4,
                         "Uint16Array": 2, "Int16Array": 2, "Uint8Array": 1, "Int8Array": 1][e.type]
            guard let width, e.offset >= 0, e.offset + e.count * width <= bytes.count else { throw ThreeWireError.badEntry(i) }
            return e
        }
    }
}
