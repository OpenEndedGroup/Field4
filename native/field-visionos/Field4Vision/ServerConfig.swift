import Foundation

/// Where the Field4 backend lives. The Kotlin/JS front end is served by the Ktor server
/// (`afield.Server`, default HTTPS on port 9092). The shell points WKWebView docks at its
/// existing endpoints — see dev_docs/vision_frontend.md.
///
/// The Mac running Field and the visionOS device are both on Tailscale, so we address the Mac
/// by its MagicDNS name `mi2` — this works identically from the simulator on the Mac itself and
/// from a real headset, no localhost/LAN-IP juggling. (If short-name resolution isn't set up,
/// use the FQDN `mi2.<tailnet>.ts.net`.) The server must be reachable on its Tailscale
/// interface — i.e. the 9092 connector bound to all interfaces, not loopback-only.
struct ServerConfig {
    var baseURL: URL

    static let `default` = ServerConfig(baseURL: URL(string: "https://mi2:9093")!)

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

/// Hosts whose self-signed dev certificate we trust (the Field server's): loopback + the Tailscale
/// machine (`mi2` / any `*.ts.net`). Overriding trust here also covers the cert's hostname mismatch
/// (issued for `localhost`, reached as `mi2`). Shared by the WebView docks (`WebDock`) and the FLine
/// WebSocket client (`FLineViewerClient`). Anything off the tailnet falls back to normal validation.
func isTrustedFieldDevHost(_ host: String) -> Bool {
    host == "localhost" || host == "127.0.0.1" || host == "::1"
        || host == "mi2" || host.hasSuffix(".ts.net")
}
