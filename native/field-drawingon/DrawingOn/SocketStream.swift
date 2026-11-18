import Foundation

/// Durable receiver handshake, then at-least-once delivery from the disk journal.
/// `through` turns the same transport into a finite, resumable upload.
final class SocketStream {
    private let queue = DispatchQueue(label: "drawingon.socket")
    private var task: URLSessionWebSocketTask?
    private var reader: LineReader?
    private var pending: Int?
    private var acknowledged = 0
    private var generation = 0
    private var endpoint = ""
    private var enabled = false
    private var journalURL: URL?
    private var sessionID: String?
    private var through: Int?
    private var ready = false
    private var helloSent = false
    private var lastStatusTime = Date.distantPast
    private var bufferedLine: Data?
    private var referenceTask: FieldBufferDownload?
    private var referenceKey: String?
    var onStatus: ((String) -> Void)?
    var onProgress: ((Int) -> Void)?
    var onComplete: (() -> Void)?
    var onReference: ((FieldReferenceMessage, Data) -> Void)?

    func connect(_ address: String, journal: URL, through: Int? = nil) {
        queue.async {
            self.enabled = false; self.generation += 1; self.task?.cancel(with: .goingAway, reason: nil)
            self.referenceTask?.cancel(); self.referenceKey = nil
            self.endpoint = address; self.journalURL = journal; self.through = through
            self.acknowledged = 0; self.sessionID = nil
            guard let url = URL(string: address), ["ws", "wss"].contains(url.scheme ?? ""), url.host != nil else {
                self.status("Enter a ws:// or wss:// address"); return
            }
            self.enabled = true; self.open(url)
        }
    }
    func disconnect() {
        queue.async {
            self.enabled = false; self.generation += 1; self.task?.cancel(with: .normalClosure, reason: nil)
            self.referenceTask?.cancel(); self.referenceKey = nil
            self.task = nil; self.status("Paused · saved on iPad")
        }
    }
    private func status(_ message: String) { DispatchQueue.main.async { self.onStatus?(message) } }
    private func progress(force: Bool = false) {
        guard force || Date().timeIntervalSince(lastStatusTime) > 0.25 else { return }
        lastStatusTime = Date()
        let seq = acknowledged
        DispatchQueue.main.async { self.onProgress?(seq) }
        status(through == nil ? "Live · synced through \(seq)" : "Uploading · \(seq) / \(through!) events")
    }
    private func open(_ url: URL) {
        guard enabled, let journalURL else { return }
        generation += 1; let g = generation
        referenceTask?.cancel(); referenceKey = nil
        do { reader = try LineReader(journalURL) } catch { status(error.localizedDescription); return }
        pending = nil; ready = false; helloSent = false; bufferedLine = nil
        let task = URLSession.shared.webSocketTask(with: url); self.task = task
        status("Connecting…"); task.resume(); receive(g); pump(g)
    }
    private func receive(_ g: Int) {
        task?.receive { result in
            self.queue.async {
                guard g == self.generation, self.enabled else { return }
                switch result {
                case .failure(let error): self.retry(g, error.localizedDescription)
                case .success(let message):
                    let data: Data
                    switch message { case .string(let text): data = Data(text.utf8); case .data(let bytes): data = bytes; @unknown default: return }
                    let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
                    if object?["type"] as? String == "drawingon/reference", self.ready, self.through == nil {
                        do { self.fetchReference(try JSONDecoder().decode(FieldReferenceMessage.self, from: data), generation: g) }
                        catch { self.status("Field reference · \(error.localizedDescription)") }
                    } else if let head = object?["ready"] as? Int, head >= 0,
                       (object?["session"] as? String)?.lowercased() == self.sessionID, !self.ready {
                        self.acknowledged = min(head, self.through ?? head)
                        self.ready = true; self.reader = try? LineReader(self.journalURL!); self.bufferedLine = nil
                        self.progress(force: true); self.pump(g)
                    } else if let replay = object?["replayFrom"] as? Int, replay > 0, self.ready {
                        self.acknowledged = replay - 1
                        self.reader = try? LineReader(self.journalURL!); self.bufferedLine = nil; self.pending = nil; self.pump(g)
                    } else if let ack = object?["ack"] as? Int, ack == self.pending,
                              (object?["session"] as? String)?.lowercased() == self.sessionID {
                        self.acknowledged = ack; self.pending = nil
                        self.progress(force: ack == self.through); self.pump(g)
                    }
                    if g == self.generation { self.receive(g) }
                }
            }
        }
    }
    private func fetchReference(_ message: FieldReferenceMessage, generation g: Int, retry: Bool = false) {
        let key = message.geometry.buffers?.generation ?? message.geometry.revision ?? message.sha256
        guard retry || key != referenceKey else { return }
        referenceKey = key; referenceTask?.cancel()
        func deliver(_ data: Data) {
            guard FieldReferenceAssets.hash(data) == message.sha256 else { self.status("Field reference · buffer hash mismatch"); self.referenceKey = nil; return }
            DispatchQueue.main.async {
                guard self.queue.sync(execute: { g == self.generation && self.enabled && self.referenceKey == key }) else { return }
                self.onReference?(message, data)
            }
        }
        guard let table = message.geometry.buffers else { deliver(Data()); return }
        guard (0...FieldReferenceBinding.maximumBytes).contains(table.bytes),
              var url = URLComponents(string: endpoint) else { status("Field reference · invalid buffer size"); return }
        url.scheme = url.scheme == "wss" ? "https" : "http"
        url.path = "/drawingon/buffers"
        url.queryItems = [URLQueryItem(name: "target", value: message.target), URLQueryItem(name: "generation", value: table.generation)]
        guard let address = url.url else { return }
        var request = URLRequest(url: address); request.timeoutInterval = 20
        referenceTask = FieldBufferDownload(request: request, expectedBytes: table.bytes) { data in
            self.queue.async {
                guard g == self.generation, self.enabled, self.referenceKey == key else { return }
                guard let data else {
                    self.status("Field reference · waiting for current geometry")
                    self.queue.asyncAfter(deadline: .now() + 2) {
                        guard g == self.generation, self.enabled, self.referenceKey == key else { return }
                        self.fetchReference(message, generation: g, retry: true)
                    }
                    return
                }
                deliver(data)
            }
        }
    }
    private func pump(_ g: Int) {
        guard g == generation, enabled, pending == nil else { return }
        if ready, let through, acknowledged >= through {
            enabled = false; generation += 1
            task?.cancel(with: .normalClosure, reason: nil); task = nil
            status("Uploaded · retained on iPad")
            DispatchQueue.main.async { self.onComplete?() }
            return
        }
        if helloSent && !ready { return }
        do {
            var batch: [String] = [], batchBytes = 0, lastSequence = 0
            while let line = try nextLine() {
                guard let object = try JSONSerialization.jsonObject(with: line) as? [String: Any],
                      let seq = object["seq"] as? Int, let session = object["session"] as? String else { throw CocoaError(.fileReadCorruptFile) }
                if !ready {
                    sessionID = session.lowercased(); helloSent = true
                    let hello: [String: Any] = ["type": "capture.hello", "session": session, "through": through as Any? ?? NSNull()]
                    let bytes = try JSONSerialization.data(withJSONObject: hello)
                    send(String(decoding: bytes, as: UTF8.self), generation: g)
                    queue.asyncAfter(deadline: .now() + 10) {
                        if g == self.generation && !self.ready { self.retry(g, "Receiver handshake timed out") }
                    }
                    return
                }
                if seq <= acknowledged { continue }
                if let through, seq > through { throw CocoaError(.fileReadCorruptFile) }
                if !batch.isEmpty && batchBytes + line.count > 512 * 1024 {
                    bufferedLine = line; break
                }
                batch.append(String(decoding: line, as: UTF8.self)); batchBytes += line.count; lastSequence = seq
                if through == nil || batch.count == 64 || seq == through { break }
            }
            if !batch.isEmpty {
                let seq = lastSequence
                pending = seq
                let text = batch.count == 1 ? batch[0] : "{\"type\":\"capture.batch\",\"events\":[" + batch.joined(separator: ",") + "]}"
                send(text, generation: g)
                queue.asyncAfter(deadline: .now() + 10) {
                    if g == self.generation && self.pending == seq { self.retry(g, "Receiver acknowledgement timed out") }
                }
                return
            }
            if ready, through != nil { status("Upload incomplete · capture file ended early"); enabled = false; task?.cancel(with: .goingAway, reason: nil); return }
            queue.asyncAfter(deadline: .now() + 0.025) { self.pump(g) }
        } catch { retry(g, error.localizedDescription) }
    }
    private func nextLine() throws -> Data? {
        if let line = bufferedLine { bufferedLine = nil; return line }
        return try reader?.next()
    }
    private func send(_ text: String, generation: Int) {
        task?.send(.string(text)) { error in
            if let error { self.queue.async { self.retry(generation, error.localizedDescription) } }
        }
    }
    private func retry(_ g: Int, _ reason: String) {
        guard g == generation, enabled else { return }
        generation += 1; let next = generation
        task?.cancel(with: .goingAway, reason: nil); task = nil
        status("Reconnecting · \(reason)")
        queue.asyncAfter(deadline: .now() + 2) {
            guard next == self.generation, self.enabled, let url = URL(string: self.endpoint) else { return }
            self.open(url)
        }
    }
}

/// Stream into a bounded buffer instead of letting an unexpected HTTP body grow without limit.
private final class FieldBufferDownload: NSObject, URLSessionDataDelegate {
    private let expectedBytes: Int
    private let completion: (Data?) -> Void
    private var bytes = Data()
    private var session: URLSession!
    private var task: URLSessionDataTask!
    init(request: URLRequest, expectedBytes: Int, completion: @escaping (Data?) -> Void) {
        self.expectedBytes = expectedBytes; self.completion = completion
        super.init()
        session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
        task = session.dataTask(with: request); task.resume()
    }
    func cancel() { task.cancel() }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let valid = (response as? HTTPURLResponse)?.statusCode == 200 &&
            (response.expectedContentLength < 0 || response.expectedContentLength == expectedBytes)
        completionHandler(valid ? .allow : .cancel)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard data.count <= expectedBytes - bytes.count else { dataTask.cancel(); return }
        bytes.append(data)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        completion(error == nil && bytes.count == expectedBytes ? bytes : nil)
        session.finishTasksAndInvalidate()
    }
}
