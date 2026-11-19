import SwiftUI
import CoreText
import Observation

/// Loads Field's Iosevka face when the selected server provides it.
@Observable
final class FieldFont {
    static let shared = FieldFont()
    private(set) var postScriptName: String?

    func load(from server: ServerConfig) {
        guard postScriptName == nil else { return }
        let url = server.baseURL.appendingPathComponent("woff2/iosevka-semicondensedlight.woff2")
        let session = URLSession(configuration: .ephemeral, delegate: FieldServerTrustDelegate(), delegateQueue: nil)
        session.dataTask(with: url) { data, _, error in
            defer { session.finishTasksAndInvalidate() }
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
        postScriptName.map { Font.custom($0, size: size) } ?? .system(size: size, design: .monospaced)
    }
}
