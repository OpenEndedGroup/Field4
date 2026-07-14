import Foundation

/// A complete Annex-B access unit, reassembled from its UDP fragments.
struct ReassembledFrame {
    var frameSeq: UInt32
    var ptsMicros: UInt64
    var codec: RTTWire.Codec
    var isKeyframe: Bool
    /// The decoded frame is a side-by-side stereo pair (left|right) to present per-eye.
    var stereo: Bool
    /// Full access unit: `00 00 00 01`-prefixed NAL units, with VPS/SPS/PPS inline on keyframes.
    var annexB: Data
}

/// Reassembles UDP fragments into whole access units per `WIRE_FORMAT.md` → "Receiver reassembly",
/// then releases them **in sequence order** through a shallow reorder buffer:
///
/// - Accumulate fragments by `frameSeq`; when all `fragCount` are present, the access unit is
///   *complete* (concatenate by `fragIndex`).
/// - A complete frame is not handed downstream immediately — it enters a reorder buffer and is
///   released only when it's the next expected `frameSeq`. UDP routinely delivers datagrams out of
///   order; without this, a frame that arrives a beat late looks like a straggler and gets dropped,
///   which breaks a single-reference (IPPP) stream's prediction chain until the next IDR (a ~0.5s
///   grey flash). Holding briefly and re-sequencing keeps the chain intact.
/// - The buffer is shallow (`reorderDepth`): if the next expected seq is still missing once that
///   many later frames have piled up, it's treated as genuinely lost — skip forward to the oldest
///   buffered frame and release. Real loss still self-heals at the next IDR; only reordering (the
///   common case) is absorbed, and in-order arrivals add zero latency.
///
/// Pure value logic, no I/O — the unit-testable core of the receiver. Not thread-safe by itself;
/// `RTTReceiver` only ever touches it from its single receive queue.
final class FrameReassembler {
    /// How many distinct in-flight `frameSeq`s to keep partially assembled at once.
    private let window: Int
    /// How many *completed* later frames may pile up behind a missing seq before we give up on it
    /// (declare it lost) and skip forward. Bounds added latency to `reorderDepth` frames, and only
    /// when a gap actually appears — in-order arrivals release immediately.
    private let reorderDepth: Int

    private struct Partial {
        let fragCount: Int
        let codec: RTTWire.Codec
        var isKeyframe: Bool
        var stereo: Bool
        var ptsMicros: UInt64
        var fragments: [Data?]      // indexed by fragIndex; nil = not yet arrived
        var received: Int
    }

    private var partials: [UInt32: Partial] = [:]
    /// Completed frames awaiting in-order release, keyed by `frameSeq`.
    private var ready: [UInt32: ReassembledFrame] = [:]
    /// Next `frameSeq` to release downstream (nil until we've locked onto the stream).
    private var nextRelease: UInt32?

    // Diagnostics.
    private(set) var completedFrames = 0
    private(set) var droppedFrames = 0

    init(window: Int = 8, reorderDepth: Int = 3) {
        self.window = max(1, window)
        self.reorderDepth = max(1, reorderDepth)
    }

    /// A keyframe at or below this seq, arriving after we've released much higher seqs, means the
    /// sender restarted (its monotonic `seq` reset to 0) — a new stream, not a straggler.
    private let restartSeqWindow: UInt32 = 8

    /// Feed one parsed fragment. Returns any frames this fragment made releasable, in sequence order
    /// (usually 0 or 1; more than one when a reordered gap fills and a run flushes at once).
    func ingest(_ pkt: RTTPacket) -> [ReassembledFrame] {
        // Stream-restart detection: a keyframe near seq 0 after a high-water mark = the sender
        // relaunched. Re-lock rather than reject the whole new stream as stragglers.
        if let n = nextRelease, seqOlder(pkt.frameSeq, than: n) || pkt.frameSeq == n &- 1 {
            if pkt.isKeyframe, pkt.frameSeq <= restartSeqWindow, n > restartSeqWindow {
                reset()   // fall through and ingest as the new stream's first frame
            } else if seqOlder(pkt.frameSeq, than: n) {
                // Older than what we've already released/skipped past — genuinely too late.
                droppedFrames += 1
                return []
            }
        }

        var partial = partials[pkt.frameSeq] ?? Partial(
            fragCount: Int(pkt.fragCount),
            codec: pkt.codec,
            isKeyframe: pkt.isKeyframe,
            stereo: pkt.isStereoSbs,
            ptsMicros: pkt.ptsMicros,
            fragments: Array(repeating: nil, count: Int(pkt.fragCount)),
            received: 0
        )

        // KEYFRAME / STEREO_SBS ride on every fragment; OR them so a late fragment can't unset them.
        partial.isKeyframe = partial.isKeyframe || pkt.isKeyframe
        partial.stereo = partial.stereo || pkt.isStereoSbs
        partial.ptsMicros = pkt.ptsMicros

        let idx = Int(pkt.fragIndex)
        if idx < partial.fragments.count, partial.fragments[idx] == nil {
            partial.fragments[idx] = pkt.payload
            partial.received += 1
        }

        if partial.received == partial.fragCount {
            partials[pkt.frameSeq] = nil
            var annexB = Data()
            for frag in partial.fragments { annexB.append(frag ?? Data()) }
            ready[pkt.frameSeq] = ReassembledFrame(frameSeq: pkt.frameSeq,
                                                   ptsMicros: partial.ptsMicros,
                                                   codec: partial.codec,
                                                   isKeyframe: partial.isKeyframe,
                                                   stereo: partial.stereo,
                                                   annexB: annexB)
            completedFrames += 1
            return releaseInOrder()
        }

        partials[pkt.frameSeq] = partial
        evictStale(newest: pkt.frameSeq)
        return []
    }

    /// Release completed frames in strict `frameSeq` order, holding at a gap until it fills or the
    /// reorder buffer overflows (gap declared lost → skip forward).
    private func releaseInOrder() -> [ReassembledFrame] {
        var out: [ReassembledFrame] = []
        // First-ever frame: start the sequence at the oldest thing we've completed.
        if nextRelease == nil { nextRelease = ready.keys.min(by: seqOlder) }

        while true {
            // Flush the contiguous run starting at nextRelease.
            while let n = nextRelease, let f = ready.removeValue(forKey: n) {
                out.append(f)
                nextRelease = n &+ 1
            }
            // Blocked on a missing seq. Wait unless too many later frames have piled up.
            guard ready.count > reorderDepth, let n = nextRelease,
                  let oldest = ready.keys.min(by: seqOlder) else { break }
            droppedFrames += Int(oldest &- n)   // frames [n, oldest) declared lost
            nextRelease = oldest
        }
        return out
    }

    /// Bound memory when incomplete frames never finish (steady loss): keep only the `window`
    /// newest partially-assembled seqs.
    private func evictStale(newest: UInt32) {
        guard partials.count > window else { return }
        let sorted = partials.keys.sorted(by: seqOlder)
        for key in sorted.prefix(partials.count - window) {
            partials[key] = nil
            droppedFrames += 1
        }
    }

    /// Forget all in-flight state so the next stream (sender restart) can lock on from its IDR.
    private func reset() {
        partials.removeAll()
        ready.removeAll()
        nextRelease = nil
    }

    /// `a` is strictly older than `b`, accounting for u32 wraparound (streams run a long time). Uses
    /// the signed-difference trick: if `a - b` interpreted as signed is < 0, `a` precedes `b`.
    private func seqOlder(_ a: UInt32, than b: UInt32) -> Bool {
        Int32(bitPattern: a &- b) < 0
    }
}
