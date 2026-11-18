import Foundation
import CryptoKit

/// The FLine subset of the AVP Three wire. Unknown fields are intentionally ignored.
struct FieldAttribute: Codable { var itemSize: Int; var type: String?; var buffer: Int?; var array: [Double]? }
struct FieldIndex: Codable { var type: String?; var buffer: Int?; var array: [Int]? }
struct FieldGeometry: Codable { var attributes: [String: FieldAttribute]; var index: FieldIndex? }
struct FieldBufferEntry: Codable { var type: String; var offset: Int; var count: Int; var hash: String? }
struct FieldBuffers: Codable { var generation: String; var bytes: Int; var entries: [FieldBufferEntry] }
struct FieldDrawableStyle: Codable { var kind: String; var opacity: Double? }
struct FieldDrawable: Codable { var geometry: FieldGeometry; var style: FieldDrawableStyle; var transform: [Double]?; var hidden: Bool? }
struct FieldGeometryDescription: Codable {
    var type: String
    var uid: String
    var revision: String?
    var sourceBox: String?
    var lines: FieldGeometry?
    var fills: FieldGeometry?
    var points: FieldGeometry?
    var drawables: [FieldDrawable]?
    var buffers: FieldBuffers?
}
struct FieldDrawingPlane: Codable {
    var origin: [Double]
    var xAxis: [Double]
    var yAxis: [Double]
    var scale: Double
    var offsetX: Double
    var offsetY: Double
    func validate() throws {
        guard [origin, xAxis, yAxis].allSatisfy({ $0.count == 3 && $0.allSatisfy(\.isFinite) }), scale.isFinite, scale > 0,
              offsetX.isFinite, offsetY.isFinite else { throw FieldReferenceError.invalid("Invalid drawing plane") }
        let cross = [xAxis[1]*yAxis[2]-xAxis[2]*yAxis[1], xAxis[2]*yAxis[0]-xAxis[0]*yAxis[2], xAxis[0]*yAxis[1]-xAxis[1]*yAxis[0]]
        guard cross.reduce(0, { $0 + $1*$1 }) > 1e-18 else { throw FieldReferenceError.invalid("Drawing plane axes must be independent") }
    }
}
struct FieldReferenceMessage: Codable {
    var type: String
    var target: String
    var title: String
    var plane: FieldDrawingPlane
    var geometry: FieldGeometryDescription
    var sha256: String
}
struct FieldReferenceRecord: Codable {
    var layer: String
    var target: String
    var title: String
    var plane: FieldDrawingPlane
    var geometry: FieldGeometryDescription
    var asset: String?
}
struct ReferenceVertex { var x: Double; var y: Double; var r: Double; var g: Double; var b: Double; var a: Double }
struct ReferenceBatch { var arity: Int; var vertices: [ReferenceVertex]; var indices: [Int] }
struct ReferenceDrawing { var batches: [ReferenceBatch] }

enum FieldReferenceError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { if case .invalid(let message) = self { return message }; return nil }
}

/// Validates and binds the same typed, little-endian buffer tables the AVP viewer consumes.
/// Projection is explicit; it never auto-frames or silently chooses a 3D axis.
enum FieldReferenceBinding {
    static let maximumBytes = 32 * 1024 * 1024
    static func bind(_ record: FieldReferenceRecord, bytes: Data) throws -> ReferenceDrawing {
        guard record.geometry.type == "three/geometry" else { throw FieldReferenceError.invalid("Unsupported Field geometry envelope") }
        try record.plane.validate()
        if let table = record.geometry.buffers {
            guard table.bytes == bytes.count, table.bytes <= maximumBytes, table.bytes >= 0 else { throw FieldReferenceError.invalid("Geometry buffer size mismatch") }
            for entry in table.entries {
                let width = try width(entry.type)
                guard entry.offset >= 0, entry.count >= 0, entry.count <= maximumBytes / width,
                      entry.offset <= bytes.count, entry.count * width <= bytes.count - entry.offset else { throw FieldReferenceError.invalid("Geometry buffer entry is outside its asset") }
            }
        } else if !bytes.isEmpty { throw FieldReferenceError.invalid("Unexpected geometry buffer data") }
        func values(_ buffer: Int?, _ inline: [Double]?) throws -> [Double] {
            guard let buffer else { return inline ?? [] }
            guard let table = record.geometry.buffers, table.entries.indices.contains(buffer) else { throw FieldReferenceError.invalid("Missing geometry buffer") }
            let entry = table.entries[buffer]
            let stride = try width(entry.type)
            return try bytes.withUnsafeBytes { raw in
                try (0..<entry.count).map { i in
                    let at = entry.offset + i * stride
                    switch entry.type {
                    case "Float32Array": return Double(Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: UInt32.self))))
                    case "Float64Array": return Double(bitPattern: UInt64(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: UInt64.self)))
                    case "Uint32Array": return Double(UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: UInt32.self)))
                    case "Int32Array": return Double(Int32(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: Int32.self)))
                    case "Uint16Array": return Double(UInt16(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: UInt16.self)))
                    case "Int16Array": return Double(Int16(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: Int16.self)))
                    case "Uint8Array": return Double(raw[at])
                    case "Int8Array": return Double(Int8(bitPattern: raw[at]))
                    default: throw FieldReferenceError.invalid("Unsupported geometry buffer type")
                    }
                }
            }
        }
        var batches: [ReferenceBatch] = []
        func append(_ geometry: FieldGeometry?, arity: Int, opacity: Double = 1, transform: [Double]? = nil) throws {
            guard let geometry, let position = geometry.attributes["position"] else { return }
            let xyz = try values(position.buffer, position.array)
            guard position.itemSize == 3, xyz.count % 3 == 0, xyz.count / 3 <= 500_000, xyz.allSatisfy(\.isFinite) else { throw FieldReferenceError.invalid("Invalid reference positions") }
            let color = geometry.attributes["color"], alpha = geometry.attributes["alpha"]
            let rgb = try color.map { try values($0.buffer, $0.array) } ?? []
            let alphas = try alpha.map { try values($0.buffer, $0.array) } ?? []
            guard color == nil || [3, 4].contains(color!.itemSize), alpha == nil || alpha!.itemSize == 1,
                  rgb.allSatisfy(\.isFinite), alphas.allSatisfy(\.isFinite), opacity.isFinite else { throw FieldReferenceError.invalid("Invalid reference colour") }
            guard color == nil || rgb.count == xyz.count / 3 * color!.itemSize,
                  alpha == nil || alphas.count == xyz.count / 3 else { throw FieldReferenceError.invalid("Reference colour count mismatch") }
            if let transform { guard transform.count == 16, transform.allSatisfy(\.isFinite) else { throw FieldReferenceError.invalid("Invalid reference transform") } }
            let vertices: [ReferenceVertex] = try stride(from: 0, to: xyz.count, by: 3).map { at in
                var p = Array(xyz[at..<(at+3)])
                if let m = transform {
                    let w = m[3]*p[0]+m[7]*p[1]+m[11]*p[2]+m[15]
                    guard w.isFinite, abs(w) > 1e-12 else { throw FieldReferenceError.invalid("Invalid homogeneous reference position") }
                    p = [(m[0]*p[0]+m[4]*p[1]+m[8]*p[2]+m[12])/w, (m[1]*p[0]+m[5]*p[1]+m[9]*p[2]+m[13])/w, (m[2]*p[0]+m[6]*p[1]+m[10]*p[2]+m[14])/w]
                }
                let plane = record.plane, delta = zip(p, plane.origin).map(-)
                let x = zip(delta, plane.xAxis).reduce(plane.offsetX) { $0 + $1.0*$1.1*plane.scale }
                let y = zip(delta, plane.yAxis).reduce(plane.offsetY) { $0 + $1.0*$1.1*plane.scale }
                guard x.isFinite, y.isFinite else { throw FieldReferenceError.invalid("Non-finite projected geometry") }
                let v = at / 3, c = v * (color?.itemSize ?? 3)
                let r = c < rgb.count ? rgb[c] : 1, g = c+1 < rgb.count ? rgb[c+1] : 1, b = c+2 < rgb.count ? rgb[c+2] : 1
                let a = v < alphas.count ? alphas[v] : ((color?.itemSize == 4 && c+3 < rgb.count) ? rgb[c+3] : 1)
                return ReferenceVertex(x: x, y: y, r: r, g: g, b: b, a: a * opacity)
            }
            let indexValues = try geometry.index.map { try values($0.buffer, $0.array?.map(Double.init)) }
            let indices: [Int]
            if let indexValues {
                guard indexValues.allSatisfy({ $0.isFinite && $0 >= 0 && $0 < Double(vertices.count) && $0.rounded() == $0 }) else { throw FieldReferenceError.invalid("Invalid reference indices") }
                indices = indexValues.map(Int.init)
            } else { indices = Array(vertices.indices) }
            guard indices.count % arity == 0 else { throw FieldReferenceError.invalid("Incomplete reference primitive") }
            batches.append(ReferenceBatch(arity: arity, vertices: vertices, indices: indices))
        }
        try append(record.geometry.fills, arity: 3)
        try append(record.geometry.lines, arity: 2)
        try append(record.geometry.points, arity: 1)
        for drawable in record.geometry.drawables ?? [] where drawable.hidden != true {
            let arity = drawable.style.kind == "lines" ? 2 : drawable.style.kind == "points" ? 1 : 3
            try append(drawable.geometry, arity: arity, opacity: drawable.style.opacity ?? 1, transform: drawable.transform)
        }
        return ReferenceDrawing(batches: batches)
    }
    private static func width(_ type: String) throws -> Int {
        switch type {
        case "Float64Array": return 8
        case "Float32Array", "Uint32Array", "Int32Array": return 4
        case "Uint16Array", "Int16Array": return 2
        case "Uint8Array", "Int8Array": return 1
        default: throw FieldReferenceError.invalid("Unknown geometry buffer type: \(type)")
        }
    }
}

/// Assets are retained on disk, and also chunked into the NDJSON journal so exports are self-contained.
final class FieldReferenceAssets {
    let directory: URL
    private var partial: [String: [Int: Data]] = [:]
    private var headers: [String: (Int, Int)] = [:]
    init(directory: URL? = nil) throws {
        self.directory = try directory ?? FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("Field assets")
        try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
    }
    static func hash(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
    private func url(_ hash: String) throws -> URL {
        guard hash.count == 64, hash.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) else { throw FieldReferenceError.invalid("Invalid reference asset hash") }
        return directory.appendingPathComponent(hash)
    }
    func put(_ bytes: Data) throws -> String {
        guard bytes.count <= FieldReferenceBinding.maximumBytes else { throw FieldReferenceError.invalid("Reference asset exceeds 32 MiB") }
        let hash = Self.hash(bytes), target = try url(hash)
        if !FileManager.default.fileExists(atPath: target.path) { try bytes.write(to: target, options: .atomic) }
        return hash
    }
    func read(_ hash: String) throws -> Data {
        let data = try Data(contentsOf: url(hash))
        guard Self.hash(data) == hash else { throw FieldReferenceError.invalid("Reference asset hash mismatch") }
        return data
    }
    func ingest(_ payload: [String: Any]) throws -> String? {
        guard let hash = payload["sha256"] as? String, let part = payload["part"] as? Int,
              let parts = payload["parts"] as? Int, let total = payload["bytes"] as? Int,
              parts > 0, parts <= 512, (0..<parts).contains(part), total >= 0, total <= FieldReferenceBinding.maximumBytes,
              let encoded = payload["data"] as? String, let data = Data(base64Encoded: encoded), data.count <= 128 * 1024 else { throw FieldReferenceError.invalid("Invalid reference asset chunk") }
        _ = try url(hash)
        if let old = headers[hash] { guard old == (parts, total) else { throw FieldReferenceError.invalid("Reference chunk header changed") } }
        headers[hash] = (parts, total); partial[hash, default: [:]][part] = data
        guard partial[hash]!.values.reduce(0, { $0 + $1.count }) <= total else {
            partial[hash] = nil; headers[hash] = nil
            throw FieldReferenceError.invalid("Reference chunks exceed declared size")
        }
        guard partial[hash]?.count == parts else { return nil }
        var bytes = Data()
        for i in 0..<parts { bytes.append(partial[hash]![i]!) }
        partial[hash] = nil; headers[hash] = nil
        guard bytes.count == total, Self.hash(bytes) == hash else { throw FieldReferenceError.invalid("Reference asset verification failed") }
        _ = try put(bytes)
        return hash
    }
}
