import Foundation
import Observation
import CoreVideo
import RealityKit
import QuartzCore

/// Owns the decode side of the pipeline (reassembled frame → VideoToolbox → CVPixelBuffer),
/// living entirely on the receiver's serial queue. Kept off the main actor so `RTTStream` can be
/// `@MainActor` for SwiftUI without dragging decoder state onto the main thread; the only thing
/// that crosses back is the finished pixel buffer via `onPixelBuffer`.
final class RTTDecodePipeline {
    var onPixelBuffer: ((CVPixelBuffer, UInt64) -> Void)?

    private var decoder: VideoDecoder?
    private var codec: RTTWire.Codec?

    /// Feed one reassembled access unit. Recreates the decoder if the stream's codec changes.
    func ingest(_ frame: ReassembledFrame) {
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

    var isRunning = false
    var status = "Idle"
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

    func start(port: UInt16 = RTTStream.defaultPort) {
        guard !isRunning else { return }
        guard converter != nil else {
            status = "Metal unavailable — cannot present"
            return
        }
        decodedFrameCount = 0

        // Decode pipeline: lives on the recv queue; hands finished frames back to the main actor.
        let pipeline = RTTDecodePipeline()
        pipeline.onPixelBuffer = { [weak self] pixelBuffer, _ in
            Task { @MainActor in self?.present(pixelBuffer) }
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
        receiver.onFrame = { frame in pipeline.ingest(frame) }
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

    /// Main-actor: upload the decoded frame to the display texture and update stats.
    private func present(_ pixelBuffer: CVPixelBuffer) {
        let resource = converter?.update(with: pixelBuffer)
        if let resource, resource !== displayTexture {
            displayTexture = resource
        }
        frameSize = CGSize(width: CVPixelBufferGetWidth(pixelBuffer),
                           height: CVPixelBufferGetHeight(pixelBuffer))
        if decodedFrameCount == 0 {
            shellLog.notice("RTT first decoded frame \(Int(self.frameSize.width))×\(Int(self.frameSize.height))")
        }
        decodedFrameCount += 1
        tickFPS()
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
                status = "Streaming \(Int(frameSize.width))×\(Int(frameSize.height)) @ \(Int(measuredFPS.rounded())) fps"
                shellLog.notice("RTT \(self.status, privacy: .public)")
            }
        }
    }
}
