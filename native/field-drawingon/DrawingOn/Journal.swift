import Foundation
import QuartzCore

/// Serial append-only journal, also used as the bounded-memory network spool.
final class Journal {
    let queue = DispatchQueue(label: "drawingon.journal", qos: .userInitiated)
    let url: URL
    let session: String
    let origin: Double
    let offset: Double
    private var writer: FileHandle
    private var sequence: Int
    var onFailure: ((String) -> Void)?
    private var failed = false

    init(directory: URL? = nil, resumeLatest: Bool = true, replay: (String, [String: Any]) -> Void) throws {
        let root = try directory ?? FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        var files = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "ndjson" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        let pointer = root.appendingPathComponent(".active-journal")
        if !resumeLatest { files = [] }
        else if let name = try? String(contentsOf: pointer, encoding: .utf8), name == (name as NSString).lastPathComponent,
                FileManager.default.fileExists(atPath: root.appendingPathComponent(name).path) {
            files = [root.appendingPathComponent(name)]
        }
        var lastSequence = 0
        var lastTime = 0.0
        var oldSession: String?
        if let latest = files.last {
            // Drop only an incomplete trailing write after an unclean termination.
            let handle = try FileHandle(forUpdating: latest)
            var validBytes: UInt64 = 0
            let reader = try LineReader(latest)
            while let line = try reader.next() {
                guard let e = try JSONSerialization.jsonObject(with: line) as? [String: Any],
                      let seq = e["seq"] as? Int, seq == lastSequence + 1,
                      let type = e["type"] as? String,
                      let payload = e["data"] as? [String: Any] else {
                    throw NSError(domain: "DrawingOn", code: 2, userInfo: [NSLocalizedDescriptionKey: "The saved journal is damaged. Its contents have been preserved."])
                }
                lastSequence = seq; lastTime = max(lastTime, e["t"] as? Double ?? 0)
                if type == "audio.buffer", let frames = payload["frames"] as? Double,
                   let rate = payload["sampleRate"] as? Double, rate > 0 {
                    lastTime = max(lastTime, (e["t"] as? Double ?? 0) + frames / rate)
                }
                oldSession = e["session"] as? String
                replay(type, payload)
                validBytes = reader.offset
            }
            try handle.truncate(atOffset: validBytes)
            try handle.close()
        }
        session = oldSession ?? UUID().uuidString
        url = oldSession != nil ? files.last! : root.appendingPathComponent("\(ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-"))-\(session).ndjson")
        if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil) }
        writer = try FileHandle(forWritingTo: url); try writer.seekToEnd()
        sequence = lastSequence; origin = CACurrentMediaTime(); offset = lastTime + (oldSession == nil ? 0 : 0.001)
        try Data(url.lastPathComponent.utf8).write(to: pointer, options: .atomic)
    }
    func time(_ uptime: Double = CACurrentMediaTime()) -> Double { uptime - origin + offset }
    func append(_ type: String, _ payload: [String: Any], at timestamp: Double? = nil) {
        let t = timestamp ?? time()
        queue.async {
            guard !self.failed else { return }
            do {
                self.sequence += 1
                let event: [String: Any] = ["v": 1, "session": self.session, "seq": self.sequence,
                    "t": t, "type": type, "data": payload]
                var bytes = try JSONSerialization.data(withJSONObject: event, options: [.sortedKeys])
                bytes.append(10); try self.writer.write(contentsOf: bytes)
            } catch {
                self.failed = true; self.onFailure?("Capture stopped saving: \(error.localizedDescription)")
            }
        }
    }
    func flush(_ completion: (() -> Void)? = nil) {
        queue.async {
            do { try self.writer.synchronize() } catch { self.onFailure?(error.localizedDescription) }
            if let completion { DispatchQueue.main.async(execute: completion) }
        }
    }
    func snapshot(_ completion: @escaping (Result<JournalSnapshot, Error>) -> Void) {
        queue.async {
            let result = Result<JournalSnapshot, Error> {
                if self.failed { throw CocoaError(.fileWriteUnknown) }
                try self.writer.synchronize()
                let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let target = folder.appendingPathComponent(self.url.lastPathComponent)
                try FileManager.default.copyItem(at: self.url, to: target)
                return JournalSnapshot(url: target, session: self.session, sequence: self.sequence, duration: self.time())
            }
            DispatchQueue.main.async { completion(result) }
        }
    }
}

struct JournalSnapshot {
    let url: URL
    let session: String
    let sequence: Int
    let duration: Double
}

final class LineReader {
    private let file: FileHandle
    private var buffer = Data()
    private(set) var offset: UInt64 = 0
    init(_ url: URL) throws { file = try FileHandle(forReadingFrom: url) }
    deinit { try? file.close() }
    func next() throws -> Data? {
        while true {
            if let newline = buffer.firstIndex(of: 10) {
                let line = Data(buffer[..<newline]); buffer.removeSubrange(...newline)
                offset += UInt64(line.count + 1); return line
            }
            let chunk = try file.read(upToCount: 65536) ?? Data()
            if chunk.isEmpty { return nil } // Retain a partial line until the next poll.
            buffer.append(chunk)
        }
    }
}
