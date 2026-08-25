import Foundation
import OSLog

/// Runs the **real** `RTTPacket` parser over hand-built datagrams at launch and logs the verdict.
///
/// This exists because there is no XCTest target in this project, so without it the wire parser has
/// no execution coverage at all — only the fact that it compiles. A header parser is the wrong place
/// to accept that: a wrong offset or a byte-order slip does not crash, it produces plausible numbers,
/// and every downstream symptom (a frozen image, a stall, a reprojection to the wrong instant) points
/// somewhere else entirely.
///
/// Datagrams are built here from `WIRE_FORMAT.md` rather than from `RTTWire`'s own constants, so a
/// change to those constants is caught instead of being silently agreed with. The offsets and the
/// magic values below are literals on purpose.
///
/// Cheap (a few microseconds) and never fails the app. The verdict is reported to the Field server
/// over `__visionpose__` by `PoseUplink`, because that is the only channel that actually reaches the
/// Mac: `os.Logger` goes to the unified log, and `simctl launch --console-pty` captures nothing from
/// this app (verified — the console file comes back empty). `tools/vision_pose_simulator_smoke.sh`
/// asserts on the server-log line.
enum RTTWireSelfCheck {
    /// Run once per launch; the result is what gets reported. Empty means everything passed.
    static let failures: [String] = check()
    private static let log = Logger(subsystem: "com.field4.vision", category: "rtt-wire")

    /// A version-2 header, spelled out by offset per the wire doc.
    private static func v2(flags: UInt8, seq: UInt32, fragIndex: UInt16, fragCount: UInt16,
                          pts: UInt64, poseId: UInt64, poseNanos: UInt64,
                          payload: [UInt8]) -> Data {
        var b = [UInt8]()
        b.append(2)                       // 0  version
        b.append(flags)                   // 1  flags
        b.append(1)                       // 2  codec = H.264
        b.append(0)                       // 3  reserved
        appendBE(&b, seq)                 // 4
        appendBE(&b, fragIndex)           // 8
        appendBE(&b, fragCount)           // 10
        appendBE(&b, pts)                 // 12
        appendBE(&b, poseId)              // 20
        appendBE(&b, poseNanos)           // 28
        b.append(contentsOf: payload)     // 36
        return Data(b)
    }

    private static func v1(flags: UInt8, seq: UInt32, fragIndex: UInt16, fragCount: UInt16,
                           pts: UInt64, payload: [UInt8]) -> Data {
        var b = [UInt8]()
        b.append(1); b.append(flags); b.append(0); b.append(0)
        appendBE(&b, seq); appendBE(&b, fragIndex); appendBE(&b, fragCount); appendBE(&b, pts)
        b.append(contentsOf: payload)
        return Data(b)
    }

    private static func appendBE(_ b: inout [UInt8], _ v: UInt16) {
        b.append(UInt8(truncatingIfNeeded: v >> 8)); b.append(UInt8(truncatingIfNeeded: v))
    }
    private static func appendBE(_ b: inout [UInt8], _ v: UInt32) {
        for s in stride(from: 24, through: 0, by: -8) { b.append(UInt8(truncatingIfNeeded: v >> UInt32(s))) }
    }
    private static func appendBE(_ b: inout [UInt8], _ v: UInt64) {
        for s in stride(from: 56, through: 0, by: -8) { b.append(UInt8(truncatingIfNeeded: v >> UInt64(s))) }
    }

    private static func check() -> [String] {
        var failures: [String] = []
        func check(_ what: String, _ ok: Bool) { if !ok { failures.append(what) } }

        // Every byte distinct, so a byte-order or offset slip cannot coincidentally agree.
        let poseId: UInt64 = 0x0102_0304_0506_0708
        let poseNanos: UInt64 = 1_234_567_890_123_456_789
        let payload: [UInt8] = [0x00, 0x00, 0x00, 0x01, 0x65, 0xAB]

        // --- v2 with a pose ---------------------------------------------------------------------
        let posed = v2(flags: 0b0001_0011, seq: 0xDEAD_BEEF, fragIndex: 2, fragCount: 5,
                       pts: 987_654_321, poseId: poseId, poseNanos: poseNanos, payload: payload)
        if let p = RTTPacket(datagram: posed) {
            check("v2 frameSeq", p.frameSeq == 0xDEAD_BEEF)
            check("v2 fragIndex", p.fragIndex == 2)
            check("v2 fragCount", p.fragCount == 5)
            check("v2 ptsMicros", p.ptsMicros == 987_654_321)
            check("v2 keyframe flag", p.isKeyframe)
            check("v2 frameEnd flag", p.isFrameEnd)
            check("v2 poseFrameId", p.poseFrameId == poseId)
            check("v2 poseDisplayTimeNanos", p.poseDisplayTimeNanos == poseNanos)
            check("v2 hasPose", p.hasPose)
            check("v2 payload", Array(p.payload) == payload)
        } else {
            check("v2 parses at all", false)
        }

        // --- v2 with the tail present but POSE_VALID clear: the tail must be ignored, not read ---
        let unflagged = v2(flags: 0b0000_0001, seq: 1, fragIndex: 0, fragCount: 1,
                           pts: 0, poseId: poseId, poseNanos: poseNanos, payload: payload)
        if let p = RTTPacket(datagram: unflagged) {
            check("cleared POSE_VALID yields no pose", p.poseFrameId == nil && p.poseDisplayTimeNanos == nil)
            check("cleared POSE_VALID hasPose false", !p.hasPose)
            check("cleared POSE_VALID payload", Array(p.payload) == payload)
        } else {
            check("v2 without POSE_VALID parses", false)
        }

        // --- v1 still parses, and its payload starts at 20 --------------------------------------
        let legacy = v1(flags: 0b0000_1011, seq: 42, fragIndex: 0, fragCount: 1,
                        pts: 555, payload: payload)
        if let p = RTTPacket(datagram: legacy) {
            check("v1 frameSeq", p.frameSeq == 42)
            check("v1 ptsMicros", p.ptsMicros == 555)
            check("v1 stereo flag", p.isStereoSbs)
            check("v1 carries no pose", p.poseFrameId == nil && p.poseDisplayTimeNanos == nil)
            // The one that matters: a v1 payload must not be read from offset 36.
            check("v1 payload offset is 20", Array(p.payload) == payload)
        } else {
            check("v1 still parses", false)
        }

        // --- what must be refused ----------------------------------------------------------------
        var unknown = Array(posed); unknown[0] = 7
        check("unknown version refused", RTTPacket(datagram: Data(unknown)) == nil)
        check("runt refused", RTTPacket(datagram: Data(Array(posed.prefix(12)))) == nil)
        // A v2 header truncated inside its pose tail is not a short v1 packet — it must be refused,
        // not silently reinterpreted with a garbage payload.
        check("truncated v2 tail refused", RTTPacket(datagram: Data(Array(posed.prefix(30)))) == nil)
        var badCodec = Array(posed); badCodec[2] = 99
        check("unknown codec refused", RTTPacket(datagram: Data(badCodec)) == nil)
        var zeroFrags = Array(posed)
        zeroFrags[10] = 0; zeroFrags[11] = 0
        check("fragCount 0 refused", RTTPacket(datagram: Data(zeroFrags)) == nil)

        // --- the reassembler carries the tail through --------------------------------------------
        let single = v2(flags: 0b0001_0011, seq: 3, fragIndex: 0, fragCount: 1,
                        pts: 1, poseId: poseId, poseNanos: poseNanos, payload: payload)
        if let pkt = RTTPacket(datagram: single) {
            let released = FrameReassembler().ingest(pkt)
            check("reassembler released the frame", released.count == 1)
            if let f = released.first {
                check("reassembled poseFrameId", f.poseFrameId == poseId)
                check("reassembled poseDisplayTimeNanos", f.poseDisplayTimeNanos == poseNanos)
                check("reassembled hasPose", f.hasPose)
            }
        } else {
            check("single-fragment v2 parses", false)
        }

        if failures.isEmpty {
            log.notice("rtt-wire-selfcheck ok (v1 + v2 pose tail)")
        } else {
            log.error("rtt-wire-selfcheck FAILED: \(failures.joined(separator: ", "), privacy: .public)")
        }
        return failures
    }
}
