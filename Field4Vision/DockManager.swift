import Foundation
import Observation

enum DockKind: String, Codable, Hashable {
    case welcome
    case document
}

/// A single dock = a scoped view onto the Field server (a projection, in the design-note
/// vocabulary). Today every dock is a WKWebView; islands (RealityKit) come later.
struct Dock: Identifiable, Hashable, Codable {
    var id = UUID()
    var title: String
    var url: URL
    var kind: DockKind
}

/// The open docks + selection. Deliberately thin: state is server-authoritative, so a dock is
/// cheap to open, close, and (later) kill-and-revive.
@Observable
final class DockManager {
    var docks: [Dock] = []
    var selection: Dock.ID?

    let server: ServerConfig

    init(server: ServerConfig = .default) {
        self.server = server
        let welcome = Dock(title: "Welcome", url: server.welcomeURL, kind: .welcome)
        docks = [welcome]
        selection = welcome.id
        openGlassCanvas()   // On visionOS the glass canvas IS the default view; land on it.
    }

    /// The selection context shared by a document's canvas + its floating editor. Keyed by file
    /// so all views of one document track the same selection (server-mediated).
    private func selectionContext(for file: String) -> String { "field:\(file)" }

    /// The file shown by the selected document dock, or "default".
    private func selectedFile() -> String {
        guard let dock = selectedDock,
              let file = URLComponents(url: dock.url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "file" })?.value,
              !file.isEmpty
        else { return "default" }
        return file
    }

    /// URL for a floating editor sharing the selected document's selection context — select a box
    /// in the canvas and this editor follows.
    func floatingEditorURL() -> URL {
        let file = selectedFile()
        return server.documentURL(file: file, flags: ["editorOnly"], selectionContext: selectionContext(for: file))
    }

    /// There is only ever one welcome dock. If one already exists, select it instead of spawning
    /// a duplicate; otherwise create it. (Floating welcome windows are deduped separately by the
    /// system — WindowGroup(for:) treats the URL as identity, so re-opening focuses the existing
    /// window rather than making another.)
    @discardableResult
    func openWelcome() -> Dock {
        if let existing = docks.first(where: { $0.kind == .welcome }) {
            dedupeWelcome(keeping: existing.id)
            selection = existing.id
            return existing
        }
        let dock = Dock(title: "Welcome", url: server.welcomeURL, kind: .welcome)
        docks.append(dock)
        selection = dock.id
        return dock
    }

    /// Enforce the single-welcome invariant: keep `keepID`, close any other welcome docks.
    private func dedupeWelcome(keeping keepID: Dock.ID) {
        for id in docks.filter({ $0.kind == .welcome && $0.id != keepID }).map(\.id) {
            close(id)
        }
    }

    /// A canvas dock: transparent (glass) field with opaque box islands, sharing a per-file
    /// selection context so a floating editor can track its selection. This is the default
    /// document view on visionOS.
    @discardableResult
    func openGlassCanvas(file: String = "default") -> Dock {
        let dock = Dock(title: file,
                        url: server.documentURL(file: file,
                                                 flags: ["noeditor", "transparent"],
                                                 selectionContext: selectionContext(for: file)),
                        kind: .document)
        docks.append(dock)
        selection = dock.id
        return dock
    }

    func close(_ id: Dock.ID) {
        docks.removeAll { $0.id == id }
        if selection == id { selection = docks.first?.id }
    }

    /// Fix 2 — relabel a dock after its page navigated itself (e.g. /welcome → a document). The
    /// URL/kind/title are all derived from the committed URL so the rail stops lying about what
    /// the dock is showing.
    func updateDock(_ id: Dock.ID, to url: URL) {
        guard let idx = docks.firstIndex(where: { $0.id == id }) else { return }
        let (kind, title) = Self.classify(url)
        docks[idx].url = url
        docks[idx].kind = kind
        docks[idx].title = title
        // If this dock just became the welcome page (e.g. an in-page link back to /welcome),
        // fold away any other welcome dock so the invariant still holds.
        if kind == .welcome {
            dedupeWelcome(keeping: id)
        }
    }

    /// Derive a dock's kind + title from the URL it currently shows.
    static func classify(_ url: URL) -> (DockKind, String) {
        let path = url.path
        if path.hasSuffix("/welcome") || path.hasSuffix("welcome.html") {
            return (.welcome, "Welcome")
        }
        if path.contains("index.html") {
            let file = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "file" })?.value
            if let file, !file.isEmpty { return (.document, file) }
            return (.document, "Document")
        }
        // Anything else (external links, /code-use, …): a generic web dock.
        return (.document, url.host ?? "Web")
    }

    var selectedDock: Dock? {
        guard let selection else { return nil }
        return docks.first { $0.id == selection }
    }
}
