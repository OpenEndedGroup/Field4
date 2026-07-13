import Foundation

/// The receiver's mirror of `field-encode`'s media wire format. This is the *authoritative*
/// contract — see `~/field4/native/field-encode/WIRE_FORMAT.md`. Every field below is fixed by
/// that doc; if a sender bumps `version` we reject it loudly rather than misparse.
///
/// Media is a one-way UDP stream of fragmented Annex-B access units. Nothing is retransmitted:
/// loss is tolerated by discarding an incomplete frame and self-healing at the next IDR.
enum RTTWire {
    static let version: UInt8 = 1
    static let headerSize = 20
    /// Sender's default fragment payload cap (safe under a 1500 MTU). Not needed to *parse* — a
    /// datagram is whatever length it arrived as — but kept for parity/asserts.
    static let maxPayload = 1200

    // Flags (byte 1).
    static let flagKeyframe: UInt8  = 1 << 0   // set on *every* fragment of an IDR frame
    static let flagFrameEnd: UInt8  = 1 << 1   // set on the last fragment (fragIndex == fragCount-1)
    static let flagParamSets: UInt8 = 1 << 2   // informational
    static let flagStereoSbs: UInt8 = 1 << 3   // frame is side-by-side stereo (left|right) → present per-eye

    enum Codec: UInt8 {
        case hevc = 0
        case h264 = 1
    }
}

/// A parsed 20-byte packet header + a slice pointing at this fragment's Annex-B payload.
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
    var payload: Data

    var isKeyframe: Bool { flags & RTTWire.flagKeyframe != 0 }
    var isFrameEnd: Bool { flags & RTTWire.flagFrameEnd != 0 }
    var isStereoSbs: Bool { flags & RTTWire.flagStereoSbs != 0 }

    /// Parse one datagram. Returns nil (and never traps) on anything malformed: a short buffer,
    /// an unknown version, an unknown codec, or an out-of-range fragment index. A one-way lossy
    /// stream will occasionally hand us garbage; drop it silently rather than crash the loop.
    init?(datagram: Data) {
        guard datagram.count >= RTTWire.headerSize else { return nil }

        // `datagram` may be a slice with a non-zero startIndex; index through withUnsafeBytes so
        // we read from the logical start regardless of the base offset.
        let parsed: (UInt8, UInt8, UInt8, UInt32, UInt16, UInt16, UInt64)? =
            datagram.withUnsafeBytes { raw -> (UInt8, UInt8, UInt8, UInt32, UInt16, UInt16, UInt64)? in
                let b = raw.bindMemory(to: UInt8.self)
                let version = b[0]
                guard version == RTTWire.version else { return nil }
                let flags = b[1]
                let codec = b[2]
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
                return (flags, codec, /*reserved b[3]*/ b[3],
                        u32(4), u16(8), u16(10), u64(12))
            }

        guard let (flags, codecByte, _, frameSeq, fragIndex, fragCount, ptsMicros) = parsed,
              let codec = RTTWire.Codec(rawValue: codecByte),
              fragCount > 0, fragIndex < fragCount
        else { return nil }

        self.flags = flags
        self.codec = codec
        self.frameSeq = frameSeq
        self.fragIndex = fragIndex
        self.fragCount = fragCount
        self.ptsMicros = ptsMicros
        // Copy the payload out of the (about-to-be-reused) receive buffer.
        self.payload = Data(datagram[(datagram.startIndex + RTTWire.headerSize)...])
    }
}
