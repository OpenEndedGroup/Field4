import Foundation
import RealityKit
import UIKit
import simd
import Metal

/// Turns an `FLinePayload` into RealityKit entities. This is where the native viewer earns "fully
/// 3D": FLine strokes become real swept **tubes** (three.js draws flat 1px GL lines), fills become
/// triangle meshes, and nodes become spheres that double as edit handles.
///
/// Two source paths:
/// - **`scene` present** (editable views): build from the faithful node structure — connected
///   polylines, native cubic tessellation, and real `thickness` → tube radius. Preferred.
/// - **flat buffers** (non-editable views): the `lines` buffer is non-indexed segment pairs, so weld
///   consecutive pairs back into polylines; `fills` is already indexed; `points` is per-node.
///
/// Everything is emitted in **model space** under `built.root`; `FLineViewerView` centres and scales
/// that root to fit the volume. Colour is a per-FLine tinted `UnlitMaterial` (the server repeats one
/// colour per FLine, so this is faithful, not a compromise); alpha needs explicit `.transparent`
/// blending or it renders opaque. `faceCulling = .none` everywhere so tube/fill winding never matters.
enum FLineMeshBuilder {

    /// A tapped interactive FLine → the callback to fire + its vertices (to find the nearest one).
    struct InteractiveRef {
        let callbackId: String
        let vertices: [FLineInteractiveVertex]
    }

    /// A draggable edit handle → which node/handle it is + its model-space position.
    struct NodeRef {
        let lineIndex: Int
        let nodeIndex: Int
        let handle: FLineHandle
        let modelPosition: SIMD3<Float>
    }

    struct Built {
        let root: Entity
        var interactive: [Entity.ID: InteractiveRef] = [:]
        var nodes: [Entity.ID: NodeRef] = [:]
    }

    private static let cubicSamples = 24

    @MainActor
    static func build(_ payload: FLinePayload, editMode: Bool) -> Built {
        let root = Entity()
        var built = Built(root: root)

        let diag = max(payload.bounds?.diagonal ?? 1, 1e-4)
        var sphereCache: [Int: MeshResource] = [:]

        if let scene = payload.scene {
            for line in scene.lines {
                buildSceneLine(line, into: root, built: &built, diag: diag,
                               editMode: editMode, sphereCache: &sphereCache)
            }
        } else {
            buildFlat(payload, into: root, built: &built, diag: diag, sphereCache: &sphereCache)
        }
        return built
    }

    // MARK: - Scene (editable) path

    @MainActor
    private static func buildSceneLine(_ line: FLineSceneLine, into root: Entity, built: inout Built,
                                       diag: Float, editMode: Bool, sphereCache: inout [Int: MeshResource]) {
        let color = vec4(line.color, .init(1, 1, 1, 1))
        let (subpaths, nodePoints) = walkNodes(line.nodes)

        // Stroke → tubes.
        if line.stroked {
            let radius = max(diag * 0.0015 * clampf(line.thickness, 1, 6), diag * 0.0012)
            for path in subpaths where path.count >= 2 {
                guard let mesh = tubeMesh(path, radius: radius) else { continue }
                let e = ModelEntity(mesh: mesh, materials: [material(color)])
                root.addChild(e)
                if !line.callbackId.isEmpty {
                    makeInteractive(e, points: path)
                    built.interactive[e.id] = InteractiveRef(callbackId: line.callbackId,
                                                             vertices: sceneVertices(line.nodes))
                }
            }
        }

        // Fill → fan triangulation of each closed-ish subpath (convex-correct; concave approximate).
        if line.filled {
            for path in subpaths where path.count >= 3 {
                guard let mesh = fanFillMesh(path) else { continue }
                let e = ModelEntity(mesh: mesh, materials: [material(color)])
                root.addChild(e)
            }
        }

        // Points → spheres.
        if line.pointed {
            let r = pointRadius(diag: diag, size: line.pointSize)
            for p in nodePoints {
                let e = sphereEntity(at: p, radius: r, color: color, cache: &sphereCache)
                root.addChild(e)
            }
        }

        // Edit handles: amber ANCHOR spheres at each node's TO point, plus — for cubics — cyan
        // CONTROL-POINT spheres (c1/c2) with faint control-polygon lines to their anchors, so the
        // Bézier tangents are editable like a real pen tool.
        if editMode {
            let anchorR = pointRadius(diag: diag, size: max(line.pointSize, 2)) * 1.8
            let ctrlR = anchorR * 0.72
            let segR = max(diag * 0.0012, 1e-4)
            let anchorColor = SIMD4<Float>(1.0, 0.83, 0.36, 1.0)
            let ctrlColor = SIMD4<Float>(0.45, 0.8, 1.0, 1.0)
            let polyColor = SIMD4<Float>(0.7, 0.85, 1.0, 0.5)

            func handle(_ p: SIMD3<Float>, _ radius: Float, _ color: SIMD4<Float>, _ h: FLineHandle, _ nodeIndex: Int) {
                let e = sphereEntity(at: p, radius: radius, color: color, cache: &sphereCache)
                // Collision larger than the visual → easier to grab.
                makeInteractive(e, sphereRadius: radius * 1.7)
                built.nodes[e.id] = NodeRef(lineIndex: line.lineIndex, nodeIndex: nodeIndex, handle: h, modelPosition: p)
                root.addChild(e)
            }
            func polyLine(_ a: SIMD3<Float>, _ b: SIMD3<Float>) {
                if let mesh = tubeMesh([a, b], radius: segR, sides: 5) {
                    root.addChild(ModelEntity(mesh: mesh, materials: [material(polyColor)]))
                }
            }

            var prevAnchor: SIMD3<Float>?
            for node in line.nodes where node.kind != "close" {
                let to = vec3(node.to)
                if node.kind == "cubic" {
                    let c1 = vec3(node.c1), c2 = vec3(node.c2)
                    if let p0 = prevAnchor { polyLine(p0, c1) }   // c1 hangs off the previous anchor
                    polyLine(to, c2)                              // c2 hangs off this anchor
                    handle(c1, ctrlR, ctrlColor, .C1, node.nodeIndex)
                    handle(c2, ctrlR, ctrlColor, .C2, node.nodeIndex)
                }
                handle(to, anchorR, anchorColor, .TO, node.nodeIndex)
                prevAnchor = to
            }
        }
    }

    /// Walk a scene line's nodes into connected polylines + the list of anchor (`to`) points.
    private static func walkNodes(_ nodes: [FLineSceneNode]) -> (subpaths: [[SIMD3<Float>]], anchors: [SIMD3<Float>]) {
        var subpaths: [[SIMD3<Float>]] = []
        var current: [SIMD3<Float>] = []
        var anchors: [SIMD3<Float>] = []
        var last: SIMD3<Float>?

        for node in nodes {
            switch node.kind {
            case "move":
                if current.count > 1 { subpaths.append(current) }
                let p = vec3(node.to); current = [p]; last = p; anchors.append(p)
            case "line":
                let p = vec3(node.to); current.append(p); last = p; anchors.append(p)
            case "cubic":
                let p = vec3(node.to)
                if let l = last { appendCubic(from: l, c1: vec3(node.c1), c2: vec3(node.c2), to: p, into: &current) }
                else { current.append(p) }
                last = p; anchors.append(p)
            case "close":
                if let first = current.first { current.append(first) }
                if current.count > 1 { subpaths.append(current) }
                current = []; last = nil
            default:
                break
            }
        }
        if current.count > 1 { subpaths.append(current) }
        return (subpaths, anchors)
    }

    private static func sceneVertices(_ nodes: [FLineSceneNode]) -> [FLineInteractiveVertex] {
        nodes.compactMap { node in
            node.kind == "close" ? nil : FLineInteractiveVertex(index: node.nodeIndex,
                                                                position: node.to ?? [0, 0, 0])
        }
    }

    private static func appendCubic(from p0: SIMD3<Float>, c1: SIMD3<Float>, c2: SIMD3<Float>,
                                    to p3: SIMD3<Float>, into out: inout [SIMD3<Float>]) {
        for i in 1...cubicSamples {
            let t = Float(i) / Float(cubicSamples)
            let u = 1 - t
            let p = u * u * u * p0 + 3 * u * u * t * c1 + 3 * u * t * t * c2 + t * t * t * p3
            out.append(p)
        }
    }

    // MARK: - Flat (non-editable) path

    @MainActor
    private static func buildFlat(_ payload: FLinePayload, into root: Entity, built: inout Built,
                                  diag: Float, sphereCache: inout [Int: MeshResource]) {
        let radius = diag * 0.002

        // Non-interactive batch.
        if let lines = payload.lines {
            for poly in weldSegmentPairs(lines) where poly.points.count >= 2 {
                guard let mesh = tubeMesh(poly.points, radius: radius) else { continue }
                root.addChild(ModelEntity(mesh: mesh, materials: [material(poly.color)]))
            }
        }
        if let fills = payload.fills, let index = fills.index {
            if let mesh = indexedFillMesh(positions: fills.positions, indices: index.array) {
                let color = firstColor(fills, fallback: .init(0.9, 0.9, 0.95, 0.6))
                root.addChild(ModelEntity(mesh: mesh, materials: [material(color)]))
            }
        }
        if let pts = payload.points {
            addPointSpheres(pts, into: root, diag: diag, cache: &sphereCache)
        }

        // Interactive objects — each raycast/tap as a unit.
        for obj in payload.interactive {
            var anyEntity: ModelEntity?
            if let lines = obj.lines {
                for poly in weldSegmentPairs(lines) where poly.points.count >= 2 {
                    guard let mesh = tubeMesh(poly.points, radius: radius) else { continue }
                    let e = ModelEntity(mesh: mesh, materials: [material(poly.color)])
                    makeInteractive(e, points: poly.points, hoverColor: obj.hoverColor)
                    built.interactive[e.id] = InteractiveRef(callbackId: obj.callbackId, vertices: obj.vertices)
                    root.addChild(e); anyEntity = e
                }
            }
            if let fills = obj.fills, let index = fills.index,
               let mesh = indexedFillMesh(positions: fills.positions, indices: index.array) {
                let e = ModelEntity(mesh: mesh, materials: [material(firstColor(fills, fallback: .init(0.9, 0.9, 0.95, 0.6)))])
                makeInteractive(e, aabbOf: triPositions(fills.positions), hoverColor: obj.hoverColor)
                built.interactive[e.id] = InteractiveRef(callbackId: obj.callbackId, vertices: obj.vertices)
                root.addChild(e); anyEntity = e
            }
            _ = anyEntity
        }
    }

    @MainActor
    private static func addPointSpheres(_ data: FLineGeometryData, into root: Entity, diag: Float,
                                        cache: inout [Int: MeshResource]) {
        let pos = data.positions, cols = data.colors, alphas = data.alphas, sizes = data.sizes
        let count = pos.count / 3
        for i in 0..<count {
            let p = SIMD3(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
            let color = SIMD4(cols?[safe: i * 3] ?? 1, cols?[safe: i * 3 + 1] ?? 1,
                              cols?[safe: i * 3 + 2] ?? 1, alphas?[safe: i] ?? 1)
            let r = pointRadius(diag: diag, size: sizes?[safe: i] ?? 3)
            root.addChild(sphereEntity(at: p, radius: r, color: color, cache: &cache))
        }
    }

    /// Weld the flat `lines` buffer (segment pairs, in emit order) back into polylines with a colour.
    /// Consecutive segments in one polyline share an endpoint (`b_i == a_{i+1}`); a mismatch starts a
    /// new polyline. No hashing needed — the server emits segments in path order.
    private static func weldSegmentPairs(_ data: FLineGeometryData) -> [(points: [SIMD3<Float>], color: SIMD4<Float>)] {
        let pos = data.positions
        let cols = data.colors, alphas = data.alphas
        let segCount = pos.count / 6
        var out: [(points: [SIMD3<Float>], color: SIMD4<Float>)] = []
        var current: [SIMD3<Float>] = []
        var currentColor = SIMD4<Float>(1, 1, 1, 1)

        func colorAt(_ vertexIndex: Int) -> SIMD4<Float> {
            SIMD4(cols?[safe: vertexIndex * 3] ?? 1, cols?[safe: vertexIndex * 3 + 1] ?? 1,
                  cols?[safe: vertexIndex * 3 + 2] ?? 1, alphas?[safe: vertexIndex] ?? 1)
        }

        for s in 0..<segCount {
            let a = SIMD3(pos[s * 6], pos[s * 6 + 1], pos[s * 6 + 2])
            let b = SIMD3(pos[s * 6 + 3], pos[s * 6 + 4], pos[s * 6 + 5])
            if let last = current.last, distance2(last, a) < 1e-10 {
                current.append(b)
            } else {
                if current.count >= 2 { out.append((current, currentColor)) }
                current = [a, b]
                currentColor = colorAt(s * 2)
            }
        }
        if current.count >= 2 { out.append((current, currentColor)) }
        return out
    }

    // MARK: - Mesh generation

    /// Swept n-gon tube along a polyline using a rotation-minimizing (parallel-transport) frame so
    /// the cross-section doesn't twist. Emits positions + radial normals + a triangle index buffer.
    static func tubeMesh(_ rawPoints: [SIMD3<Float>], radius: Float, sides: Int = 8) -> MeshResource? {
        // Drop consecutive duplicates (zero-length segments break the tangent).
        var pts: [SIMD3<Float>] = []
        for p in rawPoints {
            if let last = pts.last, distance2(last, p) < 1e-12 { continue }
            pts.append(p)
        }
        guard pts.count >= 2 else { return nil }
        let n = pts.count

        var tangents = [SIMD3<Float>](repeating: .init(0, 0, 1), count: n)
        for i in 0..<n {
            let t: SIMD3<Float>
            if i == 0 { t = pts[1] - pts[0] }
            else if i == n - 1 { t = pts[n - 1] - pts[n - 2] }
            else { t = pts[i + 1] - pts[i - 1] }
            tangents[i] = normalizeSafe(t, fallback: .init(0, 0, 1))
        }

        var normals = [SIMD3<Float>](repeating: .init(0, 1, 0), count: n)
        normals[0] = arbitraryPerpendicular(tangents[0])
        for i in 1..<n {
            let t0 = tangents[i - 1], t1 = tangents[i]
            let q = quaternionBetween(t0, t1)
            var nrm = q.act(normals[i - 1])
            // Re-orthonormalize against the new tangent to kill drift.
            nrm = normalizeSafe(nrm - dot(nrm, t1) * t1, fallback: arbitraryPerpendicular(t1))
            normals[i] = nrm
        }

        var verts: [SIMD3<Float>] = []
        var norms: [SIMD3<Float>] = []
        verts.reserveCapacity(n * sides)
        norms.reserveCapacity(n * sides)
        for i in 0..<n {
            let nrm = normals[i]
            let bin = cross(tangents[i], nrm)
            for j in 0..<sides {
                let theta = 2 * Float.pi * Float(j) / Float(sides)
                let dir = cos(theta) * nrm + sin(theta) * bin
                verts.append(pts[i] + radius * dir)
                norms.append(dir)
            }
        }

        var indices: [UInt32] = []
        indices.reserveCapacity((n - 1) * sides * 6)
        for i in 0..<(n - 1) {
            for j in 0..<sides {
                let jn = (j + 1) % sides
                let a = UInt32(i * sides + j)
                let b = UInt32(i * sides + jn)
                let c = UInt32((i + 1) * sides + j)
                let d = UInt32((i + 1) * sides + jn)
                indices.append(contentsOf: [a, c, b, b, c, d])
            }
        }

        var desc = MeshDescriptor(name: "tube")
        desc.positions = MeshBuffers.Positions(verts)
        desc.normals = MeshBuffers.Normals(norms)
        desc.primitives = .triangles(indices)
        return try? MeshResource.generate(from: [desc])
    }

    /// Fan triangulation of a boundary polygon (convex-correct; concave approximate — earcut is the
    /// faithful path, deferred). Drops a trailing closing duplicate if present.
    static func fanFillMesh(_ boundary: [SIMD3<Float>]) -> MeshResource? {
        var pts = boundary
        if pts.count >= 2, distance2(pts.first!, pts.last!) < 1e-10 { pts.removeLast() }
        guard pts.count >= 3 else { return nil }
        var indices: [UInt32] = []
        for i in 1..<(pts.count - 1) {
            indices.append(contentsOf: [0, UInt32(i), UInt32(i + 1)])
        }
        var desc = MeshDescriptor(name: "fill")
        desc.positions = MeshBuffers.Positions(pts)
        desc.primitives = .triangles(indices)
        return try? MeshResource.generate(from: [desc])
    }

    /// Already-indexed triangle mesh from the flat `fills` buffer.
    static func indexedFillMesh(positions: [Float], indices: [UInt32]) -> MeshResource? {
        guard positions.count >= 9, indices.count >= 3 else { return nil }
        var desc = MeshDescriptor(name: "fill")
        desc.positions = MeshBuffers.Positions(triPositions(positions))
        desc.primitives = .triangles(indices)
        return try? MeshResource.generate(from: [desc])
    }

    // MARK: - Entities / materials / collision

    @MainActor
    private static func sphereEntity(at p: SIMD3<Float>, radius: Float, color: SIMD4<Float>,
                                     cache: inout [Int: MeshResource]) -> ModelEntity {
        let key = Int((radius * 10000).rounded())
        let mesh = cache[key] ?? {
            let m = MeshResource.generateSphere(radius: max(radius, 1e-4))
            cache[key] = m
            return m
        }()
        let e = ModelEntity(mesh: mesh, materials: [material(color)])
        e.position = p
        return e
    }

    private static func material(_ rgba: SIMD4<Float>) -> UnlitMaterial {
        var m = UnlitMaterial(color: UIColor(red: CGFloat(rgba.x), green: CGFloat(rgba.y),
                                             blue: CGFloat(rgba.z), alpha: 1))
        if rgba.w < 0.999 {
            m.blending = .transparent(opacity: .init(floatLiteral: max(rgba.w, 0)))
        }
        m.faceCulling = .none
        return m
    }

    /// Coarse whole-FLine tap target: an AABB box shape (precise per-tube mesh hit-testing is a
    /// refinement). `points` are the polyline; the box is offset to their centre.
    @MainActor
    private static func makeInteractive(_ e: ModelEntity, points: [SIMD3<Float>],
                                        hoverColor: [Float]? = nil) {
        makeInteractive(e, aabbOf: points, hoverColor: hoverColor)
    }

    @MainActor
    private static func makeInteractive(_ e: ModelEntity, aabbOf points: [SIMD3<Float>],
                                        hoverColor: [Float]? = nil) {
        guard !points.isEmpty else { return }
        var lo = points[0], hi = points[0]
        for p in points { lo = simd_min(lo, p); hi = simd_max(hi, p) }
        let center = (lo + hi) * 0.5
        let size = simd_max(hi - lo, .init(repeating: 1e-3))
        let shape = ShapeResource.generateBox(size: size).offsetBy(translation: center)
        e.components.set(CollisionComponent(shapes: [shape]))
        e.components.set(InputTargetComponent())
        applyHover(e, hoverColor)
    }

    /// Exact sphere tap target for an edit handle, plus the system hover highlight.
    ///
    /// `HoverEffectComponent` is how visionOS squares "show me what I'm about to grab" with not
    /// telling apps where the user is looking: the effect is applied out of process by the system
    /// compositor and the app never learns the gaze position — no callback, no transform, nothing
    /// to sample. So this costs us no privacy entitlement and no gaze data, and it lights up on
    /// exactly the collision shape the pinch would hit (which is deliberately 1.7x the visual
    /// sphere here, so the highlight also teaches you how forgiving the grab target really is).
    ///
    /// Note the highlight tracks the *collision* shape, not the mesh — a handle will glow slightly
    /// larger than it looks. That is the honest thing to show.
    @MainActor
    private static func makeInteractive(_ e: ModelEntity, sphereRadius: Float) {
        e.components.set(CollisionComponent(shapes: [.generateSphere(radius: sphereRadius)]))
        e.components.set(InputTargetComponent())
        // Pure WHITE at full strength, not a lightened form of the handle's own colour. Tinting an
        // amber handle pale amber (and a cyan one pale cyan) sat too close to its resting state to
        // read at a glance — the point of the highlight is maximum contrast against *whatever* the
        // handle already is. Which kind you are about to grab is still legible from the unhovered
        // colour, so nothing is lost by making the hover state itself colour-neutral.
        e.components.set(HoverEffectComponent(
            .highlight(.init(color: .white, strength: 1.0))))
    }

    @MainActor
    private static func applyHover(_ e: ModelEntity, _ hoverColor: [Float]?) {
        guard let h = hoverColor, h.count >= 3 else { return }
        let color = UIColor(red: CGFloat(h[0]), green: CGFloat(h[1]), blue: CGFloat(h[2]),
                            alpha: CGFloat(h.count >= 4 ? h[3] : 1))
        e.components.set(HoverEffectComponent(.highlight(.init(color: color, strength: 1.0))))
    }

    /// A light wireframe cuboid (12 thin box edges) centred at the origin, sized `size` in model
    /// units. Used as the bounding cage around the model — the caller scales/orients the returned
    /// entity. Very light: low-opacity unlit edges.
    @MainActor
    static func wireCuboid(size: SIMD3<Float>, thickness: Float, color: SIMD4<Float>) -> Entity {
        let container = Entity()
        let hx = size.x / 2, hy = size.y / 2, hz = size.z / 2
        let t = max(thickness, 1e-5)
        let mat = material(color)
        func edge(_ boxSize: SIMD3<Float>, _ pos: SIMD3<Float>) {
            let m = ModelEntity(mesh: .generateBox(size: boxSize), materials: [mat])
            m.position = pos
            container.addChild(m)
        }
        for sy in [-hy, hy] { for sz in [-hz, hz] { edge(SIMD3(size.x, t, t), SIMD3(0, sy, sz)) } } // X edges
        for sx in [-hx, hx] { for sz in [-hz, hz] { edge(SIMD3(t, size.y, t), SIMD3(sx, 0, sz)) } } // Y edges
        for sx in [-hx, hx] { for sy in [-hy, hy] { edge(SIMD3(t, t, size.z), SIMD3(sx, sy, 0)) } } // Z edges
        return container
    }

    /// EXPERIMENT: a genuine 1px wireframe cuboid via `LowLevelMesh` with `.line` topology (12 edges
    /// as an indexed line list over 8 corners). Returns nil if RealityKit can't build the resource;
    /// whether it actually *shades* line-topology parts is the thing we're testing. No thickness
    /// control (Metal lines are always 1px), but razor-sharp and cheap if it renders.
    @MainActor
    static func wireCuboidLines(size: SIMD3<Float>, color: SIMD4<Float>) -> ModelEntity? {
        let hx = size.x / 2, hy = size.y / 2, hz = size.z / 2
        let corners: [SIMD3<Float>] = [
            [-hx, -hy, -hz], [hx, -hy, -hz], [hx, hy, -hz], [-hx, hy, -hz],
            [-hx, -hy,  hz], [hx, -hy,  hz], [hx, hy,  hz], [-hx, hy,  hz]
        ]
        let edges: [UInt32] = [
            0, 1, 1, 2, 2, 3, 3, 0,   // -z face
            4, 5, 5, 6, 6, 7, 7, 4,   // +z face
            0, 4, 1, 5, 2, 6, 3, 7    // connectors
        ]
        var desc = LowLevelMesh.Descriptor()
        desc.vertexCapacity = corners.count
        desc.indexCapacity = edges.count
        desc.vertexAttributes = [
            LowLevelMesh.Attribute(semantic: .position, format: .float3, layoutIndex: 0, offset: 0)
        ]
        desc.vertexLayouts = [
            LowLevelMesh.Layout(bufferIndex: 0, bufferStride: MemoryLayout<SIMD3<Float>>.stride)
        ]
        guard let mesh = try? LowLevelMesh(descriptor: desc) else { return nil }
        mesh.withUnsafeMutableBytes(bufferIndex: 0) { raw in
            let p = raw.baseAddress!.assumingMemoryBound(to: SIMD3<Float>.self)
            for (i, c) in corners.enumerated() { p[i] = c }
        }
        mesh.withUnsafeMutableIndices { raw in
            let idx = raw.baseAddress!.assumingMemoryBound(to: UInt32.self)
            for (i, e) in edges.enumerated() { idx[i] = e }
        }
        let bounds = BoundingBox(min: [-hx, -hy, -hz], max: [hx, hy, hz])
        mesh.parts.replaceAll([
            LowLevelMesh.Part(indexCount: edges.count, topology: .line, materialIndex: 0, bounds: bounds)
        ])
        guard let resource = try? MeshResource(from: mesh) else { return nil }
        return ModelEntity(mesh: resource, materials: [material(color)])
    }

    // MARK: - Small helpers

    private static func pointRadius(diag: Float, size: Float) -> Float {
        clampf(diag * 0.004 * clampf(size / 3, 0.5, 4), diag * 0.003, diag * 0.03)
    }

    private static func triPositions(_ p: [Float]) -> [SIMD3<Float>] {
        var out: [SIMD3<Float>] = []
        out.reserveCapacity(p.count / 3)
        var i = 0
        while i + 2 < p.count { out.append(SIMD3(p[i], p[i + 1], p[i + 2])); i += 3 }
        return out
    }

    private static func firstColor(_ data: FLineGeometryData, fallback: SIMD4<Float>) -> SIMD4<Float> {
        guard let c = data.colors, c.count >= 3 else { return fallback }
        return SIMD4(c[0], c[1], c[2], data.alphas?.first ?? 1)
    }
}

// MARK: - Free math helpers

private func vec3(_ a: [Float]?) -> SIMD3<Float> {
    guard let a, a.count >= 3 else { return .zero }
    return SIMD3(a[0], a[1], a[2])
}

private func vec4(_ a: [Float]?, _ fallback: SIMD4<Float>) -> SIMD4<Float> {
    guard let a, a.count >= 4 else { return fallback }
    return SIMD4(a[0], a[1], a[2], a[3])
}

private func clampf(_ x: Float, _ lo: Float, _ hi: Float) -> Float { min(max(x, lo), hi) }

private func distance2(_ a: SIMD3<Float>, _ b: SIMD3<Float>) -> Float {
    let d = a - b; return dot(d, d)
}

private func normalizeSafe(_ v: SIMD3<Float>, fallback: SIMD3<Float>) -> SIMD3<Float> {
    let len = length(v)
    return len > 1e-8 ? v / len : fallback
}

private func arbitraryPerpendicular(_ t: SIMD3<Float>) -> SIMD3<Float> {
    let ref: SIMD3<Float> = abs(dot(t, .init(0, 1, 0))) < 0.9 ? .init(0, 1, 0) : .init(1, 0, 0)
    return normalizeSafe(cross(t, ref), fallback: .init(1, 0, 0))
}

/// Minimal quaternion carrying unit vector `a` onto unit vector `b`.
private func quaternionBetween(_ a: SIMD3<Float>, _ b: SIMD3<Float>) -> simd_quatf {
    let d = dot(a, b)
    if d > 0.99999 { return simd_quatf(angle: 0, axis: .init(0, 1, 0)) }
    if d < -0.99999 { return simd_quatf(angle: .pi, axis: arbitraryPerpendicular(a)) }
    let axis = normalize(cross(a, b))
    return simd_quatf(angle: acos(max(-1, min(1, d))), axis: axis)
}

private extension Array where Element == Float {
    subscript(safe index: Int) -> Float? {
        index >= 0 && index < count ? self[index] : nil
    }
}
