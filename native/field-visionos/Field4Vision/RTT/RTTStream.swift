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
    var measuredFPS = 0.0
    /// The live RGBA texture the immersive view samples. Replaced only when the frame size changes
    /// (otherwise the same resource updates in place), so the view can bind it once.
    var displayTexture: TextureResource?

    private var receiver: RTTReceiver?
    private var pipeline: RTTDecodePipeline?
    private let converter = PixelBufferTextureConverter()

    private var fpsWindowStart = CACurrentMediaTime()
    private var fpsWindowCount = 0

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
        pipeline.onPixelBuffer = { [weak self] pixelBuffer, _, stereo in
            Task { @MainActor in self?.present(pixelBuffer, stereo: stereo) }
        }
        self.pipeline = pipeline

        let receiver = RTTReceiver(port: port)
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

    private func tickFPS() {
        fpsWindowCount += 1
        let now = CACurrentMediaTime()
        let elapsed = now - fpsWindowStart
        if elapsed >= 0.5 {
            measuredFPS = Double(fpsWindowCount) / elapsed
            fpsWindowCount = 0
            fpsWindowStart = now
            if isRunning {
                let mode = isStereo ? " (stereo SBS)" : ""
                status = "Streaming \(Int(frameSize.width))×\(Int(frameSize.height)) @ \(Int(measuredFPS.rounded())) fps\(mode)"
                shellLog.notice("RTT \(self.status, privacy: .public)")
            }
        }
    }
}
