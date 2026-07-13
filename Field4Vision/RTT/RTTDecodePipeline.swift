import Foundation
import CoreVideo

/// Decode side of the RTT pipeline (reassembled access unit → VideoToolbox → `CVPixelBuffer`),
/// decoupled from the receive loop for backpressure.
///
/// If decode ran inline on the recv queue, a slow decoder (e.g. the simulator's *software*
/// VideoToolbox) would stall `recvfrom`, overflow the socket buffer, and drop *random fragments* —
/// corrupting even keyframes. Here [submit] only enqueues (never blocks the recv queue); a dedicated
/// serial queue drains and decodes. A bounded FIFO backlog drops the oldest frames when the decoder
/// falls behind, and the sender's periodic IDR resyncs (WIRE_FORMAT's loss model). Kept off the main
/// actor so `RTTStream` can be `@MainActor` for SwiftUI without dragging decoder state onto main; the
/// only thing crossing back is the finished pixel buffer via [onPixelBuffer].
final class RTTDecodePipeline {
    var onPixelBuffer: ((CVPixelBuffer, UInt64) -> Void)?

    private let decodeQueue = DispatchQueue(label: "com.field4.vision.rtt.decode", qos: .userInitiated)
    private let lock = NSLock()
    private var backlog: [ReassembledFrame] = []
    private var draining = false
    /// After an overload with no keyframe to resync on, skip every frame until the next keyframe —
    /// feeding reference-broken P-frames only produces decode errors (and looks worse than a gap).
    private var awaitingKeyframe = false
    private let maxBacklog: Int
    private(set) var droppedFrames = 0

    // decoder/codec are touched only on decodeQueue (serial) — single-threaded, no lock needed.
    private var decoder: VideoDecoder?
    private var codec: RTTWire.Codec?

    init(maxBacklog: Int = 48) {
        self.maxBacklog = max(1, maxBacklog)
    }

    /// Enqueue a reassembled access unit for decode. Safe on the receive queue; returns immediately.
    func submit(_ frame: ReassembledFrame) {
        lock.lock()
        if awaitingKeyframe {
            if frame.isKeyframe { awaitingKeyframe = false }
            else { droppedFrames += 1; lock.unlock(); return }
        }
        backlog.append(frame)
        if backlog.count > maxBacklog {
            // Decoder is behind. Never feed reference-broken P-frames: drop back to the newest
            // keyframe still in the window (a clean resync point), or if there's none, skip forward
            // to the next keyframe. The sender's periodic IDR bounds the resulting gap.
            if let k = backlog.lastIndex(where: { $0.isKeyframe }), k > 0 {
                droppedFrames += k
                backlog.removeFirst(k)
            } else if !backlog[0].isKeyframe {
                droppedFrames += backlog.count
                backlog.removeAll()
                awaitingKeyframe = true
            }
            // else: keyframe already at the front — keep the run; it drains in order.
        }
        let startDrain = !draining
        if startDrain { draining = true }
        lock.unlock()
        if startDrain { decodeQueue.async { [weak self] in self?.drain() } }
    }

    private func drain() {
        while true {
            lock.lock()
            if backlog.isEmpty { draining = false; lock.unlock(); return }
            let frame = backlog.removeFirst()
            lock.unlock()
            decode(frame)
        }
    }

    /// Decode one access unit. Recreates the decoder if the stream's codec changes. decodeQueue only.
    private func decode(_ frame: ReassembledFrame) {
        if decoder == nil || codec != frame.codec {
            let dec = VideoDecoder(codec: frame.codec)
            dec.onPixelBuffer = { [weak self] pixelBuffer, pts in
                self?.onPixelBuffer?(pixelBuffer, pts)
            }
            decoder = dec
            codec = frame.codec
        }
        decoder?.decode(frame)
    }
}
