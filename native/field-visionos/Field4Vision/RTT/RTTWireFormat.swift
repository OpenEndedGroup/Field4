import Foundation

/// The receiver's mirror of `field-encode`'s media wire format. This is the *authoritative*
/// contract — see `~/field4/native/field-encode/WIRE_FORMAT.md`. Every field below is fixed by
/// that doc; an unknown `version` is rejected rather than misparsed.
///
/// Media is a one-way UDP stream of fragmented Annex-B access units. Nothing is retransmitted:
/// loss is tolerated by discarding an incomplete frame and self-healing at the next IDR.
///
/// **Two versions are live, and both stay live.** Version 2 appends a 16-byte pose tail so a frame
/// can say which head pose it was rendered from; version 1 has no tail. The Linux/NVENC sender emits
/// v1 by design — its host tracks locally through OpenXR, so it has no uplinked pose to report — so
/// dropping v1 support would break that path rather than tidy it up.
enum RTTWire {
    static let version: UInt8 = 2
    static let versionNoPose: UInt8 = 1
    static let headerSize = 36
    static let headerSizeV1 = 20

    /// Header length for a version byte, or nil if we do not speak it.
    static func headerSize(forVersion v: UInt8) -> Int? {
        switch v {
        case version: return headerSize
        case versionNoPose: return headerSizeV1
        default: return nil
        }
    }
    /// Sender's default fragment payload cap (safe under a 1500 MTU). Not needed to *parse* — a
    /// datagram is whatever length it arrived as — but kept for parity/asserts.
    static let maxPayload = 1200

    // Flags (byte 1).
    static let flagKeyframe: UInt8  = 1 << 0   // set on *every* fragment of an IDR frame
    static let flagFrameEnd: UInt8  = 1 << 1   // set on the last fragment (fragIndex == fragCount-1)
    static let flagParamSets: UInt8 = 1 << 2   // informational
    static let flagStereoSbs: UInt8 = 1 << 3   // frame is side-by-side stereo (left|right) → present per-eye
    /// The v2 pose tail carries a real pose. A flag rather than a sentinel, because compositor frame
    /// index 0 is legitimate. Never set on a v1 packet (there is no tail to describe).
    static let flagPoseValid: UInt8 = 1 << 4

    enum Codec: UInt8 {
        case hevc = 0
        case h264 = 1
    }
}

/// A parsed packet header + a slice pointing at this fragment's Annex-B payload.
///
/// `payload` is a `Data` slice that shares storage with the received datagram — cheap to make,
/// but the caller must copy it (the reassembler does) before the datagram buffer is reused.
struct RTTPacket {
    var flags: UInt8
    var codec: RTTWire.Codec
    var frameSeq: UInt32
    var fragIndex: UInt16
    var fragCount: UInt16
    var ptsMicros: UInt64
    /// The head's compositor frame index this frame was rendered from; nil on v1 or without the flag.
    var poseFrameId: UInt64?
    /// The head's *own* clock, nanoseconds — hand it straight back to
    /// `WorldTrackingProvider.queryDeviceAnchor(atTimestamp:)`. Not comparable with `ptsMicros`,
    /// which is a synthetic sender-side cadence on a different clock entirely.
    var poseDisplayTimeNanos: UInt64?
    var payload: Data

    var isKeyframe: Bool { flags & RTTWire.flagKeyframe != 0 }
    var isFrameEnd: Bool { flags & RTTWire.flagFrameEnd != 0 }
    var isStereoSbs: Bool { flags & RTTWire.flagStereoSbs != 0 }
    var hasPose: Bool { poseDisplayTimeNanos != nil }

    /// Parse one datagram. Returns nil (and never traps) on anything malformed: a short buffer,
    /// an unknown version, an unknown codec, or an out-of-range fragment index. A one-way lossy
    /// stream will occasionally hand us garbage; drop it silently rather than crash the loop.
    init?(datagram: Data) {
        // Enough for the shortest header we speak; the version byte tells us the real length.
        guard datagram.count >= RTTWire.headerSizeV1 else { return nil }

        struct Parsed {
            var headerSize: Int
            var flags: UInt8
            var codec: UInt8
            var frameSeq: UInt32
            var fragIndex: UInt16
            var fragCount: UInt16
            var ptsMicros: UInt64
            var poseFrameId: UInt64?
            var poseDisplayTimeNanos: UInt64?
        }

        // `datagram` may be a slice with a non-zero startIndex; index through withUnsafeBytes so
        // we read from the logical start regardless of the base offset.
        let parsed: Parsed? = datagram.withUnsafeBytes { raw -> Parsed? in
            let b = raw.bindMemory(to: UInt8.self)
            guard let headerSize = RTTWire.headerSize(forVersion: b[0]),
                  raw.count >= headerSize else { return nil }
            let flags = b[1]
            func u16(_ i: Int) -> UInt16 { (UInt16(b[i]) << 8) | UInt16(b[i + 1]) }
            func u32(_ i: Int) -> UInt32 {
                (UInt32(b[i]) << 24) | (UInt32(b[i + 1]) << 16)
                    | (UInt32(b[i + 2]) << 8) | UInt32(b[i + 3])
            }
            func u64(_ i: Int) -> UInt64 {
                var v: UInt64 = 0
                for k in 0..<8 { v = (v << 8) | UInt64(b[i + k]) }
                return v
            }
            var out = Parsed(headerSize: headerSize, flags: flags, codec: b[2],
                             /* b[3] reserved */
                             frameSeq: u32(4), fragIndex: u16(8), fragCount: u16(10),
                             ptsMicros: u64(12), poseFrameId: nil, poseDisplayTimeNanos: nil)
            // The tail is always written on a v2 packet; the flag says whether it means anything.
            if headerSize == RTTWire.headerSize, flags & RTTWire.flagPoseValid != 0 {
                out.poseFrameId = u64(20)
                out.poseDisplayTimeNanos = u64(28)
            }
            return out
        }

        guard let p = parsed,
              let codec = RTTWire.Codec(rawValue: p.codec),
              p.fragCount > 0, p.fragIndex < p.fragCount
        else { return nil }

        self.flags = p.flags
        self.codec = codec
        self.frameSeq = p.frameSeq
        self.fragIndex = p.fragIndex
        self.fragCount = p.fragCount
        self.ptsMicros = p.ptsMicros
        self.poseFrameId = p.poseFrameId
        self.poseDisplayTimeNanos = p.poseDisplayTimeNanos
        // Copy the payload out of the (about-to-be-reused) receive buffer.
        self.payload = Data(datagram[(datagram.startIndex + p.headerSize)...])
    }
}
