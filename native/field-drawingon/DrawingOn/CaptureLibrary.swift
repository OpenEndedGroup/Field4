import Foundation

struct SavedCapture: Codable, Identifiable, Equatable {
    let id: String
    var title: String
    let created: Date
    let lastSequence: Int
    let bytes: Int64
    let duration: Double
    var confirmed: [String: Int] = [:]

    func confirmedEvents(at endpoint: String) -> Int { min(lastSequence, confirmed[endpoint] ?? 0) }
    func uploaded(to endpoint: String) -> Bool { confirmedEvents(at: endpoint) >= lastSequence }
}

/// Immutable capture files plus small, atomically replaced per-capture transfer receipts.
final class CaptureLibrary {
    let directory: URL
    init(directory: URL? = nil) throws {
        self.directory = try directory ?? FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("Saved captures", isDirectory: true)
        try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
    }
    func file(_ capture: SavedCapture) -> URL { directory.appendingPathComponent(capture.id + ".ndjson") }
    func captures() throws -> [SavedCapture] {
        try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "ndjson" }
            .map { url in
                let id = url.deletingPathExtension().lastPathComponent
                let metadata = directory.appendingPathComponent(id + ".json")
                if let data = try? Data(contentsOf: metadata), let record = try? JSONDecoder().decode(SavedCapture.self, from: data) { return record }
                // A crash between saving the journal and its label must not hide a recording.
                let reader = try LineReader(url)
                var sequence = 0, duration = 0.0
                while let line = try reader.next() {
                    let event = try JSONSerialization.jsonObject(with: line) as? [String: Any]
                    sequence = max(sequence, event?["seq"] as? Int ?? 0)
                    duration = max(duration, event?["t"] as? Double ?? 0)
                }
                let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
                let record = SavedCapture(id: id, title: "Recovered capture", created: attributes[.creationDate] as? Date ?? Date(),
                    lastSequence: sequence, bytes: (attributes[.size] as? NSNumber)?.int64Value ?? 0, duration: duration)
                try persist(record); return record
            }.sorted { $0.created > $1.created }
    }
    func save(_ snapshot: JournalSnapshot, title: String) throws -> SavedCapture {
        let target = directory.appendingPathComponent(snapshot.session + ".ndjson")
        guard !FileManager.default.fileExists(atPath: target.path) else { throw CocoaError(.fileWriteFileExists) }
        try FileManager.default.moveItem(at: snapshot.url, to: target)
        let attributes = try FileManager.default.attributesOfItem(atPath: target.path)
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let record = SavedCapture(id: snapshot.session, title: trimmed.isEmpty ? "Untitled capture" : trimmed, created: Date(),
            lastSequence: snapshot.sequence, bytes: (attributes[.size] as? NSNumber)?.int64Value ?? 0, duration: snapshot.duration)
        try persist(record)
        try? FileManager.default.removeItem(at: snapshot.url.deletingLastPathComponent())
        return record
    }
    func persist(_ capture: SavedCapture) throws {
        let data = try JSONEncoder().encode(capture)
        try data.write(to: directory.appendingPathComponent(capture.id + ".json"), options: .atomic)
    }
}
