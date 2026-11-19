import SwiftUI
import UIKit

private func drawingFont(_ size: CGFloat) -> Font { .custom("Iosevka-Light", size: size * 0.85) }

@main struct DrawingOnApp: App {
    @UIApplicationDelegateAdaptor(DrawingAppDelegate.self) private var appDelegate
    private let result = Result { try CaptureStore() }
    var body: some SwiftUI.Scene {
        WindowGroup {
            switch result {
            case .success(let store): CaptureView(store: store)
            case .failure(let error):
                Text("Could not open the capture journal.\n\(error.localizedDescription)")
                    .padding(40).foregroundStyle(.white).frame(maxWidth: .infinity, maxHeight: .infinity).background(.black)
            }
        }
    }
}

final class DrawingAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask { .all }
}

struct CaptureView: View {
    @ObservedObject var store: CaptureStore
    @Environment(\.scenePhase) private var phase
    @State private var export: CaptureExport?
    @State private var renaming: String?
    @State private var layerName = ""
    @State private var libraryOpen = false
    private var ink: Color { store.light ? .black : .white }
    private var ground: Color { store.light ? .white : .black }
    private var paletteGround: Color { Color(white: store.light ? 0.975 : 0.025) }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .topLeading) {
                PencilCanvas(store: store).ignoresSafeArea()
                if store.menuOpen {
                    ground.opacity(0.01).ignoresSafeArea().onTapGesture { store.menuOpen = false }
                    PaletteSilhouette()
                        .fill(paletteGround)
                        .overlay(PaletteSilhouette().stroke(ink.opacity(0.2), lineWidth: 0.5))
                        .frame(width: min(336, geometry.size.width - 32), height: min(760, geometry.size.height - 148) + 48)
                        .padding(16)
                        .allowsHitTesting(false)
                    panel.frame(width: min(336, geometry.size.width - 32), height: min(760, geometry.size.height - 148))
                        .padding(.top, 64).padding(.leading, 16)
                }
                Button {
                    store.cancelInput?(); store.menuOpen.toggle()
                } label: {
                    VStack(spacing: 4) {
                        ForEach(0..<3) { _ in Rectangle().frame(width: 18, height: 1) }
                    }
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
                    .background {
                        if !store.menuOpen {
                            paletteGround.overlay(Rectangle().stroke(ink.opacity(0.12), lineWidth: 0.5))
                        }
                    }
                    .overlay(alignment: .bottomTrailing) {
                        if store.recording { Circle().fill(ink).frame(width: 4, height: 4).padding(8) }
                    }
                }
                .buttonStyle(.plain).accessibilityLabel(store.menuOpen ? "Close menu" : "Open menu")
                .accessibilityValue(store.recording ? "Microphone recording" : "Microphone off")
                .padding(16)
                HStack(spacing: 0) {
                    ForEach(DrawingTool.allCases) { tool in
                        Button { store.selectTool(tool) } label: {
                            ToolGlyph(tool: tool).stroke(ink, style: StrokeStyle(lineWidth: 1, lineCap: .round, lineJoin: .round))
                                .frame(width: 18, height: 18)
                                .opacity(store.tool == tool ? 1 : 0.35)
                                .frame(width: 44, height: 44).background(ground.opacity(0.92))
                                .overlay(Rectangle().stroke(ink.opacity(store.tool == tool ? 0.25 : 0), lineWidth: 0.5))
                        }
                        .buttonStyle(.plain).accessibilityLabel("\(tool.label) tool")
                        .accessibilityAddTraits(store.tool == tool ? .isSelected : [])
                        .accessibilityHint(tool == .circle ? "Drag from the center to the edge." : tool == .line ? "Drag from one endpoint to the other." : "Draw freely.")
                        .disabled(store.finalizing)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                .padding(16)
                if let error = store.error {
                    VStack { Spacer()
                        HStack(alignment: .top) {
                            Text(error).fixedSize(horizontal: false, vertical: true)
                            Button("Dismiss") { store.error = nil }
                        }.padding(20).background(ground).overlay(Rectangle().stroke(ink.opacity(0.4)))
                    }.padding(24)
                }
            }
            .foregroundStyle(ink).background(ground)
            .font(drawingFont(15))
        }
        .preferredColorScheme(store.light ? .light : .dark)
        .statusBarHidden().persistentSystemOverlays(.hidden)
        .onChange(of: store.light) { _, _ in store.appearanceChanged() }
        .onChange(of: phase) { _, value in
            if value == .background { store.background() }
            if value == .active { store.journal.append("session.active", [:]) }
        }
        .sheet(item: $export) { ShareSheet(url: $0.url) }
        .sheet(isPresented: $libraryOpen) { CaptureLibraryView(store: store) }
        .onChange(of: store.captureTitle) { _, _ in store.saveTitle() }
        .alert("Layer name", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $layerName)
            Button("Save") {
                if let i = store.scene.layers.firstIndex(where: { $0.id == renaming }) {
                    store.scene.layers[i].name = layerName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Untitled" : layerName
                    store.layerState()
                }
                renaming = nil
            }
            Button("Cancel", role: .cancel) { renaming = nil }
        }
    }
    private var panel: some View {
        VStack(alignment: .leading, spacing: 0) {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack { Text("drawing on").font(drawingFont(22)); Spacer(); Text(String(format: "%02d", store.captures.count + 1)).opacity(0.35) }.padding(.bottom, 24)
                caption("CAPTURE")
                TextField("Capture name", text: $store.captureTitle).padding(.vertical, 14).accessibilityLabel("Capture name")
                Toggle("Stream live", isOn: Binding(get: { store.streaming }, set: { value in
                    if value != store.streaming { store.toggleStream() }
                })).tint(ink)
                Text(store.streaming ? "Recording on iPad + streaming" : "Recording on iPad · no connection needed")
                    .font(drawingFont(12)).foregroundStyle(ink.opacity(0.45)).padding(.vertical, 10)
                Button { store.finishCapture() } label: {
                    HStack { Text(store.finalizing ? "Saving…" : "Finish capture"); Spacer(); Image(systemName: "checkmark") }.frame(height: 44)
                }.disabled(store.audioPending)
                Button { libraryOpen = true } label: {
                    HStack { Text("Saved captures"); Spacer(); Text("\(store.captures.count)").opacity(0.4) }.frame(height: 44)
                }
                rule
                HStack {
                    caption("LAYERS"); Spacer()
                    Button { store.newLayer() } label: { Image(systemName: "plus").frame(width: 44, height: 36) }.accessibilityLabel("New layer")
                }
                ForEach(store.scene.layers) { layer in
                    HStack(spacing: 4) {
                        Button { store.selectLayer(layer.id) } label: {
                            HStack(spacing: 9) {
                                Circle().fill(store.scene.activeLayer == layer.id ? ink : .clear)
                                    .overlay(Circle().stroke(ink.opacity(0.4), lineWidth: 0.5)).frame(width: 5, height: 5)
                                Text(layer.name).lineLimit(1); Spacer(minLength: 0)
                            }.frame(minHeight: 44).contentShape(Rectangle())
                        }.accessibilityLabel("Select \(layer.name)")
                        Button { store.visibility(layer.id) } label: {
                            Image(systemName: layer.visible ? "eye" : "eye.slash").frame(width: 40, height: 44)
                        }.accessibilityLabel("\(layer.visible ? "Hide" : "Show") \(layer.name)")
                        Button { store.solo(layer.id) } label: {
                            Text("S").opacity(store.scene.solo == layer.id ? 1 : 0.3).frame(width: 36, height: 44)
                        }.accessibilityLabel("\(store.scene.solo == layer.id ? "Unsolo" : "Solo") \(layer.name)")
                    }
                    .opacity(store.scene.visibleLayers.contains(layer.id) ? 1 : 0.4)
                    .contextMenu {
                        Button("Rename") { renaming = layer.id; layerName = layer.name }
                    }
                }
                rule
                HStack {
                    Button("Undo") { store.undo() }.disabled(store.scene.undoCandidate == nil)
                    Spacer()
                    Button("Redo") { store.redo() }.disabled(store.scene.undone.isEmpty)
                }.frame(height: 44)
                Text("2 fingers move / turn / zoom\nTap: 2 undo · 3 redo\nPencil tap undo · squeeze menu")
                    .font(drawingFont(12)).foregroundStyle(ink.opacity(0.4)).padding(.bottom, 8)
                Button("Reset view") { store.resetCanvas?() }.frame(height: 36)
                rule
                caption("VOICE").padding(.top, 12)
                Button { store.toggleAudio() } label: {
                    HStack {
                        Image(systemName: store.recording ? "stop.circle" : "mic")
                        Text(store.audioPending ? "Opening microphone…" : store.recording ? "Stop recording" : "Record microphone")
                        Spacer()
                    }.frame(height: 48)
                }.disabled(store.audioPending)
                rule
                caption("TRANSFER").padding(.top, 12)
                TextField("wss://field.local:9092/drawingon/ws", text: $store.endpoint)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    .disabled(store.streaming || store.uploadingID != nil).padding(.vertical, 14).accessibilityLabel("WebSocket address")
                HStack {
                    Text("Field target").foregroundStyle(ink.opacity(0.45))
                    TextField("ipad", text: $store.target)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .disabled(store.streaming || store.uploadingID != nil).accessibilityLabel("Field target")
                }.padding(.bottom, 12)
                if !store.referenceStatus.isEmpty {
                    Text(store.referenceStatus).font(drawingFont(12)).foregroundStyle(ink.opacity(0.5))
                }
                Text(store.connection).font(drawingFont(12))
                    .foregroundStyle(ink.opacity(0.45)).fixedSize(horizontal: false, vertical: true).padding(.bottom, 14)
                rule
                Toggle("Light canvas", isOn: $store.light).tint(ink).padding(.top, 12)
                Toggle("Draw with finger", isOn: $store.fingerDrawing).tint(ink).padding(.top, 10)
                Button {
                    store.cancelInput?()
                    store.journal.snapshot { result in
                        switch result {
                        case .success(let snapshot): export = CaptureExport(url: snapshot.url)
                        case .failure(let error): store.error = error.localizedDescription
                        }
                    }
                } label: {
                    HStack { Text("Share capture"); Spacer(); Image(systemName: "square.and.arrow.up") }.frame(height: 54)
                }
            }.padding(22)
        }
        .scrollIndicators(.hidden)
        Text("Saved on this iPad.\nEvery stroke, including the ones you undo.")
            .font(drawingFont(12)).foregroundStyle(ink.opacity(0.35))
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 22).padding(.top, 10).padding(.bottom, 24)
        }
        .frame(maxHeight: 760)
        .buttonStyle(.plain)
        .disabled(store.finalizing)
    }
    private var rule: some View { Rectangle().fill(ink.opacity(0.15)).frame(height: 0.5).padding(.vertical, 8) }
    private func caption(_ text: String) -> some View { Text(text).font(drawingFont(11)).tracking(2).foregroundStyle(ink.opacity(0.4)) }
}

/// A single surface and outline: the menu tab flows into the palette at a concave shoulder.
private struct PaletteSilhouette: Shape {
    func path(in rect: CGRect) -> Path {
        let tabRight = rect.minX + 48
        let shoulderY = rect.minY + 48
        let radius: CGFloat = 10
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: tabRight, y: rect.minY))
        path.addLine(to: CGPoint(x: tabRight, y: shoulderY - radius))
        path.addQuadCurve(to: CGPoint(x: tabRight + radius, y: shoulderY), control: CGPoint(x: tabRight, y: shoulderY))
        path.addLine(to: CGPoint(x: rect.maxX, y: shoulderY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

private struct ToolGlyph: Shape {
    let tool: DrawingTool
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let r = rect.insetBy(dx: 2, dy: 2)
        switch tool {
        case .freehand:
            path.move(to: CGPoint(x: r.minX, y: r.maxY))
            path.addCurve(to: CGPoint(x: r.midX, y: r.midY), control1: CGPoint(x: r.maxX, y: r.maxY), control2: CGPoint(x: r.minX, y: r.minY))
            path.addCurve(to: CGPoint(x: r.maxX, y: r.minY), control1: CGPoint(x: r.maxX, y: r.maxY), control2: CGPoint(x: r.minX, y: r.minY))
        case .line:
            path.move(to: CGPoint(x: r.minX, y: r.maxY)); path.addLine(to: CGPoint(x: r.maxX, y: r.minY))
        case .circle:
            path.addEllipse(in: r)
        }
        return path
    }
}

struct CaptureLibraryView: View {
    @ObservedObject var store: CaptureStore
    @Environment(\.dismiss) private var dismiss
    @State private var export: CaptureExport?
    private var ink: Color { store.light ? .black : .white }
    private var ground: Color { store.light ? .white : .black }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                Text("saved captures").font(drawingFont(24))
                Spacer(); Button("Done") { dismiss() }.frame(minHeight: 44)
            }
            TextField("wss://field.local:9092/drawingon/ws", text: $store.endpoint)
                .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                .disabled(store.streaming || store.uploadingID != nil).accessibilityLabel("Upload destination")
            HStack {
                Text("Field target").foregroundStyle(ink.opacity(0.45))
                TextField("ipad", text: $store.target)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .disabled(store.streaming || store.uploadingID != nil).accessibilityLabel("Upload Field target")
            }
            Text("Upload when you’re connected. Originals stay on this iPad.")
                .font(drawingFont(12)).foregroundStyle(ink.opacity(0.45))
            if store.captures.isEmpty {
                Text("Finish a capture to keep it here.").foregroundStyle(ink.opacity(0.5)).padding(.top, 24)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 22) {
                    ForEach(store.captures) { capture in
                        VStack(alignment: .leading, spacing: 10) {
                            Text(capture.title).font(drawingFont(18))
                            Text(capture.created.formatted(date: .abbreviated, time: .shortened) + " · " + ByteCountFormatter.string(fromByteCount: capture.bytes, countStyle: .file))
                                .font(drawingFont(12)).foregroundStyle(ink.opacity(0.45))
                            if store.uploadingID == capture.id {
                                ProgressView(value: Double(store.uploadedEvents), total: Double(max(1, capture.lastSequence))).tint(ink)
                                Text(store.uploadStatus).font(drawingFont(12)).foregroundStyle(ink.opacity(0.6))
                                Button("Pause upload") { store.pauseUpload() }.frame(minHeight: 44)
                            } else {
                                HStack {
                                    Button(capture.uploaded(to: store.connectionAddress) ? "Upload again" : capture.confirmedEvents(at: store.connectionAddress) > 0 ? "Resume upload" : "Upload") { store.upload(capture) }
                                        .disabled(store.uploadingID != nil)
                                    Spacer()
                                    if capture.uploaded(to: store.connectionAddress) { Text("Uploaded").foregroundStyle(ink.opacity(0.4)) }
                                    Button { export = CaptureExport(url: store.library.file(capture)) } label: {
                                        Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44)
                                    }.accessibilityLabel("Share \(capture.title)")
                                }.frame(minHeight: 44)
                            }
                            Rectangle().fill(ink.opacity(0.15)).frame(height: 0.5)
                        }
                    }
                }
            }
            if !store.uploadStatus.isEmpty && store.uploadingID == nil {
                Text(store.uploadStatus).font(drawingFont(12)).foregroundStyle(ink.opacity(0.45))
            }
            if let error = store.error { Text(error).font(drawingFont(12)) }
        }
        .padding(28).font(drawingFont(15)).foregroundStyle(ink)
        .frame(maxWidth: .infinity, maxHeight: .infinity).background(ground).buttonStyle(.plain)
        .sheet(item: $export) { ShareSheet(url: $0.url) }
    }
}

struct CaptureExport: Identifiable {
    let id = UUID()
    let url: URL
}

struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
