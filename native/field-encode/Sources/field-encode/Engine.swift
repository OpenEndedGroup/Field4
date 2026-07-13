import Foundation
import IOSurface
import CoreVideo

/// Owns the encoder + UDP sender + resolved IOSurfaces, dispatches control commands, and
/// fragments each access unit onto the wire. stdout writes are serialized by `outLock`
/// because encoded access units arrive on the encoder's async output thread.
final class Engine {
    private var encoder: Encoder?
    private var sender: UDPSender?
    private var surfaces: [IOSurfaceRef] = []
    private var codecHEVC = true
    private let outLock = NSLock()

    func handle(_ cmd: Command) {
        switch cmd {
        case .alloc(let a):    alloc(a)
        case .open(let o):     open(o)
        case .frame(let f):    frame(f)
        case .bitrate(let k):  encoder?.setBitrate(kbps: k)
        case .selftest(let t): selftest(t)
        case .close:           encoder?.close(); emit(Event(ev: "closed"))
        }
    }

    /// Create a ring of properly-formatted (32BGRA) IOSurfaces here and return their global IDs,
    /// so the producer can import them as its RTT backing (see `Command.Alloc`). These *are*
    /// CoreVideo-wrappable because `IOSurfaceCreate` tags a real pixelFormat — unlike MoltenVK's
    /// export path, which leaves pixelFormat = 0.
    private func alloc(_ a: Command.Alloc) {
        let count = max(1, a.count)
        let ring = (0..<count).compactMap { _ in Engine.makeTestSurface(width: a.width, height: a.height, global: true) }
        guard ring.count == count else {
            emit(Event(ev: "error", msg: "IOSurfaceCreate failed (\(ring.count)/\(count))")); return
        }
        surfaces = ring
        let ids = ring.map { IOSurfaceGetID($0) }
        for (i, s) in ring.enumerated() { Engine.dumpSurface(s, index: i) }
        emit(Event(ev: "allocated", ids: ids))
    }

    /// Build the encoder + UDP sender. Shared by `open` and `selftest`.
    private func setup(width: Int, height: Int, fps: Int, bitrateKbps: Int,
                       dest: String, codec: String?, idrIntervalMs: Int?) -> Bool {
        codecHEVC = (codec ?? "hevc").lowercased() != "h264"
        guard let sender = UDPSender(dest: dest) else {
            emit(Event(ev: "error", msg: "bad dest \(dest)")); return false
        }
        self.sender = sender
        let idrFrames = max(1, ((idrIntervalMs ?? 1000) * max(1, fps)) / 1000)
        encoder = Encoder(width: width, height: height, fps: fps,
                          bitrateKbps: bitrateKbps, hevc: codecHEVC,
                          idrIntervalFrames: idrFrames) { [weak self] annexB, keyframe, seq, pts in
            self?.sendAccessUnit(annexB, keyframe: keyframe, seq: seq, pts: pts)
        }
        guard encoder != nil else { emit(Event(ev: "error", msg: "encoder init failed")); return false }
        return true
    }

    private func open(_ o: Command.Open) {
        guard setup(width: o.width, height: o.height, fps: o.fps, bitrateKbps: o.bitrateKbps,
                    dest: o.dest, codec: o.codec, idrIntervalMs: o.idrIntervalMs) else { return }
        let ids = o.surfaces ?? []
        if !ids.isEmpty {
            // Legacy path: look up surfaces created elsewhere by global ID.
            surfaces = ids.compactMap { IOSurfaceLookup(IOSurfaceID($0)) }
            guard surfaces.count == ids.count else {
                emit(Event(ev: "error", msg: "IOSurfaceLookup failed for one or more of \(ids)"))
                return
            }
            for (i, s) in surfaces.enumerated() { Engine.dumpSurface(s, index: i) }
        } else if surfaces.isEmpty {
            emit(Event(ev: "error", msg: "open with no surfaces and none allocated — call alloc first"))
            return
        }
        emit(Event(ev: "opened"))
    }

    /// Allocate a small ring of test IOSurfaces, fill each with an animated pattern, encode +
    /// stream `frames` of them paced at `fps`. Proves capture→encode→packetize→UDP with no RTT.
    private func selftest(_ t: Command.SelfTest) {
        guard setup(width: t.width, height: t.height, fps: t.fps, bitrateKbps: t.bitrateKbps,
                    dest: t.dest, codec: t.codec, idrIntervalMs: t.idrIntervalMs) else { return }
        let ring = (0..<3).compactMap { _ in Engine.makeTestSurface(width: t.width, height: t.height) }
        guard ring.count == 3 else { emit(Event(ev: "error", msg: "IOSurfaceCreate failed")); return }

        let frames = t.frames ?? 120
        let interval = 1.0 / Double(max(1, t.fps))
        emit(Event(ev: "selftestStart", seq: UInt32(frames)))
        for i in 0..<frames {
            let surf = ring[i % ring.count]
            Engine.fillTestPattern(surf, frame: i)
            encoder?.encode(ioSurface: surf, seq: UInt32(i),
                            ptsMicros: UInt64(Double(i) * interval * 1_000_000),
                            forceIdr: i == 0)
            Thread.sleep(forTimeInterval: interval)
        }
        encoder?.complete()
        Thread.sleep(forTimeInterval: 0.15) // let the last async callbacks drain to the socket
        emit(Event(ev: "selftestDone", seq: UInt32(frames)))
    }

    /// Log a resolved IOSurface's CV-relevant properties to stderr (Field surfaces them as
    /// `[info,field-encode]`). `pixelFormat` is the OSType — decode it as a FourCC so we can see
    /// whether it's `BGRA` (kCVPixelFormatType_32BGRA, what VideoToolbox wants) or something else.
    private static func dumpSurface(_ s: IOSurfaceRef, index: Int) {
        let fmt = IOSurfaceGetPixelFormat(s)
        let w = IOSurfaceGetWidth(s), h = IOSurfaceGetHeight(s)
        let bpr = IOSurfaceGetBytesPerRow(s), bpe = IOSurfaceGetBytesPerElement(s)
        let planes = IOSurfaceGetPlaneCount(s)
        let alloc = IOSurfaceGetAllocSize(s)
        FileHandle.standardError.write(Data(
            "surface[\(index)] pixelFormat=\(fourCC(fmt)) (0x\(String(fmt, radix: 16))) \(w)x\(h) bpr=\(bpr) bpe=\(bpe) planes=\(planes) alloc=\(alloc)\n".utf8))
    }

    private static func fourCC(_ v: UInt32) -> String {
        let bytes = [UInt8((v >> 24) & 0xff), UInt8((v >> 16) & 0xff), UInt8((v >> 8) & 0xff), UInt8(v & 0xff)]
        let printable = bytes.allSatisfy { $0 >= 0x20 && $0 < 0x7f }
        return printable ? String(bytes: bytes, encoding: .ascii) ?? "?" : "non-ascii"
    }

    // MARK: - Synthetic test surface (32BGRA)

    private static func makeTestSurface(width: Int, height: Int, global: Bool = false) -> IOSurfaceRef? {
        var props: [IOSurfacePropertyKey: Any] = [
            .width: width, .height: height, .bytesPerElement: 4,
            .pixelFormat: Int(kCVPixelFormatType_32BGRA)
        ]
        // `alloc`ed surfaces are looked up by another process (Field, via IOSurfaceLookup), which
        // only resolves *globally-registered* surfaces. Plain IOSurfaceCreate ones aren't global;
        // this key makes them so. (Verified: without it, cross-process IOSurfaceLookup fails.)
        if global { props[IOSurfacePropertyKey(rawValue: "IOSurfaceIsGlobal")] = true }
        return IOSurfaceCreate(props as CFDictionary)
    }

    private static func fillTestPattern(_ surf: IOSurfaceRef, frame: Int) {
        IOSurfaceLock(surf, IOSurfaceLockOptions(), nil)
        defer { IOSurfaceUnlock(surf, IOSurfaceLockOptions(), nil) }
        let w = IOSurfaceGetWidth(surf), h = IOSurfaceGetHeight(surf)
        let stride = IOSurfaceGetBytesPerRow(surf)
        let base = IOSurfaceGetBaseAddress(surf).assumingMemoryBound(to: UInt8.self)
        for y in 0..<h {
            let row = base + y * stride
            for x in 0..<w {
                let p = row + x * 4
                p[0] = UInt8((x + y) & 0xff)          // B
                p[1] = UInt8((y + frame * 2) & 0xff)  // G
                p[2] = UInt8((x + frame * 4) & 0xff)  // R  (moving diagonal)
                p[3] = 255                            // A
            }
        }
    }

    private func frame(_ f: Command.Frame) {
        guard f.surface >= 0, f.surface < surfaces.count else {
            emit(Event(ev: "error", msg: "bad surface index \(f.surface)")); return
        }
        encoder?.encode(ioSurface: surfaces[f.surface], seq: f.seq,
                        ptsMicros: f.ptsMicros, forceIdr: f.forceIdr ?? false)
    }

    private func sendAccessUnit(_ annexB: [UInt8], keyframe: Bool, seq: UInt32, pts: UInt64) {
        guard let sender = sender, !annexB.isEmpty else { return }
        let codec = codecHEVC ? Wire.codecHEVC : Wire.codecH264
        let total = annexB.count
        let fragCount = UInt16((total + Wire.maxPayload - 1) / Wire.maxPayload)

        var sent = 0
        var offset = 0
        var frag: UInt16 = 0
        while offset < total {
            let chunk = min(Wire.maxPayload, total - offset)
            var flags: UInt8 = 0
            if keyframe { flags |= Wire.flagKeyframe }
            if frag == fragCount - 1 { flags |= Wire.flagFrameEnd }
            var pkt = Wire.header(flags: flags, codec: codec, frameSeq: seq,
                                  fragIndex: frag, fragCount: fragCount, ptsMicros: pts)
            pkt.append(contentsOf: annexB[offset..<offset + chunk])
            sender.send(pkt)
            sent += pkt.count
            offset += chunk
            frag += 1
        }
        emit(Event(ev: "encoded", seq: seq, bytes: sent))
    }

    private func emit(_ event: Event) {
        guard let data = try? JSONEncoder().encode(event) else { return }
        outLock.lock(); defer { outLock.unlock() }
        FileHandle.standardOutput.write(data)
        FileHandle.standardOutput.write(Data([0x0a]))
    }
}
