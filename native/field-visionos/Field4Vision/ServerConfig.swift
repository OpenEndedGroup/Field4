import Foundation
import OSLog

let shellLog = Logger(subsystem: "com.field4.vision", category: "shell")

struct ServerConfig: Equatable, Sendable {
    let baseURL: URL

    static let defaultAddress = "https://mi2.finch-eel.ts.net:9092"
    static let launchOverride = ProcessInfo.processInfo.environment["FIELD4_SERVER"].flatMap { ServerConfig(address: $0) }
    static let `default` = launchOverride ?? ServerConfig(address: defaultAddress)!

    init?(address: String) {
        let input = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty,
              var parts = URLComponents(string: input.contains("://") ? input : "https://\(input)"),
              let host = parts.host, !host.isEmpty, !host.contains(where: \.isWhitespace),
              let scheme = parts.scheme?.lowercased(), scheme == "https" || scheme == "http",
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/"
        else { return nil }
        let port = parts.port ?? 9092
        guard (1...65535).contains(port) else { return nil }
        parts.scheme = scheme
        parts.host = host.lowercased()
        parts.path = ""
        parts.port = port
        guard let url = parts.url else { return nil }
        baseURL = url
    }

    var port: Int { baseURL.port ?? 9092 }

    var webSocketURL: URL {
        var parts = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        parts.scheme = baseURL.scheme == "http" ? "ws" : "wss"
        parts.path = "/ws"
        return parts.url!
    }
}

/// Existing Field development certificate exceptions for local and tailnet hosts.
func isTrustedFieldDevHost(_ host: String) -> Bool {
    host == "localhost" || host == "127.0.0.1" || host == "::1"
        || host == "mi2" || host.hasSuffix(".ts.net")
        || isPrivateIPv4Literal(host)
}

func isPrivateIPv4Literal(_ host: String) -> Bool {
    let parts = host.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count == 4 else { return false }
    let octets = parts.compactMap { UInt8($0) }
    guard octets.count == 4 else { return false }
    switch (octets[0], octets[1]) {
    case (10, _), (172, 16...31), (192, 168), (169, 254): return true
    default: return false
    }
}
