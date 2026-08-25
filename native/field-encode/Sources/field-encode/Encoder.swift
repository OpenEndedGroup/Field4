import Foundation
import VideoToolbox
import CoreVideo
import CoreMedia
import IOSurface

/// Wraps a VTCompressionSession for low-latency HEVC/H.264. Input is an IOSurface (the RTT
/// the producer rendered SBS into); output is a complete Annex-B access unit delivered to
/// `onAccessUnit`, with the seq/pts/pose of the originating frame threaded through the encode
/// closure (so there is no shared mutable frame counter across the async callback).
final class Encoder {
    typealias AccessUnitHandler = (_ annexB: [UInt8], _ keyframe: Bool, _ seq: UInt32, _ pts: UInt64,
                                   _ pose: PoseTag?) -> Void

    private var session: VTCompressionSession?
    private let codec: CMVideoCodecType
    private let onAccessUnit: AccessUnitHandler

    init?(width: Int, height: Int, fps: Int, bitrateKbps: Int, hevc: Bool,
          idrIntervalFrames: Int, onAccessUnit: @escaping AccessUnitHandler) {
        self.codec = hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264
        self.onAccessUnit = onAccessUnit
        self.session = nil

        let spec: [CFString: Any] = [
            kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: true
        ]
        var created: VTCompressionSession?
        let st = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: Int32(width), height: Int32(height),
            codecType: codec,
            encoderSpecification: spec as CFDictionary,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil,
            compressionSessionOut: &created)
        guard st == noErr, let session = created else { return nil }
        self.session = session

        func set(_ key: CFString, _ value: CFTypeRef) {
            VTSessionSetProperty(session, key: key, value: value)
        }
        set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        set(kVTCompressionPropertyKey_ProfileLevel,
            hevc ? kVTProfileLevel_HEVC_Main_AutoLevel : kVTProfileLevel_H264_High_AutoLevel)
        set(kVTCompressionPropertyKey_AverageBitRate, NSNumber(value: bitrateKbps * 1000))
        set(kVTCompressionPropertyKey_ExpectedFrameRate, NSNumber(value: fps))
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, NSNumber(value: idrIntervalFrames))
        // Cap short-term rate to ~1.5x average over 1s to keep packets flowing smoothly.
        let bytesPerSec = bitrateKbps * 1000 / 8 * 3 / 2
        set(kVTCompressionPropertyKey_DataRateLimits,
            [NSNumber(value: bytesPerSec), NSNumber(value: 1)] as CFArray)
        VTCompressionSessionPrepareToEncodeFrames(session)
    }

    func encode(ioSurface: IOSurfaceRef, seq: UInt32, ptsMicros: UInt64, forceIdr: Bool,
                pose: PoseTag? = nil) {
        guard let session = session else { return }
        var pb: Unmanaged<CVPixelBuffer>?
        let st = CVPixelBufferCreateWithIOSurface(kCFAllocatorDefault, ioSurface, nil, &pb)
        guard st == kCVReturnSuccess, let pixelBuffer = pb?.takeRetainedValue() else {
            log("CVPixelBufferCreateWithIOSurface failed: \(st)"); return
        }
        let pts = CMTime(value: CMTimeValue(ptsMicros), timescale: 1_000_000)
        let props: CFDictionary? = forceIdr
            ? [kVTEncodeFrameOptionKey_ForceKeyFrame as String: true] as CFDictionary
            : nil
        VTCompressionSessionEncodeFrame(
            session, imageBuffer: pixelBuffer, presentationTimeStamp: pts,
            duration: .invalid, frameProperties: props, infoFlagsOut: nil
        ) { [weak self] status, _, sample in
            guard let self = self, status == noErr, let sample = sample else { return }
            self.emit(sample, seq: seq, pts: ptsMicros, pose: pose)
        }
    }

    func setBitrate(kbps: Int) {
        guard let session = session else { return }
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate,
                             value: NSNumber(value: kbps * 1000))
    }

    /// Flush any in-flight frames through the async output handler without tearing down.
    func complete() {
        if let session = session {
            VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        }
    }

    func close() {
        if let session = session {
            VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(session)
        }
        session = nil
    }

    // MARK: - CMSampleBuffer -> Annex-B

    private func emit(_ sample: CMSampleBuffer, seq: UInt32, pts: UInt64, pose: PoseTag?) {
        let keyframe = Encoder.isKeyframe(sample)
        let startCode: [UInt8] = [0, 0, 0, 1]
        var annexB = [UInt8]()

        // On keyframes, prepend the parameter sets inline so a mid-stream joiner can start here.
        if keyframe, let fmt = CMSampleBufferGetFormatDescription(sample) {
            for ps in Encoder.parameterSets(fmt, hevc: codec == kCMVideoCodecType_HEVC) {
                annexB += startCode; annexB += ps
            }
        }

        guard let block = CMSampleBufferGetDataBuffer(sample) else { return }
        var length = 0, total = 0
        var ptr: UnsafeMutablePointer<CChar>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: &length,
                                          totalLengthOut: &total, dataPointerOut: &ptr) == noErr,
              let base = ptr else { return }
        let bytes = UnsafeRawPointer(base).assumingMemoryBound(to: UInt8.self)

        // VideoToolbox emits length-prefixed NAL units (4-byte big-endian length). Rewrite to
        // Annex-B start codes.
        var i = 0
        while i + 4 <= total {
            let nalLen = (Int(bytes[i]) << 24) | (Int(bytes[i + 1]) << 16)
                       | (Int(bytes[i + 2]) << 8) | Int(bytes[i + 3])
            i += 4
            if nalLen <= 0 || i + nalLen > total { break }
            annexB += startCode
            annexB.append(contentsOf: UnsafeBufferPointer(start: bytes + i, count: nalLen))
            i += nalLen
        }

        onAccessUnit(annexB, keyframe, seq, pts, pose)
    }

    private static func isKeyframe(_ sample: CMSampleBuffer) -> Bool {
        guard let arr = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false)
                as? [[String: Any]], let first = arr.first else { return true }
        if let notSync = first[kCMSampleAttachmentKey_NotSync as String] as? Bool { return !notSync }
        return true // absence of NotSync => sync sample (keyframe)
    }

    private static func parameterSets(_ fmt: CMFormatDescription, hevc: Bool) -> [[UInt8]] {
        var count = 0
        if hevc {
            _ = CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                fmt, parameterSetIndex: 0, parameterSetPointerOut: nil,
                parameterSetSizeOut: nil, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
        } else {
            _ = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                fmt, parameterSetIndex: 0, parameterSetPointerOut: nil,
                parameterSetSizeOut: nil, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
        }
        var sets = [[UInt8]]()
        for idx in 0..<count {
            var p: UnsafePointer<UInt8>?
            var size = 0
            let st = hevc
                ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                    fmt, parameterSetIndex: idx, parameterSetPointerOut: &p,
                    parameterSetSizeOut: &size, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                    fmt, parameterSetIndex: idx, parameterSetPointerOut: &p,
                    parameterSetSizeOut: &size, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
            if st == noErr, let p = p {
                sets.append(Array(UnsafeBufferPointer(start: p, count: size)))
            }
        }
        return sets
    }
}
