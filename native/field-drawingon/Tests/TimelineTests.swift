import XCTest
@testable import DrawingOnCore

final class TimelineTests: XCTestCase {
    func testUndoPreservesSamplesAndRedoRestoresStroke() throws {
        var scene = Scene()
        let layer = DrawingLayer(name: "Plan")
        scene.apply("layers.state", ["layers": jsonObject([layer]), "active": layer.id, "visible": [layer.id]])
        scene.apply("stroke.begin", ["stroke": "a", "layer": layer.id])
        let sample = fixture()
        scene.apply("stroke.samples", ["stroke": "a", "samples": jsonObject([sample])])
        scene.apply("stroke.visibility", ["stroke": "a", "visible": false])
        XCTAssertNil(scene.undoCandidate)
        XCTAssertEqual(scene.strokes[0].samples, [sample])
        XCTAssertEqual(scene.undone, ["a"])
        scene.apply("stroke.visibility", ["stroke": "a", "visible": true])
        XCTAssertEqual(scene.undoCandidate, "a")
        XCTAssertTrue(scene.undone.isEmpty)
        scene.apply("stroke.visibility", ["stroke": "a", "visible": false])
        scene.apply("stroke.begin", ["stroke": "b", "layer": layer.id])
        XCTAssertTrue(scene.undone.isEmpty, "A new drawing action starts a new undo branch")
    }
    func testVisibilitySoloAndCorrectionReplay() {
        var scene = Scene()
        let a = DrawingLayer(name: "Plan"), b = DrawingLayer(name: "Section")
        scene.apply("layers.state", ["layers": jsonObject([a, b]), "active": a.id, "solo": b.id])
        XCTAssertEqual(scene.visibleLayers, [b.id])
        scene.apply("stroke.begin", ["stroke": "s", "layer": b.id])
        scene.apply("stroke.samples", ["stroke": "s", "samples": jsonObject([fixture()])])
        var corrected = fixture(); corrected.force = 0.91
        scene.apply("stroke.correct", ["stroke": "s", "samples": jsonObject([corrected])])
        XCTAssertEqual(scene.strokes[0].samples.count, 1)
        XCTAssertEqual(scene.strokes[0].samples[0].force, 0.91)
        scene.apply("stroke.prediction", ["stroke": "s", "samples": jsonObject([fixture()])])
        XCTAssertEqual(scene.strokes[0].samples.count, 1, "Predictions must never become measured samples")
        scene.apply("stroke.end", ["stroke": "s", "cancelled": true])
        XCTAssertNil(scene.undoCandidate)
    }
    func testJournalRecoverySequenceClockAndIncompleteTail() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = try Journal(directory: directory) { _, _ in }
        journal.append("stroke.begin", ["stroke": "s", "layer": "a"], at: 10)
        journal.append("audio.buffer", ["pcm": Data([0, 0, 0, 0]).base64EncodedString(), "frames": 1], at: 12)
        journal.queue.sync {}
        let handle = try FileHandle(forWritingTo: journal.url)
        try handle.seekToEnd(); try handle.write(contentsOf: Data("{\"seq\":".utf8)); try handle.close()
        var replayed: [String] = []
        let restored = try Journal(directory: directory) { type, _ in replayed.append(type) }
        XCTAssertEqual(replayed, ["stroke.begin", "audio.buffer"])
        XCTAssertEqual(restored.session, journal.session)
        XCTAssertGreaterThanOrEqual(restored.time(), 12)
        restored.append("session.resume", [:]); restored.queue.sync {}
        let reader = try LineReader(restored.url)
        var events: [[String: Any]] = []
        while let line = try reader.next() { events.append(try JSONSerialization.jsonObject(with: line) as! [String: Any]) }
        XCTAssertEqual(events.compactMap { $0["seq"] as? Int }, [1, 2, 3])
        XCTAssertEqual(events[1]["t"] as? Double, 12)
    }
    func testReaderRetainsPartialTailAsFileGrows() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("one\ntw".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let reader = try LineReader(file)
        XCTAssertEqual(try reader.next(), Data("one".utf8)); XCTAssertNil(try reader.next())
        let writer = try FileHandle(forWritingTo: file); try writer.seekToEnd()
        try writer.write(contentsOf: Data("o\n".utf8)); try writer.close()
        XCTAssertEqual(try reader.next(), Data("two".utf8)); XCTAssertEqual(reader.offset, 8)
    }
    func testMalformedCompleteLineIsPreservedAndReported() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("capture.ndjson")
        let damaged = Data("{not valid json}\n".utf8)
        try damaged.write(to: file)
        XCTAssertThrowsError(try Journal(directory: directory) { _, _ in })
        XCTAssertEqual(try Data(contentsOf: file), damaged)
    }
    func testResumedClockStartsAfterLastAudioFrame() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let journal = try Journal(directory: directory) { _, _ in }
        journal.append("audio.buffer", ["frames": 4800, "sampleRate": 48000], at: 3)
        journal.queue.sync {}
        let restored = try Journal(directory: directory) { _, _ in }
        XCTAssertGreaterThan(restored.time(), 3.1)
    }
    private func fixture() -> PenSample {
        PenSample(id: 1, t: 1, x: 10, y: 20, previousX: 9, previousY: 19, precisePreviousX: 9, precisePreviousY: 19, preciseX: 10.1, preciseY: 20.1,
            force: 0.4, maximumForce: 4, altitude: 1, azimuth: 2, azimuthX: 0, azimuthY: 1, roll: 0.2,
            radius: 1, radiusTolerance: 0, phase: 1, tapCount: 1, input: 2, estimated: 1, expectingUpdates: 1, estimationIndex: 3)
    }
}
