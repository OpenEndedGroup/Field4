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
}
