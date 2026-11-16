import RealityKit
import UIKit
import simd

/// Turns a bound `ThreeScene` into RealityKit entities, one child entity per drawable (named by its
/// id, so layers, hiding and explode can address parts later), all in model coordinates.
///
/// RealityKit on visionOS has no per-vertex colour in its materials, so colour is bucketed: every
/// triangle (or segment, or point) takes its first vertex's colour, quantised, and each bucket is one
/// mesh part with its own material. A part of one colour — the usual solid — is one bucket, exact.
/// Lines become swept tubes and points small octahedra, both sized from the scene's diagonal, because
/// Metal lines are one pixel wide and unreadable at arm's length.
enum ThreeMeshBuilder {
    struct Built {
        let root = Entity()
        /// Drawable entities by drawable id. Each sits at the origin of `root` so explode can move it by
        /// setting its position; its child carries the drawable's own transform.
        var parts: [String: Entity] = [:]
        var triangles = 0
        var segments = 0
        var points = 0
    }

    /// The most colour buckets one drawable gets before the rest fall into the nearest-of-the-first.
    static let maxBuckets = 48

    @MainActor
    static func build(_ scene: ThreeScene) -> Built {
        var built = Built()
        let diag: Float = scene.bounds.map { max(length($0.max - $0.min), 1e-4) } ?? 1
        for drawable in scene.drawables {
            let entity = Entity()
            entity.name = drawable.id
            entity.isEnabled = !drawable.hidden
            let placed = Entity()
            placed.transform = Transform(matrix: drawable.transform)
            entity.addChild(placed)
            let mesh: (MeshResource, [any RealityKit.Material])?
            switch drawable.kind {
            case .surface:
                mesh = surface(drawable, built: &built)
            case .lines:
                mesh = lines(drawable, radius: diag * 0.0015, built: &built)
            case .points:
                mesh = points(drawable, radius: diag * 0.004, built: &built)
            }
            if let (resource, materials) = mesh {
                placed.addChild(ModelEntity(mesh: resource, materials: materials))
            }
            built.root.addChild(entity)
            built.parts[drawable.id] = entity
        }
        return built
    }

    // MARK: - Colour buckets

    /// Accumulates one mesh part per colour.
    private struct Buckets {
        var keys: [UInt32: Int] = [:]
        var colors: [SIMD4<Float>] = []
        var positions: [[SIMD3<Float>]] = []
        var normals: [[SIMD3<Float>]] = []
        var indices: [[UInt32]] = []

        mutating func bucket(_ c: SIMD4<Float>) -> Int {
            let q = simd_clamp(c, .zero, .one) * 31
            let key = UInt32(q.x) | UInt32(q.y) << 5 | UInt32(q.z) << 10 | UInt32(q.w) << 15
            if let i = keys[key] { return i }
            guard colors.count < ThreeMeshBuilder.maxBuckets else {
                // Out of buckets: the nearest existing colour.
                return colors.indices.min { simd_distance_squared(colors[$0], c) < simd_distance_squared(colors[$1], c) } ?? 0
            }
            keys[key] = colors.count
            colors.append(c); positions.append([]); normals.append([]); indices.append([])
            return colors.count - 1
        }

        /// Append vertices to bucket `b`; `tris` index into `verts`.
        mutating func append(_ b: Int, verts: [SIMD3<Float>], norms: [SIMD3<Float>], tris: [UInt32]) {
            let base = UInt32(positions[b].count)
            positions[b].append(contentsOf: verts)
            normals[b].append(contentsOf: norms)
            indices[b].append(contentsOf: tris.map { $0 + base })
        }

        func resource(name: String) -> (MeshResource, [SIMD4<Float>])? {
            var descriptors: [MeshDescriptor] = []
            var used: [SIMD4<Float>] = []
            for b in colors.indices where !indices[b].isEmpty {
                var d = MeshDescriptor(name: "\(name)-\(b)")
                d.positions = MeshBuffers.Positions(positions[b])
                if normals[b].count == positions[b].count { d.normals = MeshBuffers.Normals(normals[b]) }
                d.primitives = .triangles(indices[b])
                d.materials = .allFaces(UInt32(used.count))
                descriptors.append(d)
                used.append(colors[b])
            }
            guard !descriptors.isEmpty, let r = try? MeshResource.generate(from: descriptors) else { return nil }
            return (r, used)
        }
    }

    private static func color(_ g: ThreeGeometry, _ i: Int, base: SIMD3<Float>, opacity: Float) -> SIMD4<Float> {
        let rgb = (g.colors.flatMap { i < $0.count ? $0[i] : nil } ?? SIMD3(1, 1, 1)) * base
        let a = (g.alpha.flatMap { i < $0.count ? $0[i] : nil } ?? 1) * opacity
        return SIMD4(rgb, a)
    }

    // MARK: - Surfaces

    @MainActor
    private static func surface(_ d: ThreeDrawable, built: inout Built) -> (MeshResource, [any RealityKit.Material])? {
        let g = d.geometry
        let base = d.style.material?.color.flatMap { $0.count >= 3 ? SIMD3($0[0], $0[1], $0[2]) : nil } ?? SIMD3(1, 1, 1)
        let opacity = d.style.opacity ?? 1
        let index: [UInt32] = g.indices ?? (0..<UInt32(g.positions.count)).map { $0 }
        let flat = d.style.flatShading == true || g.normals == nil
        var buckets = Buckets()
        var t = 0
        while t + 2 < index.count {
            let i0 = Int(index[t]), i1 = Int(index[t + 1]), i2 = Int(index[t + 2])
            t += 3
            guard i0 < g.positions.count, i1 < g.positions.count, i2 < g.positions.count else { continue }
            let p = [g.positions[i0], g.positions[i1], g.positions[i2]]
            let n: [SIMD3<Float>]
            if flat {
                let f = cross(p[1] - p[0], p[2] - p[0])
                let len = length(f)
                let fn = len > 1e-12 ? f / len : SIMD3(0, 0, 1)
                n = [fn, fn, fn]
            } else {
                let ns = g.normals!
                n = [i0, i1, i2].map { $0 < ns.count ? ns[$0] : SIMD3(0, 0, 1) }
            }
            let b = buckets.bucket(color(g, i0, base: base, opacity: opacity))
            buckets.append(b, verts: p, norms: n, tris: [0, 1, 2])
            built.triangles += 1
        }
        guard let (resource, colors) = buckets.resource(name: d.id) else { return nil }
        return (resource, colors.map { surfaceMaterial(d.style, $0) })
    }

    private static func surfaceMaterial(_ style: ThreeWireStyle, _ c: SIMD4<Float>) -> any RealityKit.Material {
        let ui = UIColor(red: CGFloat(c.x), green: CGFloat(c.y), blue: CGFloat(c.z), alpha: 1)
        let culling: MaterialParameterTypes.FaceCulling = style.doubleSided == false ? .back : .none
        if style.unlit == true {
            var m = UnlitMaterial(color: ui)
            if c.w < 0.999 { m.blending = .transparent(opacity: .init(floatLiteral: max(c.w, 0))) }
            m.faceCulling = culling
            return m
        }
        var m = PhysicallyBasedMaterial()
        m.baseColor = .init(tint: ui)
        m.roughness = .init(floatLiteral: style.material?.roughness ?? 0.6)
        m.metallic = .init(floatLiteral: style.material?.metalness ?? 0.1)
        if let e = style.material?.emissive, e.count >= 3, (style.material?.emissiveIntensity ?? 0) > 0 {
            m.emissiveColor = .init(color: UIColor(red: CGFloat(e[0]), green: CGFloat(e[1]), blue: CGFloat(e[2]), alpha: 1))
            m.emissiveIntensity = style.material?.emissiveIntensity ?? 0
        }
        if c.w < 0.999 { m.blending = .transparent(opacity: .init(floatLiteral: max(c.w, 0))) }
        m.faceCulling = culling
        return m
    }

    private static func unlit(_ c: SIMD4<Float>) -> any RealityKit.Material {
        var m = UnlitMaterial(color: UIColor(red: CGFloat(c.x), green: CGFloat(c.y), blue: CGFloat(c.z), alpha: 1))
        if c.w < 0.999 { m.blending = .transparent(opacity: .init(floatLiteral: max(c.w, 0))) }
        m.faceCulling = .none
        return m
    }

    // MARK: - Lines

    @MainActor
    private static func lines(_ d: ThreeDrawable, radius: Float, built: inout Built) -> (MeshResource, [any RealityKit.Material])? {
        let g = d.geometry
        let opacity = d.style.opacity ?? 1
        var buckets = Buckets()
        // Segment pairs (three.js LineSegments), or an index of pairs. Consecutive segments sharing an
        // endpoint and a colour are welded into one polyline so the tube has no gaps at the joints.
        let pairs: [(Int, Int)] = {
            if let ix = g.indices { return stride(from: 0, to: ix.count - 1, by: 2).map { (Int(ix[$0]), Int(ix[$0 + 1])) } }
            return stride(from: 0, to: g.positions.count - 1, by: 2).map { ($0, $0 + 1) }
        }()
        var chain: [SIMD3<Float>] = []
        var chainBucket = -1
        func flush() {
            if chain.count >= 2, chainBucket >= 0, let tube = tube(chain, radius: radius) {
                buckets.append(chainBucket, verts: tube.0, norms: tube.1, tris: tube.2)
            }
            chain = []
        }
        for (a, b) in pairs {
            guard a < g.positions.count, b < g.positions.count else { continue }
            let bucket = buckets.bucket(color(g, a, base: SIMD3(1, 1, 1), opacity: opacity))
            let pa = g.positions[a], pb = g.positions[b]
            if bucket == chainBucket, let last = chain.last, simd_distance_squared(last, pa) < 1e-12 {
                chain.append(pb)
            } else {
                flush()
                chain = [pa, pb]
                chainBucket = bucket
            }
            built.segments += 1
        }
        flush()
        guard let (resource, colors) = buckets.resource(name: d.id) else { return nil }
        return (resource, colors.map(unlit))
    }

    /// A swept n-gon tube along a polyline with a parallel-transport frame, so the cross-section does not twist.
    static func tube(_ raw: [SIMD3<Float>], radius: Float, sides: Int = 6) -> ([SIMD3<Float>], [SIMD3<Float>], [UInt32])? {
        var pts: [SIMD3<Float>] = []
        for p in raw where pts.last.map({ simd_distance_squared($0, p) >= 1e-14 }) ?? true { pts.append(p) }
        guard pts.count >= 2 else { return nil }
        let n = pts.count
        let tangents: [SIMD3<Float>] = (0..<n).map { i in
            let t = i == 0 ? pts[1] - pts[0] : (i == n - 1 ? pts[n - 1] - pts[n - 2] : pts[i + 1] - pts[i - 1])
            return normalizeSafe(t, fallback: SIMD3(0, 0, 1))
        }
        var normal = perpendicular(tangents[0])
        var verts: [SIMD3<Float>] = [], norms: [SIMD3<Float>] = [], tris: [UInt32] = []
        verts.reserveCapacity(n * sides); norms.reserveCapacity(n * sides)
        for i in 0..<n {
            if i > 0 {
                normal = normalizeSafe(normal - dot(normal, tangents[i]) * tangents[i], fallback: perpendicular(tangents[i]))
            }
            let binormal = cross(tangents[i], normal)
            for j in 0..<sides {
                let theta = 2 * Float.pi * Float(j) / Float(sides)
                let dir = cos(theta) * normal + sin(theta) * binormal
                verts.append(pts[i] + radius * dir)
                norms.append(dir)
            }
        }
        for i in 0..<(n - 1) {
            for j in 0..<sides {
                let jn = (j + 1) % sides
                let a = UInt32(i * sides + j), b = UInt32(i * sides + jn)
                let c = UInt32((i + 1) * sides + j), d = UInt32((i + 1) * sides + jn)
                tris.append(contentsOf: [a, c, b, b, c, d])
            }
        }
        return (verts, norms, tris)
    }

    // MARK: - Points

    @MainActor
    private static func points(_ d: ThreeDrawable, radius: Float, built: inout Built) -> (MeshResource, [any RealityKit.Material])? {
        let g = d.geometry
        let opacity = d.style.opacity ?? 1
        let axes: [SIMD3<Float>] = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]]
        let faces: [UInt32] = [0, 2, 4, 2, 1, 4, 1, 3, 4, 3, 0, 4, 2, 0, 5, 1, 2, 5, 3, 1, 5, 0, 3, 5]
        var buckets = Buckets()
        for (i, p) in g.positions.enumerated() {
            let b = buckets.bucket(color(g, i, base: SIMD3(1, 1, 1), opacity: opacity))
            buckets.append(b, verts: axes.map { p + radius * $0 }, norms: axes, tris: faces)
            built.points += 1
        }
        guard let (resource, colors) = buckets.resource(name: d.id) else { return nil }
        return (resource, colors.map(unlit))
    }

    // MARK: - Bounding cage

    /// A 1 px wireframe cuboid centred at the origin (a `LowLevelMesh` line list over 8 corners).
    @MainActor
    static func wireCuboid(size: SIMD3<Float>, color: SIMD4<Float>) -> ModelEntity? {
        let h = size / 2
        let corners: [SIMD3<Float>] = [
            [-h.x, -h.y, -h.z], [h.x, -h.y, -h.z], [h.x, h.y, -h.z], [-h.x, h.y, -h.z],
            [-h.x, -h.y, h.z], [h.x, -h.y, h.z], [h.x, h.y, h.z], [-h.x, h.y, h.z],
        ]
        let edges: [UInt32] = [0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7]
        var desc = LowLevelMesh.Descriptor()
        desc.vertexCapacity = corners.count
        desc.indexCapacity = edges.count
        desc.vertexAttributes = [LowLevelMesh.Attribute(semantic: .position, format: .float3, layoutIndex: 0, offset: 0)]
        desc.vertexLayouts = [LowLevelMesh.Layout(bufferIndex: 0, bufferStride: MemoryLayout<SIMD3<Float>>.stride)]
        guard let mesh = try? LowLevelMesh(descriptor: desc) else { return nil }
        mesh.withUnsafeMutableBytes(bufferIndex: 0) { raw in
            let p = raw.baseAddress!.assumingMemoryBound(to: SIMD3<Float>.self)
            for (i, c) in corners.enumerated() { p[i] = c }
        }
        mesh.withUnsafeMutableIndices { raw in
            let idx = raw.baseAddress!.assumingMemoryBound(to: UInt32.self)
            for (i, e) in edges.enumerated() { idx[i] = e }
        }
        mesh.parts.replaceAll([LowLevelMesh.Part(indexCount: edges.count, topology: .line, materialIndex: 0,
                                                 bounds: BoundingBox(min: -h, max: h))])
        guard let resource = try? MeshResource(from: mesh) else { return nil }
        return ModelEntity(mesh: resource, materials: [unlit(color)])
    }
}

private func normalizeSafe(_ v: SIMD3<Float>, fallback: SIMD3<Float>) -> SIMD3<Float> {
    let len = length(v)
    return len > 1e-8 ? v / len : fallback
}

private func perpendicular(_ t: SIMD3<Float>) -> SIMD3<Float> {
    let ref: SIMD3<Float> = abs(dot(t, SIMD3(0, 1, 0))) < 0.9 ? SIMD3(0, 1, 0) : SIMD3(1, 0, 0)
    return normalizeSafe(cross(t, ref), fallback: SIMD3(1, 0, 0))
}
