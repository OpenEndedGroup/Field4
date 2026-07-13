import Foundation
import Darwin

/// A minimal one-way UDP sender to a fixed "host:port". No retransmit, no congestion
/// control — that's deliberate (see WIRE_FORMAT.md). `sendto` on a shared fd is
/// thread-safe, so the encoder's output thread can call `send` directly.
///
/// `host` is resolved with `getaddrinfo`, so it accepts DNS/Tailscale MagicDNS names
/// (e.g. `mi2`), IPv4 literals, and bracketed IPv6 literals (`[fd7a:…]:9000`) — the shell
/// addresses peers over Tailscale by name, so numeric-only parsing would not do.
final class UDPSender {
    private let fd: Int32
    private var storage = sockaddr_storage()
    private let addrLen: socklen_t

    init?(dest: String) {
        // Split on the LAST colon so bracketed IPv6 literals survive; strip [] from the host.
        guard let sep = dest.range(of: ":", options: .backwards) else { return nil }
        var host = String(dest[dest.startIndex..<sep.lowerBound])
        let portStr = String(dest[sep.upperBound...])
        if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
        guard !host.isEmpty, UInt16(portStr) != nil else { return nil }

        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_DGRAM
        hints.ai_protocol = IPPROTO_UDP
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, portStr, &hints, &res) == 0, let info = res else { return nil }
        defer { freeaddrinfo(res) }

        let s = socket(info.pointee.ai_family, SOCK_DGRAM, 0)
        guard s >= 0 else { return nil }
        self.fd = s
        self.addrLen = info.pointee.ai_addrlen
        withUnsafeMutablePointer(to: &storage) { dst in
            dst.withMemoryRebound(to: UInt8.self, capacity: Int(info.pointee.ai_addrlen)) { dstBytes in
                info.pointee.ai_addr.withMemoryRebound(to: UInt8.self, capacity: Int(info.pointee.ai_addrlen)) { srcBytes in
                    dstBytes.update(from: srcBytes, count: Int(info.pointee.ai_addrlen))
                }
            }
        }

        var sndbuf: Int32 = 4 * 1024 * 1024
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, socklen_t(MemoryLayout<Int32>.size))
    }

    deinit { if fd >= 0 { close(fd) } }

    func send(_ bytes: [UInt8]) {
        withUnsafePointer(to: &storage) { sp in
            sp.withMemoryRebound(to: sockaddr.self, capacity: 1) { sap in
                bytes.withUnsafeBytes { buf in
                    _ = sendto(fd, buf.baseAddress, buf.count, 0, sap, addrLen)
                }
            }
        }
    }
}
