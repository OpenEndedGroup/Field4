import Foundation
import VideoToolbox
import CoreMedia
import CoreVideo

/// Decodes reassembled Annex-B access units into `CVPixelBuffer`s with VideoToolbox, per
/// `WIRE_FORMAT.md` step 3: build a `CMVideoFormatDescription` from the inline VPS/SPS/PPS on the
/// first keyframe, then feed access units to a `VTDecompressionSession`.
///
/// The sender serializes to Annex-B (`00 00 00 01` start codes, param sets inline on keyframes);
/// VideoToolbox wants length-prefixed NAL units + a format description built from the param sets,
/// so this class does that rewrite. Decoded frames are delivered NV12 (biplanar YUV) for a cheap
/// hand-off to the Metal YUV→RGB converter.
final class VideoDecoder {
    /// Decoded frames, in decode order (reordering is disabled on the encoder). Called on whatever
    /// queue VideoToolbox uses; the caller hops as needed.
    var onPixelBuffer: ((CVPixelBuffer, _ ptsMicros: UInt64, _ stereo: Bool) -> Void)?

    private var session: VTDecompressionSession?
    private var formatDesc: CMFormatDescription?
    private let codec: RTTWire.Codec
    /// Fingerprint of the param sets the current format description was built from — rebuild the
    /// session if the stream's SPS/PPS change (resolution change, new IDR params).
    private var paramSetKey: Data?

    /// NAL-structure diagnostic, opt-in via FIELD4_RTT_NAL_DEBUG=1 (see `decode`).
    private static let nalDebug = ProcessInfo.processInfo.environment["FIELD4_RTT_NAL_DEBUG"] == "1"
    private var nalDebugCount = 0

    init(codec: RTTWire.Codec) {
        self.codec = codec
    }

    deinit { invalidate() }

    func invalidate() {
        if let session {
            VTDecompressionSessionInvalidate(session)
        }
        session = nil
        formatDesc = nil
        paramSetKey = nil
    }

    /// Decode one access unit. Must begin at (or shortly after) a keyframe: until we've seen the
    /// inline param sets we can't build a format description, so pre-keyframe frames are dropped.
    func decode(_ frame: ReassembledFrame) {
        let nals = Self.splitAnnexB(frame.annexB)
        guard !nals.isEmpty else { return }

        // NAL structure probe (FIELD4_RTT_NAL_DEBUG=1): dump per-frame NAL types + sizes for the
        // first frames so a foreign sender's P-frame encoding can be inspected. A well-formed
        // reference P-frame is HEVC NAL type 1 (TRAIL_R); type 0 (TRAIL_N) is a non-reference frame
        // and won't be kept in the DPB → downstream P-frames concealing to grey.
        if Self.nalDebug, nalDebugCount < 8 {
            nalDebugCount += 1
            let desc = nals.map { "\(Self.hevcNalType($0))(\($0.count)B)" }.joined(separator: " ")
            shellLog.notice("RTT NALs seq=\(frame.frameSeq) pts=\(frame.ptsMicros) kf=\(frame.isKeyframe) → \(desc, privacy: .public)")
            // Hex-dump SPS (whole) and the first bytes of each VCL NAL (slice header lives here) so a
            // sender's reference/POC config can be diffed against a known-good encoder.
            for nal in nals {
                let t = (nal.first ?? 0) >> 1 & 0x3F
                let dump = (t == 33) ? nal : nal.prefix(16)   // whole SPS; slice-header head otherwise
                let hex = dump.map { String(format: "%02x", $0) }.joined(separator: " ")
                shellLog.notice("RTT hex \(Self.hevcNalType(nal), privacy: .public): \(hex, privacy: .public)")
            }
        }

        // Keyframes carry VPS/SPS/PPS inline — (re)build the format description + session from them.
        if frame.isKeyframe {
            let paramSets = nals.filter { Self.isParameterSet($0, codec: codec) }
            if !paramSets.isEmpty {
                let key = Self.fingerprint(paramSets)
                if key != paramSetKey {
                    rebuild(with: paramSets)
                    paramSetKey = key
                }
            }
        }

        guard let session, let formatDesc else {
            // Haven't locked onto a keyframe's params yet — wait for the next IDR (bounded by the
            // sender's idrIntervalMs).
            return
        }

        // VCL NAL units only — strip the param sets, they live in the format description now.
        let vcl = nals.filter { !Self.isParameterSet($0, codec: codec) }
        guard !vcl.isEmpty else { return }

        guard let sampleBuffer = Self.makeSampleBuffer(vcl: vcl, format: formatDesc,
                                                       ptsMicros: frame.ptsMicros) else { return }

        // Low-latency I/P stream (no B-frames): decode in submission order, no temporal reordering.
        // `_EnableTemporalProcessing` told VT to reorder for B-frames and left it mishandling the
        // P-frame reference chain (P-frames concealed to grey). Empty flags = decode in order,
        // output immediately, keeping the previous frame in the DPB as the P-frame reference.
        let flags: VTDecodeFrameFlags = []
        _ = VTDecompressionSessionDecodeFrame(
            session, sampleBuffer: sampleBuffer, flags: flags, infoFlagsOut: nil
        ) { [weak self] status, _, imageBuffer, pts, _ in
            guard status == noErr, let imageBuffer else {
                if status != noErr { shellLog.error("VT decode status \(status)") }
                return
            }
            let micros = pts.isValid ? UInt64(CMTimeGetSeconds(pts) * 1_000_000) : frame.ptsMicros
            self?.onPixelBuffer?(imageBuffer, micros, frame.stereo)
        }
    }

    // MARK: - Session / format description

    private func rebuild(with paramSets: [Data]) {
        invalidate()
        guard let fmt = Self.makeFormatDescription(paramSets: paramSets, codec: codec) else {
            shellLog.error("RTT: failed to build format description from \(paramSets.count) param sets")
            return
        }
        formatDesc = fmt

        // NV12 output — biplanar 4:2:0, the natural VT output and a one-copy path into Metal.
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferMetalCompatibilityKey: true,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
        ]
        var created: VTDecompressionSession?
        let st = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: fmt,
            decoderSpecification: nil,
            imageBufferAttributes: attrs as CFDictionary,
            outputCallback: nil,
            decompressionSessionOut: &created)
        guard st == noErr, let created else {
            shellLog.error("RTT: VTDecompressionSessionCreate failed \(st)")
            return
        }
        session = created
    }

    private static func makeFormatDescription(paramSets: [Data], codec: RTTWire.Codec) -> CMFormatDescription? {
        // Copy each param set into a stable heap buffer (an array's element pointer would dangle),
        // then hand the create-from APIs a pointer-of-pointers. The format description copies the
        // param sets internally, so the buffers can be freed right after.
        var pointers: [UnsafePointer<UInt8>] = []
        var sizes: [Int] = []
        var allocations: [UnsafeMutablePointer<UInt8>] = []
        defer { allocations.forEach { $0.deallocate() } }
        for ps in paramSets {
            let n = ps.count
            let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: n)
            ps.copyBytes(to: buf, count: n)
            allocations.append(buf)
            pointers.append(UnsafePointer(buf))
            sizes.append(n)
        }

        var fmt: CMFormatDescription?
        let st: OSStatus = pointers.withUnsafeBufferPointer { ptrBuf in
            sizes.withUnsafeBufferPointer { sizeBuf in
                switch codec {
                case .hevc:
                    return CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                        allocator: kCFAllocatorDefault,
                        parameterSetCount: paramSets.count,
                        parameterSetPointers: ptrBuf.baseAddress!,
                        parameterSetSizes: sizeBuf.baseAddress!,
                        nalUnitHeaderLength: 4,
                        extensions: nil,
                        formatDescriptionOut: &fmt)
                case .h264:
                    return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                        allocator: kCFAllocatorDefault,
                        parameterSetCount: paramSets.count,
                        parameterSetPointers: ptrBuf.baseAddress!,
                        parameterSetSizes: sizeBuf.baseAddress!,
                        nalUnitHeaderLength: 4,
                        formatDescriptionOut: &fmt)
                }
            }
        }
        return st == noErr ? fmt : nil
    }

    /// Wrap VCL NAL units as one length-prefixed (4-byte big-endian) CMSampleBuffer.
    private static func makeSampleBuffer(vcl: [Data], format: CMFormatDescription,
                                         ptsMicros: UInt64) -> CMSampleBuffer? {
        var payload = Data()
        for nal in vcl {
            var len = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &len) { payload.append(contentsOf: $0) }
            payload.append(nal)
        }

        var blockBuffer: CMBlockBuffer?
        let bytes = [UInt8](payload)
        var st = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: bytes.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: bytes.count,
            flags: 0,
            blockBufferOut: &blockBuffer)
        guard st == kCMBlockBufferNoErr, let blockBuffer else { return nil }
        st = bytes.withUnsafeBytes {
            CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: blockBuffer,
                                          offsetIntoDestination: 0, dataLength: bytes.count)
        }
        guard st == kCMBlockBufferNoErr else { return nil }

        var sampleBuffer: CMSampleBuffer?
        var sampleSize = bytes.count
        // No B-frames ⇒ decode order == presentation order, so DTS == PTS. Giving VT a real,
        // monotonic decode timestamp (not `.invalid`) lets it order the DPB and thread references.
        let ts = CMTime(value: CMTimeValue(ptsMicros), timescale: 1_000_000)
        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: ts,
            decodeTimeStamp: ts)
        st = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: format,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer)
        guard st == noErr else { return nil }
        return sampleBuffer
    }

    // MARK: - Annex-B parsing

    /// Split an Annex-B buffer into raw NAL units (start codes removed). Handles 3- and 4-byte
    /// start codes; the sender emits 4-byte but we stay lenient.
    static func splitAnnexB(_ data: Data) -> [Data] {
        var nals: [Data] = []
        let bytes = [UInt8](data)
        let n = bytes.count
        var i = 0

        func startCodeLen(at p: Int) -> Int {
            if p + 3 < n, bytes[p] == 0, bytes[p + 1] == 0, bytes[p + 2] == 0, bytes[p + 3] == 1 { return 4 }
            if p + 2 < n, bytes[p] == 0, bytes[p + 1] == 0, bytes[p + 2] == 1 { return 3 }
            return 0
        }

        // Find first start code.
        while i < n, startCodeLen(at: i) == 0 { i += 1 }
        while i < n {
            let sc = startCodeLen(at: i)
            guard sc > 0 else { break }
            let nalStart = i + sc
            var j = nalStart
            while j < n, startCodeLen(at: j) == 0 { j += 1 }
            if nalStart < j {
                nals.append(Data(bytes[nalStart..<j]))
            }
            i = j
        }
        return nals
    }

    static func isParameterSet(_ nal: Data, codec: RTTWire.Codec) -> Bool {
        guard let first = nal.first else { return false }
        switch codec {
        case .hevc:
            let type = (first >> 1) & 0x3F
            return type == 32 || type == 33 || type == 34   // VPS / SPS / PPS
        case .h264:
            let type = first & 0x1F
            return type == 7 || type == 8                    // SPS / PPS
        }
    }

    /// HEVC NAL unit type (`(byte0 >> 1) & 0x3F`) as a short label for the debug dump. Key types:
    /// 0=TRAIL_N (non-ref), 1=TRAIL_R (ref P/B), 19/20=IDR, 21=CRA, 32=VPS, 33=SPS, 34=PPS.
    private static func hevcNalType(_ nal: Data) -> String {
        guard let first = nal.first else { return "?" }
        let t = (first >> 1) & 0x3F
        let name: String
        switch t {
        case 0: name = "TRAIL_N"; case 1: name = "TRAIL_R"
        case 19: name = "IDR_W_RADL"; case 20: name = "IDR_N_LP"; case 21: name = "CRA"
        case 32: name = "VPS"; case 33: name = "SPS"; case 34: name = "PPS"
        case 39, 40: name = "SEI"
        default: name = "t\(t)"
        }
        return "\(name)#\(t)"
    }

    private static func fingerprint(_ paramSets: [Data]) -> Data {
        var out = Data()
        for ps in paramSets { out.append(ps) }
        return out
    }
}
