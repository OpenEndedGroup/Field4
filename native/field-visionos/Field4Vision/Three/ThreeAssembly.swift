import Foundation
import simd

// Explode as the browser viewer does it (`explodeOffsets` in `threejs_viewer.html`). Pure: no
// RealityKit, so the arithmetic is the same arithmetic and the view only has to move entities. The
// amount comes from the browser viewer in Field (`three/view-state`), whose slider stays the control.

enum ThreeExplode {
    /// Each assembly group's centroid: the vertex-weighted mean of the drawables in it, placed by
    /// their transforms. `nil` for a group with no vertices, which then stays where it is.
    static func centroids(_ scene: ThreeScene, groupCount: Int) -> [SIMD3<Float>?] {
        var sums = [SIMD3<Float>](repeating: .zero, count: groupCount)
        var counts = [Int](repeating: 0, count: groupCount)
        for d in scene.drawables where d.group >= 0 && d.group < groupCount && !d.geometry.positions.isEmpty {
            var local = SIMD3<Float>.zero
            for p in d.geometry.positions { local += p }
            let mean = local / Float(d.geometry.positions.count)
            let placed = d.transform * SIMD4(mean, 1)
            sums[d.group] += SIMD3(placed.x, placed.y, placed.z) * Float(d.geometry.positions.count)
            counts[d.group] += d.geometry.positions.count
        }
        return (0..<groupCount).map { counts[$0] > 0 ? sums[$0] / Float(counts[$0]) : nil }
    }

    /// The displacement of each group at [amount]. Exactly zero at `amount == 0`, the assembled state.
    ///
    /// Radial pushes each group away from [origin] by [amount]; axis lays the groups out in rank order
    /// along the axis, [amount] apart, centred so the middle of the assembly stays put.
    static func offsets(_ centroids: [SIMD3<Float>?], origin: SIMD3<Float>, amount: Float,
                        mode: String, axis: SIMD3<Float>) -> [SIMD3<Float>] {
        let zero = [SIMD3<Float>](repeating: .zero, count: centroids.count)
        guard amount != 0 else { return zero }
        if mode == "axis" {
            let unit = length(axis) > 1e-12 ? normalize(axis) : SIMD3(0, 1, 0)
            var order: [(g: Int, at: Float)] = []
            for (g, c) in centroids.enumerated() { if let c { order.append((g, dot(c, unit))) } }
            order.sort { (a: (g: Int, at: Float), b: (g: Int, at: Float)) -> Bool in
                a.at == b.at ? a.g < b.g : a.at < b.at
            }
            let middle = Float(order.count - 1) * 0.5
            var out = zero
            for (rank, entry) in order.enumerated() { out[entry.g] = unit * ((Float(rank) - middle) * amount) }
            return out
        }
        return centroids.map { c in
            guard let c else { return .zero }
            let d = c - origin
            let len = length(d)
            // A group sitting exactly on the origin has no direction to go.
            return len > 1e-12 ? d / len * amount : .zero
        }
    }

    /// The slider's range: the author's `sliderMax`, else three quarters of the bounds diagonal.
    static func sliderMax(_ scene: ThreeScene) -> Float {
        if let m = scene.inspector?.explode?.sliderMax, m > 0 { return Float(m) }
        guard let b = scene.bounds else { return 1 }
        return max(length(b.max - b.min) * 0.75, 1e-4)
    }
}
