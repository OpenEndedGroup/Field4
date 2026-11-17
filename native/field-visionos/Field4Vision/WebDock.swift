import SwiftUI
import WebKit
import OSLog

/// Shell diagnostics — filter with: log show --predicate 'subsystem == "com.field4.vision"'
let shellLog = Logger(subsystem: "com.field4.vision", category: "shell")

/// Approx visionOS window corner radius (points). The system owns this — there is no API to ask
/// for a smaller one — so it is a fact to design around, not a value to set.
let shellWindowCornerInset: CGFloat = 56

/// How far web content is held off the window edge so that arc cannot bite into it. Full-bleed
/// content loses its top-left to the curve, which ate the editor's first line number.
///
/// 24pt is the smallest inset that clears it with margin. Treating the corner as a circle of
/// radius `shellWindowCornerInset` centred at (56, 56): at x = 24 the arc admits everything below
/// y ≈ 11.5, so a first text line sitting at y ≈ 24 is comfortably inside the window.
let shellContentInset: CGFloat = 24

/// The radius a toast has to tuck around at the content's own corner. Kept as a small value rather
/// than the system's 56pt because the content no longer sits in the system arc at all.
///
/// Note this is NOT achieved by clipping. Rounding the content with a `.clipShape` renders every
/// web view black on device: visionOS composites `WKWebView` out of process, and a SwiftUI mask
/// over it breaks that path — the page still loads (the sockets connect) but nothing is drawn.
/// Inset-only it is; the content keeps square corners inside the window's glass margin.
let shellContentCornerRadius: CGFloat = 22

extension View {
    /// Hold web content clear of the window's fixed corner arc. Replaces a bare
    /// `.ignoresSafeArea()`: we still want to own the full window rather than accept SwiftUI's
    /// default insets, we just want a deliberate margin instead of content disappearing under the
    /// glass curve. No mask — see `shellContentCornerRadius`.
    func shellWindowCornerSafe() -> some View {
        self
            .padding(shellContentInset)
            .ignoresSafeArea()
    }
}

/// A WKWebView hosting one Field4 front-end page.
///
/// This is the crux of the whole approach: on visionOS the system composites this web content
/// natively (out-of-process, foveated), and the OS delivers hit-testing / keyboard / pointer
/// events to the real view. We never rasterize HTML into a texture and never synthesize
/// events — so the "html → texture map ← events" problem simply doesn't arise here.
struct WebDock: UIViewRepresentable {
    let url: URL

    /// Fires when the page asks for a new window (`window.open(…, "_blank")`) — e.g. opening a
    /// multi-document project or a `_blank` link on /welcome. WKWebView drops these unless a
    /// WKUIDelegate handles them, so we surface the URL and let the shell open a window.
    var onOpenURL: ((URL) -> Void)? = nil

    /// Fires when the page navigates itself in place (e.g. /welcome → /index.html?file=…), so
    /// the shell can relabel the dock to match what it's actually showing.
    var onNavigate: ((URL) -> Void)? = nil

    /// Points the web layer needs to keep clear of the window's rounded corner (bottom-left).
    /// The web page can't see the native split-view, so we push this in as a CSS variable
    /// (`--shell-corner-inset`): 0 when a sidebar covers the corner, ~the corner radius when the
    /// web view reaches the window edge itself (collapsed rail, or a standalone floating window).
    var cornerInset: CGFloat = 0

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        if WebProbe.enabled { WebProbe.install(into: config) }

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.isInspectable = true            // enable Safari Web Inspector during bring-up
        // Let transparent pages (?transparent) show the window's glass through the web content.
        // Harmless for opaque pages — their own background still covers the glass.
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        context.coordinator.onOpenURL = onOpenURL
        context.coordinator.onNavigate = onNavigate
        context.coordinator.webView = webView
        context.coordinator.cornerInset = cornerInset
        webView.load(URLRequest(url: url))
        context.coordinator.loadedURL = url
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.onOpenURL = onOpenURL
        context.coordinator.onNavigate = onNavigate
        context.coordinator.webView = webView
        if context.coordinator.cornerInset != cornerInset {
            context.coordinator.cornerInset = cornerInset
            context.coordinator.applyCornerInset()   // live update when the sidebar toggles
        }
        guard context.coordinator.loadedURL != url else { return }
        context.coordinator.loadedURL = url
        webView.load(URLRequest(url: url))
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var loadedURL: URL?
        var onOpenURL: ((URL) -> Void)?
        var onNavigate: ((URL) -> Void)?
        weak var webView: WKWebView?
        var cornerInset: CGFloat = 0

        /// Push the corner clearance into the page as a CSS variable the toast/HUD CSS reads.
        func applyCornerInset() {
            let js = "document.documentElement.style.setProperty('--shell-corner-inset', '\(Int(cornerInset))px')"
            webView?.evaluateJavaScript(js, completionHandler: nil)
        }

        /// Fix 2 — report in-place navigations. When /welcome sends the dock to a document via
        /// `window.location.href`, the WKWebView just navigates and the dock's stored URL/title
        /// go stale. Reporting the committed URL lets the shell relabel the dock. We also keep
        /// `loadedURL` in sync so the follow-up `updateUIView` doesn't redundantly reload the
        /// page the web view already navigated to on its own.
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard let current = webView.url else { return }
            loadedURL = current
            applyCornerInset()   // survive (re)loads — inline nav resets inline styles
            onNavigate?(current)
            if WebProbe.enabled { WebProbe.report(webView) }
        }

        /// Fix 1 — honor `window.open(…, "_blank")`. WKWebView silently drops these unless a
        /// WKUIDelegate is present, which is why multi-doc project opens and the `_blank`
        /// buttons on /welcome did nothing. Returning nil means "no child web view" — we handle
        /// the request out-of-band by having the shell open a new window for the URL.
        func webView(_ webView: WKWebView,
                     createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = navigationAction.request.url {
                onOpenURL?(url)
            }
            return nil
        }

        /// Accept the Field server's self-signed certificate for trusted dev hosts only:
        /// loopback and the Tailscale machine (`mi2` / any `*.ts.net`). Overriding trust here
        /// also covers the hostname mismatch — the cert is issued for `localhost`, so reaching
        /// it as `mi2` fails both the self-signed and name-check tests without this. Anything
        /// off the tailnet falls back to normal validation.
        func webView(_ webView: WKWebView,
                     didReceive challenge: URLAuthenticationChallenge,
                     completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
            guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
                  let trust = challenge.protectionSpace.serverTrust,
                  Self.isTrustedDevHost(challenge.protectionSpace.host)
            else {
                completionHandler(.performDefaultHandling, nil)
                return
            }
            completionHandler(.useCredential, URLCredential(trust: trust))
        }

        private static func isTrustedDevHost(_ host: String) -> Bool {
            isTrustedFieldDevHost(host)   // shared with ThreeClient — see ServerConfig.swift
        }
    }
}

/// Headless diagnostics for the web docks (simctl cannot tap or open Web Inspector). With
/// `FIELD4_WEB_PROBE=1` every page records its console errors and failed loads, and after each
/// navigation the shell logs fonts, the welcome page's workspace and its document count:
///   log stream --predicate 'subsystem == "com.field4.vision" AND category == "probe"'
/// `FIELD4_WEB_PROBE_CLICK=<css selector>` additionally clicks that element once, a few seconds
/// after the first page that has it finishes loading (e.g. `#open-first-steps`).
enum WebProbe {
    static let log = Logger(subsystem: "com.field4.vision", category: "probe")
    static var enabled: Bool { ProcessInfo.processInfo.environment["FIELD4_WEB_PROBE"] == "1" }
    static var clickSelector: String? { ProcessInfo.processInfo.environment["FIELD4_WEB_PROBE_CLICK"] }
    nonisolated(unsafe) static var clicked = false

    static func install(into config: WKWebViewConfiguration) {
        let js = """
        window.__probe = [];
        const keep = (m) => { try { window.__probe.push(String(m).slice(0, 400)); } catch (_) {} };
        const oe = console.error; console.error = function(...a) { keep('console.error ' + a.join(' ')); oe.apply(console, a); };
        window.addEventListener('error', (e) => keep('error ' + (e.message || (e.target && (e.target.src || e.target.href)) || e)), true);
        window.addEventListener('unhandledrejection', (e) => keep('rejection ' + (e.reason && (e.reason.stack || e.reason))));
        """
        config.userContentController.addUserScript(
            WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: true))
    }

    static func report(_ webView: WKWebView) {
        let js = """
        JSON.stringify({ url: location.href,
          bodyFont: getComputedStyle(document.body).fontFamily,
          iosevka: document.fonts.check('300 16px "Iosevka Web"'),
          fonts: [...document.fonts].filter(f => f.status !== 'unloaded').map(f => f.family + ' ' + f.weight + ' ' + f.stretch + ' ' + f.status),
          workspace: (document.getElementById('current-workspace') || {}).textContent,
          docRows: document.querySelectorAll('[data-filename], .doc-row, .document-row').length,
          errors: window.__probe || [] })
        """
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak webView] in
            webView?.evaluateJavaScript(js) { result, error in
                log.notice("probe: \(String(describing: result ?? error), privacy: .public)")
            }
            guard let sel = clickSelector, !clicked, let webView else { return }
            let esc = sel.replacingOccurrences(of: "'", with: "\\'")
            webView.evaluateJavaScript("(() => { const n = document.querySelector('\(esc)'); if (!n) return false; n.click(); return true; })()") { r, _ in
                if (r as? Bool) == true { clicked = true; log.notice("probe: clicked \(sel, privacy: .public)") }
            }
        }
    }
}
