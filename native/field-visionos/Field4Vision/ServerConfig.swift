import Foundation

/// Where the Field4 backend lives. The Kotlin/JS front end is served by the Ktor server
/// (`afield.Server`, default HTTPS on port 9092). The shell points WKWebView docks at its
/// existing endpoints — see dev_docs/vision_frontend.md.
///
/// Addressing: device testing runs over the **plain LAN** by default — the Mac's Wi-Fi address,
/// no Tailscale in the path. Tailscale's MagicDNS name (`mi2`, or the FQDN `mi2.<tailnet>.ts.net`)
/// still works and is the right answer when the two machines aren't on the same subnet; select it
/// without rebuilding via the `FIELD4_SERVER` environment variable (Xcode scheme → Run →
/// Arguments → Environment Variables), e.g. `FIELD4_SERVER=https://mi2:9092`.
///
/// Either way the server must be reachable off-loopback — the HTTPS connector binds `0.0.0.0`.
struct ServerConfig {
    var baseURL: URL

    /// The Mac's LAN address. Both this and the Tailscale name are covered by the dev-cert trust
    /// override below, since the self-signed cert's SAN matches neither an IP literal nor `mi2`.
    static let lanBaseURL = URL(string: "https://10.0.0.4:9092")!

    static let `default` = ServerConfig(baseURL: envBaseURL ?? lanBaseURL)

    /// `FIELD4_SERVER` override, ignored unless it parses as an absolute http(s) URL.
    private static var envBaseURL: URL? {
        guard let s = ProcessInfo.processInfo.environment["FIELD4_SERVER"],
              let u = URL(string: s), let scheme = u.scheme,
              scheme == "https" || scheme == "http", u.host != nil
        else { return nil }
        return u
    }

    /// The welcome page — the shell's day-one target.
    var welcomeURL: URL { baseURL.appendingPathComponent("welcome") }

    /// The main Kotlin/JS app (Monaco editor + box canvas), optionally opening a document and
    /// passing bare display-mode flags (e.g. "noeditor", "transparent") understood by the front end.
    func documentURL(file: String? = nil, flags: [String] = [], selectionContext: String? = nil) -> URL {
        let base = baseURL.appendingPathComponent("index.html")
        guard var comps = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return base }
        var items: [URLQueryItem] = []
        if let file, !file.isEmpty {
            items.append(URLQueryItem(name: "file", value: file))
        }
        for flag in flags {
            items.append(URLQueryItem(name: flag, value: nil))   // bare "?flag" — front end reads presence
        }
        if let selectionContext, !selectionContext.isEmpty {
            items.append(URLQueryItem(name: "shaderSelectionContext", value: selectionContext))
        }
        comps.queryItems = items.isEmpty ? nil : items
        return comps.url ?? base
    }

    /// The Field WebSocket bus (`/ws`) as a `wss://` URL derived from `baseURL`. Used by the native
    /// FLine viewer client (the WebViews reach `/ws` themselves via their loaded pages).
    var webSocketURL: URL {
        var comps = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        comps?.scheme = (baseURL.scheme == "http") ? "ws" : "wss"
        comps?.path = "/ws"
        return comps?.url ?? baseURL
    }
}

/// Hosts whose self-signed dev certificate we trust (the Field server's): loopback, a private-LAN
/// address, and the Tailscale machine (`mi2` / any `*.ts.net`). Overriding trust here also covers
/// the cert's hostname mismatch — it is issued for `localhost`, so reaching it as `mi2` or as a bare
/// IP literal fails both the self-signed *and* the name-check tests. Shared by the WebView docks
/// (`WebDock`) and the FLine WebSocket client (`FLineViewerClient`). Anything else falls back to
/// normal validation.
///
/// The private-range allowance is deliberately narrow: RFC1918 + link-local only, so a public host
/// can never have its certificate waved through.
func isTrustedFieldDevHost(_ host: String) -> Bool {
    host == "localhost" || host == "127.0.0.1" || host == "::1"
        || host == "mi2" || host.hasSuffix(".ts.net")
        || isPrivateIPv4Literal(host)
}

/// True for a dotted-quad in a private/link-local range (10/8, 172.16/12, 192.168/16, 169.254/16).
func isPrivateIPv4Literal(_ host: String) -> Bool {
    let parts = host.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count == 4 else { return false }
    let octets = parts.compactMap { UInt8($0) }
    guard octets.count == 4 else { return false }
    switch (octets[0], octets[1]) {
    case (10, _):                     return true
    case (172, 16...31):              return true
    case (192, 168):                  return true
    case (169, 254):                  return true
    default:                          return false
    }
}
