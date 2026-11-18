import XCTest
@testable import DrawingOnCore

final class NavigationTests: XCTestCase {
    func testTwoContactsPanScaleAndRotateTogether() {
        let transform = DrawingTransform.identity.moved(from: [(0, 0), (100, 0)], to: [(50, 40), (50, 240)])
        XCTAssertEqual(transform.scale, 2, accuracy: 0.00001)
        XCTAssertEqual(transform.a, 0, accuracy: 0.00001)
        XCTAssertEqual(transform.b, 2, accuracy: 0.00001)
        let first = transform.drawingPoint(x: 50, y: 40)
        let second = transform.drawingPoint(x: 50, y: 240)
        XCTAssertEqual(first.x, 0, accuracy: 0.00001); XCTAssertEqual(first.y, 0, accuracy: 0.00001)
        XCTAssertEqual(second.x, 100, accuracy: 0.00001); XCTAssertEqual(second.y, 0, accuracy: 0.00001)
    }
    func testNavigationComposesInScreenSpaceAndKeepsInkUnderContacts() {
        let base = DrawingTransform(a: 2, b: 0, c: 0, d: 2, tx: 30, ty: -10)
        let transformed = base.moved(from: [(30, -10), (230, -10)], to: [(50, 70), (50, 170)])
        XCTAssertEqual(transformed.scale, 1, accuracy: 0.00001)
        let p = transformed.drawingPoint(x: 50, y: 170)
        XCTAssertEqual(p.x, 100, accuracy: 0.00001); XCTAssertEqual(p.y, 0, accuracy: 0.00001)
        let limited = transformed.moved(from: [(0, 0), (100, 0)], to: [(0, 0), (100000, 0)])
        XCTAssertEqual(limited.scale, 32, accuracy: 0.00001)
    }
    func testViewportStoresExactRotatedCornersAndEnclosingBounds() {
        let view = DrawingViewport(width: 200, height: 100,
            transform: DrawingTransform(a: 0, b: 2, c: -2, d: 0, tx: 50, ty: 30))
        XCTAssertEqual(view.screen, .init(x: 0, y: 0, width: 200, height: 100))
        XCTAssertEqual(view.drawingCorners, [[-15,25],[-15,-75],[35,-75],[35,25]])
        XCTAssertEqual(view.drawingBounds, .init(x: -15, y: -75, width: 50, height: 100))
    }
    func testTransformAndViewportSurviveTimelineReplay() {
        var scene = Scene()
        let transform = DrawingTransform(a: 2, b: 0, c: 0, d: 2, tx: 150, ty: -50)
        let viewport = DrawingViewport(width: 1000, height: 800, transform: transform)
        scene.apply("canvas.transform", ["transform": jsonObject(transform), "viewport": jsonObject(viewport), "phase": "ended"])
        XCTAssertEqual(scene.transform, transform)
        XCTAssertEqual(viewport.drawingBounds, .init(x: -75, y: 25, width: 500, height: 400))
    }
    func testTentativeNavigationContactsDoNotDestroyRedo() {
        var scene = Scene()
        scene.apply("stroke.begin", ["stroke": "ink", "layer": "a", "input": 2])
        scene.apply("stroke.visibility", ["stroke": "ink", "visible": false])
        scene.apply("stroke.begin", ["stroke": "finger", "layer": "a", "input": 0])
        scene.apply("stroke.end", ["stroke": "finger", "cancelled": true])
        XCTAssertEqual(scene.undone, ["ink"])
        scene.apply("stroke.visibility", ["stroke": "ink", "visible": true])
        XCTAssertTrue(scene.undone.isEmpty)
        XCTAssertTrue(scene.strokes[0].visible)
    }
}
