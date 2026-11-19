import SwiftUI
import UIKit

@MainActor final class CaptureStore: ObservableObject {
    var scene = Scene()
    private(set) var journal: Journal
    let socket = SocketStream()
    private(set) var audio: AudioCapture
    let library: CaptureLibrary
    let referenceAssets: FieldReferenceAssets
    private(set) var referenceRevision = 0
    var referenceDrawings: [String: ReferenceDrawing] = [:] {
        didSet { referenceRevision &+= 1 }
    }
    private var recordedAssets: Set<String> = []
    @Published var captures: [SavedCapture] = []
    @Published var captureTitle = ""
    @Published var finalizing = false
    @Published var uploadingID: String?
    @Published var uploadStatus = ""
    @Published var uploadedEvents = 0
    private var uploader: SocketStream?
    @Published var menuOpen = false
    @Published var recording = false
    @Published var audioPending = false
    @Published var connection = "Offline · saved on iPad"
    @Published var error: String?
    @Published var light = UserDefaults.standard.bool(forKey: "light")
    @Published var fingerDrawing = false
    @Published var endpoint = UserDefaults.standard.string(forKey: "endpoint") ?? "wss://field.local:9092/drawingon/ws"
    @Published var streaming = false
    @Published var target = UserDefaults.standard.string(forKey: "field.target") ?? "ipad"
    @Published var referenceStatus = ""
    @Published private(set) var tool = DrawingTool.freehand
    var redraw: (() -> Void)?
    var cancelInput: (() -> Void)?
    var resetCanvas: (() -> Void)?
    var captureDidChange: (() -> Void)?
    private var timer: Timer?

    init() throws {
        var restored = Scene()
        let assets = try FieldReferenceAssets()
        var retained: Set<String> = []
        var assetError: Error?
        let savedLibrary = try CaptureLibrary()
        let savedRecords = try savedLibrary.captures()
        library = savedLibrary
        captures = savedRecords
        var initial = try Journal { type, data in
            if type == "field.asset" {
                do { if let hash = try assets.ingest(data) { retained.insert(hash) } } catch { assetError = error }
            }
            restored.apply(type, data)
        }
        if let assetError { throw assetError }
        // Finalization may have saved the archive just before the previous process ended.
        if savedRecords.contains(where: { $0.id == initial.session }) {
            initial = try Journal(resumeLatest: false) { _, _ in }; restored = Scene(); retained = []
        }
        referenceAssets = assets; recordedAssets = retained
        journal = initial
        scene = restored
        tool = restored.selectedTool
        audio = AudioCapture(journal: journal)
        captureTitle = UserDefaults.standard.string(forKey: "title.\(journal.session)") ?? Self.defaultTitle()
        configureCapture()
        bootstrap()
        try rebuildReferences()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.journal.flush() }
        }
    }
    private static func defaultTitle() -> String { "Capture · " + Date().formatted(date: .abbreviated, time: .shortened) }
    private func configureCapture() {
        journal.onFailure = { [weak self] message in DispatchQueue.main.async { self?.error = message } }
        socket.onStatus = { [weak self] in self?.connection = $0 }
        socket.onReference = { [weak self] message, bytes in self?.receiveReference(message, bytes: bytes) }
        audio.onState = { [weak self] active, message in
            self?.recording = active; self?.audioPending = false
            if let message { self?.error = message }
        }
    }
    private func bootstrap() {
        let wallClock = ISO8601DateFormatter()
        wallClock.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        journal.append("session.clock", ["wallTime": wallClock.string(from: Date()),
            "uptimeOrigin": journal.origin, "timelineOffset": journal.offset,
            "device": UIDevice.current.model, "system": UIDevice.current.systemVersion,
            "coordinates": "UIKit points; top-left origin; radians", "resumed": !scene.layers.isEmpty])
        if scene.layers.isEmpty { newLayer() }
        // A terminated process may have left an open stroke; keep its samples and close it explicitly.
        for stroke in scene.strokes where !stroke.ended {
            emit("stroke.end", ["stroke": stroke.id, "cancelled": false, "reason": "recovered after process ended"])
        }
        journal.append("session.resume", ["reason": "application launch; microphone remains off"])
        emit("canvas.tool", ["tool": tool.rawValue])
        journal.append("canvas.appearance", ["light": light, "opacity": 0.24, "width": 1.8, "reason": "bootstrap"])
    }
    func selectTool(_ selected: DrawingTool) {
        guard !finalizing else { return }
        cancelInput?(); tool = selected; menuOpen = false
        emit("canvas.tool", ["tool": selected.rawValue])
    }
    var connectionAddress: String {
        guard var url = URLComponents(string: endpoint), url.path == "/drawingon/ws" else { return endpoint }
        url.queryItems = (url.queryItems ?? []).filter { $0.name != "target" } + [URLQueryItem(name: "target", value: target)]
        return url.string ?? endpoint
    }
    private func rebuildReferences() throws {
        referenceDrawings = try scene.references.mapValues { reference in
            try FieldReferenceBinding.bind(reference, bytes: reference.asset.map { try referenceAssets.read($0) } ?? Data())
        }
    }
    private func receiveReference(_ message: FieldReferenceMessage, bytes: Data) {
        guard streaming, !finalizing, message.target == target else { return }
        do {
            guard FieldReferenceAssets.hash(bytes) == message.sha256 else { throw FieldReferenceError.invalid("Field geometry hash mismatch") }
            let hash = message.geometry.buffers == nil ? nil : try referenceAssets.put(bytes)
            let record = FieldReferenceRecord(layer: "field:\(message.target)", target: message.target, title: message.title,
                plane: message.plane, geometry: message.geometry, asset: hash)
            let drawing = try FieldReferenceBinding.bind(record, bytes: bytes)
            if let hash, !recordedAssets.contains(hash) {
                let chunk = 128 * 1024, parts = max(1, (bytes.count + chunk - 1) / chunk)
                for part in 0..<parts {
                    let data = bytes.subdata(in: (part * chunk)..<min(bytes.count, (part + 1) * chunk))
                    journal.append("field.asset", ["sha256": hash, "part": part, "parts": parts, "bytes": bytes.count, "data": data.base64EncodedString()])
                }
                recordedAssets.insert(hash)
            }
            objectWillChange.send()
            referenceDrawings[record.layer] = drawing
            emit("field.reference", ["reference": jsonObject(record)])
            referenceStatus = "Field · \(message.title)"
        } catch { self.error = error.localizedDescription }
    }
    func saveTitle() { UserDefaults.standard.set(captureTitle, forKey: "title.\(journal.session)") }
    func finishCapture() {
        guard !finalizing, !audioPending else { return }
        cancelInput?(); finalizing = true
        let previous = journal, title = captureTitle
        audio.stop(reason: "capture finished") { [weak self] in
            guard let self else { return }
            previous.append("capture.finish", ["title": title])
            previous.snapshot { result in
                do {
                    let saved = try self.library.save(result.get(), title: title)
                    self.captures = try self.library.captures()
                    self.socket.disconnect()
                    self.journal = try Journal(resumeLatest: false) { _, _ in }
                    self.audio = AudioCapture(journal: self.journal)
                    self.scene = Scene(); self.captureTitle = Self.defaultTitle()
                    self.recordedAssets = []; self.referenceDrawings = [:]; self.referenceStatus = ""
                    self.configureCapture(); self.bootstrap(); self.captureDidChange?()
                    if self.streaming { self.socket.connect(self.connectionAddress, journal: self.journal.url) }
                    // The immutable library copy is now authoritative; remove the redundant working copy.
                    try? FileManager.default.removeItem(at: previous.url)
                    self.uploadStatus = "Saved \(saved.title) on this iPad"
                } catch { self.error = error.localizedDescription }
                self.finalizing = false
            }
        }
    }
    func upload(_ capture: SavedCapture) {
        guard uploadingID == nil else { return }
        endpoint = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        target = target.trimmingCharacters(in: .whitespacesAndNewlines)
        let destination = connectionAddress
        UserDefaults.standard.set(endpoint, forKey: "endpoint")
        UserDefaults.standard.set(target, forKey: "field.target")
        uploadingID = capture.id; uploadedEvents = 0; uploadStatus = "Connecting…"
        let sender = SocketStream(); uploader = sender
        sender.onStatus = { [weak self] status in
            guard self?.uploadingID == capture.id else { return }
            self?.uploadStatus = status
        }
        sender.onProgress = { [weak self] seq in
            guard let self, self.uploadingID == capture.id,
                  let i = self.captures.firstIndex(where: { $0.id == capture.id }) else { return }
            self.uploadedEvents = seq; self.captures[i].confirmed[destination] = seq
            do { try self.library.persist(self.captures[i]) } catch { self.error = error.localizedDescription }
        }
        sender.onComplete = { [weak self] in
            guard self?.uploadingID == capture.id else { return }
            self?.uploadingID = nil; self?.uploadStatus = "Uploaded · retained on iPad"; self?.uploader = nil
        }
        sender.connect(destination, journal: library.file(capture), through: capture.lastSequence)
    }
    func pauseUpload() {
        uploader?.disconnect(); uploader = nil; uploadingID = nil; uploadStatus = "Paused · resume when connected"
    }
    func emit(_ type: String, _ data: [String: Any]) {
        scene.apply(type, data); journal.append(type, data); redraw?()
    }
    func layerState() {
        objectWillChange.send()
        emit("layers.state", ["layers": jsonObject(scene.layers), "active": scene.activeLayer,
            "solo": scene.solo as Any? ?? NSNull(), "visible": scene.visibleLayers])
    }
    func newLayer() {
        cancelInput?()
        let layer = DrawingLayer(name: "Layer \(scene.layers.filter { $0.source != "field" }.count + 1)")
        scene.layers.append(layer); scene.activeLayer = layer.id
        if scene.solo != nil { scene.solo = layer.id }
        layerState()
    }
    func selectLayer(_ id: String) {
        cancelInput?()
        if scene.layers.first(where: { $0.id == id })?.source != "field" { scene.activeLayer = id }
        if scene.solo != nil { scene.solo = id }
        if let i = scene.layers.firstIndex(where: { $0.id == id }) { scene.layers[i].visible = true }
        layerState()
    }
    func visibility(_ id: String) {
        cancelInput?()
        guard let i = scene.layers.firstIndex(where: { $0.id == id }) else { return }
        scene.layers[i].visible.toggle(); layerState()
    }
    func solo(_ id: String) {
        cancelInput?(); scene.solo = scene.solo == id ? nil : id; layerState()
    }
    func undo() {
        guard !finalizing else { return }
        cancelInput?()
        guard let id = scene.undoCandidate else { return }
        objectWillChange.send(); emit("stroke.visibility", ["stroke": id, "visible": false, "reason": "undo"])
    }
    func redo() {
        guard !finalizing else { return }
        cancelInput?()
        guard let id = scene.undone.last else { return }
        objectWillChange.send(); emit("stroke.visibility", ["stroke": id, "visible": true, "reason": "redo"])
    }
    func toggleAudio() {
        guard !finalizing else { return }
        if recording { audio.stop() } else { audioPending = true; audio.start() }
    }
    func toggleStream() {
        endpoint = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        target = target.trimmingCharacters(in: .whitespacesAndNewlines)
        streaming.toggle()
        UserDefaults.standard.set(endpoint, forKey: "endpoint")
        UserDefaults.standard.set(target, forKey: "field.target")
        if streaming { socket.connect(connectionAddress, journal: journal.url) } else { socket.disconnect() }
    }
    func appearanceChanged() {
        UserDefaults.standard.set(light, forKey: "light")
        journal.append("canvas.appearance", ["light": light, "opacity": 0.24, "width": 1.8]); redraw?()
    }
    func background() {
        pauseUpload()
        cancelInput?(); audio.stop(reason: "background")
        journal.append("session.suspend", [:]); journal.flush()
    }
}
