import XCTest
@testable import DrawingOnCore

final class ShapeTests: XCTestCase {
    func testLineUsesDrawingEndpointsAndRetainsIntermediateMeasurements() {
        var scene = Scene()
        scene.apply("stroke.begin", ["stroke": "line", "layer": "a", "tool": "line"])
        let samples = [sample(1, 10, 20), sample(2, 400, -60), sample(3, 100, 50)]
        scene.apply("stroke.samples", ["stroke": "line", "samples": jsonObject(samples)])
        let line = scene.strokes[0]
        XCTAssertEqual(line.tool, .line)
        XCTAssertEqual(line.geometry?.start, DrawingPoint(x: 10, y: 20))
        XCTAssertEqual(line.geometry?.end, DrawingPoint(x: 100, y: 50))
        XCTAssertEqual(line.samples, samples, "Shape constraints must not discard raw motion or pressure")
    }
    func testCircleIsCenterToEdgeInDrawingCoordinates() throws {
        var scene = Scene()
        scene.apply("stroke.begin", ["stroke": "circle", "layer": "a", "tool": "circle"])
        scene.apply("stroke.samples", ["stroke": "circle", "samples": jsonObject([sample(1, -10, 30), sample(2, -7, 34)])])
        let shape = try XCTUnwrap(scene.strokes[0].geometry)
        XCTAssertEqual(shape.center, DrawingPoint(x: -10, y: 30))
        XCTAssertEqual(try XCTUnwrap(shape.radius), 5, accuracy: 0.00001)
        XCTAssertNotEqual(shape.center?.x, scene.strokes[0].samples.first?.preciseX, "Raw screen coordinates must not leak into shape geometry")
    }
    func testLatePencilCorrectionUpdatesShapeWithoutAddingSamples() throws {
        var scene = Scene()
        scene.apply("stroke.begin", ["stroke": "c", "layer": "a", "tool": "circle"])
        scene.apply("stroke.samples", ["stroke": "c", "samples": jsonObject([sample(1, 0, 0), sample(2, 3, 4)])])
        scene.apply("stroke.end", ["stroke": "c", "cancelled": false])
        scene.apply("stroke.correct", ["stroke": "c", "samples": jsonObject([sample(2, 6, 8)])])
        XCTAssertEqual(scene.strokes[0].samples.count, 2)
        XCTAssertEqual(try XCTUnwrap(scene.strokes[0].geometry?.radius), 10, accuracy: 0.00001)
        scene.apply("stroke.visibility", ["stroke": "c", "visible": false])
        XCTAssertFalse(scene.strokes[0].visible)
        XCTAssertEqual(scene.strokes[0].tool, .circle)
        scene.apply("stroke.visibility", ["stroke": "c", "visible": true])
        XCTAssertEqual(try XCTUnwrap(scene.strokes[0].geometry?.radius), 10, accuracy: 0.00001)
    }
    func testOldStrokesRemainFreehandAfterToolSelectionReplay() {
        var scene = Scene()
        scene.apply("canvas.tool", ["tool": "line"])
        XCTAssertEqual(scene.selectedTool, .line)
        scene.apply("stroke.begin", ["stroke": "old", "layer": "a"])
        XCTAssertEqual(scene.strokes[0].tool, .freehand)
        XCTAssertNil(scene.strokes[0].geometry)
        scene.apply("canvas.tool", ["tool": "circle"])
        XCTAssertEqual(scene.selectedTool, .circle)
    }
    private func sample(_ id: Int, _ x: Double, _ y: Double) -> PenSample {
        PenSample(id: id, t: Double(id) * 0.01, x: x * 2 + 50, y: y * 2 + 60,
            previousX: 0, previousY: 0, precisePreviousX: 0, precisePreviousY: 0,
            preciseX: x * 2 + 50, preciseY: y * 2 + 60, drawingX: x, drawingY: y,
            force: 0.7, maximumForce: 4, altitude: 1, azimuth: 2, azimuthX: 0, azimuthY: 1,
            roll: 0.2, radius: 1, radiusTolerance: 0, phase: 1, tapCount: 1, input: 2,
            estimated: 0, expectingUpdates: 0, estimationIndex: nil)
    }
}
