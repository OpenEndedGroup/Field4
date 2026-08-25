import CompositorServices
import Foundation
import OSLog

/// Where the time goes, measured on the head — which is the only place the headline figure can be
/// computed without reconciling two clocks.
///
/// A pose's `predictedDisplayTime` is in *this* device's clock, and the media wire carries it back as
/// `poseDisplayTimeNanos`. So subtracting it from the moment the resulting frame reaches the display
/// texture is motion-to-photon in one clock, with no offset estimation involved. The Kotlin side
/// (`afield/graphics/VisionPoseLatency.kt`) owns the cross-clock work and the readout; this owns the
/// spans only this end can see.
///
/// **The jitter buffer is reported separately and is not a fault.** `RTTStream` holds frames on
/// purpose so that network jitter becomes smoothness rather than dropped frames. Folded into a single
/// total it reads as a slow network, and the obvious fix — shrinking it — trades away exactly what it
/// was bought for. `FIELD4_RTT_BUFFER=0` disables it if you want the unbuffered number for comparison.
///
/// What this cannot see: actual photons. The last observable moment is the texture upload; the
/// compositor shows it up to one refresh later, and RealityKit offers no hook for that. So
/// `pose → texture` is a **lower bound**. It closes when the present path moves to Compositor
/// Services, which is the same change that makes reprojection possible.
final class PoseLatency: @unchecked Sendable {
    static let shared = PoseLatency()

    /// Span names. These strings are the wire keys and appear verbatim in the host's readout, so the
    /// host does not need to know the set in advance — a span added here shows up there.
    enum Span {
        static let receiveToDecode = "head: reassembled to decoded"
        static let decodeToRelease = "head: jitter buffer hold (deliberate)"
        static let releaseToTexture = "head: release to texture upload"
        static let poseToTexture = "head: POSE to texture (motion-to-photon lower bound)"
        static let poseToArrival = "head: pose instant to frame arrival"
    }

    private let log = Logger(subsystem: "com.field4.vision", category: "pose-latency")
    private let lock = NSLock()

    /// Bounded ring per span. Percentiles, not a mean: the tail is what a wearer notices.
    private var samples: [String: [Double]] = [:]
    private let window = 2048

    /// Per-frame marks, keyed by the stream's `ptsMicros` — the one identifier that survives the
    /// decoder, which drops everything else from `ReassembledFrame`.
    private struct Marks {
        var poseDisplayTimeNanos: UInt64?
        var receivedAt: Double
        var decodedAt: Double?
    }
    private var inFlight: [UInt64: Marks] = [:]

    /// Device clock, seconds. The same clock `LayerRenderer.Clock` and the pose timestamps use, so
    /// these are directly comparable with `poseDisplayTimeNanos`.
    private func now() -> Double { LayerRenderer.Clock().now.timeInterval }

    private func add(_ span: String, _ seconds: Double) {
        // A negative span means the frame was attributed to the wrong pose, or a sender restart moved
        // the timeline. Dropping it lets `n` fall short rather than flattering the result with a zero.
        guard seconds >= 0, seconds.isFinite else { return }
        lock.withLock {
            var list = samples[span] ?? []
            list.append(seconds * 1000.0)
            if list.count > window { list.removeFirst(list.count - window) }
            samples[span] = list
        }
    }

    // MARK: - Marks

    /// A complete access unit came off the wire and is about to be handed to the decoder.
    func reassembled(_ frame: ReassembledFrame) {
        let t = now()
        lock.withLock {
            inFlight[frame.ptsMicros] = Marks(poseDisplayTimeNanos: frame.poseDisplayTimeNanos,
                                              receivedAt: t, decodedAt: nil)
            // A frame that never decodes would otherwise sit here forever.
            if inFlight.count > 256 {
                for key in inFlight.keys.sorted().prefix(inFlight.count - 256) { inFlight[key] = nil }
            }
        }
        if let pose = frame.poseDisplayTimeNanos {
            add(Span.poseToArrival, t - Double(pose) / 1.0e9)
        }
    }

    /// The decoder produced a pixel buffer for this access unit.
    func decoded(ptsMicros: UInt64) {
        let t = now()
        let received: Double? = lock.withLock {
            guard var m = inFlight[ptsMicros] else { return nil }
            m.decodedAt = t
            inFlight[ptsMicros] = m
            return m.receivedAt
        }
        if let received { add(Span.receiveToDecode, t - received) }
    }

    /// The jitter buffer released this frame for presentation.
    func released(ptsMicros: UInt64) {
        let t = now()
        let decodedAt: Double? = lock.withLock { inFlight[ptsMicros]?.decodedAt }
        if let decodedAt { add(Span.decodeToRelease, t - decodedAt) }
        releasedAt = (ptsMicros, t)
    }

    private var releasedAt: (pts: UInt64, at: Double)?

    /// The frame reached the display texture — the last moment observable from here.
    func presented(ptsMicros: UInt64) {
        let t = now()
        let marks: Marks? = lock.withLock { inFlight.removeValue(forKey: ptsMicros) }
        if let r = releasedAt, r.pts == ptsMicros { add(Span.releaseToTexture, t - r.at) }
        if let pose = marks?.poseDisplayTimeNanos {
            add(Span.poseToTexture, t - Double(pose) / 1.0e9)
        }
    }

    // MARK: - Reporting

    private struct Reported: Encodable {
        let p50Millis: Double
        let p95Millis: Double
        let maxMillis: Double
        let n: Int
    }

    private func percentile(_ sorted: [Double], _ fraction: Double) -> Double {
        guard !sorted.isEmpty else { return 0 }
        let index = Int((Double(sorted.count - 1) * fraction).rounded(.down))
        return sorted[min(max(index, 0), sorted.count - 1)]
    }

    /// Snapshot every span as the wire shape the host decodes.
    func snapshot() -> [String: [String: Double]] {
        let copy = lock.withLock { samples }
        var out: [String: [String: Double]] = [:]
        for (name, values) in copy where !values.isEmpty {
            let sorted = values.sorted()
            out[name] = [
                "p50Millis": percentile(sorted, 0.50),
                "p95Millis": percentile(sorted, 0.95),
                "maxMillis": sorted[sorted.count - 1],
                "n": Double(sorted.count),
            ]
        }
        return out
    }

    /// True when there is anything worth sending.
    var hasSamples: Bool { lock.withLock { samples.contains { !$0.value.isEmpty } } }

    func reset() {
        lock.withLock { samples.removeAll(); inFlight.removeAll() }
        releasedAt = nil
    }
}
