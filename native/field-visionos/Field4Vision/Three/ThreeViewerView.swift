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
    @State private var built: ThreeMeshBuilder.Built?
    @State private var builtUID: String?
    @State private var builtRevision: String?

    /// Assembly group centroids for the built scene, for explode.
    @State private var centroids: [SIMD3<Float>?] = []
    @State private var modelCenter: SIMD3<Float> = .zero
    @State private var modelMaxDim: Float = 1

    @State private var yaw: Float = 0
    @State private var pitch: Float = 0
    @State private var committedYaw: Float = 0
    @State private var committedPitch: Float = 0
    @State private var userScale: Float = 1
    @State private var committedScale: Float = 1

    /// The model's longest axis maps to this many metres, inside the default 0.8 m volume.
    private let targetExtent: Float = 0.6

    var body: some View {
        RealityView { content in
            content.add(pivot)
            content.add(cage)
        }
        .gesture(orbitGesture)
        .simultaneousGesture(scaleGesture)
        .onAppear { client.start(); rebuild() }
        .onChange(of: client.revision) { _, _ in rebuild() }
        .onChange(of: client.shownUID) { _, _ in resetManipulation(); rebuild() }
        .onChange(of: client.viewStates[client.shownUID ?? ""]) { _, _ in applyViewState() }
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
        return parts.isEmpty ? "empty" : parts.joined(separator: " · ")
    }

    // MARK: - Build and fit

    private func rebuild() {
        guard let scene = client.shownScene else {
            pivot.children.removeAll(); cage.children.removeAll()
            built = nil; builtUID = nil; builtRevision = nil
            return
        }
        // The same geometry again (a follow message, or another view's update): nothing to do.
        let key = scene.generation ?? scene.revision
        if scene.uid == builtUID, key != nil, key == builtRevision { return }
        builtUID = scene.uid
        builtRevision = key

        pivot.children.removeAll()
        let b = ThreeMeshBuilder.build(scene)
        pivot.addChild(b.root)
        built = b

        centroids = ThreeExplode.centroids(scene, groupCount: scene.inspector?.groups?.count ?? 0)
        applyViewState()
    }

    /// Mirror the browser viewer's explode and hidden parts onto the built entities. No rebuild.
    private func applyViewState() {
        guard let scene = client.shownScene, let b = built else { return }
        let state = client.viewStates[scene.uid]
        let hidden = Set(state?.hidden ?? [])
        let explode = scene.inspector?.explode
        let offsets = ThreeExplode.offsets(
            centroids, origin: scene.bounds.map { ($0.min + $0.max) / 2 } ?? .zero,
            amount: state?.explode ?? Float(explode?.amount ?? 0), mode: explode?.mode ?? "radial",
            axis: explode?.axis.flatMap { $0.count >= 3 ? SIMD3($0[0], $0[1], $0[2]) : nil } ?? SIMD3(0, 1, 0))
        for d in scene.drawables {
            guard let entity = b.parts[d.id] else { continue }
            // Until the browser has said anything, a part's own `hidden` decides.
            entity.isEnabled = state == nil ? !d.hidden : !hidden.contains(d.id)
            entity.position = d.group >= 0 && d.group < offsets.count ? offsets[d.group] : .zero
        }
        fit(scene, offsets: offsets)
    }

    /// Frame the model, exploded as it is now, into the volume. A volume clips what leaves it, so the
    /// fit has to cover where the groups went, not only the assembled bounds: the assembled box,
    /// moved by each group's offset, is a bound on every exploded part.
    private func fit(_ scene: ThreeScene, offsets: [SIMD3<Float>]) {
        guard let bounds = scene.bounds else { return }
        var lo = bounds.min, hi = bounds.max
        for o in offsets where o != .zero { lo = simd_min(lo, bounds.min + o); hi = simd_max(hi, bounds.max + o) }
        modelCenter = (lo + hi) / 2
        let dims = hi - lo
        modelMaxDim = max(dims.max(), 1e-4)
        cage.children.removeAll()
        if let wire = ThreeMeshBuilder.wireCuboid(size: dims, color: SIMD4(1, 1, 1, 0.35)) { cage.addChild(wire) }
        applyTransform()
    }

    private static func formatted(_ v: Float) -> String {
        v >= 100 ? String(format: "%.0f", v) : String(format: "%.2g", v)
    }

    private func applyTransform() {
        let q = simd_quatf(angle: yaw, axis: [0, 1, 0]) * simd_quatf(angle: pitch, axis: [1, 0, 0])
        let s = (targetExtent / modelMaxDim) * userScale
        pivot.orientation = q
        pivot.scale = SIMD3(repeating: s)
        pivot.position = -(q.act(s * modelCenter))
        cage.orientation = q
        cage.scale = SIMD3(repeating: s)
    }

    private func resetManipulation() {
        yaw = 0; pitch = 0; committedYaw = 0; committedPitch = 0
        userScale = 1; committedScale = 1
    }

    // MARK: - Gestures

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
