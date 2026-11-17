import SwiftUI
import CoreText
import Observation

/// Field's interface face (Iosevka, semi-condensed) for the native shell chrome.
///
/// The web docks already render in Iosevka — the pages load `/woff2/*.woff2` from the server. The
/// native rail did not: its labels ("Welcome", document names) fell back to SF, which is the
/// "wrong font" next to the web content. Rather than bundle a megabyte font into the app, fetch
/// the same file the welcome page preloads from the server we already trust and register it for
/// this process. Until it arrives (or if it never does), the system font is used.
@Observable
final class FieldFont {
    static let shared = FieldFont()
    private(set) var postScriptName: String?

    func load(from server: ServerConfig) {
        guard postScriptName == nil else { return }
        let url = server.baseURL.appendingPathComponent("woff2/iosevka-semicondensedlight.woff2")
        let session = URLSession(configuration: .ephemeral, delegate: DevTrust(), delegateQueue: nil)
        session.dataTask(with: url) { data, _, error in
            guard let data,
                  let provider = CGDataProvider(data: data as CFData),
                  let font = CGFont(provider)
            else {
                shellLog.error("field font: fetch failed \(String(describing: error), privacy: .public)")
                return
            }
            var cfError: Unmanaged<CFError>?
            if !CTFontManagerRegisterGraphicsFont(font, &cfError) {
                shellLog.notice("field font: register said \(String(describing: cfError?.takeRetainedValue()), privacy: .public)")
            }
            let name = font.postScriptName as String?
            shellLog.notice("field font: registered \(name ?? "nil", privacy: .public)")
            DispatchQueue.main.async { self.postScriptName = name }
        }.resume()
    }

    func font(size: CGFloat) -> Font {
        postScriptName.map { Font.custom($0, size: size) } ?? .system(size: size)
    }

    /// Same dev-cert trust rule as the web docks (`isTrustedFieldDevHost`).
    private final class DevTrust: NSObject, URLSessionDelegate {
        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
            guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
                  let trust = challenge.protectionSpace.serverTrust,
                  isTrustedFieldDevHost(challenge.protectionSpace.host)
            else { return completionHandler(.performDefaultHandling, nil) }
            completionHandler(.useCredential, URLCredential(trust: trust))
        }
    }
}
