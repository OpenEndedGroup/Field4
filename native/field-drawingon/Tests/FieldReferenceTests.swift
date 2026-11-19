import XCTest
@testable import DrawingOnCore

final class FieldReferenceTests: XCTestCase {
    func record(_ geometry: FieldGeometryDescription, plane: FieldDrawingPlane? = nil, asset: String? = nil) -> FieldReferenceRecord {
        FieldReferenceRecord(layer: "field:ipad", target: "ipad", title: "Plan", plane: plane ?? FieldDrawingPlane(origin: [0,0,0], xAxis: [1,0,0], yAxis: [0,1,0], scale: 1, offsetX: 0, offsetY: 0), geometry: geometry, asset: asset)
    }
    func inline() -> FieldGeometryDescription {
        FieldGeometryDescription(type: "three/geometry", uid: "test", lines: FieldGeometry(attributes: ["position": FieldAttribute(itemSize: 3, array: [1,2,3, 4,5,6])]))
    }
    func testExplicitPlaneAndDrawableTransform() throws {
        var geometry = inline()
        let line = geometry.lines!; geometry.lines = nil
        geometry.drawables = [FieldDrawable(geometry: line, style: FieldDrawableStyle(kind: "lines", opacity: 0.4), transform: [1,0,0,0, 0,1,0,0, 0,0,1,0, 10,20,30,1])]
        let plane = FieldDrawingPlane(origin: [1,2,3], xAxis: [1,0,0], yAxis: [0,0,-1], scale: 2, offsetX: 100, offsetY: 200)
        let result = try FieldReferenceBinding.bind(record(geometry, plane: plane), bytes: Data())
        XCTAssertEqual(result.batches[0].vertices[0].x, 120)
        XCTAssertEqual(result.batches[0].vertices[0].y, 140)
        XCTAssertEqual(result.batches[0].vertices[0].a, 0.4)
        var invalid = plane; invalid.yAxis = [2,0,0]
        XCTAssertThrowsError(try FieldReferenceBinding.bind(record(geometry, plane: invalid), bytes: Data()))
    }
    func testTypedBufferOffsetsIndicesAndInvalidData() throws {
        var bytes = Data(repeating: 0, count: 8)
        for value: Float in [1,2,3,4,5,6] {
            var bits = value.bitPattern.littleEndian
            withUnsafeBytes(of: &bits) { bytes.append(contentsOf: $0) }
        }
        for value: UInt16 in [1,0] { var bits = value.littleEndian; withUnsafeBytes(of: &bits) { bytes.append(contentsOf: $0) } }
        var geometry = inline()
        geometry.buffers = FieldBuffers(generation: "one", bytes: bytes.count, entries: [
            FieldBufferEntry(type: "Float32Array", offset: 8, count: 6), FieldBufferEntry(type: "Uint16Array", offset: 32, count: 2)])
        geometry.lines = FieldGeometry(attributes: ["position": FieldAttribute(itemSize: 3, buffer: 0)], index: FieldIndex(buffer: 1))
        let bound = try FieldReferenceBinding.bind(record(geometry), bytes: bytes)
        XCTAssertEqual(bound.batches[0].indices, [1,0])
        XCTAssertEqual(bound.batches[0].vertices[1].y, 5)
        geometry.buffers!.entries[0].offset = Int.max
        XCTAssertThrowsError(try FieldReferenceBinding.bind(record(geometry), bytes: bytes))
        var bad = inline(); bad.lines!.index = FieldIndex(array: [0,3])
        XCTAssertThrowsError(try FieldReferenceBinding.bind(record(bad), bytes: Data()))
        bad = inline(); bad.lines!.attributes["position"]!.array![0] = .nan
        XCTAssertThrowsError(try FieldReferenceBinding.bind(record(bad), bytes: Data()))
    }
    func testAssetsReconstructFromJournalAndReferencesRespectLayersWithoutEnteringUndo() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = try FieldReferenceAssets(directory: root.appendingPathComponent("source"))
        let restored = try FieldReferenceAssets(directory: root.appendingPathComponent("restored"))
        let bytes = Data((0..<200_000).map { UInt8($0 % 251) }), hash = try source.put(bytes)
        let chunks = (0..<2).map { i -> [String: Any] in
            let data = bytes.subdata(in: (i*128*1024)..<min(bytes.count, (i+1)*128*1024))
            return ["sha256": hash, "part": i, "parts": 2, "bytes": bytes.count, "data": data.base64EncodedString()]
        }
        // A cached asset does not prove all chunks exist in this capture's journal.
        _ = try restored.put(bytes)
        XCTAssertNil(try restored.ingest(chunks[1])); XCTAssertEqual(try restored.ingest(chunks[0]), hash)
        XCTAssertEqual(try restored.read(hash), bytes)
        var corrupt = chunks[0]; corrupt["sha256"] = String(repeating: "0", count: 64)
        XCTAssertNil(try restored.ingest(corrupt))
        corrupt = chunks[1]; corrupt["sha256"] = String(repeating: "0", count: 64)
        XCTAssertThrowsError(try restored.ingest(corrupt))
        var scene = Scene(); scene.layers = [DrawingLayer(id: "human", name: "Pencil")]; scene.activeLayer = "human"
        scene.apply("stroke.begin", ["stroke": "one", "layer": "human"])
        scene.apply("stroke.end", ["stroke": "one", "cancelled": false])
        scene.apply("field.reference", ["reference": jsonObject(record(inline()))])
        XCTAssertEqual(scene.activeLayer, "human"); XCTAssertEqual(scene.undoCandidate, "one")
        scene.apply("stroke.visibility", ["stroke": "one", "visible": false, "reason": "undo"])
        XCTAssertEqual(scene.references.count, 1)
        scene.layers[1].visible = false
        XCTAssertEqual(scene.visibleLayers, ["human"])
        scene.layers[1].visible = true; scene.solo = "field:ipad"
        XCTAssertEqual(scene.visibleLayers, ["field:ipad"])
    }
}

final class FieldSocketTests: XCTestCase {
    func testActualFieldFLineReturnsToNativeClientAndSurvivesReplay() throws {
        guard let endpoint = ProcessInfo.processInfo.environment["DRAWINGON_FIELD_ENDPOINT"] else { throw XCTSkip("Field integration server not configured") }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let journal = try Journal(directory: root) { _, _ in }
        journal.append("stroke.begin", ["stroke": "native", "layer": "human"])
        journal.append("audio.buffer", ["frames": 2, "sampleRate": 48000, "channels": 1, "pcm": Data(repeating: 0, count: 8).base64EncodedString()])
        journal.append("stroke.end", ["stroke": "native", "cancelled": false])
        journal.append("stroke.visibility", ["stroke": "native", "visible": false, "reason": "undo"])
        journal.queue.sync {}
        let stream = SocketStream(); defer { stream.disconnect() }
        let returned = expectation(description: "native URLSession fetches real Field FLine buffers")
        let assets = try FieldReferenceAssets(directory: root.appendingPathComponent("assets"))
        var response: FieldReferenceRecord?
        stream.onReference = { message, bytes in
            do {
                XCTAssertEqual(message.title, "Field response")
                let hash = try assets.put(bytes)
                let reference = FieldReferenceRecord(layer: "field:integration", target: message.target, title: message.title, plane: message.plane, geometry: message.geometry, asset: hash)
                let bound = try FieldReferenceBinding.bind(reference, bytes: bytes)
                XCTAssertEqual(bound.batches[0].vertices[0].x, 90, accuracy: 0.001)
                XCTAssertEqual(bound.batches[0].vertices[0].y, 120, accuracy: 0.001)
                XCTAssertEqual(bound.batches[0].vertices.last!.x, 490, accuracy: 0.001)
                journal.append("field.asset", ["sha256": hash, "part": 0, "parts": 1, "bytes": bytes.count, "data": bytes.base64EncodedString()])
                journal.append("field.reference", ["reference": jsonObject(reference)])
                response = reference; returned.fulfill()
            } catch { XCTFail(error.localizedDescription); returned.fulfill() }
        }
        stream.connect(endpoint, journal: journal.url)
        wait(for: [returned], timeout: 20)
        XCTAssertNotNil(response)
        let synced = expectation(description: "received reference also committed in Field archive")
        let archive = URL(fileURLWithPath: ProcessInfo.processInfo.environment["DRAWINGON_TEST_ARCHIVE"]!).appendingPathComponent(journal.session + ".ndjson")
        DispatchQueue.global().async {
            for _ in 0..<100 {
                if let text = try? String(contentsOf: archive), text.split(separator: "\n").count == 6 { synced.fulfill(); return }
                Thread.sleep(forTimeInterval: 0.05)
            }
        }
        wait(for: [synced], timeout: 10)
        stream.disconnect(); journal.queue.sync {}
        let restored = try FieldReferenceAssets(directory: root.appendingPathComponent("fresh-assets"))
        var scene = Scene()
        _ = try Journal(directory: root) { type, data in
            if type == "field.asset" { _ = try? restored.ingest(data) }
            scene.apply(type, data)
        }
        let record = try XCTUnwrap(scene.references["field:integration"])
        XCTAssertEqual(try FieldReferenceBinding.bind(record, bytes: restored.read(record.asset!)).batches[0].vertices[0].x, 90)
        // The same data uploaded later is input history, with no reference response or feedback loop.
        let upload = SocketStream(); defer { upload.disconnect() }
        let noReturn = expectation(description: "uploads receive no Field geometry"); noReturn.isInverted = true
        let uploaded = expectation(description: "offline replay finishes")
        upload.onReference = { _, _ in noReturn.fulfill() }; upload.onComplete = { uploaded.fulfill() }
        upload.connect(endpoint, journal: journal.url, through: 6)
        wait(for: [uploaded], timeout: 15); wait(for: [noReturn], timeout: 0.2)
    }
}
