import SwiftUI
import RealityKit

/// The native FLine viewer, presented in a movable/resizable volumetric window. It subscribes to
/// every live three-geometry view (`FLineViewerClient`), rebuilds RealityKit geometry when the shown
/// view's payload changes (event-driven — NOT per frame), auto-frames it into the volume, and offers
/// orbit/scale manipulation plus tap-to-click interactivity and node-drag editing.
///
/// Transform model: a persistent `pivot` entity carries the fit + user orbit/scale; the built model
/// (`built.root`, all in model coordinates) is re-parented under it on each rebuild. Because entities
/// are reference types, we mutate `pivot` directly outside the RealityView closures — no per-frame work.
struct FLineViewerView: View {
    @Environment(FLineViewerClient.self) private var client

    /// Persistent transform node; the built model hangs under it. Never replaced, only re-childed.
    @State private var pivot = Entity()
    @State private var built: FLineMeshBuilder.Built?

    // Auto-frame inputs, captured from the last payload's bounds.
    @State private var modelCenter: SIMD3<Float> = .zero
    @State private var modelMaxDim: Float = 1

    // User manipulation (composed on top of the fit).
    @State private var yaw: Float = 0
    @State private var pitch: Float = 0
    @State private var committedYaw: Float = 0
    @State private var committedPitch: Float = 0
    @State private var userScale: Float = 1
    @State private var committedScale: Float = 1

    @State private var editMode = false
    @State private var appliedRevision = -1

    // Node-drag state (model space).
    @State private var dragLastModel: SIMD3<Float>?
    // Locally-edited copy of the scene (optimistic). The server doesn't re-push after an edit — like
    // the web viewer, we apply edits locally and keep them until a fresh server payload reconciles.
    @State private var editScene: FLineScene?

    // Bounding cage: two wireframe cuboids we crossfade between — `cageModel` tumbles/scales WITH the
    // model (shown while manipulating), `cageAligned` is axis-aligned in window space (shown at rest).
    @State private var cageModel = Entity()
    @State private var cageAligned = Entity()
    @State private var manipulating = false
    @State private var crossfadeTask: Task<Void, Never>?

    /// Fit target: the model's longest axis maps to ~this many metres, sitting inside the default
    /// 0.8 m volume. (Reacting to live volume-resize is a device-verify refinement.)
    private let targetExtent: Float = 0.6

    var body: some View {
        RealityView { content in
            content.add(pivot)
            cageModel.components.set(OpacityComponent(opacity: 0))
            cageAligned.components.set(OpacityComponent(opacity: 0))
            content.add(cageModel)
            content.add(cageAligned)
        }
        // Node-drag + tap are targeted (only fire on a handle/interactive entity); give them PRIORITY
        // over the untargeted orbit drag so grabbing a handle edits it instead of rotating the space.
        // Over empty space the targeted gestures don't start, so orbit still runs.
        .highPriorityGesture(nodeDragGesture)
        .highPriorityGesture(tapGesture)
        .gesture(orbitGesture)
        .gesture(scaleGesture)
        .onAppear {
            client.start()
            // Headless testing: FIELD4_FLINE_EDIT=1 starts in edit mode (simctl can't tap "Edit").
            if ProcessInfo.processInfo.environment["FIELD4_FLINE_EDIT"] == "1" { editMode = true }
            rebuild()
        }
        .onChange(of: client.revision) { _, _ in editScene = nil; rebuildFromLatest() }
        .onChange(of: client.selectedUID) { _, _ in editScene = nil; resetManipulation(); rebuild() }
        .onChange(of: editMode) { _, _ in rebuild() }
        .ornament(attachmentAnchor: .scene(.bottom)) { toolbar }
    }

    // MARK: - Toolbar

    @ViewBuilder private var toolbar: some View {
        HStack(spacing: 12) {
            if client.order.count > 1 {
                Picker("View", selection: viewSelection) {
                    ForEach(client.order, id: \.self) { uid in
                        Text(shortLabel(uid)).tag(uid as String?)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
            }

            let editable = client.selectedPayload?.editableEnabled ?? false
            Button {
                editMode.toggle()
            } label: {
                // Constant short label (the fill + tint signal the active state) so it never wraps.
                Label("Edit", systemImage: editMode ? "pencil.circle.fill" : "pencil.circle")
            }
            .tint(editMode ? .orange : nil)
            .disabled(!editable)

            Button { resetManipulation(); applyTransform() } label: {
                Label("Reset", systemImage: "arrow.counterclockwise")
            }

            if editMode, let scene = client.selectedPayload?.scene {
                Button(role: .destructive) {
                    client.sendClear(sessionId: scene.sessionId)
                } label: {
                    Label("Clear", systemImage: "trash")
                }
            }

            Text(statusText)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .lineLimit(1)
        .fixedSize()   // size the ornament to its content so labels never wrap ("Edit-ing")
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .glassBackgroundEffect()
    }

    private var viewSelection: Binding<String?> {
        // Just set the uid — `.onChange(of: client.selectedUID)` resets manipulation and rebuilds.
        Binding(get: { client.selectedUID }, set: { client.selectedUID = $0 })
    }

    private var statusText: String {
        guard let p = client.selectedPayload else { return client.status }
        let m = p.meta
        return "\(m?.lineSegments ?? 0) seg · \(m?.triangles ?? 0) tri · \(m?.points ?? 0) pt"
    }

    private func shortLabel(_ uid: String) -> String {
        // uids are "<boxId>_<name>"; show the trailing name if present.
        if let r = uid.range(of: "_", options: .backwards) { return String(uid[r.upperBound...]) }
        return uid
    }

    // MARK: - Build / fit

    private func rebuildFromLatest() {
        guard client.revision != appliedRevision || built == nil else { return }
        appliedRevision = client.revision
        rebuild()
    }

    private func rebuild() {
        guard var payload = client.selectedPayload else { return }
        // Prefer the locally-edited scene (optimistic edits) over the server's when present.
        if let editScene { payload.scene = editScene }
        appliedRevision = client.revision
        pivot.children.removeAll()
        let b = FLineMeshBuilder.build(payload, editMode: editMode && payload.editableEnabled)
        pivot.addChild(b.root)
        built = b

        if let bounds = payload.bounds {
            modelCenter = bounds.center
            modelMaxDim = max(bounds.maxDimension, 1e-4)
            let dims = SIMD3(bounds.max[0] - bounds.min[0],
                             bounds.max[1] - bounds.min[1],
                             bounds.max[2] - bounds.min[2])
            rebuildCage(dims: dims)
        }
        applyTransform()
        if !manipulating { setCageIdleInstant() }
    }

    /// EXPERIMENT toggle: try genuine 1px `.line`-topology cage (LowLevelMesh); fall back to thin
    /// boxes if RealityKit won't build/shade it. Flip to `false` to force the thick-box wireframe.
    private let useLineTopologyCage = true

    /// Rebuild both cage wireframes to the model's AABB dimensions (model units, centred at origin).
    private func rebuildCage(dims: SIMD3<Float>) {
        let t = max(modelMaxDim * 0.004, 1e-4)
        cageModel.children.removeAll()
        cageAligned.children.removeAll()
        cageModel.addChild(makeCage(dims: dims, thickness: t, color: .init(1, 1, 1, 0.6)))
        cageAligned.addChild(makeCage(dims: dims, thickness: t, color: .init(1, 1, 1, 0.45)))
    }

    private func makeCage(dims: SIMD3<Float>, thickness: Float, color: SIMD4<Float>) -> Entity {
        if useLineTopologyCage, let lines = FLineMeshBuilder.wireCuboidLines(size: dims, color: color) {
            return lines
        }
        return FLineMeshBuilder.wireCuboid(size: dims, thickness: thickness, color: color)
    }

    /// Recompute the pivot transform from the captured fit + user orbit/scale — no mesh rebuild.
    private func applyTransform() {
        let q = simd_quatf(angle: yaw, axis: [0, 1, 0]) * simd_quatf(angle: pitch, axis: [1, 0, 0])
        let s = (targetExtent / modelMaxDim) * userScale
        pivot.orientation = q
        pivot.scale = SIMD3(repeating: s)
        // Map the model centre to the volume origin after rotation + scale.
        pivot.position = -(q.act(s * modelCenter))

        // Cages are centred at the volume origin (where the model centre lands) and scaled by the same
        // fit+user scale. `cageModel` shares the model's rotation (tumbles with it); `cageAligned` is
        // held axis-aligned to the window. Crossfading between them settles the cage upright at rest.
        let cageScale = SIMD3(repeating: s)
        cageModel.position = .zero; cageModel.scale = cageScale; cageModel.orientation = q
        cageAligned.position = .zero; cageAligned.scale = cageScale
        cageAligned.orientation = simd_quatf(angle: 0, axis: [0, 1, 0])
    }

    private func resetManipulation() {
        yaw = 0; pitch = 0; committedYaw = 0; committedPitch = 0
        userScale = 1; committedScale = 1
    }

    // MARK: - Bounding cage crossfade

    /// Called at the start/end of an orbit or scale gesture. Active → show the tumbling `cageModel`;
    /// at rest → crossfade to the axis-aligned `cageAligned` (a gentle "settle" over ~0.45s).
    private func setCageManipulating(_ active: Bool) {
        guard manipulating != active else { return }
        manipulating = active
        crossfadeTask?.cancel()
        let duration = active ? 0.18 : 0.45
        let fromM = cageOpacity(cageModel), fromA = cageOpacity(cageAligned)
        let toM: Float = active ? 1 : 0
        let toA: Float = active ? 0 : 1
        crossfadeTask = Task { @MainActor in
            let steps = 24
            for i in 0...steps {
                if Task.isCancelled { return }
                let t = Float(i) / Float(steps)
                let e = t * t * (3 - 2 * t)   // smoothstep
                setCageOpacity(cageModel, fromM + (toM - fromM) * e)
                setCageOpacity(cageAligned, fromA + (toA - fromA) * e)
                try? await Task.sleep(for: .seconds(duration / Double(steps)))
            }
        }
    }

    /// No-animation idle state (used right after a rebuild when not manipulating).
    private func setCageIdleInstant() {
        crossfadeTask?.cancel()
        setCageOpacity(cageModel, 0)
        setCageOpacity(cageAligned, 1)
    }

    private func cageOpacity(_ e: Entity) -> Float { e.components[OpacityComponent.self]?.opacity ?? 0 }
    private func setCageOpacity(_ e: Entity, _ v: Float) { e.components.set(OpacityComponent(opacity: max(0, min(1, v)))) }

    // MARK: - Gestures

    /// Orbit the whole model — an untargeted drag over empty space / non-interactive geometry.
    private var orbitGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                // Yield to an in-progress node drag (edit mode) so a handle drag doesn't also orbit.
                guard dragLastModel == nil else { return }
                setCageManipulating(true)
                yaw = committedYaw + Float(value.translation.width) * 0.01
                pitch = clampAngle(committedPitch + Float(value.translation.height) * 0.01)
                applyTransform()
            }
            .onEnded { _ in committedYaw = yaw; committedPitch = pitch; setCageManipulating(false) }
    }

    private var scaleGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                setCageManipulating(true)
                userScale = max(0.05, committedScale * Float(value.magnification))
                applyTransform()
            }
            .onEnded { _ in committedScale = userScale; setCageManipulating(false) }
    }

    /// Tap an interactive FLine → fire its server callback with the nearest vertex.
    private var tapGesture: some Gesture {
        SpatialTapGesture()
            .targetedToAnyEntity()
            .onEnded { value in
                guard !editMode, let ref = built?.interactive[value.entity.id] else { return }
                let world = value.convert(value.location3D, from: .local, to: .scene)
                let model = pivot.convert(position: world, from: nil)
                let nearest = ref.vertices.min {
                    distanceSquared($0.position, model) < distanceSquared($1.position, model)
                }
                client.sendClick(callbackId: ref.callbackId,
                                 closestVertexIndex: nearest?.index ?? -1,
                                 position: model)
            }
    }

    /// Drag a node handle (edit mode) → send incremental MODEL-space translate deltas.
    private var nodeDragGesture: some Gesture {
        DragGesture()
            .targetedToAnyEntity()
            .onChanged { value in
                guard editMode, let ref = built?.nodes[value.entity.id],
                      let baseScene = client.selectedPayload?.scene else { return }
                if editScene == nil { editScene = baseScene }   // start local edits from the server scene
                let world = value.convert(value.location3D, from: .local, to: .scene)
                let model = pivot.convert(position: world, from: nil)
                guard let last = dragLastModel else { dragLastModel = model; return }
                let delta = model - last
                dragLastModel = model
                // Optimistic: move the handle for live feedback AND accumulate into the local scene.
                // We don't rebuild the tubes mid-drag (that would recreate the entity we're dragging);
                // the geometry re-renders from the edited scene on release.
                value.entity.position += delta
                applyNodeDelta(ref: ref, delta: delta)
                client.sendTranslate(
                    sessionId: baseScene.sessionId,
                    points: [FLinePointRefMsg(lineIndex: ref.lineIndex, nodeIndex: ref.nodeIndex, handle: ref.handle)],
                    delta: delta)
            }
            .onEnded { _ in dragLastModel = nil; rebuild() }   // rebuild geometry from the edited scene
    }
}

extension FLineViewerView {
    /// Accumulate a model-space drag delta into the local (optimistic) scene for one node handle.
    fileprivate func applyNodeDelta(ref: FLineMeshBuilder.NodeRef, delta: SIMD3<Float>) {
        guard editScene != nil,
              let li = editScene!.lines.firstIndex(where: { $0.lineIndex == ref.lineIndex }),
              let ni = editScene!.lines[li].nodes.firstIndex(where: { $0.nodeIndex == ref.nodeIndex })
        else { return }
        func bump(_ p: [Float]?) -> [Float]? {
            guard let p, p.count >= 3 else { return p }
            return [p[0] + delta.x, p[1] + delta.y, p[2] + delta.z]
        }
        switch ref.handle {
        case .TO: editScene!.lines[li].nodes[ni].to = bump(editScene!.lines[li].nodes[ni].to)
        case .C1: editScene!.lines[li].nodes[ni].c1 = bump(editScene!.lines[li].nodes[ni].c1)
        case .C2: editScene!.lines[li].nodes[ni].c2 = bump(editScene!.lines[li].nodes[ni].c2)
        }
    }
}

private func clampAngle(_ x: Float) -> Float { min(max(x, -1.5), 1.5) }

private func distanceSquared(_ a: [Float], _ b: SIMD3<Float>) -> Float {
    guard a.count >= 3 else { return .greatestFiniteMagnitude }
    let dx = a[0] - b.x, dy = a[1] - b.y, dz = a[2] - b.z
    return dx * dx + dy * dy + dz * dz
}
