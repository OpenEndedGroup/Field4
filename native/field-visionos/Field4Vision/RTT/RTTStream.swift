import Foundation
import Observation
import CoreVideo
import RealityKit
import QuartzCore

/// Top-level RTT pipeline: UDP receiver → reassembler → decoder → Metal texture, exposing an
/// observable `displayTexture` for the RealityKit present layer plus light diagnostics.
///
/// The receive + decode path runs on the receiver's serial queue (in frame order, so inter-frame
/// prediction stays intact); only the final texture upload and the published stats hop to the main
/// actor. This is the "pure-Swift UDP + reassembler skeleton, testable against the field-encode
/// Mac sender" the handoff asked for, carried all the way to a presentable texture.
@Observable
@MainActor
final class RTTStream {
    /// Default media port. The sender's examples use `…:9000` (`--dest host:9000`).
    nonisolated static let defaultPort: UInt16 = 9000

    /// The port to bind, honouring `FIELD4_RTT_PORT` (e.g. when Field holds the default 9000 on the
    /// shared host stack during same-Mac simulator testing). Shared by every presentation so the
    /// window and the theatre agree on which socket to open.
    nonisolated static var resolvedPort: UInt16 {
        if let s = ProcessInfo.processInfo.environment["FIELD4_RTT_PORT"], let p = UInt16(s) { return p }
        return defaultPort
    }

    /// How many presentations (the windowed viewer + the immersive theatre) currently want the
    /// stream. The receiver starts when the first opens and stops only when the last closes, so
    /// opening one presentation never tears down the other. Use `acquire`/`release`, not `start`/`stop`.
    private var presentationCount = 0

    var isRunning = false
    var status = "Idle"
    /// The current stream is side-by-side stereo (decoded frame is left|right) — present per-eye.
    var isStereo = false
    var frameSize = CGSize.zero
    var decodedFrameCount = 0
    /// Not observable on purpose. This is recomputed twice a second; if a SwiftUI view depended on
    /// it, every window would re-render at 2Hz for a number that is only ever diagnostic. The
    /// precise value goes to the `RTTSTAT` telemetry line; the UI gets the deadbanded `status`.
    @ObservationIgnored var measuredFPS = 0.0
    /// The live RGBA texture the immersive view samples. Replaced only when the frame size changes
    /// (otherwise the same resource updates in place), so the view can bind it once.
    var displayTexture: TextureResource?

    private var receiver: RTTReceiver?
    private var pipeline: RTTDecodePipeline?
    private let converter = PixelBufferTextureConverter()

    private var fpsWindowStart = CACurrentMediaTime()
    private var fpsWindowCount = 0

    /// Held so telemetry can read reassembly counters; the receiver owns the ingest side.
    /// All `@ObservationIgnored` — pure bookkeeping, nothing here should invalidate a SwiftUI view.
    /// `FIELD4_RTT_STATS=0` removes the once-a-second telemetry entirely — the clean A/B for
    /// "is the diagnostic itself causing the stall it is meant to measure".
    @ObservationIgnored
    static let statsEnabled = ProcessInfo.processInfo.environment["FIELD4_RTT_STATS"] != "0"
    @ObservationIgnored
    static let statsQueue = DispatchQueue(label: "com.field4.vision.rtt.stats", qos: .utility)

    // MARK: - Presentation clock (jitter buffer)
    //
    // WIRE_FORMAT specifies present-on-arrival ("no jitter buffer … present the newest complete
    // frame"). That was the wrong trade for a 90Hz display. The sender can only emit on a render
    // tick — ~8.3ms at Field's ~120fps loop — so a 33.3ms target is 4 ticks, and whenever phase
    // drifts a frame goes out after 3 (25ms) or 5 (41.7ms). Measured on loopback: ~190 of 2530
    // frames off-nominal, one every 0.45s. Presented on arrival, each of those is held for the
    // wrong number of display refreshes — a short hiccup twice a second, independent of network,
    // decoder and reassembler (all of whose counters were clean throughout).
    //
    // So: hold a few frames and release each at its OWN `ptsMicros`, re-based onto the local clock.
    // This absorbs sender quantisation and network jitter with one mechanism. Cost is latency, and
    // for a world-locked video panel — not a head-tracked render path — that is not perceptual.
    @ObservationIgnored
    static let bufferFrames: Int = {
        if let s = ProcessInfo.processInfo.environment["FIELD4_RTT_BUFFER"], let v = Int(s) {
            return max(0, v)
        }
        return 3   // ~100ms at 30fps. 0 restores present-on-arrival.
    }()

    private struct Pending {
        let pixelBuffer: CVPixelBuffer
        let ptsMicros: UInt64
        let stereo: Bool
    }
    @ObservationIgnored private var pending: [Pending] = []
    /// Maps stream time onto local time: `wall(pts) = baseWall + (pts - basePts)/1e6`.
    @ObservationIgnored private var nextRelease: CFTimeInterval?
    @ObservationIgnored private var lastPresentedPts: UInt64?
    /// Frames discarded by the buffer itself — cap overflow, or superseded while catching up.
    /// Previously invisible, which hid the presenter falling behind.
    @ObservationIgnored private var bufferDropped = 0

    /// Decoded frames land here instead of being presented immediately.
    private func enqueue(_ pixelBuffer: CVPixelBuffer, ptsMicros: UInt64, stereo: Bool) {
        guard Self.bufferFrames > 0 else {
            present(pixelBuffer, stereo: stereo)   // buffering disabled → old behaviour
            return
        }
        // A sender restart resets pts to ~0; rebase rather than stall until the clocks agree again.
        if let l = lastPresentedPts, ptsMicros &+ 1_000_000 < l { resetClock() }
        pending.append(Pending(pixelBuffer: pixelBuffer, ptsMicros: ptsMicros, stereo: stereo))

        // Hard cap: if we somehow accumulate (decoder burst, clock drift), drop the oldest rather
        // than grow latency without bound.
        let cap = max(Self.bufferFrames * 4, 8)
        if pending.count > cap {
            bufferDropped += pending.count - cap
            pending.removeFirst(pending.count - cap)
            resetClock()
        }
    }

    private func resetClock() {
        nextRelease = nil
        lastPresentedPts = nil
    }

    /// Release the next buffered frame when its slot is due. Called once per RealityKit frame, so
    /// the release clock is the display clock.
    ///
    /// This is a depth-servoed clock, NOT a fixed pts→wall mapping. The first attempt pinned
    /// `baseWall` once and scheduled every frame off it; because the sender's real rate differs
    /// from nominal by a fraction of a percent, that error accumulated without bound — the queue
    /// drained, two frames came due in one tick, and the older was discarded (`bufDrop=+2` with
    /// `queue=1`, presented fps stuck ~26 against 30 arriving). A jitter buffer has to track the
    /// source's actual rate, so instead we release one frame per interval and nudge the interval
    /// slightly to hold the queue at `bufferFrames`. Slow, bounded correction: it absorbs jitter
    /// without ever throwing a frame away.
    func presentDue(now: CFTimeInterval) {
        guard Self.bufferFrames > 0, let head = pending.first else { return }

        // Prime: wait until the buffer is banked, then start the cadence.
        if nextRelease == nil {
            guard pending.count >= Self.bufferFrames else { return }
            nextRelease = now
        }
        guard let due = nextRelease, now >= due else { return }

        // Interval from the stream's own timeline where possible (adapts to any fps), else nominal.
        var interval = 1.0 / 30.0
        if pending.count > 1 {
            let d = Double(pending[1].ptsMicros &- head.ptsMicros) / 1_000_000.0
            if d > 0.004, d < 0.2 { interval = d }
        } else if let l = lastPresentedPts {
            let d = Double(head.ptsMicros &- l) / 1_000_000.0
            if d > 0.004, d < 0.2 { interval = d }
        }

        pending.removeFirst()
        lastPresentedPts = head.ptsMicros
        present(head.pixelBuffer, stereo: head.stereo)

        // Servo: run marginally fast when we are over-buffered, marginally slow when under. 4% is
        // far below the ~8ms jitter we are absorbing, so it never itself becomes visible.
        // Deadbanded and WEAK. An earlier 4% trim was itself a rate error: releases only happen on
        // a display refresh, so varying the interval by 4% (32-34.7ms against an 11.11ms grid) made
        // frames land on 3 refreshes sometimes and 4 others — manufacturing exactly the judder this
        // buffer exists to remove. Correct only when the queue is genuinely off target, and gently
        // enough that the release cadence stays locked to the same refresh count.
        var trim = 0.0
        if pending.count > Self.bufferFrames + 1 { trim = interval * 0.005 }
        else if pending.count < Self.bufferFrames - 1 { trim = -interval * 0.005 }
        nextRelease = due + interval - trim

        // Hard resync if we have fallen badly behind (app suspended, decoder stall) rather than
        // sprinting through a backlog.
        if now - (nextRelease ?? now) > 0.25 { nextRelease = now }
    }

    @ObservationIgnored private var reassembler: FrameReassembler?
    @ObservationIgnored private var lastTelemetry = CACurrentMediaTime()
    @ObservationIgnored private var lastCompleted = 0
    @ObservationIgnored private var lastDropped = 0
    @ObservationIgnored private var lastSkips = 0
    @ObservationIgnored private var lastHeld = 0
    @ObservationIgnored private var lastPipeDropped = 0
    @ObservationIgnored private var lastBufferDropped = 0

    // Concealment diagnostics: count distinct grey "runs" (edges into concealment), not every frame.
    private var wasConcealed = false
    private var concealedRuns = 0

    /// Concealment masking (opt-in via FIELD4_RTT_CONCEAL_MASK=1): when a coloured stream decodes a
    /// frame to neutral grey — a reference-broken (concealed) frame — hold the last good frame instead
    /// of flashing grey. Off by default: it hides genuine glitches too, so it's a demo/degradation aid,
    /// not a fix. The real fix for concealment is upstream (here it's the sim's software decoder).
    private static let concealMask = ProcessInfo.processInfo.environment["FIELD4_RTT_CONCEAL_MASK"] == "1"
    private var hasSeenColor = false
    private var heldConcealed = 0
    private var concealedMasked = 0
    /// Cap the hold so a genuinely neutral stream isn't frozen on an old frame forever (~2s at 20fps).
    private let maxConcealHold = 45

    func start(port: UInt16 = RTTStream.defaultPort) {
        guard !isRunning else { return }
        guard converter != nil else {
            status = "Metal unavailable — cannot present"
            return
        }
        decodedFrameCount = 0

        // Decode pipeline: lives on the recv queue; hands finished frames back to the main actor.
        let pipeline = RTTDecodePipeline()
        pipeline.onPixelBuffer = { [weak self] pixelBuffer, ptsMicros, stereo in
            Task { @MainActor in self?.enqueue(pixelBuffer, ptsMicros: ptsMicros, stereo: stereo) }
        }
        self.pipeline = pipeline

        let reassembler = FrameReassembler()
        self.reassembler = reassembler
        let receiver = RTTReceiver(port: port, reassembler: reassembler)
        receiver.onError = { [weak self] msg in
            Task { @MainActor in
                self?.status = "Error: \(msg)"
                self?.isRunning = false
            }
        }
        // Captures `pipeline` strongly; the closure runs on the receiver's serial queue, which is
        // the only thing that ever touches the pipeline (so decode submission stays single-threaded
        // and ordered). No self-owned decoder state is read off-main.
        receiver.onFrame = { frame in pipeline.submit(frame) }
        self.receiver = receiver
        receiver.start()

        isRunning = true
        status = "Listening on udp/\(port)"
    }

    func stop() {
        receiver?.stop()
        receiver = nil
        pipeline = nil
        reassembler = nil
        pending.removeAll()
        resetClock()
        isRunning = false
        status = "Stopped"
    }

    /// A presentation opened and wants the stream running. Starts the receiver on the first acquire;
    /// subsequent acquires just bump the count (the running receiver is shared).
    func acquire(port: UInt16 = RTTStream.resolvedPort) {
        presentationCount += 1
        if presentationCount == 1 { start(port: port) }
    }

    /// A presentation closed. Stops the receiver only when the last presentation releases it.
    func release() {
        presentationCount = max(0, presentationCount - 1)
        if presentationCount == 0 { stop() }
    }

    /// Main-actor: upload the decoded frame to the display texture and update stats.
    private func present(_ pixelBuffer: CVPixelBuffer, stereo: Bool) {
        if isStereo != stereo { isStereo = stereo }

        let concealed = Self.isNeutralChroma(pixelBuffer)

        // Concealment detector (quiet unless it fires): a P-frame decoded without its reference reads
        // neutral chroma. Log the edge into a grey run + the pipeline's drop counter so a residual
        // flash is pinned to its cause rather than sampled by eye.
        if concealed {
            if !wasConcealed {
                concealedRuns += 1
                shellLog.error("RTT concealed frame f\(self.decodedFrameCount) (run #\(self.concealedRuns), pipeline dropped=\(self.pipeline?.droppedFrames ?? 0), masking=\(Self.concealMask))")
            }
            wasConcealed = true
        } else {
            wasConcealed = false
            hasSeenColor = true
            heldConcealed = 0
        }

        // Concealment masking (behind FIELD4_RTT_CONCEAL_MASK): hold the last good frame rather than
        // push grey to the texture. The converter updates the texture in place, so simply skipping
        // the update leaves the previous frame on screen. Bounded by maxConcealHold so a genuinely
        // neutral stream isn't frozen forever. Still tick decodedFrameCount → the view redraws (and
        // re-samples the held frame) and fps stays honest.
        if Self.concealMask, concealed, hasSeenColor, displayTexture != nil, heldConcealed < maxConcealHold {
            heldConcealed += 1
            concealedMasked += 1
            if heldConcealed == 1 {
                shellLog.notice("RTT masking concealed frame(s) — holding last good frame (total masked=\(self.concealedMasked))")
            }
            decodedFrameCount += 1
            tickFPS()
            return
        }

        let resource = converter?.update(with: pixelBuffer)
        if let resource, resource !== displayTexture {
            displayTexture = resource
        }
        frameSize = CGSize(width: CVPixelBufferGetWidth(pixelBuffer),
                           height: CVPixelBufferGetHeight(pixelBuffer))
        if decodedFrameCount == 0 {
            shellLog.notice("RTT first decoded frame \(Int(self.frameSize.width))×\(Int(self.frameSize.height))")
        }
        if decodedFrameCount <= 40 || decodedFrameCount % 90 == 0 {
            Self.logCenterPixel(pixelBuffer, frame: decodedFrameCount)
        }
        decodedFrameCount += 1
        tickFPS()
    }

    /// True if the centre pixel's chroma is neutral (Cb≈Cr≈128) — a solid-colour stream reading this
    /// means a P-frame was decoded without its reference (concealed to grey).
    private static func isNeutralChroma(_ pb: CVPixelBuffer) -> Bool {
        guard CVPixelBufferGetPlaneCount(pb) >= 2 else { return false }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        guard let cB = CVPixelBufferGetBaseAddressOfPlane(pb, 1) else { return false }
        let cStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
        let c = cB.assumingMemoryBound(to: UInt8.self)
        let Cb = Int(c[(h / 4) * cStride + (w / 4) * 2])
        let Cr = Int(c[(h / 4) * cStride + (w / 4) * 2 + 1])
        return abs(Cb - 128) < 6 && abs(Cr - 128) < 6
    }

    /// Log the centre pixel's Y/Cb/Cr from an NV12 (biplanar, video-range) buffer + the RGB it maps
    /// to under BT.709 video-range — the ground truth of what the decoder produced from the wire.
    private static func logCenterPixel(_ pb: CVPixelBuffer, frame: Int = -1) {
        guard CVPixelBufferGetPlaneCount(pb) >= 2 else {
            shellLog.notice("RTT centre pixel: not biplanar (fmt \(CVPixelBufferGetPixelFormatType(pb)))"); return
        }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        guard let yB = CVPixelBufferGetBaseAddressOfPlane(pb, 0),
              let cB = CVPixelBufferGetBaseAddressOfPlane(pb, 1) else { return }
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
        let cStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
        let cx = w / 2, cy = h / 2
        let Y = Double(yB.assumingMemoryBound(to: UInt8.self)[cy * yStride + cx])
        let c = cB.assumingMemoryBound(to: UInt8.self)
        let Cb = Double(c[(cy / 2) * cStride + (cx / 2) * 2])
        let Cr = Double(c[(cy / 2) * cStride + (cx / 2) * 2 + 1])
        // BT.709 video-range YCbCr → RGB.
        let yv = (Y - 16) * 1.164, u = Cb - 128, v = Cr - 128
        let r = max(0, min(255, yv + 1.793 * v))
        let g = max(0, min(255, yv - 0.213 * u - 0.533 * v))
        let b = max(0, min(255, yv + 2.112 * u))
        shellLog.notice("RTT centre pixel f\(frame): Y=\(Int(Y)) Cb=\(Int(Cb)) Cr=\(Int(Cr)) → RGB≈(\(Int(r)),\(Int(g)),\(Int(b)))")
    }

    /// Once-a-second counters on **stdout**, not os_log, so `devicectl device process launch
    /// --console` can read them from the Mac. macOS 26 removed `log stream --device-name`, so
    /// os_log is only reachable through Xcode's console — stdout is the scriptable channel and the
    /// only way to get real numbers off the device into a diff.
    ///
    /// Deltas per second, not totals: what matters is whether stalls are happening *now* and at
    /// what rate, not how many have accumulated since launch.
    private func emitTelemetry(now: CFTimeInterval) {
        guard Self.statsEnabled, let r = reassembler else { return }
        let dt = max(0.001, now - lastTelemetry)
        let completed = r.completedFrames, dropped = r.droppedFrames
        let skips = r.reorderSkips, held = r.heldFrames
        let pipeDropped = pipeline?.droppedFrames ?? 0
        let line = String(format:
            "RTTSTAT fps=%.1f completed=+%d dropped=+%d reorderSkips=+%d held=+%d pipelineDropped=+%d bufDrop=+%d queue=%d depth=%d",
            measuredFPS,
            completed - lastCompleted, dropped - lastDropped,
            skips - lastSkips, held - lastHeld, pipeDropped - lastPipeDropped,
            bufferDropped - lastBufferDropped, pending.count,
            FrameReassembler.defaultReorderDepth)
        // Write OFF the main actor. `print` + `fflush` is a synchronous write to a pipe, and with
        // Xcode's debugger attached that pipe is drained by the IDE — the write can block for
        // milliseconds. Doing it on the main actor once every 0.5s put a stall on exactly the
        // cadence of the reported hiccup. Formatting stays here (it reads main-actor state); only
        // the write hops off.
        Self.statsQueue.async { print(line); fflush(stdout) }
        (lastCompleted, lastDropped, lastSkips, lastHeld, lastPipeDropped) =
            (completed, dropped, skips, held, pipeDropped)
        lastBufferDropped = bufferDropped
        lastTelemetry = now
        _ = dt
    }

    private func tickFPS() {
        fpsWindowCount += 1
        let now = CACurrentMediaTime()
        let elapsed = now - fpsWindowStart
        if elapsed >= 0.5 {
            measuredFPS = Double(fpsWindowCount) / elapsed
            fpsWindowCount = 0
            fpsWindowStart = now
            if isRunning {
                // Publish `status` only when it MEANINGFULLY changes.
                //
                // This label is rendered inside `.glassBackgroundEffect()` ornaments in two windows.
                // Writing it every 0.5s made SwiftUI re-render glass twice a second while the
                // immersive scene was presenting, and because the raw fps jitters 29↔31 the string
                // differed every single time, so nothing ever coalesced. That produced a visible
                // hitch locked exactly to this 0.5s window — the "hiccup every 500ms". It was never
                // the sender, the network, the reassembler or the decoder: all their counters were
                // clean throughout. Quantising to the nearest 5fps gives a string that is stable in
                // steady state, still moves on a real drop (30 → 15), and leaves the exact figure to
                // the RTTSTAT telemetry line where precision actually matters.
                let mode = isStereo ? " (stereo SBS)" : ""
                let shownFPS = Int((measuredFPS / 5.0).rounded()) * 5
                let next = "Streaming \(Int(frameSize.width))×\(Int(frameSize.height)) @ ~\(shownFPS) fps\(mode)"
                if next != status {
                    status = next
                    shellLog.notice("RTT \(self.status, privacy: .public)")
                }
                emitTelemetry(now: now)
            }
        }
    }
}
