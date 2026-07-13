import SwiftUI

/// The dock/tab manager: a rail of open docks on the left, the selected dock's WKWebView on
/// the right. The rail is collapsible (it eats a lot of space in the room), and any dock can be
/// undocked into its own window floating in space.
struct ContentView: View {
    @State private var manager = DockManager()
    @State private var columnVisibility: NavigationSplitViewVisibility = .doubleColumn
    @State private var didAutoOpenEditor = false
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            List(selection: $manager.selection) {
                Section("Docks") {
                    ForEach(manager.docks) { dock in
                        Label(dock.title, systemImage: icon(for: dock.kind))
                            .tag(dock.id)
                            .contextMenu {
                                Button {
                                    openWindow(value: dock.url)
                                } label: {
                                    Label("Undock into space", systemImage: "rectangle.badge.plus")
                                }
                                Button(role: .destructive) {
                                    manager.close(dock.id)
                                } label: {
                                    Label("Close", systemImage: "xmark")
                                }
                            }
                    }
                }
            }
            .navigationTitle("Field4")
            .navigationSplitViewColumnWidth(min: 180, ideal: 220, max: 300)
        } detail: {
            detailContent
                .toolbar {
                    // Everything lives in one always-visible bottom ornament — it floats outside
                    // the window, so there's no top title bar eating canvas height (visionOS won't
                    // shrink that bar via title modifiers). The toggle stays reachable even when the
                    // rail is collapsed; the doc name shows in the rail.
                    ToolbarItemGroup(placement: .bottomOrnament) {
                        Button {
                            withAnimation {
                                columnVisibility = columnVisibility == .detailOnly ? .doubleColumn : .detailOnly
                            }
                        } label: {
                            Label("Toggle Docks", systemImage: "sidebar.left")
                        }
                        Button {
                            manager.openWelcome()
                        } label: {
                            Label("Welcome", systemImage: "house")
                        }
                        Button {
                            shellLog.notice("tap: canvas")
                            manager.openGlassCanvas()
                        } label: {
                            Label("Canvas", systemImage: "square.on.square")
                        }
                        Button {
                            let u = manager.floatingEditorURL()
                            shellLog.notice("tap: floating-editor -> \(u.absoluteString, privacy: .public)")
                            openWindow(value: u)
                        } label: {
                            Label("Floating Editor", systemImage: "chevron.left.forwardslash.chevron.right")
                        }
                        Button {
                            shellLog.notice("tap: undock (selected=\(manager.selectedDock?.title ?? "nil", privacy: .public))")
                            if let dock = manager.selectedDock { openWindow(value: dock.url) }
                        } label: {
                            Label("Undock", systemImage: "rectangle.badge.plus")
                        }
                    }
                }
        }
        .task {
            // Auto-open the floating editor on launch so the shared-selection pair is up immediately.
            if !didAutoOpenEditor {
                didAutoOpenEditor = true
                try? await Task.sleep(for: .seconds(1.2))
                openWindow(value: manager.floatingEditorURL())
            }
        }
    }

    @ViewBuilder
    private var detailContent: some View {
        if let dock = manager.selectedDock {
            WebDock(
                url: dock.url,
                onOpenURL: { openWindow(value: $0) },
                onNavigate: { manager.updateDock(dock.id, to: $0) },
                // Only tuck around the window corner when the rail is collapsed — with it open the
                // sidebar covers the bottom-left corner, so the detail web view is already clear.
                cornerInset: columnVisibility == .detailOnly ? shellWindowCornerInset : 0
            )
                .ignoresSafeArea()
        } else {
            ContentUnavailableView("No dock selected",
                                   systemImage: "square.dashed",
                                   description: Text("Open a dock from the toolbar below."))
        }
    }

    private func icon(for kind: DockKind) -> String {
        switch kind {
        case .welcome: "house"
        case .document: "doc.text"
        }
    }
}

#Preview(windowStyle: .automatic) {
    ContentView()
}
