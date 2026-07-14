import Foundation
import Darwin

/// The UDP half of the receiver: one socket bound to the media port, one `recvfrom` loop feeding
/// datagrams through the `FrameReassembler`. Whole access units are handed to `onFrame` on the
/// receive queue (the caller hops to wherever it needs).
///
/// This is deliberately a plain BSD socket, matching `WIRE_FORMAT.md`'s "one `recvfrom` loop":
/// the stream is one-way and connectionless, so there's no handshake, no per-peer connection to
/// juggle, and cancellation is just "close the socket and let `recvfrom` fail out."
final class RTTReceiver {
    let port: UInt16
    private let reassembler: FrameReassembler
    private let queue = DispatchQueue(label: "com.field4.vision.rtt.recv", qos: .userInteractive)

    private var fd: Int32 = -1
    private var running = false

    /// Called on the receive queue with each completed access unit.
    var onFrame: ((ReassembledFrame) -> Void)?
    /// Called on the receive queue for a fatal socket error (bind/recv failure).
    var onError: ((String) -> Void)?

    // Diagnostics (read from any thread; single writer on the recv queue).
    private(set) var datagramsReceived = 0
    private(set) var bytesReceived = 0
    private(set) var malformedDatagrams = 0

    init(port: UInt16, reassembler: FrameReassembler = FrameReassembler()) {
        self.port = port
        self.reassembler = reassembler
    }

    func start() {
        queue.async { [weak self] in self?.openAndLoop() }
    }

    func stop() {
        // Flip the flag then close the fd out from under `recvfrom`, which unblocks it with EBADF.
        queue.async { [weak self] in
            guard let self else { return }
            self.running = false
            if self.fd >= 0 { close(self.fd); self.fd = -1 }
        }
    }

    private func openAndLoop() {
        let s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard s >= 0 else { onError?("socket() failed: \(errnoString())"); return }

        // Allow quick rebinds across app relaunches.
        var yes: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        // A generous receive buffer — bursty IDR frames fragment into many datagrams at once.
        var rcvbuf: Int32 = 4 * 1024 * 1024
        setsockopt(s, SOL_SOCKET, SO_RCVBUF, &rcvbuf, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = INADDR_ANY.bigEndian    // bind all interfaces (loopback + tailnet)
        addr.sin_port = port.bigEndian
        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else {
            let msg = "bind(:\(port)) failed: \(errnoString())"
            shellLog.error("RTT receiver \(msg, privacy: .public)")
            close(s)
            onError?(msg)
            return
        }

        fd = s
        running = true
        shellLog.notice("RTT receiver listening on udp/\(self.port)")

        // Max UDP payload; the sender caps fragments well under MTU, so this is comfortably large.
        let bufSize = 65_536
        var buf = [UInt8](repeating: 0, count: bufSize)
        var loggedPeer = false

        while running {
            // recvfrom (not recv) so we can log the sender's source address — the "a remote box
            // connected and is sending" signal for smoke-testing across machines (e.g. mi2:<port>).
            var src = sockaddr_storage()
            var srcLen = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let n = withUnsafeMutablePointer(to: &src) { sp in
                sp.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    recvfrom(s, &buf, bufSize, 0, sa, &srcLen)
                }
            }
            if n < 0 {
                if running { onError?("recv() failed: \(errnoString())") }
                break
            }
            if n == 0 { continue }
            if !loggedPeer {
                loggedPeer = true
                shellLog.notice("RTT first packet (\(n) bytes) from \(Self.peerString(&src), privacy: .public)")
            }
            datagramsReceived += 1
            bytesReceived += n

            let datagram = Data(bytes: buf, count: n)
            guard let pkt = RTTPacket(datagram: datagram) else {
                // Malformed — the prime failure mode for a new/foreign sender. Log the reason
                // (throttled) so a bad wire format isn't a silent black hole.
                malformedDatagrams += 1
                if malformedDatagrams == 1 || malformedDatagrams % 256 == 0 {
                    shellLog.error("RTT dropped malformed datagram #\(self.malformedDatagrams): \(Self.rejectReason(buf, n), privacy: .public)")
                }
                continue
            }
            for frame in reassembler.ingest(pkt) {
                onFrame?(frame)
            }

            // Periodic rx heartbeat: makes "arriving but not completing" visible (loss, bad
            // fragCount/fragIndex → datagrams climb while completed stays flat).
            if datagramsReceived % 300 == 0 {
                shellLog.notice("RTT rx: \(self.datagramsReceived) datagrams, \(self.reassembler.completedFrames) frames, \(self.reassembler.droppedFrames) dropped, \(self.malformedDatagrams) malformed")
            }
        }

        if fd >= 0 { close(fd); fd = -1 }
        running = false
        shellLog.notice("RTT receiver stopped (udp/\(self.port))")
    }

    private func errnoString() -> String { String(cString: strerror(errno)) }

    /// Reconstruct *why* `RTTPacket.init` rejected a datagram (mirrors its guards), so a malformed
    /// stream reports the actual fault — wrong version, bad codec, short header, or frag range.
    private static func rejectReason(_ buf: [UInt8], _ n: Int) -> String {
        if n < RTTWire.headerSize { return "short (\(n)B < \(RTTWire.headerSize))" }
        if buf[0] != RTTWire.version { return "version=\(buf[0]) (want \(RTTWire.version)), first bytes \(buf[0]) \(buf[1]) \(buf[2]) \(buf[3])" }
        if RTTWire.Codec(rawValue: buf[2]) == nil { return "codec=\(buf[2]) (want 0=HEVC/1=H264)" }
        let fragIndex = (UInt16(buf[8]) << 8) | UInt16(buf[9])
        let fragCount = (UInt16(buf[10]) << 8) | UInt16(buf[11])
        return "frag \(fragIndex)/\(fragCount) out of range"
    }

    /// "ip:port" for a received datagram's source (IPv4 or IPv6), for smoketest logging.
    private static func peerString(_ src: inout sockaddr_storage) -> String {
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        var serv = [CChar](repeating: 0, count: Int(NI_MAXSERV))
        let len = socklen_t(src.ss_len)
        let rc = withUnsafePointer(to: &src) { sp in
            sp.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                getnameinfo(sa, len, &host, socklen_t(host.count), &serv, socklen_t(serv.count),
                            NI_NUMERICHOST | NI_NUMERICSERV)
            }
        }
        guard rc == 0 else { return "unknown" }
        return "\(String(cString: host)):\(String(cString: serv))"
    }
}
