import SwiftUI
import RealityKit

/// The native three.js viewer: a volume that shows the 3D views of whatever box is selected in Field.
///
/// Geometry is rebuilt when a scene changes (event-driven, never per frame) and auto-framed into the
/// volume. A persistent `pivot` carries the fit plus the person's orbit and scale; the built model
/// hangs under it and is replaced on each rebuild. A view change resets the orbit; a geometry update
/// of the same view keeps it, so live code edits do not throw the model back to its start.
struct ThreeViewerView: View {
    @Environment(ThreeClient.self) private var client

    @State private var pivot = Entity()
    @State private var cage = Entity()
    /// The selected part's bounds, drawn as a wire box under `pivot`.
    @State private var selection = Entity()
    @State private var light = Entity()
    /// `label(...)` attachments, under `pivot` so they follow the orbit; their scale undoes the pivot's.
    @State private var labelRoot = Entity()
    /// `handle(...)` markers, under `pivot` in scene coordinates; each is a constant size in the room.
    @State private var handleRoot = Entity()
    /// Where this headset has put each handle during the current drag, by id.
    @State private var handleHeld: [String: SIMD3<Double>] = [:]
    @State private var handleGrab: (id: String, offset: SIMD3<Double>)?
    @State private var handleSentAt: Date = .distantPast
    @State private var builtHandles: [ThreeHandle] = []
    @State private var cache = ThreeMeshBuilder.Cache()
    /// The shot explode amount each drawable's model was last built at.
    @State private var shotApplied: [String: Float] = [:]
    @State private var built: ThreeMeshBuilder.Built?
    @State private var builtUID: String?
    @State private var builtRevision: String?

    /// Assembly group centroids for the built scene, for explode.
    @State private var centroids: [SIMD3<Float>?] = []
    @State private var modelCenter: SIMD3<Float> = .zero
    @State private var modelMaxDim: Float = 1
    /// Half the fitted bounds' diagonal: the farthest any corner gets from the centre at any orbit.
    @State private var modelRadius: Float = 1

    @State private var yaw: Float = 0
    @State private var pitch: Float = 0
    @State private var committedYaw: Float = 0
    @State private var committedPitch: Float = 0
    @State private var userScale: Float = 1
    @State private var committedScale: Float = 1

    /// The model's bounding sphere maps to this radius, inside the default 0.8 m volume (half-width
    /// 0.4 m). Fitting the longest axis instead let corners leave the volume, and be clipped, as soon
    /// as the model was orbited.
    private let targetRadius: Float = 0.36

    var body: some View {
        RealityView { content, _ in
            content.add(pivot)
            content.add(cage)
            pivot.addChild(selection)
            pivot.addChild(labelRoot)
            pivot.addChild(handleRoot)
            // A key light from above and in front, as the browser viewer's key light; the volume's
            // environment lighting does the fill.
            let key = DirectionalLightComponent(color: .white, intensity: 2600)
            light.components.set(key)
            light.look(at: [0, 0, 0], from: [0.4, 1, 0.8], relativeTo: nil)
            content.add(light)
        } update: { _, attachments in
            placeLabels(attachments)
        } attachments: {
            ForEach(labelItems) { item in
                Attachment(id: item.id) { ThreeLabelView(label: item.label) }
            }
        }
        .highPriorityGesture(handleGesture)
        .gesture(pickGesture)
        .simultaneousGesture(orbitGesture)
        .simultaneousGesture(scaleGesture)
        .onAppear { client.openVolumes += 1; client.start(); rebuild() }
        .onDisappear { client.openVolumes -= 1 }
        .onChange(of: client.revision) { _, _ in rebuild() }
        .onChange(of: client.shownUID) { _, _ in resetManipulation(); rebuild() }
        .onChange(of: client.viewStates[client.shownUID ?? ""]) { _, _ in applyViewState() }
        .onChange(of: client.liveHandles[client.shownUID ?? ""]) { _, _ in placeHandles() }
        .ornament(attachmentAnchor: .scene(.bottom)) { toolbar }
    }

    // MARK: - Toolbar

    @ViewBuilder private var toolbar: some View {
        HStack(spacing: 12) {
            Image(systemName: client.isConnected ? "cube.transparent" : "cube.transparent.fill")
                .foregroundStyle(client.isConnected ? Color.primary : Color.orange)
            if let name = client.shownBoxName {
                Text(name).font(.headline)
            }
            if client.isHolding {
                Image(systemName: "pin.fill").foregroundStyle(.secondary)
                    .help("Still showing this box: the box selected in Field has no 3D view")
            }
            if client.views.count > 1 {
                Picker("View", selection: shown) {
                    ForEach(client.views, id: \.uid) { view in Text(view.name).tag(view.uid as String?) }
                }
                .pickerStyle(.menu)
                .labelsHidden()
            }
            Text(statusText).font(.caption).foregroundStyle(.secondary)
        }
        .lineLimit(1)
        .fixedSize()
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .glassBackgroundEffect()
    }

    private var shown: Binding<String?> {
        Binding(get: { client.shownUID }, set: { client.shownUID = $0 })
    }

    private var statusText: String {
        guard client.shownScene != nil, let b = built, client.status == "Connected" || client.isHolding else { return client.status }
        var parts: [String] = []
        if let state = client.viewStates[client.shownUID ?? ""] {
            if state.explode > 0 { parts.append("explode \(Self.formatted(state.explode)) \(client.shownScene?.inspector?.units ?? "")") }
            if !state.hidden.isEmpty { parts.append("\(state.hidden.count) hidden") }
        }
        if b.triangles > 0 { parts.append("\(b.triangles) tri") }
        if b.segments > 0 { parts.append("\(b.segments) seg") }
        if b.points > 0 { parts.append("\(b.points) pt") }
        if b.reused > 0 { parts.append("\(b.reused) reused") }
        return parts.isEmpty ? "empty" : parts.joined(separator: " · ")
    }

    // MARK: - Build and fit

    private func rebuild() {
        guard let scene = client.shownScene else {
            pivot.children.removeAll(); cage.children.removeAll()
            built = nil; builtUID = nil; builtRevision = nil
            return
        }
        // The same geometry and styles again (a follow message, or another view's update): nothing to do.
        let key = (scene.generation ?? scene.revision).map { "\($0)|\(scene.appearance.hashValue)" }
        if scene.uid == builtUID, key != nil, key == builtRevision {
            // The same geometry, but a re-run may have moved its handles.
            if scene.handles != builtHandles { builtHandles = scene.handles; buildHandles(scene) }
            return
        }
        builtHandles = scene.handles
        builtUID = scene.uid
        builtRevision = key

        pivot.children.removeAll()
        let b = ThreeMeshBuilder.build(scene, cache: cache)
        pivot.addChild(b.root)
        pivot.addChild(selection)
        pivot.addChild(labelRoot)
        pivot.addChild(handleRoot)
        buildHandles(scene)
        shotApplied = [:]
        built = b

        centroids = ThreeExplode.centroids(scene, groupCount: scene.inspector?.groups?.count ?? 0)
        applyViewState()
    }

    /// Mirror the browser viewer's state onto the built entities. No rebuild.
    ///
    /// A viewer that sends `placements` has already applied explode, motion, assembly steps and shots
    /// to every part: its matrices are used as they are, and its `groupOffsets` move the FLine half.
    /// Before the browser has said anything (or from one that sends only an amount) the explode is
    /// worked out here, as it was.
    private func applyViewState() {
        guard let scene = client.shownScene, let b = built else { return }
        let state = client.viewStates[scene.uid]
        let hidden = Set(state?.hidden ?? [])
        let explode = scene.inspector?.explode
        let computed = ThreeExplode.offsets(
            centroids, origin: scene.bounds.map { ($0.min + $0.max) / 2 } ?? .zero,
            amount: state?.explode ?? Float(explode?.amount ?? 0), mode: explode?.mode ?? "radial",
            axis: explode?.axis.flatMap { $0.count >= 3 ? SIMD3($0[0], $0[1], $0[2]) : nil } ?? SIMD3(0, 1, 0))
        let mirrored = state?.groupOffsets?.map { $0.count >= 3 ? SIMD3($0[0], $0[1], $0[2]) : SIMD3<Float>.zero }
        let offsets = mirrored ?? computed
        let xray = state?.xray?.enabled == true ? state?.xray : nil
        for d in scene.drawables {
            guard let entity = b.parts[d.key], let placed = b.placed[d.key] else { continue }
            // Until the browser has said anything, a part's own `hidden` decides.
            entity.isEnabled = state == nil ? !d.hidden : !hidden.contains(d.id)
            if let m = state?.placements?[d.id], m.count == 16 {
                entity.position = .zero
                placed.transform = Transform(matrix: ThreeBinding.matrix(m))
            } else {
                placed.transform = Transform(matrix: d.transform)
                entity.position = d.group >= 0 && d.group < offsets.count ? offsets[d.group] : .zero
            }
            applyShot(d, state?.shot, placed: placed)
            if b.surfaces.contains(d.key), let xray {
                entity.components.set(OpacityComponent(opacity: d.appearance?.xrayOpacity ?? xray.opacity))
            } else if let shot = state?.shot, let solid = d.style.solidOpacity {
                // The shot mixes from solid to authored; the material already carries the authored opacity.
                let authored = max(d.style.opacity ?? 1, 1e-3)
                entity.components.set(OpacityComponent(opacity: min(1, (solid + (authored - solid) * shot.xray) / authored)))
            } else {
                entity.components.remove(OpacityComponent.self)
            }
        }
        showSelection(state?.selected, built: b)
        fit(scene, built: b)
    }

    /// A playing shot's per-vertex explode: swap in the model built at its amount (0 once it stops).
    private func applyShot(_ d: ThreeDrawable, _ shot: ThreeViewState.Shot?, placed: Entity) {
        guard d.geometry.explode != nil else { return }
        let amount = (Float(shot?.explode ?? 0) * 30).rounded() / 30
        guard shotApplied[d.key, default: 0] != amount else { return }
        let diag: Float = client.shownScene?.bounds.map { max(length($0.max - $0.min), 1e-4) } ?? 1
        guard let model = ThreeMeshBuilder.shotModel(d, amount: amount, diag: diag, cache: cache) else { return }
        placed.children.removeAll()
        placed.addChild(model)
        shotApplied[d.key] = amount
    }

    /// A wire box round the part selected in Field (or picked here).
    private func showSelection(_ id: String?, built b: ThreeMeshBuilder.Built) {
        selection.children.removeAll()
        guard let id else { return }
        // A part can be several entities (a surface and its edges): box them together.
        var box = BoundingBox.empty
        for (key, entity) in b.parts where b.ids[key] == id && entity.isEnabled {
            let bounds = entity.visualBounds(relativeTo: pivot)
            if !bounds.isEmpty { box = box.isEmpty ? bounds : box.union(bounds) }
        }
        guard !box.isEmpty else { return }
        let pad = box.extents * 0.02 + SIMD3(repeating: modelMaxDim * 0.002)
        if let wire = ThreeMeshBuilder.wireCuboid(size: box.extents + pad, color: SIMD4(1, 0.78, 0.2, 1)) {
            wire.position = box.center
            selection.addChild(wire)
        }
    }

    /// Frame the model, as it is placed now, into the volume. A volume clips what leaves it, so the fit
    /// covers where exploded or moved parts went as well as the assembled bounds.
    private func fit(_ scene: ThreeScene, built b: ThreeMeshBuilder.Built) {
        guard let bounds = scene.bounds else { return }
        var lo = bounds.min, hi = bounds.max
        let placed = b.root.visualBounds(relativeTo: pivot)
        if !placed.isEmpty { lo = simd_min(lo, placed.min); hi = simd_max(hi, placed.max) }
        modelCenter = (lo + hi) / 2
        let dims = hi - lo
        modelMaxDim = max(dims.max(), 1e-4)
        modelRadius = max(length(dims) / 2, 1e-4)
        cage.children.removeAll()
        if let wire = ThreeMeshBuilder.wireCuboid(size: dims, color: SIMD4(1, 1, 1, 0.35)) { cage.addChild(wire) }
        // x, y, z in red, green, blue from the most negative corner, so the axes stay readable at any orbit.
        if let axes = ThreeMeshBuilder.axisMarker(length: modelMaxDim * 0.2) {
            axes.position = -dims / 2
            cage.addChild(axes)
        }
        applyTransform()
    }

    private static func formatted(_ v: Float) -> String {
        v >= 100 ? String(format: "%.0f", v) : String(format: "%.2g", v)
    }

    private func applyTransform() {
        let q = simd_quatf(angle: yaw, axis: [0, 1, 0]) * simd_quatf(angle: pitch, axis: [1, 0, 0])
        let s = (targetRadius / modelRadius) * userScale
        pivot.orientation = q
        pivot.scale = SIMD3(repeating: s)
        labelRoot.children.forEach { $0.scale = SIMD3(repeating: Self.labelScale / s) }
        handleRoot.children.forEach { $0.scale = SIMD3(repeating: 1 / s) }
        pivot.position = -(q.act(s * modelCenter))
        cage.orientation = q
        cage.scale = SIMD3(repeating: s)
    }

    private func resetManipulation() {
        yaw = 0; pitch = 0; committedYaw = 0; committedPitch = 0
        userScale = 1; committedScale = 1
    }

    // MARK: - Labels

    private struct LabelItem: Identifiable {
        let id: String
        let label: ThreeLabel
    }

    /// An attachment's points come out a few millimetres tall at the volume's distance; on the headset this
    /// matches the browser's 12 px chip (2.5, judged in the simulator, read twice as large on the device).
    private static let labelScale: Float = 1.25

    private var labelItems: [LabelItem] {
        guard let scene = client.shownScene else { return [] }
        var items = scene.labels.enumerated().map { LabelItem(id: "\(scene.uid)#\(scene.revision ?? "")#\($0.offset)", label: $0.element) }
        for h in scene.handles {
            guard let text = h.label else { continue }
            // Lifted about 2 cm above the marker in the room, so the name does not sit on it.
            let lift = Double(0.02 / max(pivot.scale.x, 1e-6))
            let p = handlePosition(h) + SIMD3(0, lift, 0)
            items.append(LabelItem(id: "\(scene.uid)#handle#\(h.id)",
                                   label: ThreeLabel(position: [p.x, p.y, p.z], text: text, style: "tag",
                                                     color: nil, background: nil, border: nil)))
        }
        return items
    }

    /// Pin each label's attachment at its scene point, facing the viewer, at a size the orbit and fit do not change.
    private func placeLabels(_ attachments: RealityViewAttachments) {
        let items = labelItems
        let wanted = Set(items.map(\.id))
        for child in labelRoot.children where !wanted.contains(child.name) { child.removeFromParent() }
        let s = pivot.scale.x > 0 ? pivot.scale.x : 1
        for item in items {
            guard let entity = attachments.entity(for: item.id), item.label.position.count >= 3 else { continue }
            if entity.parent !== labelRoot {
                entity.name = item.id
                entity.components.set(BillboardComponent())
                labelRoot.addChild(entity)
            }
            let p = item.label.position
            entity.position = SIMD3(Float(p[0]), Float(p[1]), Float(p[2]))
            entity.scale = SIMD3(repeating: Self.labelScale / s)
        }
    }

    // MARK: - Handles

    /// A handle's place: where this headset is holding it, where another viewer is, or where the box drew it.
    private func handlePosition(_ h: ThreeHandle) -> SIMD3<Double> {
        if let held = handleHeld[h.id] { return held }
        if let live = client.liveHandles[client.shownUID ?? ""]?[h.id] { return live }
        return h.position.count >= 3 ? SIMD3(h.position[0], h.position[1], h.position[2]) : .zero
    }

    private func buildHandles(_ scene: ThreeScene) {
        handleRoot.children.removeAll()
        let s = pivot.scale.x > 0 ? pivot.scale.x : 1
        for h in scene.handles {
            let colour = cssColor(h.color).map { UIColor($0) } ?? UIColor(red: 1, green: 0.82, blue: 0.25, alpha: 1)
            // A 1 cm marker, with a 2 cm target so a pinch finds it.
            let marker = ModelEntity(mesh: .generateSphere(radius: 0.01), materials: [UnlitMaterial(color: colour)])
            marker.name = "handle:\(h.id)"
            marker.components.set(ThreeHandleComponent(id: h.id))
            marker.components.set(CollisionComponent(shapes: [.generateSphere(radius: 0.02)]))
            marker.components.set(InputTargetComponent())
            marker.components.set(HoverEffectComponent())
            marker.scale = SIMD3(repeating: 1 / s)
            handleRoot.addChild(marker)
        }
        placeHandles()
    }

    private func placeHandles() {
        guard let scene = client.shownScene else { return }
        for h in scene.handles {
            guard let marker = handleRoot.findEntity(named: "handle:\(h.id)") else { continue }
            let p = handlePosition(h)
            marker.position = SIMD3(Float(p.x), Float(p.y), Float(p.z))
        }
    }

    /// Pinch a marker and move it: in 3D, held to the handle's line or plane, told to Field as the
    /// browser tells it (start, moves at most every 33 ms, end).
    private var handleGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .targetedToEntity(where: .has(ThreeHandleComponent.self))
            .onChanged { value in
                guard let id = value.entity.components[ThreeHandleComponent.self]?.id,
                      let h = client.shownScene?.handles.first(where: { $0.id == id }) else { return }
                let f = value.convert(value.location3D, from: .local, to: pivot)
                let at = SIMD3<Double>(Double(f.x), Double(f.y), Double(f.z))
                if handleGrab?.id != id {
                    handleGrab = (id, handlePosition(h) - at)
                    client.dragHandle(id, to: handlePosition(h), phase: "start")
                    handleSentAt = Date()
                }
                let p = h.constrain(at + (handleGrab?.offset ?? .zero))
                handleHeld[id] = p
                placeHandles()
                if Date().timeIntervalSince(handleSentAt) >= 0.033 {
                    client.dragHandle(id, to: p, phase: "move")
                    handleSentAt = Date()
                }
            }
            .onEnded { value in
                guard let id = value.entity.components[ThreeHandleComponent.self]?.id else { return }
                if let p = handleHeld[id] { client.dragHandle(id, to: p, phase: "end") }
                handleGrab = nil
                handleHeld[id] = nil
            }
    }

    // MARK: - Gestures

    /// Tap a part to select it in Field; tap the selected part again to clear. The FLine half is not a part.
    private var pickGesture: some Gesture {
        SpatialTapGesture()
            .targetedToAnyEntity()
            .onEnded { value in
                guard let b = built else { return }
                var e: Entity? = value.entity
                while let current = e, b.parts[current.name] !== current { e = current.parent }
                guard let key = e?.name, let id = b.ids[key], !id.hasPrefix("__") else { return }
                let current = client.viewStates[client.shownUID ?? ""]?.selected
                client.select(part: current == id ? nil : id)
            }
    }

    private var orbitGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                yaw = committedYaw + Float(value.translation.width) * 0.01
                pitch = min(max(committedPitch + Float(value.translation.height) * 0.01, -1.5), 1.5)
                applyTransform()
            }
            .onEnded { _ in committedYaw = yaw; committedPitch = pitch }
    }

    private var scaleGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                userScale = max(0.05, committedScale * Float(value.magnification))
                applyTransform()
            }
            .onEnded { _ in committedScale = userScale }
    }
}
