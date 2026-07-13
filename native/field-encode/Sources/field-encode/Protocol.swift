import Foundation

// MARK: - Control channel (stdin JSON, one command per line)

enum Command {
    case alloc(Alloc)
    case open(Open)
    case frame(Frame)
    case bitrate(Int) // kbps
    case selftest(SelfTest)
    case close

    /// Allocate a ring of IOSurfaces *here* and hand their global IDs back, so the producer can
    /// import them as the backing of its Vulkan RTTs. This is the fix for MoltenVK's export path
    /// producing pixelFormat=0 surfaces that CoreVideo can't wrap: a surface born via
    /// `IOSurfaceCreate` with a real pixelFormat (32BGRA) is CV-wrappable, and IOSurface is
    /// cross-process, so Field imports it by ID.
    struct Alloc: Decodable {
        var width: Int
        var height: Int
        var count: Int         // ring size (2–3 typical; 1 for a single-buffer bring-up)
    }

    struct Open: Decodable {
        var surfaces: [UInt32]? // legacy: global IOSurfaceIDs to look up. Omit/empty to use the
                                // ring created by a prior `alloc`.
        var width: Int
        var height: Int
        var fps: Int
        var bitrateKbps: Int
        var dest: String       // "host:port"
        var codec: String?     // "hevc" (default) | "h264"
        var idrIntervalMs: Int?
    }

    struct Frame: Decodable {
        var surface: Int       // index into Open.surfaces
        var seq: UInt32        // producer's monotonic frame counter
        var ptsMicros: UInt64
        var forceIdr: Bool?
    }

    /// Self-contained smoke test: the helper allocates its own IOSurfaces, fills them with an
    /// animated test pattern, and streams `frames` encoded frames — no producer/RTT needed.
    /// This is the "boot it and push packets into the void" path.
    struct SelfTest: Decodable {
        var width: Int = 1280
        var height: Int = 720
        var fps: Int = 60
        var bitrateKbps: Int = 20000
        var dest: String
        var codec: String? = nil
        var frames: Int? = nil       // default 120
        var idrIntervalMs: Int? = nil
    }
}

extension Command: Decodable {
    private enum Keys: String, CodingKey { case cmd, kbps }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Keys.self)
        switch try c.decode(String.self, forKey: .cmd) {
        case "alloc":    self = .alloc(try Alloc(from: decoder))
        case "open":     self = .open(try Open(from: decoder))
        case "frame":    self = .frame(try Frame(from: decoder))
        case "bitrate":  self = .bitrate(try c.decode(Int.self, forKey: .kbps))
        case "selftest": self = .selftest(try SelfTest(from: decoder))
        case "close":    self = .close
        case let other:
            throw DecodingError.dataCorruptedError(forKey: .cmd, in: c,
                debugDescription: "unknown cmd \(other)")
        }
    }
}

// MARK: - Event channel (stdout JSON, one event per line)

struct Event: Encodable {
    var ev: String
    var seq: UInt32?
    var bytes: Int?
    var msg: String?
    var ids: [UInt32]?      // for the "allocated" event: the ring's global IOSurfaceIDs
}

// MARK: - Media wire format (see WIRE_FORMAT.md)

enum Wire {
    static let version: UInt8 = 1
    static let headerSize = 20
    static let maxPayload = 1200

    static let flagKeyframe: UInt8   = 1 << 0
    static let flagFrameEnd: UInt8   = 1 << 1
    static let flagParamSets: UInt8  = 1 << 2

    static let codecHEVC: UInt8 = 0
    static let codecH264: UInt8 = 1

    static func header(flags: UInt8, codec: UInt8, frameSeq: UInt32,
                       fragIndex: UInt16, fragCount: UInt16, ptsMicros: UInt64) -> [UInt8] {
        var b = [UInt8]()
        b.reserveCapacity(headerSize)
        b.append(version)
        b.append(flags)
        b.append(codec)
        b.append(0) // reserved
        b.appendBE(frameSeq)
        b.appendBE(fragIndex)
        b.appendBE(fragCount)
        b.appendBE(ptsMicros)
        return b
    }
}

extension Array where Element == UInt8 {
    mutating func appendBE(_ v: UInt16) {
        append(UInt8((v >> 8) & 0xff)); append(UInt8(v & 0xff))
    }
    mutating func appendBE(_ v: UInt32) {
        for shift in stride(from: 24, through: 0, by: -8) { append(UInt8((v >> UInt32(shift)) & 0xff)) }
    }
    mutating func appendBE(_ v: UInt64) {
        for shift in stride(from: 56, through: 0, by: -8) { append(UInt8((v >> UInt64(shift)) & 0xff)) }
    }
}
