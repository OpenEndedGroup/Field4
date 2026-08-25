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
        var stereo: Bool?      // frames are side-by-side stereo → set flagStereoSbs on the wire
    }

    struct Frame: Decodable {
        var surface: Int       // index into Open.surfaces
        var seq: UInt32        // producer's monotonic frame counter
        var ptsMicros: UInt64
        var forceIdr: Bool?
        // Which head pose this frame was rendered from, when the producer knows. Both present or
        // both absent; absent means "not rendered for a tracked head" and the wire says so with a
        // cleared flagPoseValid rather than with a magic value.
        var poseFrameId: UInt64?
        var poseDisplayTimeNanos: UInt64?
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

/// The pose a frame was rendered from, as it rides the wire.
///
/// `displayTimeNanos` is the head's *own* clock, because that is the only clock that can be used:
/// the receiver hands it straight back to ARKit as `queryDeviceAnchor(atTimestamp:)`, which is what
/// lets it reproject the frame to where the head actually is. `frameId` is the head's compositor
/// frame index, carried for association and logging — the timestamp is the operative field.
struct PoseTag {
    var frameId: UInt64
    var displayTimeNanos: UInt64
}

enum Wire {
    /// **Version 2** adds the 16-byte pose tail (see `header`). Version 1 remains a valid stream and
    /// the visionOS receiver still parses it: the Linux/NVENC sender has no uplinked pose to report
    /// (its Linux host tracks locally through OpenXR) and stays on v1 deliberately. So this is not a
    /// migration with an end date — both versions are live, and a v1 stream simply carries no pose.
    static let version: UInt8 = 2
    static let headerSize = 36
    /// The version-1 header, still emitted by the Linux sender and still parsed by the receiver.
    static let headerSizeV1 = 20
    static let maxPayload = 1200

    static let flagKeyframe: UInt8   = 1 << 0
    static let flagFrameEnd: UInt8   = 1 << 1
    static let flagParamSets: UInt8  = 1 << 2
    // The access unit is a side-by-side stereo pair (left|right in one double-wide frame). Set on
    // every fragment (stream-level fact) so a mid-stream joiner knows to present it per-eye. The
    // producer owns making the content actually SBS; this flag only carries the intent.
    static let flagStereoSbs: UInt8  = 1 << 3
    /// The pose tail carries a real pose. Cleared means the two fields are zero and mean nothing —
    /// a flag rather than a sentinel, because compositor frame index 0 is perfectly legitimate.
    /// Set on every fragment, like the flags above.
    static let flagPoseValid: UInt8   = 1 << 4

    static let codecHEVC: UInt8 = 0
    static let codecH264: UInt8 = 1

    /// Build a version-2 header. The pose tail is always written — fixed-size headers keep the
    /// receiver's parse a length check rather than a branch per packet — and `flagPoseValid` says
    /// whether it means anything.
    ///
    /// Note what is deliberately *not* done here: the pose display time does not go into
    /// `ptsMicros`. That field is stamped on the ideal grid by the producer, on purpose, because a
    /// real timestamp bakes render-tick quantisation into the stream and a receiver scheduling
    /// against it reproduces the irregularity. The pose time is a different quantity with a
    /// different consumer, so it gets its own field.
    static func header(flags: UInt8, codec: UInt8, frameSeq: UInt32,
                       fragIndex: UInt16, fragCount: UInt16, ptsMicros: UInt64,
                       pose: PoseTag?) -> [UInt8] {
        var b = [UInt8]()
        b.reserveCapacity(headerSize)
        b.append(version)
        b.append(pose == nil ? flags : (flags | flagPoseValid))
        b.append(codec)
        b.append(0) // reserved
        b.appendBE(frameSeq)
        b.appendBE(fragIndex)
        b.appendBE(fragCount)
        b.appendBE(ptsMicros)
        b.appendBE(pose?.frameId ?? 0)
        b.appendBE(pose?.displayTimeNanos ?? 0)
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
