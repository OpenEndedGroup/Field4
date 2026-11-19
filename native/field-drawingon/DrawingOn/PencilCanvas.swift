import SwiftUI
import UIKit

struct PencilCanvas: UIViewRepresentable {
    let store: CaptureStore
    func makeUIView(context: Context) -> CanvasView { CanvasView(store: store) }
    func updateUIView(_ view: CanvasView, context: Context) { view.setNeedsDisplay() }
}

final class CanvasView: UIView, UIPencilInteractionDelegate {
    private let store: CaptureStore
    private var active: [ObjectIdentifier: String] = [:]
    private var sampleID = 0
    private var estimates: [Int: (String, PenSample)] = [:]
    private var estimateTransforms: [Int: DrawingTransform] = [:]
    private var predictions: [String: [PenSample]] = [:]
    private var paths: [String: (Int, CGMutablePath)] = [:]
    private var previousSize = CGSize.zero
    private var navigationBase = DrawingTransform.identity

    init(store: CaptureStore) {
        self.store = store
        super.init(frame: .zero)
        isOpaque = true; isMultipleTouchEnabled = true; contentMode = .redraw
        accessibilityLabel = "Drawing canvas"
        accessibilityHint = "Draw with Apple Pencil. Move two fingers to pan, rotate and scale. Two-finger tap undoes; three-finger tap redoes."
        addInteraction(UIPencilInteraction(delegate: self))
        let navigation = TwoFingerNavigation(target: nil, action: nil)
        navigation.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        navigation.update = { [weak self] gesture, phase, event in self?.navigate(gesture, phase: phase, event: event) }
        addGestureRecognizer(navigation)
        let undo = UITapGestureRecognizer(target: self, action: #selector(undoTap))
        undo.numberOfTouchesRequired = 2
        let redo = UITapGestureRecognizer(target: self, action: #selector(redoTap))
        redo.numberOfTouchesRequired = 3
        for gesture in [undo, redo] {
            gesture.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
            gesture.delaysTouchesBegan = false
            addGestureRecognizer(gesture)
        }
        undo.require(toFail: redo)
        undo.require(toFail: navigation)
        redo.require(toFail: navigation)
        let hover = UIHoverGestureRecognizer(target: self, action: #selector(hovered(_:)))
        hover.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.pencil.rawValue)]
        addGestureRecognizer(hover)
        store.redraw = { [weak self] in self?.setNeedsDisplay() }
        store.cancelInput = { [weak self] in self?.finishAll() }
        store.captureDidChange = { [weak self] in
            guard let self else { return }
            self.active.removeAll(); self.paths.removeAll(); self.predictions.removeAll()
            self.estimates.removeAll(); self.estimateTransforms.removeAll(); self.previousSize = .zero
            self.setNeedsLayout(); self.setNeedsDisplay()
        }
        store.resetCanvas = { [weak self] in
            guard let self else { return }
            self.finishAll(); self.setTransform(.identity, phase: "reset")
        }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override var canBecomeFirstResponder: Bool { true }
    override var keyCommands: [UIKeyCommand]? {
        [UIKeyCommand(title: "Undo stroke", action: #selector(undoTap), input: "z", modifierFlags: .command),
         UIKeyCommand(title: "Redo stroke", action: #selector(redoTap), input: "z", modifierFlags: [.command, .shift])]
    }
    override func didMoveToWindow() { super.didMoveToWindow(); becomeFirstResponder() }
    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != previousSize && bounds.width > 0 {
            if previousSize != .zero { finishAll() }
            previousSize = bounds.size
            var viewport = visibleArea()
            viewport["scale"] = window?.screen.scale ?? contentScaleFactor
            viewport["orientation"] = window?.windowScene?.interfaceOrientation.rawValue ?? 0
            viewport["transform"] = jsonObject(store.scene.transform)
            store.journal.append("canvas.viewport", viewport)
        }
    }
    @objc private func undoTap() { store.undo() }
    @objc private func redoTap() { store.redo() }
    @objc private func hovered(_ gesture: UIHoverGestureRecognizer) {
        guard !store.finalizing else { return }
        let p = gesture.location(in: self), v = gesture.azimuthUnitVector(in: self)
        let drawing = store.scene.transform.drawingPoint(x: p.x, y: p.y)
        store.journal.append("pencil.hover", ["x": p.x, "y": p.y, "z": gesture.zOffset,
            "drawingX": drawing.x, "drawingY": drawing.y,
            "altitude": gesture.altitudeAngle, "azimuth": gesture.azimuthAngle(in: self),
            "azimuthX": v.dx, "azimuthY": v.dy, "roll": gesture.rollAngle, "phase": gesture.state.rawValue,
            "timestampSource": "callback"])
    }
    private func pose(_ pose: UIPencilHoverPose?) -> Any {
        guard let pose else { return NSNull() }
        return ["x": pose.location.x, "y": pose.location.y, "z": pose.zOffset,
            "altitude": pose.altitudeAngle, "azimuth": pose.azimuthAngle,
            "azimuthX": pose.azimuthUnitVector.dx, "azimuthY": pose.azimuthUnitVector.dy, "roll": pose.rollAngle]
    }
    func pencilInteraction(_ interaction: UIPencilInteraction, didReceiveTap tap: UIPencilInteraction.Tap) {
        guard !store.finalizing else { return }
        store.journal.append("pencil.tap", ["pose": pose(tap.hoverPose)], at: store.journal.time(tap.timestamp))
        if UIPencilInteraction.preferredTapAction != .ignore { store.undo() }
    }
    func pencilInteraction(_ interaction: UIPencilInteraction, didReceiveSqueeze squeeze: UIPencilInteraction.Squeeze) {
        guard !store.finalizing else { return }
        store.journal.append("pencil.squeeze", ["pose": pose(squeeze.hoverPose), "phase": squeeze.phase.rawValue], at: store.journal.time(squeeze.timestamp))
        if squeeze.phase == .ended && UIPencilInteraction.preferredSqueezeAction != .ignore {
            finishAll(); store.menuOpen.toggle()
        }
    }
    private func sample(_ touch: UITouch, id: Int) -> PenSample {
        let p = touch.location(in: self), precise = touch.preciseLocation(in: self)
        let previous = touch.previousLocation(in: self), precisePrevious = touch.precisePreviousLocation(in: self)
        let azimuth = touch.azimuthUnitVector(in: self)
        let drawing = store.scene.transform.drawingPoint(x: precise.x, y: precise.y)
        let pencil = touch.type == .pencil
        return PenSample(id: id, t: store.journal.time(touch.timestamp), x: p.x, y: p.y,
            previousX: previous.x, previousY: previous.y, precisePreviousX: precisePrevious.x, precisePreviousY: precisePrevious.y,
            preciseX: precise.x, preciseY: precise.y,
            drawingX: drawing.x, drawingY: drawing.y,
            force: touch.force, maximumForce: touch.maximumPossibleForce,
            altitude: pencil ? touch.altitudeAngle : nil, azimuth: pencil ? touch.azimuthAngle(in: self) : nil,
            azimuthX: pencil ? azimuth.dx : nil, azimuthY: pencil ? azimuth.dy : nil, roll: pencil ? touch.rollAngle : nil,
            radius: touch.majorRadius, radiusTolerance: touch.majorRadiusTolerance,
            phase: touch.phase.rawValue, tapCount: touch.tapCount, input: touch.type.rawValue,
            estimated: Int(touch.estimatedProperties.rawValue), expectingUpdates: Int(touch.estimatedPropertiesExpectingUpdates.rawValue),
            estimationIndex: touch.estimationUpdateIndex?.intValue)
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !store.finalizing, !store.menuOpen, store.scene.visibleLayers.contains(store.scene.activeLayer) else { return }
        for touch in touches where touch.type == .pencil || (store.fingerDrawing && touch.type == .direct) {
            let id = UUID().uuidString
            active[ObjectIdentifier(touch)] = id
            store.emit("stroke.begin", ["stroke": id, "layer": store.scene.activeLayer,
                "tool": store.tool.rawValue,
                "visibleLayers": store.scene.visibleLayers, "input": touch.type.rawValue,
                "viewport": visibleArea(), "transform": jsonObject(store.scene.transform)])
            capture(touch, event)
        }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches { capture(touch, event) }
    }
    private func capture(_ touch: UITouch, _ event: UIEvent?) {
        guard let stroke = active[ObjectIdentifier(touch)] else { return }
        var delivered = event?.coalescedTouches(for: touch) ?? []
        // Coalesced history need not contain the terminal touch (notably delayed gesture delivery).
        if let last = delivered.last {
            if last.timestamp != touch.timestamp || last.phase != touch.phase || last.preciseLocation(in: self) != touch.preciseLocation(in: self) {
                delivered.append(touch)
            }
        } else { delivered = [touch] }
        let measured = delivered.map { t -> PenSample in
            sampleID += 1
            let s = sample(t, id: sampleID)
            if let index = s.estimationIndex, s.expectingUpdates != 0 {
                estimates[index] = (stroke, s); estimateTransforms[index] = store.scene.transform
            }
            return s
        }
        store.emit("stroke.samples", ["stroke": stroke, "samples": jsonObject(measured),
            "eventUptime": event?.timestamp ?? touch.timestamp, "touchUptime": touch.timestamp, "touchPhase": touch.phase.rawValue])
        recordGeometry(stroke)
        let predicted = (event?.predictedTouches(for: touch) ?? []).enumerated().map { sample($0.element, id: -($0.offset + 1)) }
        predictions[stroke] = predicted
        // Every replacement invalidates all earlier predictions for this stroke.
        store.journal.append("stroke.prediction", ["stroke": stroke, "samples": jsonObject(predicted)])
        setNeedsDisplay()
    }
    override func touchesEstimatedPropertiesUpdated(_ touches: Set<UITouch>) {
        for touch in touches {
            guard let index = touch.estimationUpdateIndex?.intValue, let (stroke, original) = estimates[index] else { continue }
            var s = sample(touch, id: original.id)
            s.t = original.t; s.phase = original.phase
            // Estimate updates use the view transform at original capture, even after navigation.
            // UIKit corrections can change precise location; recover the original view from its event.
            let transform = estimateTransforms[index] ?? store.scene.transform
            let drawing = transform.drawingPoint(x: s.preciseX, y: s.preciseY)
            s.drawingX = drawing.x; s.drawingY = drawing.y
            store.emit("stroke.correct", ["stroke": stroke, "samples": jsonObject([s])])
            recordGeometry(stroke)
            if s.expectingUpdates == 0 {
                estimates.removeValue(forKey: index); estimateTransforms.removeValue(forKey: index)
            } else { estimates[index] = (stroke, s) }
            paths.removeValue(forKey: stroke)
        }
        setNeedsDisplay()
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { finish(touches, event, cancelled: false) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { finish(touches, event, cancelled: true) }
    private func finish(_ touches: Set<UITouch>, _ event: UIEvent?, cancelled: Bool) {
        for touch in touches {
            guard let id = active[ObjectIdentifier(touch)] else { continue }
            capture(touch, event)
            store.emit("stroke.end", ["stroke": id, "cancelled": cancelled])
            predictions.removeValue(forKey: id); active.removeValue(forKey: ObjectIdentifier(touch))
        }
        setNeedsDisplay()
    }
    private func finishAll() {
        for id in active.values { store.emit("stroke.end", ["stroke": id, "cancelled": false, "reason": "interface action"]) }
        active.removeAll(); predictions.removeAll(); setNeedsDisplay()
    }
    private func recordGeometry(_ id: String) {
        if let geometry = store.scene.strokes.last(where: { $0.id == id })?.geometry {
            store.journal.append("stroke.geometry", ["stroke": id, "geometry": jsonObject(geometry)])
        }
    }
    private func visibleArea() -> [String: Any] {
        let viewport = DrawingViewport(width: bounds.width, height: bounds.height, transform: store.scene.transform)
        return jsonObject(viewport) as? [String: Any] ?? [:]
    }
    private func setTransform(_ transform: DrawingTransform, phase: String, contacts: [PenSample] = []) {
        store.scene.transform = transform
        store.emit("canvas.transform", ["transform": jsonObject(transform), "phase": phase,
            "viewport": visibleArea(), "contacts": jsonObject(contacts)])
    }
    private func navigate(_ gesture: TwoFingerNavigation, phase: String, event: UIEvent?) {
        guard !store.finalizing else { return }
        if phase == "began" {
            navigationBase = store.scene.transform
            for id in active.values {
                let pencil = store.scene.strokes.last(where: { $0.id == id })?.samples.last?.input == UITouch.TouchType.pencil.rawValue
                store.emit("stroke.end", ["stroke": id, "cancelled": !pencil, "reason": "view navigation"])
            }
            active.removeAll(); predictions.removeAll()
        }
        let initial = gesture.origin.map { (Double($0.x), Double($0.y)) }
        let current = gesture.contacts.map { touch -> (Double, Double) in
            let p = touch.location(in: self); return (Double(p.x), Double(p.y))
        }
        let transform = phase == "cancelled" ? navigationBase : navigationBase.moved(from: initial, to: current)
        store.scene.transform = transform
        let contacts = gesture.contacts.flatMap { touch -> [PenSample] in
            let history = event?.coalescedTouches(for: touch) ?? [touch]
            return history.map { t in sampleID += 1; return sample(t, id: sampleID) }
        }
        setTransform(transform, phase: phase, contacts: contacts)
    }
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        (store.light ? UIColor.white : UIColor.black).setFill(); context.fill(bounds)
        let transform = store.scene.transform
        context.concatenate(CGAffineTransform(a: transform.a, b: transform.b, c: transform.c, d: transform.d, tx: transform.tx, ty: transform.ty))
        context.setBlendMode(store.light ? .plusDarker : .plusLighter)
        context.setLineCap(.round); context.setLineJoin(.round); context.setLineWidth(1.8)
        context.setStrokeColor((store.light ? UIColor.black : UIColor.white).withAlphaComponent(0.24).cgColor)
        context.setFillColor((store.light ? UIColor.black : UIColor.white).withAlphaComponent(0.24).cgColor)
        let visible = Set(store.scene.visibleLayers)
        context.saveGState()
        for layer in store.scene.layers where visible.contains(layer.id) {
            guard let drawing = store.referenceDrawings[layer.id] else { continue }
            for batch in drawing.batches {
                for start in stride(from: 0, to: batch.indices.count, by: batch.arity) {
                    let vertices = (0..<batch.arity).map { batch.vertices[batch.indices[start + $0]] }
                    let first = vertices[0]
                    func channel(_ c: Double) -> CGFloat { CGFloat(min(1, max(0, store.light ? 1 - c : c))) }
                    let color = UIColor(red: channel(first.r), green: channel(first.g), blue: channel(first.b), alpha: CGFloat(min(1, max(0, first.a)))).cgColor
                    context.setFillColor(color); context.setStrokeColor(color)
                    if batch.arity == 1 {
                        context.fillEllipse(in: CGRect(x: first.x - 1.5, y: first.y - 1.5, width: 3, height: 3))
                    } else {
                        context.move(to: CGPoint(x: first.x, y: first.y))
                        for vertex in vertices.dropFirst() { context.addLine(to: CGPoint(x: vertex.x, y: vertex.y)) }
                        if batch.arity == 3 { context.closePath(); context.fillPath() } else { context.strokePath() }
                    }
                }
            }
        }
        context.restoreGState()
        for stroke in store.scene.strokes where stroke.visible && !stroke.cancelled && visible.contains(stroke.layer) {
            if stroke.tool != .freehand {
                guard let first = stroke.samples.first, let last = predictions[stroke.id]?.last ?? stroke.samples.last else { continue }
                let geometry = StrokeGeometry(tool: stroke.tool, start: DrawingPoint(x: first.pointX, y: first.pointY), end: DrawingPoint(x: last.pointX, y: last.pointY))
                switch stroke.tool {
                case .line:
                    context.move(to: CGPoint(x: geometry.start.x, y: geometry.start.y))
                    context.addLine(to: CGPoint(x: geometry.end.x, y: geometry.end.y)); context.strokePath()
                case .circle:
                    let r = geometry.radius ?? 0
                    if r > 0 { context.strokeEllipse(in: CGRect(x: first.pointX - r, y: first.pointY - r, width: r * 2, height: r * 2)) }
                case .freehand: break
                }
                continue
            }
            var (count, path) = paths[stroke.id] ?? (0, CGMutablePath())
            for sample in stroke.samples.dropFirst(count) {
                let p = CGPoint(x: sample.pointX, y: sample.pointY)
                if count == 0 { path.move(to: p) } else { path.addLine(to: p) }
                count += 1
            }
            paths[stroke.id] = (count, path)
            if count == 1, let sample = stroke.samples.first {
                context.fillEllipse(in: CGRect(x: sample.pointX - 0.9, y: sample.pointY - 0.9, width: 1.8, height: 1.8))
            } else { context.addPath(path); context.strokePath() }
            if let future = predictions[stroke.id], !future.isEmpty, let last = stroke.samples.last {
                context.move(to: CGPoint(x: last.pointX, y: last.pointY))
                for s in future { context.addLine(to: CGPoint(x: s.pointX, y: s.pointY)) }
                context.strokePath()
            }
        }
    }
}

/// One recognizer computes all three motions from the same pair of contacts.
/// It stays possible during a stationary tap, so undo/redo retain their gestures.
final class TwoFingerNavigation: UIGestureRecognizer {
    private(set) var contacts: [UITouch] = []
    private(set) var origin: [CGPoint] = []
    var update: ((TwoFingerNavigation, String, UIEvent?) -> Void)?
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        for touch in touches where !contacts.contains(where: { $0 === touch }) { contacts.append(touch) }
        if contacts.count > 2 {
            if state == .began || state == .changed { update?(self, "cancelled", event); state = .cancelled }
            else { state = .failed }
        } else if contacts.count == 2 { origin = contacts.map { $0.location(in: view) } }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard contacts.count == 2, origin.count == 2 else { return }
        if state == .possible {
            let moved = zip(contacts, origin).contains { touch, initial in
                let p = touch.location(in: view)
                return hypot(p.x - initial.x, p.y - initial.y) > 6
            }
            guard moved else { return }
            state = .began; update?(self, "began", event)
        } else if state == .began || state == .changed {
            state = .changed; update?(self, "changed", event)
        }
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        if state == .began || state == .changed { update?(self, "ended", event); state = .ended }
        else { state = .failed }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        if state == .began || state == .changed { update?(self, "cancelled", event); state = .cancelled }
        else { state = .failed }
    }
    override func reset() { super.reset(); contacts.removeAll(); origin.removeAll() }
}
