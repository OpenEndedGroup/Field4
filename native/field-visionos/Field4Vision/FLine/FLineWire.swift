import Foundation

/// Wire types for the native FLine viewer — the visionOS-native equivalent of Field's three.js
/// iframe viewer. It speaks the SAME `__threejs__` WebSocket protocol the iframe does; these structs
/// mirror the kotlinx-serialized payloads (`ThreeJsViewer.kt`) and the outbound edit/click messages
/// (`rpc.kt`). JSON keys are already camelCase on the wire, so property names match 1:1 (no CodingKeys).
///
/// Inbound geometry is pre-flattened into three.js-style buffers: `lines` are NON-indexed segment
/// PAIRS, `fills` are indexed triangles, `points` are one vertex per node. The `scene` block (only
/// present when the view was created `editable = true`) is the faithful FLine node structure WITH
/// thickness and cubic control points — the viewer prefers it (real connectivity + tube radius).

// MARK: - Inbound (server → viewer)

struct FLineAttribute: Decodable {
    let itemSize: Int
    let array: [Float]
    var type: String = "Float32Array"
    var normalized: Bool = false
}

struct FLineIndex: Decodable {
    let type: String
    let array: [UInt32]
}

struct FLineGeometryData: Decodable {
    let attributes: [String: FLineAttribute]
    let index: FLineIndex?

    var positions: [Float] { attributes["position"]?.array ?? [] }
    var colors: [Float]? { attributes["color"]?.array }
    var alphas: [Float]? { attributes["alpha"]?.array }
    var sizes: [Float]? { attributes["size"]?.array }
}

struct FLineBounds: Decodable {
    let min: [Float]
    let max: [Float]

    var center: SIMD3<Float> {
        SIMD3((min[0] + max[0]) * 0.5, (min[1] + max[1]) * 0.5, (min[2] + max[2]) * 0.5)
    }
    /// Longest edge of the box — used for bounds-relative radii and the auto-frame scale.
    var maxDimension: Float {
        Swift.max(max[0] - min[0], Swift.max(max[1] - min[1], max[2] - min[2]))
    }
    var diagonal: Float {
        let dx = max[0] - min[0], dy = max[1] - min[1], dz = max[2] - min[2]
        return (dx * dx + dy * dy + dz * dz).squareRoot()
    }
}

struct FLineMeta: Decodable {
    var lineSegments: Int = 0
    var triangles: Int = 0
    var points: Int = 0
}

struct FLineInteractiveVertex: Decodable {
    let index: Int
    let position: [Float]
}

struct FLineInteractiveObject: Decodable {
    let callbackId: String
    let hoverColor: [Float]?
    let lines: FLineGeometryData?
    let fills: FLineGeometryData?
    let points: FLineGeometryData?
    var vertices: [FLineInteractiveVertex] = []
}

struct FLineSceneNode: Decodable {
    let nodeIndex: Int
    /// "move" | "line" | "cubic" | "close"
    let kind: String
    // `var` so the viewer can apply optimistic local edits (drag) before the server reconciles.
    var to: [Float]?
    var c1: [Float]?
    var c2: [Float]?
}

struct FLineSceneLine: Decodable {
    let lineIndex: Int
    let color: [Float]
    let thickness: Float
    let stroked: Bool
    let filled: Bool
    let pointed: Bool
    let pointSize: Float
    var callbackId: String = ""
    let hoverColor: [Float]?
    var nodes: [FLineSceneNode]
}

struct FLineScene: Decodable {
    let sessionId: String
    var lines: [FLineSceneLine]
}

/// One full geometry push for a single view (keyed by `uid`).
struct FLinePayload: Decodable {
    var type: String = "three/geometry"
    let uid: String
    let lines: FLineGeometryData?
    let fills: FLineGeometryData?
    let points: FLineGeometryData?
    let bounds: FLineBounds?
    let meta: FLineMeta?
    var autoFrame: Bool = true
    var interactive: [FLineInteractiveObject] = []
    var scene: FLineScene?
    var editableEnabled: Bool = false
}

// MARK: - Outbound (viewer → server)

/// Channel constant + the fixed routing fields every outbound frame carries.
enum FLineWire {
    static let channel = "__threejs__"
    /// The `debug_uid` for a discovery client — the server's `subscribe-all` branch ignores its value.
    static let discoveryUID = "*"
}

/// `subscribe-all`: ask the server to stream every live view's payloads (see `installThreeJsHandler`).
struct FLineSubscribeAll: Encodable {
    let channel = FLineWire.channel
    let debug_uid = FLineWire.discoveryUID
    let type = "subscribe-all"
}

/// A tap on an interactive FLine → fires the server-registered `onClick` callback.
struct FLineClickMessage: Encodable {
    let channel = FLineWire.channel
    let debug_uid: String
    let type = "fline-click"
    let callbackId: String
    let closestVertexIndex: Int
    let x: Double
    let y: Double
    let z: Double
}

/// Which control point of a node an edit targets — mirrors `rpc.kt` `FLineHandleKind`.
enum FLineHandle: String, Encodable { case TO, C1, C2 }
/// Coordinate space of an edit delta — mirrors `rpc.kt` `FLineEditSpace`. We always send MODEL.
enum FLineSpace: String, Encodable { case MODEL, VIEW }

struct FLinePointRefMsg: Encodable {
    let lineIndex: Int
    let nodeIndex: Int
    let handle: FLineHandle
}

struct FLinePoint3Msg: Encodable {
    let x: Double
    let y: Double
    var z: Double = 0
}

/// `fline-edit-translate` — move one or more node handles by a delta (mirrors `FLineTranslatePoints`).
struct FLineTranslateMessage: Encodable {
    let channel = FLineWire.channel
    let debug_uid: String
    let type = "fline-edit-translate"
    let sessionId: String
    let points: [FLinePointRefMsg]
    let dx: Double
    let dy: Double
    let dz: Double
    var space: FLineSpace = .MODEL
}

/// `fline-edit-add-line` — append a whole polyline (mirrors `FLineAddLine`; server style defaults apply).
struct FLineAddLineMessage: Encodable {
    let channel = FLineWire.channel
    let debug_uid: String
    let type = "fline-edit-add-line"
    let sessionId: String
    let points: [FLinePoint3Msg]
    var closed: Bool = false
    var space: FLineSpace = .MODEL
}

/// `fline-edit-begin-line` — start a freehand line at a point (mirrors `FLineBeginLine`).
struct FLineBeginLineMessage: Encodable {
    let channel = FLineWire.channel
    let debug_uid: String
    let type = "fline-edit-begin-line"
    let sessionId: String
    let lineId: String
    let point: FLinePoint3Msg
    var space: FLineSpace = .MODEL
}

/// `fline-edit-append-line` — extend a freehand line (mirrors `FLineAppendLine`).
struct FLineAppendLineMessage: Encodable {
    let channel = FLineWire.channel
    let debug_uid: String
    let type = "fline-edit-append-line"
    let sessionId: String
    let lineId: String
    let points: [FLinePoint3Msg]
    var space: FLineSpace = .MODEL
}

/// `fline-edit-end-line` — finish a freehand line (mirrors `FLineEndLine`).
struct FLineEndLineMessage: Encodable {
    let channel = FLineWire.channel
    let debug_uid: String
    let type = "fline-edit-end-line"
    let sessionId: String
    let lineId: String
}

/// `fline-edit-clear` — drop all edits in the session (mirrors `FLineClearSession`).
struct FLineClearMessage: Encodable {
    let channel = FLineWire.channel
    let debug_uid: String
    let type = "fline-edit-clear"
    let sessionId: String
}
