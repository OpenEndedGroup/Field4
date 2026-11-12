/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
The main content view.
*/

import SwiftUI
#if targetEnvironment(simulator)
import FoveatedStreamingSimulator
#else
import FoveatedStreaming
#endif

struct ContentView: View {
    @Environment(AppModel.self) private var appModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) var dismissWindow
    @Environment(\.scenePhase) var scenePhase

    @State var isShowingDisconnectAlert = false
    @State var disconnectReasonDescription: String? = nil
    
    let session: FoveatedStreamingSession
    
    var isDisconnected: Bool {
        switch session.status {
            case .disconnected, .initialized, .connecting: true
            default: false
        }
    }
    
    var body: some View {
        @Bindable var appModel = appModel
        VStack {
            if isDisconnected {
                StreamConnectionView()
                    .transition(.opacity.combined(with: .scale))
            } else {
                StreamControlsView(status: session.status)
                    .transition(.opacity.combined(with: .scale))
            }
            Button("Open Field IDE", systemImage: "macwindow") {
                openWindow(id: "field-browser")
            }
            .buttonStyle(.borderedProminent)
            .padding(12)
        }
        .animation(.spring, value: isDisconnected)
        .onChange(of: session.status) {
            if case .disconnected(let disconnectReason) = session.status,
               disconnectReason != .appInitiatedDisconnect,
               disconnectReason != .unauthorized,
               disconnectReason != .endpointInitiatedDisconnect {
                disconnectReasonDescription = disconnectReason.errorDescription
                isShowingDisconnectAlert = true
            }
        }
        .alert(disconnectReasonDescription ?? "Unknown Reason", isPresented: $isShowingDisconnectAlert) {
            Button("OK") { }
        }
        .trackWindowState($appModel.mainWindowState)
    }
}

#if targetEnvironment(simulator)
#Preview(windowStyle: .plain, traits: .fixedLayout(width: 1000, height: 850)) {
    @Previewable @State var isConnectionSuccessful: Bool = true
    let session = FoveatedStreamingSession()

    VStack {
        ContentView(session: session)
            .environment(
                StreamActions(
                    connect: { _ in
                        session.status = .connecting
                        try await Task.sleep(for: .seconds(1))
                        session.status = isConnectionSuccessful ?
                                .connected : .disconnected(.simulatorTestDisconnect)
                    },
                    pause: {
                        session.status = .pausing
                        try await Task.sleep(for: .seconds(1))
                        session.status = .paused
                    },
                    resume: {
                        session.status = .resuming
                        try await Task.sleep(for: .seconds(1))
                        session.status = .connected
                    },
                    disconnect: {
                        session.status = .disconnecting
                        try? await Task.sleep(for: .seconds(0.2))
                        session.status = .disconnected(.appInitiatedDisconnect)
                    }
                )
            )
            .environment(AppModel())
            .ornament(attachmentAnchor: .parent(.bottomFront)) {
                Toggle(isOn: $isConnectionSuccessful) {
                    Text("isConnectionSuccessful")
                }
                .toggleStyle(.button)
            }
    }
}
#endif

// A native, independently focused editor alongside the streamed OpenXR world.
import SwiftUI
import WebKit
import Combine
import CryptoKit

@MainActor
final class FieldBrowserModel: ObservableObject {
    @Published var address = UserDefaults.standard.string(forKey: "fieldEditorURL") ?? ""
    @Published var certificatePin = UserDefaults.standard.string(forKey: "fieldEditorCertificatePin") ?? ""
    @Published var failure: String?
    let webView = WKWebView(frame: .zero)

    func load() {
        guard let url = URL(string: address), url.scheme == "https" else {
            failure = "Enter an HTTPS Field URL."
            return
        }
        UserDefaults.standard.set(address, forKey: "fieldEditorURL")
        UserDefaults.standard.set(certificatePin, forKey: "fieldEditorCertificatePin")
        failure = nil
        webView.load(URLRequest(url: url))
    }
}

struct FieldBrowserView: View {
    @Environment(\.openWindow) private var openWindow
    @StateObject private var browser = FieldBrowserModel()

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("Stream controls", systemImage: "slider.horizontal.3") {
                    openWindow(id: "main")
                }
                TextField("Field URL", text: $browser.address)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit { browser.load() }
                Button("Open") { browser.load() }
                Button("Reload", systemImage: "arrow.clockwise") { browser.webView.reload() }
            }
            .padding(12)
            DisclosureGroup("Development certificate") {
                TextField("Optional SHA-256 fingerprint (64 hex digits)", text: $browser.certificatePin)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(12)
            }
            .padding(.horizontal, 12)
            if let failure = browser.failure {
                Text(failure).font(.caption).padding(8)
            }
            FieldWebView(browser: browser)
        }
        .frame(minWidth: 700, minHeight: 500)
    }
}

private struct FieldWebView: UIViewRepresentable {
    @ObservedObject var browser: FieldBrowserModel

    func makeCoordinator() -> Coordinator { Coordinator(browser: browser) }

    func makeUIView(context: Context) -> WKWebView {
        let view = browser.webView
        view.navigationDelegate = context.coordinator
        view.isInspectable = true
        if !browser.address.isEmpty { browser.load() }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        let browser: FieldBrowserModel
        init(browser: FieldBrowserModel) { self.browser = browser }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            browser.failure = error.localizedDescription
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            browser.failure = error.localizedDescription
        }

        func webView(_ webView: WKWebView, didReceive challenge: URLAuthenticationChallenge,
                     completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
            // Trust only this host's pinned Field development certificate, never arbitrary TLS.
            if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
               challenge.protectionSpace.host == URL(string: browser.address)?.host,
               let trust = challenge.protectionSpace.serverTrust,
               let certificates = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
               let certificate = certificates.first {
                let digest = SHA256.hash(data: SecCertificateCopyData(certificate) as Data)
                    .map { String(format: "%02x", $0) }.joined()
                let pin = browser.certificatePin.lowercased().replacingOccurrences(of: ":", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
                if pin.count == 64 && digest == pin {
                    completionHandler(.useCredential, URLCredential(trust: trust))
                    return
                }
            }
            completionHandler(.performDefaultHandling, nil)
        }
    }
}


