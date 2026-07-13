import Foundation

/// A complete Annex-B access unit, reassembled from its UDP fragments.
struct ReassembledFrame {
    var frameSeq: UInt32
    var ptsMicros: UInt64
    var codec: RTTWire.Codec
    var isKeyframe: Bool
    /// Full access unit: `00 00 00 01`-prefixed NAL units, with VPS/SPS/PPS inline on keyframes.
    var annexB: Data
}

/// Reassembles UDP fragments into whole access units per `WIRE_FORMAT.md` → "Receiver reassembly":
///
/// - Accumulate fragments by `frameSeq`; when all `fragCount` are present, concatenate by
///   `fragIndex` → a complete access unit.
/// - If a newer `frameSeq` arrives first, the older incomplete frame is abandoned (discarded) —
///   the surface is world-locked, so we present the newest complete frame and drop stragglers.
/// - No jitter buffer beyond what reassembly needs.
///
/// Pure value logic, no I/O — this is the unit-testable core of the receiver. Not thread-safe by
/// itself; `RTTReceiver` only ever touches it from its single receive queue.
final class FrameReassembler {
    /// How many distinct in-flight `frameSeq`s to keep partially assembled at once. A frame's
    /// fragments arrive back-to-back, so this only needs to absorb a little reordering across a
    /// frame boundary; anything older is a straggler and gets dropped.
    private let window: Int

    private struct Partial {
        let fragCount: Int
        let codec: RTTWire.Codec
        var isKeyframe: Bool
        var ptsMicros: UInt64
        var fragments: [Data?]      // indexed by fragIndex; nil = not yet arrived
        var received: Int
    }

    private var partials: [UInt32: Partial] = [:]
    /// Highest `frameSeq` we've completed and emitted. Fragments for anything at or below this are
    /// stragglers for an already-presented (or already-abandoned) frame — drop them.
    private var highestEmitted: UInt32?

    // Diagnostics.
    private(set) var completedFrames = 0
    private(set) var droppedFrames = 0

    init(window: Int = 4) {
        self.window = max(1, window)
    }

    /// Feed one parsed fragment. Returns a complete frame if this fragment finished one.
    func ingest(_ pkt: RTTPacket) -> ReassembledFrame? {
        if let hi = highestEmitted, seqNotNewer(pkt.frameSeq, than: hi) {
            return nil   // straggler for a frame we've already moved past
        }

        var partial = partials[pkt.frameSeq] ?? Partial(
            fragCount: Int(pkt.fragCount),
            codec: pkt.codec,
            isKeyframe: pkt.isKeyframe,
            ptsMicros: pkt.ptsMicros,
            fragments: Array(repeating: nil, count: Int(pkt.fragCount)),
            received: 0
        )

        // KEYFRAME is set on every fragment of an IDR; OR them so a late-arriving fragment can't
        // unset it. Same idea for pts — take the value that rode in with each fragment (identical
        // across a frame in practice).
        partial.isKeyframe = partial.isKeyframe || pkt.isKeyframe
        partial.ptsMicros = pkt.ptsMicros

        let idx = Int(pkt.fragIndex)
        if idx < partial.fragments.count, partial.fragments[idx] == nil {
            partial.fragments[idx] = pkt.payload
            partial.received += 1
        }

        if partial.received == partial.fragCount {
            partials[pkt.frameSeq] = nil
            emitCleanup(upTo: pkt.frameSeq)
            highestEmitted = pkt.frameSeq
            completedFrames += 1

            var annexB = Data()
            for frag in partial.fragments { annexB.append(frag ?? Data()) }
            return ReassembledFrame(frameSeq: pkt.frameSeq,
                                    ptsMicros: partial.ptsMicros,
                                    codec: partial.codec,
                                    isKeyframe: partial.isKeyframe,
                                    annexB: annexB)
        }

        partials[pkt.frameSeq] = partial
        evictStale(newest: pkt.frameSeq)
        return nil
    }

    /// Once a frame completes, any still-incomplete frame with a lower seq is abandoned — its
    /// newer sibling arrived first, so per spec we discard it.
    private func emitCleanup(upTo seq: UInt32) {
        for key in partials.keys where seqNotNewer(key, than: seq) {
            partials[key] = nil
            droppedFrames += 1
        }
    }

    /// Bound memory when frames never complete (steady loss): keep only the `window` newest seqs.
    private func evictStale(newest: UInt32) {
        guard partials.count > window else { return }
        let sorted = partials.keys.sorted { seqNotNewer($0, than: $1) }
        let dropCount = partials.count - window
        for key in sorted.prefix(dropCount) {
            partials[key] = nil
            droppedFrames += 1
        }
    }

    /// `a` is not newer than `b`, accounting for u32 wraparound (frames run for a long time). Uses
    /// the usual signed-difference trick: if `a - b` interpreted as signed is <= 0, `a` is older.
    private func seqNotNewer(_ a: UInt32, than b: UInt32) -> Bool {
        Int32(bitPattern: a &- b) <= 0
    }
}
