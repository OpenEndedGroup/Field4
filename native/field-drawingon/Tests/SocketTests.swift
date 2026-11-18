import XCTest
@testable import DrawingOnCore

final class SocketTests: XCTestCase {
    func testNativeStreamAgainstReceiver() throws {
        guard let endpoint = ProcessInfo.processInfo.environment["DRAWINGON_TEST_RECEIVER"],
              let output = ProcessInfo.processInfo.environment["DRAWINGON_TEST_ARCHIVE"] else {
            throw XCTSkip("Set DRAWINGON_TEST_RECEIVER and DRAWINGON_TEST_ARCHIVE to run the live socket test")
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = try Journal(directory: directory) { _, _ in }
        journal.append("stroke.begin", ["stroke": "integration", "layer": "plan"])
        journal.append("stroke.samples", ["stroke": "integration", "samples": []])
        journal.append("audio.buffer", ["frames": 2, "sampleRate": 48000, "channels": 1,
            "sampleTime": 0, "pcm": Data(repeating: 0, count: 8).base64EncodedString()])
        journal.append("stroke.visibility", ["stroke": "integration", "visible": false, "reason": "undo"])
        journal.queue.sync {}
        let stream = SocketStream()
        let confirmed = expectation(description: "native client receives durable receiver ack")
        var fulfilled = false
        stream.onStatus = { status in
            if status.hasPrefix("Live"), !fulfilled { fulfilled = true; confirmed.fulfill() }
        }
        stream.connect(endpoint, journal: journal.url)
        wait(for: [confirmed], timeout: 15)
        let archive = URL(fileURLWithPath: output).appendingPathComponent("\(journal.session).ndjson")
        let allEvents = expectation(description: "all journal events are archived")
        DispatchQueue.global().async {
            for _ in 0..<100 {
                if let text = try? String(contentsOf: archive), text.split(separator: "\n").count == 4 {
                    allEvents.fulfill(); return
                }
                Thread.sleep(forTimeInterval: 0.05)
            }
        }
        wait(for: [allEvents], timeout: 8)
        stream.disconnect()
        let reader = try LineReader(archive)
        var seq: [Int] = []
        while let line = try reader.next() {
            let event = try JSONSerialization.jsonObject(with: line) as! [String: Any]
            seq.append(event["seq"] as! Int)
        }
        XCTAssertEqual(seq, [1, 2, 3, 4])
        // Relaunch-style replay from seq 1 must not duplicate anything on the receiver.
        let second = SocketStream()
        let replay = expectation(description: "replay is acknowledged")
        var replayed = false
        second.onStatus = { status in
            if status.hasPrefix("Live"), !replayed { replayed = true; replay.fulfill() }
        }
        second.connect(endpoint, journal: journal.url)
        wait(for: [replay], timeout: 15)
        second.disconnect()
        XCTAssertEqual(try String(contentsOf: archive).split(separator: "\n").count, 4)
    }
}

extension SocketTests {
    func testOfflineUploadResumesAndStopsAtSavedSnapshot() throws {
        guard let endpoint = ProcessInfo.processInfo.environment["DRAWINGON_TEST_RECEIVER"],
              let output = ProcessInfo.processInfo.environment["DRAWINGON_TEST_ARCHIVE"] else {
            throw XCTSkip("Live receiver not configured")
        }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let journal = try Journal(directory: root) { _, _ in }
        for i in 1...10 { journal.append("offline.event", ["index": i]) }
        let frozen = expectation(description: "saved offline capture")
        var snapshot: JournalSnapshot?
        journal.snapshot { result in snapshot = try? result.get(); frozen.fulfill() }
        wait(for: [frozen], timeout: 5)
        let file = try XCTUnwrap(snapshot)
        defer { try? FileManager.default.removeItem(at: file.url.deletingLastPathComponent()) }
        journal.append("later.drawing", [:]); journal.queue.sync {}
        let partial = SocketStream()
        defer { partial.disconnect() }
        let prefix = expectation(description: "first three events committed")
        partial.onComplete = { prefix.fulfill() }
        partial.connect(endpoint, journal: file.url, through: 3)
        wait(for: [prefix], timeout: 15)
        let archive = URL(fileURLWithPath: output).appendingPathComponent(file.session + ".ndjson")
        XCTAssertEqual(try String(contentsOf: archive).split(separator: "\n").count, 3)
        let resumed = SocketStream()
        defer { resumed.disconnect() }
        var firstConfirmed: Int?
        resumed.onProgress = { seq in if firstConfirmed == nil { firstConfirmed = seq } }
        let complete = expectation(description: "saved capture completely uploaded")
        resumed.onComplete = { complete.fulfill() }
        resumed.connect(endpoint, journal: file.url, through: file.sequence)
        wait(for: [complete], timeout: 15)
        XCTAssertEqual(firstConfirmed, 3, "Receiver handshake resumes at its durable head")
        XCTAssertEqual(try String(contentsOf: archive).split(separator: "\n").count, 10)
        XCTAssertEqual(try String(contentsOf: journal.url).split(separator: "\n").count, 11)
    }
}
