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

        while running {
            let n = recv(s, &buf, bufSize, 0)
            if n < 0 {
                if running { onError?("recv() failed: \(errnoString())") }
                break
            }
            if n == 0 { continue }
            datagramsReceived += 1
            bytesReceived += n

            let datagram = Data(bytes: buf, count: n)
            guard let pkt = RTTPacket(datagram: datagram) else { continue }  // malformed / bad version
            if let frame = reassembler.ingest(pkt) {
                onFrame?(frame)
            }
        }

        if fd >= 0 { close(fd); fd = -1 }
        running = false
        shellLog.notice("RTT receiver stopped (udp/\(self.port))")
    }

    private func errnoString() -> String { String(cString: strerror(errno)) }
}
