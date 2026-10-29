@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spatial

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.convexHullMesh
import afield.geometry.mesh.solidCentroid
import afield.geometry.mesh.volume
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The three-dimensional companion of lp_trajectories.kt: the same linear-programming methods, as
 * curve generators, on a convex polytope `{ x : nᵢ·x ≤ bᵢ }` in space. The methods are the 2-D ones
 * with 3×3 linear algebra in place of 2×2 (the ellipsoid method's constants change with the
 * dimension: centre step 1/(n+1) = 1/4, expansion n²/(n²−1) = 9/8). The simplex walk follows the
 * polytope's edges, which the constructor finds by enumerating vertices.
 *
 * Pure math, independent of Box; doubles throughout, Vec3 only for drawing.
 */

/**
 * A bounded convex polytope with an interior, held as its half-spaces `nᵢ·x ≤ bᵢ` (unit outward
 * normals) and as the vertices and edges those half-spaces make, ready for the trajectory methods
 * ([centralPath], [centralFan], [primalDual], [pdhg], [ellipsoid], [simplexWalk], [cuttingPlanes]).
 *
 * The constructor takes the half-spaces as given — normals of any nonzero length, which are
 * normalised — and keeps every one, including any that are redundant: a redundant half-space does
 * not change the polytope but does push the barrier methods, as in 2-D. It rejects, with a message,
 * a set that is unbounded (the normals do not surround the origin) or has no interior. Use
 * [LpPolytope.hull] for the convex hull of a point set, [LpPolytope.of] for a mesh's hull, and
 * [LpPolytope.box] for an axis-aligned box.
 *
 * [vertices] are found by intersecting every triple of planes, so construction costs `O(m⁴)` in
 * the number of half-spaces; it is meant for the tens of faces a drawn figure has, not thousands.
 */
class LpPolytope(normals: List<Vec3>, bounds: List<Double>) {
    /** x component of each half-space's unit outward normal. */
    val normalX: DoubleArray
    /** y component of each half-space's unit outward normal. */
    val normalY: DoubleArray
    /** z component of each half-space's unit outward normal. */
    val normalZ: DoubleArray
    /** Each half-space's bound: the polytope is `normal[i]·x ≤ bound[i]` for every `i`. */
    val bound: DoubleArray
    /** The vertices, each where three or more of the planes meet. */
    val vertices: List<Vec3>
    /** For each vertex, the indices of the half-spaces tight at it. */
    val vertexPlanes: List<IntArray>
    /** The edges, as pairs of indices into [vertices]. */
    val edges: List<IntArray>
    /** The mean of the vertices, strictly inside; the default start. */
    val center: Vec3

    init {
        require(normals.size == bounds.size) { "LpPolytope needs one bound per normal: ${normals.size} normals, ${bounds.size} bounds" }
        require(normals.size >= 4) { "LpPolytope needs at least 4 half-spaces to be bounded, got ${normals.size}" }
        val m = normals.size
        normalX = DoubleArray(m)
        normalY = DoubleArray(m)
        normalZ = DoubleArray(m)
        bound = DoubleArray(m)
        for (i in 0 until m) {
            val n = normals[i]
            val len = sqrt(n.x.toDouble() * n.x + n.y.toDouble() * n.y + n.z.toDouble() * n.z)
            require(len > 0.0 && len.isFinite() && bounds[i].isFinite()) { "LpPolytope: half-space $i (${normals[i]} · x ≤ ${bounds[i]}) is not a finite nonzero normal and bound" }
            normalX[i] = n.x / len
            normalY[i] = n.y / len
            normalZ[i] = n.z / len
            bound[i] = bounds[i] / len
        }
        require(normalsSurroundOrigin(normalX, normalY, normalZ)) { "LpPolytope: the half-spaces leave a direction unbounded (their normals do not surround the origin)" }
        val enumerated = enumerateVertices(normalX, normalY, normalZ, bound)
        require(enumerated.first.size >= 4) { "LpPolytope: the half-spaces have no interior (only ${enumerated.first.size} vertices)" }
        vertices = enumerated.first.map { vec(it[0], it[1], it[2]) }
        vertexPlanes = enumerated.second
        val pts = enumerated.first
        val cx = pts.sumOf { it[0] } / pts.size
        val cy = pts.sumOf { it[1] } / pts.size
        val cz = pts.sumOf { it[2] } / pts.size
        require((0 until m).all { bound[it] - normalX[it] * cx - normalY[it] * cy - normalZ[it] * cz > 1e-9 * (1.0 + abs(bound[it])) }) {
            "LpPolytope: the half-spaces have no interior (the vertices are coplanar)"
        }
        center = vec(cx, cy, cz)
        val e = ArrayList<IntArray>()
        for (a in pts.indices) for (b in a + 1 until pts.size) {
            val shared = vertexPlanes[a].filter { it in vertexPlanes[b] }
            // Two vertices on two non-parallel common planes lie on that planes' line, and that line
            // meets the polytope in exactly one edge.
            if (shared.size >= 2 && shared.any { i -> shared.any { j -> crossLength(i, j) > 1e-9 } }) e.add(intArrayOf(a, b))
        }
        edges = e
    }

    private fun crossLength(i: Int, j: Int): Double {
        val x = normalY[i] * normalZ[j] - normalZ[i] * normalY[j]
        val y = normalZ[i] * normalX[j] - normalX[i] * normalZ[j]
        val z = normalX[i] * normalY[j] - normalY[i] * normalX[j]
        return sqrt(x * x + y * y + z * z)
    }

    /** The number of half-spaces, which is the number of constraints. */
    val constraintCount: Int get() = bound.size

    /** How far `(x, y, z)` is inside half-space [i]: positive inside, zero on its plane. */
    fun slack(i: Int, x: Double, y: Double, z: Double): Double = bound[i] - normalX[i] * x - normalY[i] * y - normalZ[i] * z

    /** The largest constraint violation at `(x, y, z)`, zero inside or on the polytope. */
    fun violation(x: Double, y: Double, z: Double): Double {
        var v = 0.0
        for (i in 0 until constraintCount) v = max(v, -slack(i, x, y, z))
        return v
    }

    /** True when `(x, y, z)` is strictly inside every half-space. */
    fun containsStrictly(x: Double, y: Double, z: Double): Boolean = (0 until constraintCount).all { slack(it, x, y, z) > 0.0 }

    /** The vertex that maximises `direction · x`. */
    fun optimalVertex(direction: Vec3): Vec3 = vertices.maxBy { it.x.toDouble() * direction.x + it.y.toDouble() * direction.y + it.z.toDouble() * direction.z }

    /** The vertices next to vertex [i] along an edge. */
    fun neighbours(i: Int): List<Int> = edges.mapNotNull { if (it[0] == i) it[1] else if (it[1] == i) it[0] else null }

    /** The edges as one [FLine] of separate segments: the polytope's wireframe. */
    fun toFLine(color: Vec4 = vec(0.62, 0.70, 0.80, 0.9), thickness: Double = 1.4): FLine = FLine().also { f ->
        for (e in edges) {
            f.moveTo(vertices[e[0]])
            f.lineTo(vertices[e[1]])
        }
        f.color = Vec4(color)
        f.thickness = thickness
    }

    /** The solid, as a triangulated [TriMesh] (the hull of [vertices]). */
    fun toMesh(): TriMesh = convexHullMesh(vertices)

    /** The enclosed volume. */
    val volume: Double get() = toMesh().volume()

    companion object {
        /**
         * The convex hull of [points] as a polytope: any point cloud, with repeats and interior
         * points. The hull's triangles become half-spaces, and coplanar triangles (a cube's face
         * is two) merge into one, so a cube has six constraints, not twelve. Fails, with a message,
         * when the points have no volume (fewer than four, or all coplanar).
         */
        fun hull(points: List<Vec3>): LpPolytope {
            val mesh = convexHullMesh(points)
            require(!mesh.isEmpty) { "LpPolytope.hull: the ${points.size} points have no volume (fewer than 4, or all coplanar)" }
            return fromHullMesh(mesh)
        }

        /** The convex hull of [mesh]'s vertices, as a polytope; see [hull]. */
        fun of(mesh: TriMesh): LpPolytope = hull(mesh.vertices())

        /** The axis-aligned box from `(x0, y0, z0)` to `(x1, y1, z1)`. */
        fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): LpPolytope {
            require(x1 > x0 && y1 > y0 && z1 > z0) { "LpPolytope.box needs x1 > x0, y1 > y0, z1 > z0" }
            return LpPolytope(
                listOf(vec(1.0, 0.0, 0.0), vec(-1.0, 0.0, 0.0), vec(0.0, 1.0, 0.0), vec(0.0, -1.0, 0.0), vec(0.0, 0.0, 1.0), vec(0.0, 0.0, -1.0)),
                listOf(x1, -x0, y1, -y0, z1, -z0),
            )
        }

        /** The cube of half-width [half] centred on the origin. */
        fun cube(half: Double = 1.0): LpPolytope = box(-half, -half, -half, half, half, half)

        private fun fromHullMesh(mesh: TriMesh): LpPolytope {
            val n = mesh.vertexCount
            var cx = 0.0
            var cy = 0.0
            var cz = 0.0
            for (i in 0 until n) {
                cx += mesh.x(i); cy += mesh.y(i); cz += mesh.z(i)
            }
            cx /= n; cy /= n; cz /= n
            var scale = 1.0
            for (i in 0 until n) scale = max(scale, max(abs(mesh.x(i).toDouble()), max(abs(mesh.y(i).toDouble()), abs(mesh.z(i).toDouble()))))
            val planes = ArrayList<DoubleArray>()
            for (t in 0 until mesh.triangleCount) {
                val a = mesh.indices[3 * t]
                val b = mesh.indices[3 * t + 1]
                val c = mesh.indices[3 * t + 2]
                val ux = mesh.x(b).toDouble() - mesh.x(a); val uy = mesh.y(b).toDouble() - mesh.y(a); val uz = mesh.z(b).toDouble() - mesh.z(a)
                val vx = mesh.x(c).toDouble() - mesh.x(a); val vy = mesh.y(c).toDouble() - mesh.y(a); val vz = mesh.z(c).toDouble() - mesh.z(a)
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len < 1e-12 * scale * scale) continue
                nx /= len; ny /= len; nz /= len
                var d = nx * mesh.x(a) + ny * mesh.y(a) + nz * mesh.z(a)
                // Orient away from the vertex mean, which is inside, whatever the mesh's winding.
                if (nx * cx + ny * cy + nz * cz > d) { nx = -nx; ny = -ny; nz = -nz; d = -d }
                // Merge coplanar triangles: same normal, same offset. The hull's points are floats.
                if (planes.none { p -> p[0] * nx + p[1] * ny + p[2] * nz > 1 - 1e-6 && abs(p[3] - d) < 1e-5 * scale }) planes.add(doubleArrayOf(nx, ny, nz, d))
            }
            // The hull's points are floats, so its surface can be very slightly non-convex. Move
            // each plane out to its furthest hull vertex, so every vertex is inside every half-space
            // and no sliver vertex appears where a plane would have clipped a corner.
            for (p in planes) for (i in 0 until n) p[3] = max(p[3], p[0] * mesh.x(i) + p[1] * mesh.y(i) + p[2] * mesh.z(i))
            return LpPolytope(planes.map { vec(it[0], it[1], it[2]) }, planes.map { it[3] })
        }
    }
}

/*
Whether the origin is strictly inside the convex hull of the normals, i.e. no direction d has
nᵢ·d ≤ 0 for every i (which would be an unbounded ray). Equivalently the normals' hull is a solid
whose every face plane has the origin strictly on its inner side.
*/
private fun normalsSurroundOrigin(nx: DoubleArray, ny: DoubleArray, nz: DoubleArray): Boolean {
    val hull = convexHullMesh(List(nx.size) { vec(nx[it], ny[it], nz[it]) })
    if (hull.isEmpty) return false
    val c = hull.vertices()
    val mx = c.sumOf { it.x.toDouble() } / c.size
    val my = c.sumOf { it.y.toDouble() } / c.size
    val mz = c.sumOf { it.z.toDouble() } / c.size
    for (t in 0 until hull.triangleCount) {
        val a = hull.indices[3 * t]
        val b = hull.indices[3 * t + 1]
        val cc = hull.indices[3 * t + 2]
        val ux = hull.x(b).toDouble() - hull.x(a); val uy = hull.y(b).toDouble() - hull.y(a); val uz = hull.z(b).toDouble() - hull.z(a)
        val vx = hull.x(cc).toDouble() - hull.x(a); val vy = hull.y(cc).toDouble() - hull.y(a); val vz = hull.z(cc).toDouble() - hull.z(a)
        val px = uy * vz - uz * vy
        val py = uz * vx - ux * vz
        val pz = ux * vy - uy * vx
        val len = sqrt(px * px + py * py + pz * pz)
        if (len < 1e-14) continue
        val d = (px * hull.x(a) + py * hull.y(a) + pz * hull.z(a)) / len
        val side = (px * mx + py * my + pz * mz) / len - d
        val origin = -d
        // The origin must be on the same side as the hull's own interior, by a margin.
        if (side * origin <= 0.0 || abs(origin) < 1e-7) return false
    }
    return true
}

/*
Every point where three planes meet and every constraint holds, merged when two are the same point,
each with the set of constraints tight there.
*/
private fun enumerateVertices(nx: DoubleArray, ny: DoubleArray, nz: DoubleArray, b: DoubleArray): Pair<List<DoubleArray>, List<IntArray>> {
    val m = b.size
    val scale = 1.0 + b.maxOf { abs(it) }
    val tolerance = 1e-9 * scale
    // A vertex where many nearly coplanar planes meet (a hull of float points) is found by many
    // triples, the ill-conditioned ones a little off. Take the best-conditioned triples first; a
    // later triple whose three planes are all tight at a vertex already found is that vertex.
    val tightTolerance = 1e3 * tolerance
    class Candidate(val p: DoubleArray, val planes: IntArray, val det: Double)
    val candidates = ArrayList<Candidate>()
    for (i in 0 until m) for (j in i + 1 until m) for (k in j + 1 until m) {
        val det = abs(det3(nx[i], ny[i], nz[i], nx[j], ny[j], nz[j], nx[k], ny[k], nz[k]))
        if (det < 1e-9) continue
        val p = solve3(nx[i], ny[i], nz[i], nx[j], ny[j], nz[j], nx[k], ny[k], nz[k], b[i], b[j], b[k]) ?: continue
        if ((0 until m).any { nx[it] * p[0] + ny[it] * p[1] + nz[it] * p[2] > b[it] + tightTolerance }) continue
        candidates.add(Candidate(p, intArrayOf(i, j, k), det))
    }
    candidates.sortByDescending { it.det }
    val points = ArrayList<DoubleArray>()
    for (cand in candidates) {
        val known = points.any { p -> cand.planes.all { abs(b[it] - nx[it] * p[0] - ny[it] * p[1] - nz[it] * p[2]) <= tightTolerance } }
        if (!known) points.add(cand.p)
    }
    val tight = points.map { p ->
        (0 until m).filter { abs(b[it] - nx[it] * p[0] - ny[it] * p[1] - nz[it] * p[2]) <= tightTolerance }.toIntArray()
    }
    return points to tight
}

private fun det3(a0: Double, a1: Double, a2: Double, b0: Double, b1: Double, b2: Double, c0: Double, c1: Double, c2: Double) =
    a0 * (b1 * c2 - b2 * c1) - a1 * (b0 * c2 - b2 * c0) + a2 * (b0 * c1 - b1 * c0)

/* Cramer's rule for the 3×3 system with rows a, b, c and right-hand side r; null when singular. */
private fun solve3(
    a0: Double, a1: Double, a2: Double, b0: Double, b1: Double, b2: Double, c0: Double, c1: Double, c2: Double,
    r0: Double, r1: Double, r2: Double,
): DoubleArray? {
    val det = a0 * (b1 * c2 - b2 * c1) - a1 * (b0 * c2 - b2 * c0) + a2 * (b0 * c1 - b1 * c0)
    if (abs(det) < 1e-12) return null
    val x = r0 * (b1 * c2 - b2 * c1) - a1 * (r1 * c2 - b2 * r2) + a2 * (r1 * c1 - b1 * r2)
    val y = a0 * (r1 * c2 - b2 * r2) - r0 * (b0 * c2 - b2 * c0) + a2 * (b0 * r2 - r1 * c0)
    val z = a0 * (b1 * r2 - r1 * c1) - a1 * (b0 * r2 - r1 * c0) + r0 * (b0 * c1 - b1 * c0)
    return doubleArrayOf(x / det, y / det, z / det)
}

/* Solves the symmetric 3×3 system H d = g, H = [[a, b, c], [b, d, e], [c, e, f]]; null when singular. */
private fun solveSym3(h: DoubleArray, g0: Double, g1: Double, g2: Double): DoubleArray? {
    val r = solve3(h[0], h[1], h[2], h[1], h[3], h[4], h[2], h[4], h[5], g0, g1, g2) ?: return null
    return if (r.all { it.isFinite() }) r else null
}

/**
 * One step of a 3-D trajectory: the 3-D [LpSample].
 *
 * @property scale the method's own measure, as for [LpSample]: μ for [centralPath] and
 *   [primalDual], the dual norm for [pdhg], `√det P` for [ellipsoid], the remaining volume for
 *   [cuttingPlanes], `NaN` for [simplexWalk].
 */
data class LpSample3(
    val x: Double,
    val y: Double,
    val z: Double,
    val iteration: Int,
    val objective: Double,
    val violation: Double,
    val scale: Double,
    val tag: String,
) {
    /** The iterate as a [Vec3], for drawing. */
    val position: Vec3 get() = vec(x, y, z)
}

/** A 3-D method's whole walk, in order; see [LpTrajectory]. */
class LpTrajectory3(val method: String, val samples: List<LpSample3>, val solverSteps: Int = 0) {
    /** The final iterate. */
    val last: LpSample3 get() = samples.last()

    /** The iterates as [Vec3]s. */
    val positions: List<Vec3> get() = samples.map { it.position }

    /** The walk as an open 3-D polyline. */
    fun toFLine(color: Vec4 = vec(1.0, 0.72, 0.32, 0.95), thickness: Double = 1.6): FLine =
        FLine().also { f ->
            samples.forEachIndexed { i, s -> if (i == 0) f.moveTo(s.x, s.y, s.z) else f.lineTo(s.x, s.y, s.z) }
            f.color = Vec4(color)
            f.thickness = thickness
        }
}

/**
 * The ellipsoid `{ z : (z − c)ᵀ P⁻¹ (z − c) ≤ 1 }` with centre `(cx, cy, cz)` and symmetric
 * positive definite `P = [[pxx, pxy, pxz], [pxy, pyy, pyz], [pxz, pyz, pzz]]`, as kept by
 * [LpPolytope.ellipsoid].
 */
data class LpEllipsoid(
    val cx: Double, val cy: Double, val cz: Double,
    val pxx: Double, val pxy: Double, val pxz: Double, val pyy: Double, val pyz: Double, val pzz: Double,
) {
    /** `det P`; the volume is `4/3 π √det`. */
    val determinant: Double
        get() = pxx * (pyy * pzz - pyz * pyz) - pxy * (pxy * pzz - pyz * pxz) + pxz * (pxy * pyz - pyy * pxz)

    /** True when P is symmetric positive definite (all leading minors positive). */
    val isPositiveDefinite: Boolean get() = pxx > 0.0 && pxx * pyy - pxy * pxy > 0.0 && determinant > 0.0

    /** `(z − c)ᵀ P⁻¹ (z − c)` for `z = (x, y, z)`: at most 1 inside the ellipsoid. */
    fun quadraticForm(x: Double, y: Double, z: Double): Double {
        val dx = x - cx
        val dy = y - cy
        val dz = z - cz
        val s = solveSym3(doubleArrayOf(pxx, pxy, pxz, pyy, pyz, pzz), dx, dy, dz) ?: return Double.POSITIVE_INFINITY
        return dx * s[0] + dy * s[1] + dz * s[2]
    }

    // Cholesky factor L (P = L Lᵀ), lower triangular, row-major.
    private fun cholesky(): DoubleArray {
        val l11 = sqrt(max(pxx, 0.0))
        val l21 = if (l11 > 0.0) pxy / l11 else 0.0
        val l31 = if (l11 > 0.0) pxz / l11 else 0.0
        val l22 = sqrt(max(pyy - l21 * l21, 0.0))
        val l32 = if (l22 > 0.0) (pyz - l31 * l21) / l22 else 0.0
        val l33 = sqrt(max(pzz - l31 * l31 - l32 * l32, 0.0))
        return doubleArrayOf(l11, l21, l22, l31, l32, l33)
    }

    /**
     * A wireframe: the unit sphere's [rings] circles of latitude and [rings] meridians, mapped onto
     * the ellipsoid through the Cholesky factor of P, as one [FLine].
     */
    fun toFLine(rings: Int = 6, segments: Int = 48, color: Vec4 = vec(0.55, 0.80, 1.0, 0.4), thickness: Double = 0.8): FLine {
        val l = cholesky()
        fun map(ux: Double, uy: Double, uz: Double) = vec(
            cx + l[0] * ux,
            cy + l[1] * ux + l[2] * uy,
            cz + l[3] * ux + l[4] * uy + l[5] * uz,
        )
        return FLine().also { f ->
            for (r in 1 until rings) {
                val phi = PI * r / rings - PI / 2
                for (k in 0..segments) {
                    val a = 2.0 * PI * k / segments
                    val p = map(cos(phi) * cos(a), cos(phi) * sin(a), sin(phi))
                    if (k == 0) f.moveTo(p) else f.lineTo(p)
                }
            }
            for (r in 0 until rings) {
                val a = PI * r / rings
                for (k in 0..segments) {
                    val phi = 2.0 * PI * k / segments
                    val p = map(cos(phi) * cos(a), cos(phi) * sin(a), sin(phi))
                    if (k == 0) f.moveTo(p) else f.lineTo(p)
                }
            }
            f.color = Vec4(color)
            f.thickness = thickness
        }
    }
}

/** [LpPolytope.ellipsoid]'s result: the centres, and the ellipsoid kept at every step. */
class LpEllipsoidTrace3(val trajectory: LpTrajectory3, val ellipsoids: List<LpEllipsoid>)

/** [LpPolytope.pdhg]'s result: the primal walk, and the dual vector at every step. */
class LpPdhgTrace3(val trajectory: LpTrajectory3, val duals: List<DoubleArray>)

/** [LpPolytope.cuttingPlanes]'s result: the centres, and the region still in play at every step. */
class LpCuttingPlaneTrace3(val trajectory: LpTrajectory3, val regions: List<LpPolytope>)

private fun unit3(direction: Vec3): DoubleArray {
    val len = sqrt(direction.x.toDouble() * direction.x + direction.y.toDouble() * direction.y + direction.z.toDouble() * direction.z)
    require(len > 0.0 && len.isFinite()) { "the objective direction must be a nonzero finite vector, got $direction" }
    return doubleArrayOf(direction.x / len, direction.y / len, direction.z / len)
}

private fun LpPolytope.sample(c: DoubleArray, x: Double, y: Double, z: Double, k: Int, scale: Double, tag: String) =
    LpSample3(x, y, z, k, c[0] * x + c[1] * y + c[2] * z, violation(x, y, z), scale, tag)

private fun LpPolytope.checkedWeights(weights: DoubleArray?): DoubleArray {
    val w = weights ?: DoubleArray(constraintCount) { 1.0 }
    require(w.size == constraintCount) { "need one weight per half-space: ${w.size} weights for $constraintCount half-spaces" }
    require(w.all { it.isFinite() && it > 0.0 }) { "half-space weights must be positive and finite: ${w.toList()}" }
    return w
}

/* Gradient and Hessian (negated, so positive definite) of c·x + μ Σ wᵢ ln sᵢ; null outside. */
private fun barrierParts(
    nx: DoubleArray, ny: DoubleArray, nz: DoubleArray, b: DoubleArray, w: DoubleArray,
    c: DoubleArray, mu: Double, x: Double, y: Double, z: Double,
): Pair<DoubleArray, DoubleArray>? {
    var g0 = c[0]
    var g1 = c[1]
    var g2 = c[2]
    val h = DoubleArray(6)
    for (i in nx.indices) {
        val s = b[i] - nx[i] * x - ny[i] * y - nz[i] * z
        if (s <= 0.0) return null
        val t = mu * w[i] / s
        g0 -= t * nx[i]; g1 -= t * ny[i]; g2 -= t * nz[i]
        val q = t / s
        h[0] += q * nx[i] * nx[i]; h[1] += q * nx[i] * ny[i]; h[2] += q * nx[i] * nz[i]
        h[3] += q * ny[i] * ny[i]; h[4] += q * ny[i] * nz[i]; h[5] += q * nz[i] * nz[i]
    }
    return doubleArrayOf(g0, g1, g2) to h
}

/* Damped Newton for the barrier maximiser in 3-D. Returns (x, y, z, steps). */
private fun barrierNewton3(
    nx: DoubleArray, ny: DoubleArray, nz: DoubleArray, b: DoubleArray, w: DoubleArray,
    c: DoubleArray, mu: Double, x0: Double, y0: Double, z0: Double, maxIterations: Int = 100,
): DoubleArray {
    var x = x0
    var y = y0
    var z = z0
    var steps = 0
    fun value(px: Double, py: Double, pz: Double): Double {
        var v = c[0] * px + c[1] * py + c[2] * pz
        for (i in nx.indices) {
            val s = b[i] - nx[i] * px - ny[i] * py - nz[i] * pz
            if (s <= 0.0) return Double.NEGATIVE_INFINITY
            v += mu * w[i] * ln(s)
        }
        return v
    }
    repeat(maxIterations) {
        val (g, h) = barrierParts(nx, ny, nz, b, w, c, mu, x, y, z) ?: return doubleArrayOf(x, y, z, steps.toDouble())
        val d = solveSym3(h, g[0], g[1], g[2]) ?: error("degenerate barrier Hessian")
        val decrement = g[0] * d[0] + g[1] * d[1] + g[2] * d[2]
        if (decrement < 1e-18) return doubleArrayOf(x, y, z, steps.toDouble())
        steps++
        val before = value(x, y, z)
        var alpha = 1.0
        var accepted = false
        for (tries in 0 until 80) {
            val after = value(x + alpha * d[0], y + alpha * d[1], z + alpha * d[2])
            if (after.isFinite() && after >= before + 1e-4 * alpha * decrement) {
                x += alpha * d[0]; y += alpha * d[1]; z += alpha * d[2]
                accepted = true
                break
            }
            alpha *= 0.5
        }
        if (!accepted) return doubleArrayOf(x, y, z, steps.toDouble())
    }
    return doubleArrayOf(x, y, z, steps.toDouble())
}

/**
 * The log-barrier central path in 3-D: for μ falling geometrically from [muHigh] to [muLow], the
 * maximiser of `c·x + μ Σ wᵢ ln(slackᵢ)`, by damped Newton (a 3×3 solve per step) warm-started
 * from the previous sample. It leaves the weighted analytic centre and ends at the optimal vertex
 * for [direction]. Every sample is strictly feasible; [LpSample3.scale] is μ. See the 2-D
 * [LpPolygon.centralPath] for what the weights do.
 */
fun LpPolytope.centralPath(
    direction: Vec3,
    weights: DoubleArray? = null,
    samples: Int = 160,
    muHigh: Double = 1000.0,
    muLow: Double = 1e-5,
    start: Vec3 = center,
): LpTrajectory3 {
    require(samples >= 2) { "centralPath needs at least 2 samples, got $samples" }
    require(muHigh > muLow && muLow > 0.0) { "centralPath needs muHigh > muLow > 0, got $muHigh and $muLow" }
    require(containsStrictly(start.x.toDouble(), start.y.toDouble(), start.z.toDouble())) { "centralPath must start strictly inside the polytope; $start is not" }
    val c = unit3(direction)
    val w = checkedWeights(weights)
    var p = doubleArrayOf(start.x.toDouble(), start.y.toDouble(), start.z.toDouble())
    var steps = 0
    val out = List(samples) { k ->
        val mu = exp(ln(muHigh) + (ln(muLow) - ln(muHigh)) * k / (samples - 1))
        p = barrierNewton3(normalX, normalY, normalZ, bound, w, c, mu, p[0], p[1], p[2])
        steps += p[3].toInt()
        sample(c, p[0], p[1], p[2], k, mu, "central-path")
    }
    return LpTrajectory3("central-path", out, steps)
}

/**
 * [count] directions spread evenly over the unit sphere (a Fibonacci spiral), the directions
 * [LpPolytope.centralFan] uses.
 */
fun sphereDirections(count: Int): List<Vec3> {
    require(count > 0) { "sphereDirections needs at least one direction, got $count" }
    val golden = PI * (3.0 - sqrt(5.0))
    return List(count) { i ->
        val z = 1.0 - 2.0 * (i + 0.5) / count
        val r = sqrt(max(0.0, 1.0 - z * z))
        vec(r * cos(golden * i), r * sin(golden * i), z)
    }
}

/**
 * A fan of central paths over [directions] objective directions spread over the sphere
 * ([sphereDirections]): every path leaves the same weighted analytic centre and ends at the vertex
 * that wins for its direction.
 */
fun LpPolytope.centralFan(
    directions: Int = 64,
    samples: Int = 100,
    weights: DoubleArray? = null,
    muHigh: Double = 1000.0,
    muLow: Double = 1e-5,
): List<LpTrajectory3> = sphereDirections(directions).map { centralPath(it, weights, samples, muHigh, muLow) }

/**
 * Mehrotra's predictor–corrector primal-dual interior-point method in 3-D, every Newton iterate
 * recorded; the 3×3 port of [LpPolygon.primalDual]. [LpSample3.scale] is the duality measure μ.
 */
fun LpPolytope.primalDual(
    direction: Vec3,
    maxIterations: Int = 60,
    tolerance: Double = 1e-9,
    stepFraction: Double = 0.95,
    start: Vec3 = center,
): LpTrajectory3 {
    require(maxIterations >= 0) { "maxIterations must be non-negative, got $maxIterations" }
    require(stepFraction > 0.0 && stepFraction < 1.0) { "stepFraction must be in (0, 1), got $stepFraction" }
    require(containsStrictly(start.x.toDouble(), start.y.toDouble(), start.z.toDouble())) { "primalDual must start strictly inside the polytope; $start is not" }
    val c = unit3(direction)
    val m = constraintCount
    var x = start.x.toDouble()
    var y = start.y.toDouble()
    var z = start.z.toDouble()
    val s = DoubleArray(m) { slack(it, x, y, z) }
    val u = DoubleArray(m) { 1.0 }
    val out = ArrayList<LpSample3>()
    fun mu() = (0 until m).sumOf { s[it] * u[it] } / m
    out.add(sample(c, x, y, z, 0, mu(), "primal-dual"))
    val ds = DoubleArray(m)
    val du = DoubleArray(m)
    val rc = DoubleArray(m)
    fun direction(rd: DoubleArray): DoubleArray {
        val h = DoubleArray(6)
        var r0 = -rd[0]
        var r1 = -rd[1]
        var r2 = -rd[2]
        for (i in 0 until m) {
            val q = u[i] / s[i]
            h[0] += q * normalX[i] * normalX[i]; h[1] += q * normalX[i] * normalY[i]; h[2] += q * normalX[i] * normalZ[i]
            h[3] += q * normalY[i] * normalY[i]; h[4] += q * normalY[i] * normalZ[i]; h[5] += q * normalZ[i] * normalZ[i]
            r0 -= normalX[i] * rc[i] / s[i]
            r1 -= normalY[i] * rc[i] / s[i]
            r2 -= normalZ[i] * rc[i] / s[i]
        }
        val d = solveSym3(h, r0, r1, r2) ?: error("degenerate primal-dual normal matrix")
        for (i in 0 until m) {
            ds[i] = -(normalX[i] * d[0] + normalY[i] * d[1] + normalZ[i] * d[2])
            du[i] = (rc[i] - u[i] * ds[i]) / s[i]
        }
        return d
    }
    fun maxStep(v: DoubleArray, dv: DoubleArray): Double {
        var a = 1.0
        for (i in v.indices) if (dv[i] < 0.0) a = min(a, -v[i] / dv[i])
        return a
    }
    for (k in 1..maxIterations) {
        val rd = doubleArrayOf(
            (0 until m).sumOf { normalX[it] * u[it] } - c[0],
            (0 until m).sumOf { normalY[it] * u[it] } - c[1],
            (0 until m).sumOf { normalZ[it] * u[it] } - c[2],
        )
        val muNow = mu()
        if (muNow < tolerance && sqrt(rd[0] * rd[0] + rd[1] * rd[1] + rd[2] * rd[2]) < tolerance) break
        for (i in 0 until m) rc[i] = -s[i] * u[i]
        direction(rd)
        val ap = maxStep(s, ds)
        val ad = maxStep(u, du)
        val muAff = (0 until m).sumOf { (s[it] + ap * ds[it]) * (u[it] + ad * du[it]) } / m
        val sigma = (muAff / muNow).let { it * it * it }
        for (i in 0 until m) rc[i] = -s[i] * u[i] - ds[i] * du[i] + sigma * muNow
        val d = direction(rd)
        val alphaP = min(1.0, stepFraction * maxStep(s, ds))
        val alphaD = min(1.0, stepFraction * maxStep(u, du))
        x += alphaP * d[0]
        y += alphaP * d[1]
        z += alphaP * d[2]
        for (i in 0 until m) {
            s[i] = slack(i, x, y, z)
            u[i] += alphaD * du[i]
        }
        out.add(sample(c, x, y, z, k, mu(), "primal-dual"))
    }
    return LpTrajectory3("primal-dual", out)
}

/**
 * Primal-dual hybrid gradient (Chambolle–Pock) in 3-D, every primal and dual state kept,
 * infeasible ones included; the port of [LpPolygon.pdhg]. The primal walk overshoots the polytope
 * and spirals into the optimal vertex. [LpSample3.scale] is the dual vector's norm.
 */
fun LpPolytope.pdhg(
    direction: Vec3,
    iterations: Int = 1000,
    tau: Double = 0.15,
    sigma: Double = 0.15,
    theta: Double = 1.0,
    start: Vec3 = center,
): LpPdhgTrace3 {
    require(iterations >= 0) { "iterations must be non-negative, got $iterations" }
    require(tau > 0.0 && sigma > 0.0 && theta >= 0.0) { "pdhg needs tau > 0, sigma > 0, theta >= 0; got $tau, $sigma, $theta" }
    val c = unit3(direction)
    val m = constraintCount
    val dual = DoubleArray(m)
    var x = start.x.toDouble()
    var y = start.y.toDouble()
    var z = start.z.toDouble()
    var bx = x
    var by = y
    var bz = z
    fun dualNorm() = sqrt(dual.sumOf { it * it })
    val samples = arrayListOf(sample(c, x, y, z, 0, 0.0, "pdhg"))
    val duals = arrayListOf(dual.copyOf())
    for (k in 1..iterations) {
        for (i in 0 until m) dual[i] = max(0.0, dual[i] - sigma * slack(i, bx, by, bz))
        var gx = c[0]
        var gy = c[1]
        var gz = c[2]
        for (i in 0 until m) {
            gx -= normalX[i] * dual[i]; gy -= normalY[i] * dual[i]; gz -= normalZ[i] * dual[i]
        }
        val nx = x + tau * gx
        val ny = y + tau * gy
        val nz = z + tau * gz
        if (!nx.isFinite() || !ny.isFinite() || !nz.isFinite()) break
        bx = nx + theta * (nx - x); by = ny + theta * (ny - y); bz = nz + theta * (nz - z)
        x = nx; y = ny; z = nz
        samples.add(sample(c, x, y, z, k, dualNorm(), "pdhg"))
        duals.add(dual.copyOf())
    }
    return LpPdhgTrace3(LpTrajectory3("pdhg", samples), duals)
}

/**
 * The central-cut ellipsoid method in 3-D, every centre and ellipsoid kept. It starts from a ball
 * of [initialRadius] (default: the polytope's diameter) around [start], cuts on the most violated
 * half-space at an infeasible centre and on the objective at a feasible one. In `n = 3` dimensions
 * each cut moves the centre by `−Pg / (4√(gᵀPg))` and replaces `P` by
 * `9/8 · (P − ½ (Pg)(Pg)ᵀ / gᵀPg)` (`n²/(n²−1)` and `2/(n+1)`), which multiplies the volume by
 * `27/32 ≈ 0.84` every step: slower than the 2-D method's 0.77, as the method is in higher
 * dimensions. [LpSample3.tag] and [LpSample3.scale] (`√det P`) are as in [LpPolygon.ellipsoid].
 */
fun LpPolytope.ellipsoid(
    direction: Vec3,
    iterations: Int = 120,
    initialRadius: Double? = null,
    start: Vec3 = center,
): LpEllipsoidTrace3 {
    require(iterations >= 0) { "iterations must be non-negative, got $iterations" }
    val c = unit3(direction)
    val radius = initialRadius ?: vertices.maxOf { a -> vertices.maxOf { b -> a.distance(b).toDouble() } }
    require(radius > 0.0 && radius.isFinite()) { "initialRadius must be positive, got $radius" }
    var x = start.x.toDouble()
    var y = start.y.toDouble()
    var z = start.z.toDouble()
    val p = doubleArrayOf(radius * radius, 0.0, 0.0, radius * radius, 0.0, radius * radius)
    val samples = ArrayList<LpSample3>()
    val ellipsoids = ArrayList<LpEllipsoid>()
    for (k in 0..iterations) {
        val e = LpEllipsoid(x, y, z, p[0], p[1], p[2], p[3], p[4], p[5])
        ellipsoids.add(e)
        var worst = -1
        var worstViolation = 0.0
        for (i in 0 until constraintCount) {
            val v = -slack(i, x, y, z)
            if (v > worstViolation) {
                worstViolation = v
                worst = i
            }
        }
        val tag = if (k == iterations) "final" else if (worst >= 0) "feasibility-cut" else "objective-cut"
        samples.add(sample(c, x, y, z, k, sqrt(max(e.determinant, 0.0)), tag))
        if (k == iterations) break
        val gx = if (worst >= 0) normalX[worst] else -c[0]
        val gy = if (worst >= 0) normalY[worst] else -c[1]
        val gz = if (worst >= 0) normalZ[worst] else -c[2]
        val ux = p[0] * gx + p[1] * gy + p[2] * gz
        val uy = p[1] * gx + p[3] * gy + p[4] * gz
        val uz = p[2] * gx + p[4] * gy + p[5] * gz
        val gpg = gx * ux + gy * uy + gz * uz
        if (gpg <= 1e-280 || !gpg.isFinite()) break
        val norm = sqrt(gpg)
        x -= ux / (4.0 * norm)
        y -= uy / (4.0 * norm)
        z -= uz / (4.0 * norm)
        val factor = 9.0 / 8.0
        val shrink = 0.5 / gpg
        p[0] = factor * (p[0] - shrink * ux * ux)
        p[1] = factor * (p[1] - shrink * ux * uy)
        p[2] = factor * (p[2] - shrink * ux * uz)
        p[3] = factor * (p[3] - shrink * uy * uy)
        p[4] = factor * (p[4] - shrink * uy * uz)
        p[5] = factor * (p[5] - shrink * uz * uz)
    }
    return LpEllipsoidTrace3(LpTrajectory3("ellipsoid", samples), ellipsoids)
}

/**
 * The simplex method in 3-D: a walk along the polytope's [LpPolytope.edges] from [startVertex],
 * moving each time to the neighbouring vertex that improves `c·x` most, until none does. A convex
 * polytope has no local optimum that is not global, so it ends at the optimal vertex.
 * [LpSample3.scale] is `NaN`.
 */
fun LpPolytope.simplexWalk(direction: Vec3, startVertex: Int = 0): LpTrajectory3 {
    require(startVertex in vertices.indices) { "startVertex must be in 0 until ${vertices.size}, got $startVertex" }
    val c = unit3(direction)
    fun value(i: Int) = c[0] * vertices[i].x + c[1] * vertices[i].y + c[2] * vertices[i].z
    val out = ArrayList<LpSample3>()
    var i = startVertex
    for (step in 0..vertices.size) {
        val v = vertices[i]
        out.add(sample(c, v.x.toDouble(), v.y.toDouble(), v.z.toDouble(), step, Double.NaN, "simplex"))
        val next = neighbours(i).maxByOrNull { value(it) } ?: break
        if (value(next) > value(i) + 1e-9) i = next else break
    }
    return LpTrajectory3("simplex", out)
}

/*
The Chebyshev centre in 3-D: maximise r subject to nᵢ·x + r ≤ bᵢ, a four-variable LP whose optimum
has four tight constraints. Solves each quadruple's 4×4 system and keeps the feasible solution with
the largest r. Returns (x, y, z, r).
*/
internal fun chebyshevCenter3(nx: DoubleArray, ny: DoubleArray, nz: DoubleArray, b: DoubleArray): DoubleArray {
    val m = b.size
    var best: DoubleArray? = null
    val a = Array(4) { DoubleArray(5) }
    for (i in 0 until m) for (j in i + 1 until m) for (k in j + 1 until m) for (l in k + 1 until m) {
        val rows = intArrayOf(i, j, k, l)
        for (r in 0 until 4) {
            val q = rows[r]
            a[r][0] = nx[q]; a[r][1] = ny[q]; a[r][2] = nz[q]; a[r][3] = 1.0; a[r][4] = b[q]
        }
        val sol = gauss4(a) ?: continue
        val r = sol[3]
        if (r <= 0.0 || (best != null && r <= best[3])) continue
        val tolerance = 1e-9 * (1.0 + abs(r))
        if ((0 until m).all { nx[it] * sol[0] + ny[it] * sol[1] + nz[it] * sol[2] + r <= b[it] + tolerance }) best = sol
    }
    return checkNotNull(best) { "chebyshevCenter3: the region has no interior" }
}

/* Gaussian elimination with partial pivoting on a 4×5 augmented matrix (overwritten). */
private fun gauss4(a: Array<DoubleArray>): DoubleArray? {
    for (col in 0 until 4) {
        var piv = col
        for (r in col + 1 until 4) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
        if (abs(a[piv][col]) < 1e-12) return null
        if (piv != col) { val t = a[piv]; a[piv] = a[col]; a[col] = t }
        for (r in col + 1 until 4) {
            val f = a[r][col] / a[col][col]
            for (cc in col until 5) a[r][cc] -= f * a[col][cc]
        }
    }
    val x = DoubleArray(4)
    for (r in 3 downTo 0) {
        var s = a[r][4]
        for (cc in r + 1 until 4) s -= a[r][cc] * x[cc]
        x[r] = s / a[r][r]
    }
    return x
}

/*
The 3-D form of `volumetricCenter` in lp_trajectories.kt: minimise ½ ln det H, H = Σ aᵢaᵢᵀ / sᵢ²,
with Vaidya's Hessian approximation Q = Σ σᵢ aᵢaᵢᵀ / sᵢ² and backtracking, from a strictly
feasible start.
*/
internal fun volumetricCenter3(
    nx: DoubleArray, ny: DoubleArray, nz: DoubleArray, b: DoubleArray, start: DoubleArray, maxIterations: Int = 100,
): DoubleArray {
    val x = start.copyOf()
    // symmetric 3x3 as (xx, xy, xz, yy, yz, zz)
    fun sum(px: Double, py: Double, pz: Double, weight: (Int, Double) -> Double): DoubleArray? {
        val m = DoubleArray(6)
        for (i in nx.indices) {
            val s = b[i] - nx[i] * px - ny[i] * py - nz[i] * pz
            if (s <= 0.0) return null
            val q = weight(i, s)
            m[0] += q * nx[i] * nx[i]; m[1] += q * nx[i] * ny[i]; m[2] += q * nx[i] * nz[i]
            m[3] += q * ny[i] * ny[i]; m[4] += q * ny[i] * nz[i]; m[5] += q * nz[i] * nz[i]
        }
        return m
    }
    fun det(m: DoubleArray) = m[0] * (m[3] * m[5] - m[4] * m[4]) - m[1] * (m[1] * m[5] - m[4] * m[2]) + m[2] * (m[1] * m[4] - m[3] * m[2])
    // adjugate, so inverse = adj / det
    fun adj(m: DoubleArray) = doubleArrayOf(
        m[3] * m[5] - m[4] * m[4], m[2] * m[4] - m[1] * m[5], m[1] * m[4] - m[2] * m[3],
        m[0] * m[5] - m[2] * m[2], m[1] * m[2] - m[0] * m[4], m[0] * m[3] - m[1] * m[1],
    )
    fun quad(a: DoubleArray, u: Double, v: Double, w: Double) =
        a[0] * u * u + a[3] * v * v + a[5] * w * w + 2 * (a[1] * u * v + a[2] * u * w + a[4] * v * w)
    fun value(px: Double, py: Double, pz: Double): Double {
        val h = sum(px, py, pz) { _, s -> 1.0 / (s * s) } ?: return Double.POSITIVE_INFINITY
        val d = det(h)
        return if (d > 0.0) 0.5 * ln(d) else Double.POSITIVE_INFINITY
    }
    repeat(maxIterations) {
        val h = sum(x[0], x[1], x[2]) { _, s -> 1.0 / (s * s) } ?: return x
        val hd = det(h)
        if (!(hd > 0.0)) return x
        val hInv = adj(h)
        val g = DoubleArray(3)
        val q = sum(x[0], x[1], x[2]) { i, s ->
            val leverage = quad(hInv, nx[i], ny[i], nz[i]) / hd / (s * s)
            g[0] += leverage * nx[i] / s; g[1] += leverage * ny[i] / s; g[2] += leverage * nz[i] / s
            leverage / (s * s)
        } ?: return x
        val qd = det(q)
        if (!(qd > 0.0) || !qd.isFinite()) return x
        val qa = adj(q)
        val d = doubleArrayOf(
            -(qa[0] * g[0] + qa[1] * g[1] + qa[2] * g[2]) / qd,
            -(qa[1] * g[0] + qa[3] * g[1] + qa[4] * g[2]) / qd,
            -(qa[2] * g[0] + qa[4] * g[1] + qa[5] * g[2]) / qd,
        )
        val decrement = -(g[0] * d[0] + g[1] * d[1] + g[2] * d[2])
        if (decrement < 1e-18) return x
        val before = value(x[0], x[1], x[2])
        var alpha = 1.0
        var accepted = false
        for (tries in 0 until 80) {
            val after = value(x[0] + alpha * d[0], x[1] + alpha * d[1], x[2] + alpha * d[2])
            if (after.isFinite() && after <= before - 1e-4 * alpha * decrement) {
                for (k in 0..2) x[k] += alpha * d[k]
                accepted = true
                break
            }
            alpha *= 0.5
        }
        if (!accepted) return x
    }
    return x
}

/**
 * A cutting-plane walk in 3-D: find a centre of the region still in play, cut it with the objective
 * (`c·x ≥ c·centre`), repeat. [center] is [LpCenter.ANALYTIC] (the analytic centre of every
 * constraint so far, cuts included), [LpCenter.CENTROID] (the centre of gravity, which removes at
 * least `1 − (3/4)³ ≈ 58%` of the volume each step) or [LpCenter.CHEBYSHEV] (the centre of the
 * largest inscribed ball) or [LpCenter.VOLUMETRIC] (Vaidya's volumetric centre of every constraint
 * so far, started from the analytic centre).
 * Stops when the remaining volume falls below [minVolume] or after [iterations].
 * [LpSample3.scale] is the remaining volume, and [LpCuttingPlaneTrace3.regions] holds each region.
 */
fun LpPolytope.cuttingPlanes(
    direction: Vec3,
    iterations: Int = 20,
    center: LpCenter = LpCenter.ANALYTIC,
    minVolume: Double = 1e-9,
): LpCuttingPlaneTrace3 {
    require(iterations >= 0) { "iterations must be non-negative, got $iterations" }
    val c = unit3(direction)
    val normals = (0 until constraintCount).map { vec(normalX[it], normalY[it], normalZ[it]) }.toMutableList()
    val bounds = bound.toMutableList()
    var region = this
    val samples = ArrayList<LpSample3>()
    val regions = ArrayList<LpPolytope>()
    for (k in 0..iterations) {
        val mesh = region.toMesh()
        val volume = mesh.volume()
        val p = when (center) {
            LpCenter.CENTROID -> mesh.solidCentroid().let { doubleArrayOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
            LpCenter.CHEBYSHEV -> chebyshevCenter3(region.normalX, region.normalY, region.normalZ, region.bound)
            LpCenter.ANALYTIC, LpCenter.VOLUMETRIC -> {
                val s = region.center
                val analytic = barrierNewton3(
                    region.normalX, region.normalY, region.normalZ, region.bound, DoubleArray(region.constraintCount) { 1.0 },
                    doubleArrayOf(0.0, 0.0, 0.0), 1.0, s.x.toDouble(), s.y.toDouble(), s.z.toDouble(),
                )
                if (center == LpCenter.ANALYTIC) analytic
                else volumetricCenter3(region.normalX, region.normalY, region.normalZ, region.bound, analytic)
            }
        }
        samples.add(sample(c, p[0], p[1], p[2], k, volume, "cutting-plane"))
        regions.add(region)
        if (k == iterations || volume < minVolume) break
        val cut = -(c[0] * p[0] + c[1] * p[1] + c[2] * p[2])
        normals.add(vec(-c[0], -c[1], -c[2]))
        bounds.add(cut)
        region = runCatching { LpPolytope(normals, bounds) }.getOrNull() ?: break
    }
    return LpCuttingPlaneTrace3(LpTrajectory3("cutting-plane-${center.name.lowercase()}", samples), regions)
}

/** Every path of a 3-D fan as an [FLine], coloured round a hue circle. */
@JvmName("toFLines3")
fun List<LpTrajectory3>.toFLines(alpha: Double = 0.8, thickness: Double = 1.1): List<FLine> =
    mapIndexed { i, t ->
        val h = 2.0 * PI * i / size
        t.toFLine(vec(0.6 + 0.4 * cos(h), 0.6 + 0.4 * cos(h - 2.1), 0.6 + 0.4 * cos(h + 2.1), alpha), thickness)
    }
