import XCTest
@testable import DrawingOnCore

final class CaptureLibraryTests: XCTestCase {
    func testSavedCaptureIsImmutableAndReceiptsSurviveRelaunch() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let library = try CaptureLibrary(directory: root.appendingPathComponent("saved"))
        let journal = try Journal(directory: root) { _, _ in }
        journal.append("stroke.begin", ["stroke": "cafe", "layer": "plan"])
        journal.append("audio.buffer", ["frames": 2, "pcm": Data(repeating: 0, count: 8).base64EncodedString()])
        journal.append("capture.finish", ["title": "Café section"])
        let saved = expectation(description: "immutable snapshot")
        var snapshot: JournalSnapshot?
        journal.snapshot { result in snapshot = try? result.get(); saved.fulfill() }
        wait(for: [saved], timeout: 5)
        var record = try library.save(XCTUnwrap(snapshot), title: "Café section")
        XCTAssertEqual(record.lastSequence, 3)
        journal.append("not.part.of.snapshot", [:]); journal.queue.sync {}
        XCTAssertEqual(try String(contentsOf: library.file(record)).split(separator: "\n").count, 3)
        record.confirmed["ws://studio.local:8765"] = 2
        try library.persist(record)
        let restored = try CaptureLibrary(directory: library.directory).captures()
        XCTAssertEqual(restored, [record]); XCTAssertFalse(record.uploaded(to: "ws://studio.local:8765"))
        record.confirmed["ws://studio.local:8765"] = 3
        XCTAssertTrue(record.uploaded(to: "ws://studio.local:8765"))
        XCTAssertFalse(record.uploaded(to: "ws://other-studio:8765"))
    }
    func testInterruptedMetadataWriteDoesNotHideCapture() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let library = try CaptureLibrary(directory: root)
        defer { try? FileManager.default.removeItem(at: root) }
        let id = UUID().uuidString
        let event: [String: Any] = ["v": 1, "session": id, "seq": 1, "t": 10.0, "type": "capture.finish", "data": [:]]
        var bytes = try JSONSerialization.data(withJSONObject: event); bytes.append(10)
        try bytes.write(to: root.appendingPathComponent(id + ".ndjson"))
        let records = try library.captures()
        XCTAssertEqual(records.count, 1); XCTAssertEqual(records[0].lastSequence, 1)
        XCTAssertEqual(records[0].id, id)
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent(id + ".json").path))
    }
    func testNewCaptureHasItsOwnSessionAndBecomesTheResumedJournal() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let first = try Journal(directory: root) { _, _ in }
        first.append("capture.finish", [:]); first.queue.sync {}
        let second = try Journal(directory: root, resumeLatest: false) { _, _ in }
        second.append("session.clock", [:]); second.queue.sync {}
        XCTAssertNotEqual(first.session, second.session)
        let restored = try Journal(directory: root) { _, _ in }
        XCTAssertEqual(restored.session, second.session)
    }
}
