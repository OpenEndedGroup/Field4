import Foundation

struct PenSample: Codable, Equatable {
    var id: Int
    var t: Double
    var x: Double
    var y: Double
    var previousX: Double
    var previousY: Double
    var precisePreviousX: Double
    var precisePreviousY: Double
    var preciseX: Double
    var preciseY: Double
    var drawingX: Double? = nil
    var drawingY: Double? = nil
    var pointX: Double { drawingX ?? preciseX }
    var pointY: Double { drawingY ?? preciseY }
    var force: Double
    var maximumForce: Double
    var altitude: Double?
    var azimuth: Double?
    var azimuthX: Double?
    var azimuthY: Double?
    var roll: Double?
    var radius: Double
    var radiusTolerance: Double
    var phase: Int
    var tapCount: Int
    var input: Int
    var estimated: Int
    var expectingUpdates: Int
    var estimationIndex: Int?
}

/// Maps drawing coordinates to on-screen points. Raw Pencil positions remain in the sample too.
struct DrawingTransform: Codable, Equatable {
    var a = 1.0, b = 0.0, c = 0.0, d = 1.0, tx = 0.0, ty = 0.0
    static let identity = DrawingTransform()
    var scale: Double { hypot(a, b) }
    func drawingPoint(x: Double, y: Double) -> (x: Double, y: Double) {
        let determinant = a * d - b * c
        return ((d * (x - tx) - c * (y - ty)) / determinant,
                (-b * (x - tx) + a * (y - ty)) / determinant)
    }
    /// A two-contact similarity transform in screen space, composed with the starting view.
    func moved(from start: [(Double, Double)], to end: [(Double, Double)]) -> DrawingTransform {
        guard start.count == 2, end.count == 2 else { return self }
        let sx = start[1].0 - start[0].0, sy = start[1].1 - start[0].1
        let ex = end[1].0 - end[0].0, ey = end[1].1 - end[0].1
        guard hypot(sx, sy) > 8, scale > 0 else { return self }
        let ratio = min(32 / scale, max(0.05 / scale, hypot(ex, ey) / hypot(sx, sy)))
        let angle = atan2(ey, ex) - atan2(sy, sx)
        let ga = ratio * cos(angle), gb = ratio * sin(angle), gc = -gb, gd = ga
        let startX = (start[0].0 + start[1].0) / 2, startY = (start[0].1 + start[1].1) / 2
        let endX = (end[0].0 + end[1].0) / 2, endY = (end[0].1 + end[1].1) / 2
        let gx = endX - ga * startX - gc * startY, gy = endY - gb * startX - gd * startY
        return DrawingTransform(a: ga*a + gc*b, b: gb*a + gd*b, c: ga*c + gc*d, d: gb*c + gd*d,
            tx: ga*tx + gc*ty + gx, ty: gb*tx + gd*ty + gy)
    }
}

struct DrawingViewport: Codable, Equatable {
    struct Rect: Codable, Equatable { var x: Double, y: Double, width: Double, height: Double }
    var width: Double
    var height: Double
    var screen: Rect
    /// Clockwise from the top-left of the display; exact visible footprint in drawing coordinates.
    var drawingCorners: [[Double]]
    var drawingBounds: Rect
    init(width: Double, height: Double, transform: DrawingTransform) {
        self.width = width; self.height = height
        screen = Rect(x: 0, y: 0, width: width, height: height)
        drawingCorners = [(0.0, 0.0), (width, 0), (width, height), (0, height)].map {
            let p = transform.drawingPoint(x: $0.0, y: $0.1); return [p.x, p.y]
        }
        let xs = drawingCorners.map { $0[0] }, ys = drawingCorners.map { $0[1] }
        let x = xs.min() ?? 0, y = ys.min() ?? 0
        drawingBounds = Rect(x: x, y: y, width: (xs.max() ?? 0) - x, height: (ys.max() ?? 0) - y)
    }
}

struct DrawingLayer: Codable, Equatable, Identifiable {
    var id: String = UUID().uuidString
    var name: String
    var visible = true
    var source: String? = nil
}

enum DrawingTool: String, Codable, CaseIterable, Identifiable {
    case freehand, line, circle
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
}

struct DrawingPoint: Codable, Equatable {
    var x: Double
    var y: Double
}

/// Shape intent is additional to, rather than a replacement for, measured input.
struct StrokeGeometry: Codable, Equatable {
    var tool: DrawingTool
    var start: DrawingPoint
    var end: DrawingPoint
    var center: DrawingPoint?
    var radius: Double?

    init(tool: DrawingTool, start: DrawingPoint, end: DrawingPoint) {
        self.tool = tool; self.start = start; self.end = end
        if tool == .circle {
            center = start; radius = hypot(end.x - start.x, end.y - start.y)
        }
    }
}

struct DrawingStroke: Identifiable {
    var id: String
    var layer: String
    var tool: DrawingTool = .freehand
    var samples: [PenSample] = []
    var visible = true
    var cancelled = false
    var ended = false
    var geometry: StrokeGeometry? {
        guard tool != .freehand, let first = samples.first, let last = samples.last else { return nil }
        return StrokeGeometry(tool: tool, start: DrawingPoint(x: first.pointX, y: first.pointY), end: DrawingPoint(x: last.pointX, y: last.pointY))
    }
}

/// Scene is a projection of the journal. Undo never mutates the journal.
struct Scene {
    var layers: [DrawingLayer] = []
    var strokes: [DrawingStroke] = []
    var activeLayer = ""
    var solo: String?
    var undone: [String] = []
    var transform = DrawingTransform.identity
    var selectedTool = DrawingTool.freehand
    var references: [String: FieldReferenceRecord] = [:]

    var visibleLayers: [String] {
        layers.filter { $0.visible && (solo == nil || solo == $0.id) }.map(\.id)
    }
    var undoCandidate: String? {
        strokes.last { $0.visible && !$0.cancelled && visibleLayers.contains($0.layer) }?.id
    }
    mutating func apply(_ type: String, _ p: [String: Any]) {
        switch type {
        case "field.reference":
            guard let raw = p["reference"], let data = try? JSONSerialization.data(withJSONObject: raw),
                  let reference = try? JSONDecoder().decode(FieldReferenceRecord.self, from: data) else { return }
            if !layers.contains(where: { $0.id == reference.layer }) {
                layers.append(DrawingLayer(id: reference.layer, name: reference.title, source: "field"))
            }
            references[reference.layer] = reference
        case "canvas.tool":
            selectedTool = DrawingTool(rawValue: p["tool"] as? String ?? "") ?? .freehand
        case "canvas.transform":
            if let raw = p["transform"], let data = try? JSONSerialization.data(withJSONObject: raw),
               let value = try? JSONDecoder().decode(DrawingTransform.self, from: data) { transform = value }
        case "layers.state":
            if let data = try? JSONSerialization.data(withJSONObject: p["layers"] ?? []),
               let decoded = try? JSONDecoder().decode([DrawingLayer].self, from: data) { layers = decoded }
            activeLayer = p["active"] as? String ?? activeLayer
            solo = p["solo"] as? String
        case "stroke.begin":
            guard let id = p["stroke"] as? String, let layer = p["layer"] as? String else { return }
            strokes.append(DrawingStroke(id: id, layer: layer, tool: DrawingTool(rawValue: p["tool"] as? String ?? "") ?? .freehand))
            // Finger contacts may still become navigation or undo/redo. Commit their undo branch only on a real end.
            if p["input"] as? Int != 0 { undone.removeAll() }
        case "stroke.samples", "stroke.correct":
            guard let id = p["stroke"] as? String, let index = strokes.lastIndex(where: { $0.id == id }),
                  let raw = p["samples"], let data = try? JSONSerialization.data(withJSONObject: raw),
                  let samples = try? JSONDecoder().decode([PenSample].self, from: data) else { return }
            if type == "stroke.correct" {
                for sample in samples {
                    if let i = strokes[index].samples.firstIndex(where: { $0.id == sample.id }) { strokes[index].samples[i] = sample }
                }
            } else { strokes[index].samples.append(contentsOf: samples) }
        case "stroke.end":
            if let id = p["stroke"] as? String, let i = strokes.lastIndex(where: { $0.id == id }) {
                strokes[i].cancelled = p["cancelled"] as? Bool ?? false
                strokes[i].ended = true
                if !strokes[i].cancelled && strokes[i].samples.first?.input == 0 { undone.removeAll() }
            }
        case "stroke.visibility":
            guard let id = p["stroke"] as? String, let visible = p["visible"] as? Bool,
                  let i = strokes.firstIndex(where: { $0.id == id }) else { return }
            strokes[i].visible = visible
            if visible { undone.removeAll { $0 == id } } else if !undone.contains(id) { undone.append(id) }
        default: break
        }
    }
}

func jsonObject<T: Encodable>(_ value: T) -> Any {
    // All model values originate in finite UIKit / audio values.
    guard let data = try? JSONEncoder().encode(value), let object = try? JSONSerialization.jsonObject(with: data) else { return NSNull() }
    return object
}
