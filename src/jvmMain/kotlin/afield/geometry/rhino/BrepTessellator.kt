package afield.geometry.rhino

import afield.geometry.path.pointInRing2D
import afield.geometry.path.signedArea2D
import afield.geometry.mesh.TriMesh
import afield.geometry.path.FloatEarcut
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where a Brep's or extrusion's triangles come from when [Rhino3dm.load] reads it.
 *
 * Rhino caches a render mesh on each Brep face unless the file was saved with *Save Small*; a
 * face without one can be tessellated here from its exact NURBS surface and trim loops instead.
 */
enum class RhinoBrepMeshing {
    /** Only the render meshes Rhino saved; a Brep without one is a [RhinoSkipped]. */
    SAVED,

    /** Always tessellate the NURBS surfaces, ignoring saved meshes. */
    EVALUATE,

    /** The saved mesh for every face that has one, and a tessellation for every face that does not. */
    SAVED_ELSE_EVALUATE,
}

/**
 * One rational tensor-product NURBS surface, in homogeneous control points.
 *
 * @param degreeU polynomial degree in u.
 * @param degreeV polynomial degree in v.
 * @param countU control points in u.
 * @param countV control points in v.
 * @param knotsU the full clamped knot vector in u, `degreeU + countU + 1` values.
 * @param knotsV the full clamped knot vector in v.
 * @param cvs `(x w, y w, z w, w)` per control point, u-major: point `(i, j)` starts at `(i * countV + j) * 4`.
 */
class NurbsSurface(
    val degreeU: Int,
    val degreeV: Int,
    val countU: Int,
    val countV: Int,
    val knotsU: DoubleArray,
    val knotsV: DoubleArray,
    val cvs: DoubleArray,
) {
    init {
        require(knotsU.size == degreeU + countU + 1) { "u knots: ${knotsU.size}, expected ${degreeU + countU + 1}" }
        require(knotsV.size == degreeV + countV + 1) { "v knots: ${knotsV.size}, expected ${degreeV + countV + 1}" }
        require(cvs.size == countU * countV * 4) { "cvs: ${cvs.size}, expected ${countU * countV * 4}" }
    }

    /** The parameter interval in u, `[min, max]`. */
    val domainU: DoubleArray get() = doubleArrayOf(knotsU[degreeU], knotsU[countU])

    /** The parameter interval in v. */
    val domainV: DoubleArray get() = doubleArrayOf(knotsV[degreeV], knotsV[countV])

    /** The distinct knot values inside the u domain, ends included — where the surface may bend sharply. */
    fun breaksU(): DoubleArray = breaks(knotsU, degreeU, countU)

    /** The distinct knot values inside the v domain, ends included. */
    fun breaksV(): DoubleArray = breaks(knotsV, degreeV, countV)

    private fun breaks(k: DoubleArray, p: Int, n: Int): DoubleArray {
        val out = ArrayList<Double>()
        for (i in p..n) if (out.isEmpty() || k[i] > out.last()) out.add(k[i])
        return out.toDoubleArray()
    }

    /**
     * The point and unnormalised normal (`dS/du x dS/dv`) at `(u, v)`, into [out] as
     * `x y z nx ny nz`. Uses the first derivatives of the rational basis (The NURBS Book, A2.3 and
     * eq. 4.20); where they vanish — a sphere's pole — the normal is taken a hair inside the domain.
     */
    fun evaluate(u: Double, v: Double, out: DoubleArray) {
        evaluateRaw(u, v, out)
        if (out[3] * out[3] + out[4] * out[4] + out[5] * out[5] > 1e-24 * scale2()) return
        val du = domainU.let { it[1] - it[0] } * 1e-5
        val dv = domainV.let { it[1] - it[0] } * 1e-5
        val cu = (domainU[0] + domainU[1]) / 2
        val cv = (domainV[0] + domainV[1]) / 2
        val x = out[0]; val y = out[1]; val z = out[2]
        evaluateRaw(u + if (u < cu) du else -du, v + if (v < cv) dv else -dv, out)
        out[0] = x; out[1] = y; out[2] = z
    }

    private var scale2Cache = -1.0
    private fun scale2(): Double {
        if (scale2Cache < 0) {
            var m = 0.0
            for (i in 0 until countU * countV) {
                val w = cvs[i * 4 + 3]
                for (c in 0..2) m = max(m, abs(cvs[i * 4 + c] / w))
            }
            scale2Cache = max(m * m, 1e-300).let { it * it }
        }
        return scale2Cache
    }

    private val bu = Array(2) { DoubleArray(degreeU + 1) }
    private val bv = Array(2) { DoubleArray(degreeV + 1) }

    private fun evaluateRaw(u: Double, v: Double, out: DoubleArray) {
        val su = span(knotsU, degreeU, countU, u)
        val sv = span(knotsV, degreeV, countV, v)
        basisWithDerivative(knotsU, degreeU, su, u, bu)
        basisWithDerivative(knotsV, degreeV, sv, v, bv)
        val a = DoubleArray(4); val au = DoubleArray(4); val av = DoubleArray(4)
        for (i in 0..degreeU) {
            val iu = su - degreeU + i
            for (j in 0..degreeV) {
                val jv = sv - degreeV + j
                val base = (iu * countV + jv) * 4
                val n = bu[0][i] * bv[0][j]
                val nu = bu[1][i] * bv[0][j]
                val nv = bu[0][i] * bv[1][j]
                for (c in 0..3) {
                    val p = cvs[base + c]
                    a[c] += n * p; au[c] += nu * p; av[c] += nv * p
                }
            }
        }
        val w = a[3]
        val sx = a[0] / w; val sy = a[1] / w; val sz = a[2] / w
        val ux = (au[0] - au[3] * sx) / w; val uy = (au[1] - au[3] * sy) / w; val uz = (au[2] - au[3] * sz) / w
        val vx = (av[0] - av[3] * sx) / w; val vy = (av[1] - av[3] * sy) / w; val vz = (av[2] - av[3] * sz) / w
        out[0] = sx; out[1] = sy; out[2] = sz
        out[3] = uy * vz - uz * vy
        out[4] = uz * vx - ux * vz
        out[5] = ux * vy - uy * vx
    }

    private fun span(k: DoubleArray, p: Int, n: Int, t: Double): Int {
        if (t >= k[n]) {
            var s = n - 1
            while (s > p && k[s] >= k[s + 1]) s--
            return s
        }
        if (t <= k[p]) {
            var s = p
            while (s < n - 1 && k[s + 1] <= k[p]) s++
            return s
        }
        var lo = p; var hi = n
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (t < k[mid]) hi = mid else lo = mid
        }
        return lo
    }

    private fun basisWithDerivative(k: DoubleArray, p: Int, s: Int, t: Double, out: Array<DoubleArray>) {
        // ndu[j][r]: basis values (upper triangle) and knot differences (lower), as A2.3.
        val ndu = Array(p + 1) { DoubleArray(p + 1) }
        val left = DoubleArray(p + 1); val right = DoubleArray(p + 1)
        ndu[0][0] = 1.0
        for (j in 1..p) {
            left[j] = t - k[s + 1 - j]
            right[j] = k[s + j] - t
            var saved = 0.0
            for (r in 0 until j) {
                ndu[j][r] = right[r + 1] + left[j - r]
                val temp = if (ndu[j][r] == 0.0) 0.0 else ndu[r][j - 1] / ndu[j][r]
                ndu[r][j] = saved + right[r + 1] * temp
                saved = left[j - r] * temp
            }
            ndu[j][j] = saved
        }
        for (j in 0..p) out[0][j] = ndu[j][p]
        if (p == 0) { out[1][0] = 0.0; return }
        for (r in 0..p) {
            var d = 0.0
            if (r >= 1) d += ndu[r - 1][p - 1] / ndu[p][r - 1]
            if (r <= p - 1) d -= ndu[r][p - 1] / ndu[p][r]
            out[1][r] = if (d.isFinite()) d * p else 0.0
        }
    }
}

/**
 * A Brep face as the wrapper hands it over: its surface, whether it faces against the surface's
 * normal, whether Rhino saved a mesh for it, and its trim loops in the surface's parameters.
 *
 * @param outer the outer loop, `u v` pairs; it closes from the last point to the first.
 * @param inner the hole loops, in the same form.
 * @param outerPoints the outer loop's points in model space, `x y z` per `u v` of [outer]; `null`
 *   to take them from the surface. The reader fills them from the Brep's edges, each edge sampled
 *   once, so two faces sharing an edge carry the same points and [BrepTessellator] joins them exactly.
 * @param innerPoints the hole loops' points in model space, likewise.
 */
class BrepFace(
    val surface: NurbsSurface?,
    val reversed: Boolean,
    val hasSavedMesh: Boolean,
    val outer: List<DoubleArray>,
    val inner: List<DoubleArray>,
    val outerPoints: List<DoubleArray>? = null,
    val innerPoints: List<DoubleArray>? = null,
)

/**
 * Tessellates trimmed NURBS Brep faces into a [TriMesh].
 *
 * Per face: an adaptive subdivision of the trim loops' parameter box, one root cell per knot
 * span, each cell split in u, in v or both until the surface strays from the bilinear patch through
 * its corners by less than [tolerance]. Cells wholly inside the trimmed region become two triangles
 * each; cells a trim crosses are intersected with the region (`java.awt.geom.Area`) and the pieces
 * handed to [FloatEarcut]. Every vertex is evaluated on the exact surface, so the mesh is inscribed
 * in it.
 *
 * The faces are then stitched by the Brep's topology. The reader samples each Brep edge once and
 * gives every face that uses it the same boundary points ([BrepFace.outerPoints]), so neighbours
 * meet vertex for vertex. Where a quadtree cell cuts a loop segment the new vertex is put on that
 * segment's 3D chord, and the face across the edge is split at it, found by the segment's two end
 * vertices rather than by distance. What is still open after that (faces without edge points, a
 * cell's hanging vertices) is split geometrically, at any open-edge vertex lying on the edge;
 * [lastStitch] counts both, and calls a geometric split at a boundary vertex a fallback. A closed
 * Brep comes out closed. Last, faces are welded where their vertices meet and their normals agree,
 * so a smooth seam shades smoothly and a crease stays sharp.
 *
 * @param tolerance the largest allowed chord deviation, in model units; `null` for a thousandth of
 *   the Brep's control-point bounding box diagonal.
 */
class BrepTessellator(val tolerance: Double? = null) {

    /** Every face in [faces] for which [include] holds, as one mesh with normals; `null` if none produced triangles. */
    fun meshFaces(faces: List<BrepFace>, include: (BrepFace) -> Boolean = { true }): TriMesh? {
        val chosen = faces.filter { it.surface != null && include(it) }
        if (chosen.isEmpty()) return null
        val diagonal = diagonal(chosen.mapNotNull { it.surface })
        val tol = tolerance ?: (diagonal * 1e-3)
        val positions = ArrayList<Double>()
        val normals = ArrayList<Double>()
        val indices = ArrayList<Int>()
        val boundary = HashSet<Int>()
        val crossings = ArrayList<Crossing>()
        val chains = ArrayList<DoubleArray>()
        for (face in chosen) face(face, tol, positions, normals, indices, boundary, crossings, chains)
        if (indices.isEmpty()) return null
        val stitched = stitch(positions, normals, indices, diagonal * 1e-6, 2 * tol, boundary, crossings, chains)
        lastStitch = stitched.second
        return stitched.first
    }

    /** What the last [meshFaces] call's [stitch] did, for measuring. */
    var lastStitch: StitchReport? = null
        private set

    /**
     * Counts from one [stitch]: open edges (used by one triangle) after welding by position alone,
     * before and after; [splits], edges split geometrically at a vertex found lying on them, of which
     * [fallbackSplits] were at a face-boundary vertex (a shared Brep edge the topology did not join;
     * 0 for a file read with edge points); and [topologicalSplits], edges split at a vertex the face
     * across a loop segment put on it.
     */
    data class StitchReport(
        val openEdgesBefore: Int, val openEdgesAfter: Int, val splits: Int, val passes: Int,
        val topologicalSplits: Int = 0, val fallbackSplits: Int = 0,
    )

    /** Input vertex [vertex], which a quadtree cell put on segment [k] (points `k` to `k + 1`) of loop [chain], at fraction [f]. */
    internal class Crossing(val vertex: Int, val chain: Int, val k: Int, val f: Double)

    /**
     * Makes neighbouring faces share their boundary samples. Vertices are first joined by position
     * alone (within [eps]); then every edge only one triangle uses, with another such edge's vertex
     * lying on it (within [reach], strictly between its ends), is split at that very vertex, its
     * triangle becoming a fan. Two faces that sampled their common Brep edge at different points
     * thereby both end up with the union of the points, and a quadtree's hanging vertices are closed
     * the same way. Finally the corners are welded by position and normal for shading.
     */
    internal fun stitch(
        positions: ArrayList<Double>, normals: ArrayList<Double>, indices: ArrayList<Int>, eps: Double, reach: Double,
        boundary: Set<Int> = emptySet(), crossings: List<Crossing> = emptyList(), chains: List<DoubleArray> = emptyList(),
    ): Pair<TriMesh, StitchReport> {
        val n = positions.size / 3
        // Position-only weld into topological vertices.
        val topo = IntArray(n)
        val tp = ArrayList<Double>()
        val grid = HashMap<Triple<Long, Long, Long>, MutableList<Int>>()
        val inv = 1.0 / max(eps, 1e-300)
        for (i in 0 until n) {
            val x = positions[3 * i]; val y = positions[3 * i + 1]; val z = positions[3 * i + 2]
            val kx = floor(x * inv).toLong(); val ky = floor(y * inv).toLong(); val kz = floor(z * inv).toLong()
            var found = -1
            search@ for (dx in -1L..1L) for (dy in -1L..1L) for (dz in -1L..1L) {
                for (o in grid[Triple(kx + dx, ky + dy, kz + dz)] ?: continue) {
                    val ox = tp[3 * o] - x; val oy = tp[3 * o + 1] - y; val oz = tp[3 * o + 2] - z
                    if (ox * ox + oy * oy + oz * oz <= eps * eps) { found = o; break@search }
                }
            }
            if (found < 0) {
                found = tp.size / 3
                tp.add(x); tp.add(y); tp.add(z)
                grid.getOrPut(Triple(kx, ky, kz)) { ArrayList() }.add(found)
            }
            topo[i] = found
        }
        // Triangles as corners: topological vertex and that corner's own normal.
        val tv = ArrayList<IntArray>()
        val tn = ArrayList<DoubleArray>()
        var t = 0
        while (t < indices.size) {
            val c = IntArray(3) { topo[indices[t + it]] }
            if (c[0] != c[1] && c[1] != c[2] && c[0] != c[2]) {
                tv.add(c)
                tn.add(DoubleArray(9) { normals[3 * indices[t + it / 3] + it % 3] })
            }
            t += 3
        }
        fun key(a: Int, b: Int) = (min(a, b).toLong() shl 32) or max(a, b).toLong()
        fun openEdges(): Map<Long, Int> {
            val count = HashMap<Long, Int>()
            for (c in tv) for (e in 0..2) count.merge(key(c[e], c[(e + 1) % 3]), 1, Int::plus)
            return count
        }
        val before = openEdges().count { it.value == 1 }
        // A loop point's topological vertex; a new one (on no triangle yet) when no face kept that point.
        fun lookupOrAdd(x: Double, y: Double, z: Double): Int {
            val kx = floor(x * inv).toLong(); val ky = floor(y * inv).toLong(); val kz = floor(z * inv).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) for (dz in -1L..1L) {
                for (o in grid[Triple(kx + dx, ky + dy, kz + dz)] ?: continue) {
                    val ox = tp[3 * o] - x; val oy = tp[3 * o + 1] - y; val oz = tp[3 * o + 2] - z
                    if (ox * ox + oy * oy + oz * oz <= eps * eps) return o
                }
            }
            val added = tp.size / 3
            tp.add(x); tp.add(y); tp.add(z)
            grid.getOrPut(Triple(kx, ky, kz)) { ArrayList() }.add(added)
            return added
        }
        // Replace triangle ti's edge e (corners a -> b) by a fan through `chain` (ending in b), with
        // normals interpolated at the fractions `at` along the edge.
        fun fan(ti: Int, e: Int, chainInner: List<Int>, at: List<Double>) {
            val c = tv[ti]
            val a = c[e]; val b = c[(e + 1) % 3]; val o = c[(e + 2) % 3]
            val nrm = tn[ti]
            val na = DoubleArray(3) { nrm[3 * e + it] }; val nb = DoubleArray(3) { nrm[3 * ((e + 1) % 3) + it] }
            val no = DoubleArray(3) { nrm[3 * ((e + 2) % 3) + it] }
            var prev = a; var prevN = na
            val chain = chainInner + b
            val chainN = at.map { sp -> DoubleArray(3) { na[it] + (nb[it] - na[it]) * sp } } + listOf(nb)
            for (k in chain.indices) {
                val tri = intArrayOf(prev, chain[k], o)
                val triN = DoubleArray(9).also { prevN.copyInto(it, 0); chainN[k].copyInto(it, 3); no.copyInto(it, 6) }
                if (k == 0) { tv[ti] = tri; tn[ti] = triN } else { tv.add(tri); tn.add(triN) }
                prev = chain[k]; prevN = chainN[k]
            }
        }
        // Topological pass. Each trim loop is a chain of edge points shared bit-for-bit with the face
        // across each edge; with the vertices cells put on its segments, from either side, each chain
        // is an ordered list of topological vertices. An open edge between two vertices of one chain
        // is split at every chain vertex between them.
        val chainTopo = chains.map { xyz -> IntArray(xyz.size / 3) { k -> lookupOrAdd(xyz[3 * k], xyz[3 * k + 1], xyz[3 * k + 2]) } }
        val segPoints = HashMap<Long, MutableList<Pair<Double, Int>>>()
        for (x in crossings) {
            val ct = chainTopo[x.chain]
            val ta = ct[x.k]; val tb = ct[(x.k + 1) % ct.size]
            val v = topo[x.vertex]
            if (ta == tb || v == ta || v == tb) continue
            segPoints.getOrPut(key(ta, tb)) { ArrayList() }.add((if (ta < tb) x.f else 1 - x.f) to v)
        }
        // chainPoints[c]: (parameter along the chain, vertex), sorted; where[v]: (chain, index) entries.
        val chainPoints = chainTopo.map { ct ->
            val out = ArrayList<Pair<Double, Int>>()
            for (k in ct.indices) {
                val ta = ct[k]; val tb = ct[(k + 1) % ct.size]
                out.add(k.toDouble() to ta)
                val on = segPoints[key(ta, tb)] ?: continue
                for ((f, v) in on) out.add(k + (if (ta < tb) f else 1 - f) to v)
            }
            out.sortedBy { it.first }
        }
        val where = HashMap<Int, MutableList<Pair<Int, Int>>>()
        chainPoints.forEachIndexed { c, pts -> pts.forEachIndexed { i, (_, v) -> where.getOrPut(v) { ArrayList() }.add(c to i) } }
        var topological = 0
        var tpasses = 0
        while (chains.isNotEmpty() && tpasses < 24) {
            tpasses++
            val count = openEdges()
            var changed = false
            val size = tv.size
            for (ti in 0 until size) {
                val c = tv[ti]
                for (e in 0..2) {
                    val a = c[e]; val b = c[(e + 1) % 3]
                    if (count[key(a, b)] != 1) continue
                    val wa = where[a] ?: continue; val wb = where[b] ?: continue
                    // The nearest pair of places along one chain (a seam puts a vertex on a chain twice).
                    var best: Triple<Int, Int, Int>? = null; var bestGap = Int.MAX_VALUE
                    for ((ca, ia) in wa) for ((cb, ib) in wb) {
                        if (ca != cb) continue
                        val n = chainPoints[ca].size
                        val d = abs(ia - ib); val gap = min(d, n - d)
                        if (gap in 2 until bestGap) { bestGap = gap; best = Triple(ca, ia, ib) }
                    }
                    val (ch, ia, ib) = best ?: continue
                    val pts = chainPoints[ch]; val n = pts.size
                    val forward = ((ib - ia + n) % n) <= ((ia - ib + n) % n)
                    val o = c[(e + 2) % 3]
                    val between = ArrayList<Int>()
                    var i = ia
                    while (true) {
                        i = if (forward) (i + 1) % n else (i - 1 + n) % n
                        if (i == ib) break
                        val v = pts[i].second
                        if (v != a && v != b && v != o && (between.isEmpty() || between.last() != v) && v !in between) between.add(v)
                    }
                    if (between.isEmpty()) continue
                    // Fractions along a -> b by projection, for the interpolated normals.
                    val ax = tp[3 * a]; val ay = tp[3 * a + 1]; val az = tp[3 * a + 2]
                    val ex = tp[3 * b] - ax; val ey = tp[3 * b + 1] - ay; val ez = tp[3 * b + 2] - az
                    val len2 = max(ex * ex + ey * ey + ez * ez, 1e-300)
                    val at = between.map { v -> ((tp[3 * v] - ax) * ex + (tp[3 * v + 1] - ay) * ey + (tp[3 * v + 2] - az) * ez) / len2 }
                    // Only along the edge: a diagonal that touches the loop at both ends is not a loop edge.
                    val r2 = reach * reach
                    val along = between.indices.all { j ->
                        val f = at[j]; val v = between[j]
                        val qx = tp[3 * v] - ax - f * ex; val qy = tp[3 * v + 1] - ay - f * ey; val qz = tp[3 * v + 2] - az - f * ez
                        f > 0 && f < 1 && (j == 0 || f > at[j - 1]) && qx * qx + qy * qy + qz * qz <= r2
                    }
                    if (!along) continue
                    fan(ti, e, between, at)
                    topological += between.size
                    changed = true
                    break
                }
            }
            if (!changed) break
        }
        val boundaryTopo = HashSet<Int>().also { set -> for (i in boundary) set.add(topo[i]) }
        var splits = 0
        var fallback = 0
        var passes = 0
        val cs = max(reach, 1e-300)
        while (passes < 24) {
            passes++
            val count = openEdges()
            val candidates = HashSet<Int>()
            for (c in tv) for (e in 0..2) if (count[key(c[e], c[(e + 1) % 3])] == 1) { candidates.add(c[e]); candidates.add(c[(e + 1) % 3]) }
            if (candidates.isEmpty()) break
            val near = HashMap<Triple<Long, Long, Long>, MutableList<Int>>()
            for (v in candidates) near.getOrPut(Triple(floor(tp[3 * v] / cs).toLong(), floor(tp[3 * v + 1] / cs).toLong(), floor(tp[3 * v + 2] / cs).toLong())) { ArrayList() }.add(v)
            var changed = false
            val size = tv.size
            for (ti in 0 until size) {
                val c = tv[ti]
                for (e in 0..2) {
                    val a = c[e]; val b = c[(e + 1) % 3]
                    if (count[key(a, b)] != 1) continue
                    val ax = tp[3 * a]; val ay = tp[3 * a + 1]; val az = tp[3 * a + 2]
                    val ex = tp[3 * b] - ax; val ey = tp[3 * b + 1] - ay; val ez = tp[3 * b + 2] - az
                    val len2 = ex * ex + ey * ey + ez * ez
                    if (len2 <= 0) continue
                    val r = min(reach, 0.25 * sqrt(len2))
                    val on = ArrayList<Pair<Double, Int>>()
                    // Walk the segment through the hash; r <= cs, so the 3x3x3 block around each step covers it.
                    val steps = (sqrt(len2) / cs).toInt() + 1
                    val seen = HashSet<Int>()
                    for (k in 0..steps) {
                        val f = k.toDouble() / steps
                        val gx = floor((ax + ex * f) / cs).toLong(); val gy = floor((ay + ey * f) / cs).toLong(); val gz = floor((az + ez * f) / cs).toLong()
                        for (dx in -1L..1L) for (dy in -1L..1L) for (dz in -1L..1L) {
                            for (v in near[Triple(gx + dx, gy + dy, gz + dz)] ?: continue) {
                                if (v == a || v == b || !seen.add(v)) continue
                                val px = tp[3 * v] - ax; val py = tp[3 * v + 1] - ay; val pz = tp[3 * v + 2] - az
                                val sParam = (px * ex + py * ey + pz * ez) / len2
                                if (sParam <= 1e-6 || sParam >= 1 - 1e-6) continue
                                val qx = px - sParam * ex; val qy = py - sParam * ey; val qz = pz - sParam * ez
                                if (qx * qx + qy * qy + qz * qz <= r * r) on.add(sParam to v)
                            }
                        }
                    }
                    if (on.isEmpty()) continue
                    on.sortBy { it.first }
                    val o = c[(e + 2) % 3]
                    if (on.any { it.second == o }) continue
                    fan(ti, e, on.map { it.second }, on.map { it.first })
                    splits += on.size
                    fallback += on.count { it.second in boundaryTopo }
                    changed = true
                    break // this triangle's other edges are handled next pass
                }
            }
            if (!changed) break
        }
        val after = openEdges().count { it.value == 1 }
        val cp = ArrayList<Double>(tv.size * 9); val cn = ArrayList<Double>(tv.size * 9)
        for (k in tv.indices) for (j in 0..2) {
            val v = tv[k][j]
            for (c in 0..2) { cp.add(tp[3 * v + c]); cn.add(tn[k][3 * j + c]) }
        }
        return weld(cp, cn, ArrayList((0 until tv.size * 3).toList()), eps) to StitchReport(before, after, splits, passes, topological, fallback)
    }

    private fun diagonal(surfaces: List<NurbsSurface>): Double {
        val lo = DoubleArray(3) { Double.MAX_VALUE }; val hi = DoubleArray(3) { -Double.MAX_VALUE }
        for (s in surfaces) for (i in 0 until s.countU * s.countV) {
            val w = s.cvs[i * 4 + 3]
            for (c in 0..2) { val x = s.cvs[i * 4 + c] / w; lo[c] = min(lo[c], x); hi[c] = max(hi[c], x) }
        }
        return sqrt((0..2).sumOf { (hi[it] - lo[it]).let { d -> d * d } }).takeIf { it > 0 } ?: 1.0
    }

    private fun face(
        face: BrepFace, tol: Double, positions: ArrayList<Double>, normals: ArrayList<Double>, indices: ArrayList<Int>,
        boundary: MutableSet<Int>, crossings: MutableList<Crossing>, chains: MutableList<DoubleArray>,
    ) {
        val s = face.surface!!
        val loops = face.outer + face.inner
        if (face.outer.isEmpty()) return
        // Each loop point's model-space position: the reader's edge point, else the surface's.
        val given = (face.outerPoints ?: List(face.outer.size) { null }) + (face.innerPoints ?: List(face.inner.size) { null })
        val loopXyz = loops.mapIndexed { li, l ->
            given.getOrNull(li)?.takeIf { it.size == l.size / 2 * 3 } ?: DoubleArray(l.size / 2 * 3).also { xyz ->
                val e = DoubleArray(6)
                val du0 = s.domainU; val dv0 = s.domainV
                for (k in 0 until l.size / 2) {
                    s.evaluate(min(max(l[2 * k], du0[0]), du0[1]), min(max(l[2 * k + 1], dv0[0]), dv0[1]), e)
                    for (c in 0..2) xyz[3 * k + c] = e[c]
                }
            }
        }
        // Only loops with the reader's edge points join by topology; the rest fall back to positions.
        val topological = face.outerPoints != null
        val chain0 = chains.size
        if (topological) chains.addAll(loopXyz)
        val du = s.domainU; val dv = s.domainV
        var u0 = Double.MAX_VALUE; var u1 = -Double.MAX_VALUE; var v0 = Double.MAX_VALUE; var v1 = -Double.MAX_VALUE
        for (l in loops) for (k in 0 until l.size / 2) {
            u0 = min(u0, l[2 * k]); u1 = max(u1, l[2 * k]); v0 = min(v0, l[2 * k + 1]); v1 = max(v1, l[2 * k + 1])
        }
        u0 = max(u0, du[0]); u1 = min(u1, du[1]); v0 = max(v0, dv[0]); v1 = min(v1, dv[1])
        if (u1 <= u0 || v1 <= v0) return

        val region = Path2D.Double(Path2D.WIND_EVEN_ODD)
        for (l in loops) {
            if (l.size < 6) continue
            region.moveTo(l[0], l[1])
            for (k in 1 until l.size / 2) region.lineTo(l[2 * k], l[2 * k + 1])
            region.closePath()
        }
        val area = Area(region)

        // Trim segments' parameter boxes, binned by u so a cell only looks at the segments near it.
        val segs = ArrayList<DoubleArray>()
        for (l in loops) {
            val n = l.size / 2
            for (k in 0 until n) {
                val a = k; val b = (k + 1) % n
                segs.add(doubleArrayOf(min(l[2 * a], l[2 * b]), max(l[2 * a], l[2 * b]), min(l[2 * a + 1], l[2 * b + 1]), max(l[2 * a + 1], l[2 * b + 1])))
            }
        }
        val bins = 64
        val binOf = Array(bins) { ArrayList<DoubleArray>() }
        fun bin(u: Double) = min(bins - 1, max(0, ((u - u0) / (u1 - u0) * bins).toInt()))
        for (g in segs) for (b in bin(g[0])..bin(g[1])) binOf[b].add(g)
        fun crossed(ua: Double, ub: Double, va: Double, vb: Double): Boolean {
            for (b in bin(ua)..bin(ub)) for (g in binOf[b]) if (g[1] >= ua && g[0] <= ub && g[3] >= va && g[2] <= vb) return true
            return false
        }

        val cells = quadtree(s, area, ::crossed, u0, u1, v0, v1, tol)

        val vertexOf = HashMap<Long, Int>()
        val keyScaleU = 1e9 / (u1 - u0); val keyScaleV = 1e9 / (v1 - v0)
        fun uvKey(u: Double, v: Double) = (Math.round((u - u0) * keyScaleU) shl 31) xor Math.round((v - v0) * keyScaleV)
        // Loop points by their uv, and loop segments binned by u, to place boundary vertices in 3D.
        val sampleAt = HashMap<Long, DoubleArray>()
        val segBins = Array(bins) { ArrayList<IntArray>() } // (loop, start point)
        loops.forEachIndexed { li, l ->
            val n = l.size / 2
            for (k in 0 until n) {
                sampleAt.putIfAbsent(uvKey(l[2 * k], l[2 * k + 1]), loopXyz[li].copyOfRange(3 * k, 3 * k + 3))
                val k2 = (k + 1) % n
                for (b in bin(min(l[2 * k], l[2 * k2]))..bin(max(l[2 * k], l[2 * k2]))) segBins[b].add(intArrayOf(li, k))
            }
        }
        val onSegment = 1e-9 * ((u1 - u0) + (v1 - v0))
        val eval = DoubleArray(6)
        fun vertex(u: Double, v: Double): Int {
            val key = uvKey(u, v)
            return vertexOf.getOrPut(key) {
                s.evaluate(min(max(u, du[0]), du[1]), min(max(v, dv[0]), dv[1]), eval)
                val sign = if (face.reversed) -1.0 else 1.0
                val len = sqrt(eval[3] * eval[3] + eval[4] * eval[4] + eval[5] * eval[5]).takeIf { it > 0 } ?: 1.0
                val index = positions.size / 3
                var at: DoubleArray? = sampleAt[key]
                if (at == null) {
                    // A cell's cut through a loop segment: on that segment's chord in model space.
                    for (seg in segBins[bin(u)]) {
                        val l = loops[seg[0]]; val n = l.size / 2; val k = seg[1]; val k2 = (k + 1) % n
                        val ax = l[2 * k]; val ay = l[2 * k + 1]; val ex = l[2 * k2] - ax; val ey = l[2 * k2 + 1] - ay
                        val len2 = ex * ex + ey * ey
                        if (len2 <= 0) continue
                        val f = ((u - ax) * ex + (v - ay) * ey) / len2
                        if (f <= 0 || f >= 1) continue
                        val qx = u - ax - f * ex; val qy = v - ay - f * ey
                        if (qx * qx + qy * qy > onSegment * onSegment) continue
                        val xyz = loopXyz[seg[0]]
                        val a = xyz.copyOfRange(3 * k, 3 * k + 3); val b = xyz.copyOfRange(3 * k2, 3 * k2 + 3)
                        if (topological) {
                            at = DoubleArray(3) { a[it] + (b[it] - a[it]) * f }
                            crossings.add(Crossing(index, chain0 + seg[0], k, f))
                        } else {
                            at = doubleArrayOf(eval[0], eval[1], eval[2])
                        }
                        break
                    }
                }
                if (at != null) boundary.add(index)
                for (c in 0..2) positions.add(at?.get(c) ?: eval[c])
                for (c in 3..5) normals.add(sign * eval[c] / len)
                index
            }
        }
        fun triangle(a: Int, b: Int, c: Int) {
            if (a == b || b == c || a == c) return
            val ax = positions[3 * a]; val ay = positions[3 * a + 1]; val az = positions[3 * a + 2]
            val ex = positions[3 * b] - ax; val ey = positions[3 * b + 1] - ay; val ez = positions[3 * b + 2] - az
            val fx = positions[3 * c] - ax; val fy = positions[3 * c + 1] - ay; val fz = positions[3 * c + 2] - az
            val nx = ey * fz - ez * fy; val ny = ez * fx - ex * fz; val nz = ex * fy - ey * fx
            if (nx * nx + ny * ny + nz * nz < 1e-30) return
            val dot = nx * (normals[3 * a] + normals[3 * b] + normals[3 * c]) +
                ny * (normals[3 * a + 1] + normals[3 * b + 1] + normals[3 * c + 1]) +
                nz * (normals[3 * a + 2] + normals[3 * b + 2] + normals[3 * c + 2])
            if (dot >= 0) { indices.add(a); indices.add(b); indices.add(c) } else { indices.add(a); indices.add(c); indices.add(b) }
        }

        for ((ua, ub, va, vb, cut) in cells) {
            if (!cut) {
                val p00 = vertex(ua, va); val p10 = vertex(ub, va); val p11 = vertex(ub, vb); val p01 = vertex(ua, vb)
                triangle(p00, p10, p11); triangle(p00, p11, p01)
                continue
            }
            val piece = Area(Rectangle2D.Double(ua, va, ub - ua, vb - va))
            piece.intersect(area)
            if (piece.isEmpty) continue
            for ((ring, holes) in rings(piece)) {
                val all = ArrayList<DoubleArray>().apply { add(ring); addAll(holes) }
                val flat = FloatArray(all.sumOf { it.size })
                val holeStarts = IntArray(holes.size)
                var o = 0
                // Earcut in cell-local coordinates: float precision relative to the cell, not the domain.
                val sx = 1.0 / (ub - ua); val sy = 1.0 / (vb - va)
                all.forEachIndexed { r, pts ->
                    if (r > 0) holeStarts[r - 1] = o / 2
                    for (k in 0 until pts.size / 2) {
                        flat[o++] = ((pts[2 * k] - ua) * sx).toFloat(); flat[o++] = ((pts[2 * k + 1] - va) * sy).toFloat()
                    }
                }
                val uvs = DoubleArray(flat.size)
                var q = 0
                for (pts in all) { pts.copyInto(uvs, q); q += pts.size }
                val tris = FloatEarcut.earcut(flat, if (holeStarts.isEmpty()) null else holeStarts, 2)
                var t = 0
                while (t + 2 < tris.size) {
                    val a = tris[t]; val b = tris[t + 1]; val c = tris[t + 2]
                    triangle(vertex(uvs[2 * a], uvs[2 * a + 1]), vertex(uvs[2 * b], uvs[2 * b + 1]), vertex(uvs[2 * c], uvs[2 * c + 1]))
                    t += 3
                }
            }
        }
    }

    /** One leaf of a face's parameter quadtree; [cut] when a trim segment's box touches it. */
    private data class Cell(val ua: Double, val ub: Double, val va: Double, val vb: Double, val cut: Boolean)

    /**
     * The leaves of an adaptive subdivision of `[u0, u1] x [v0, v1]`: one root per knot span, each
     * split in u, in v or both while the surface strays from the bilinear patch through the cell's
     * corners by more than [tol] in that direction. Cells wholly outside the trimmed region are
     * dropped without refining them. Neighbours of different sizes leave hanging vertices on the
     * larger cell's edge; [stitch] closes those along with the cracks between faces.
     */
    private fun quadtree(
        s: NurbsSurface, area: Area, crossed: (Double, Double, Double, Double) -> Boolean,
        u0: Double, u1: Double, v0: Double, v1: Double, tol: Double,
    ): List<Cell> {
        fun stops(b: DoubleArray, lo: Double, hi: Double) = (listOf(lo) + b.filter { it > lo && it < hi } + listOf(hi))
        val su = stops(s.breaksU(), u0, u1); val sv = stops(s.breaksV(), v0, v1)
        val out = ArrayList<Cell>()
        val c00 = DoubleArray(6); val c10 = DoubleArray(6); val c01 = DoubleArray(6); val c11 = DoubleArray(6); val m = DoubleArray(6)
        fun off(u: Double, v: Double, fu: Double, fv: Double): Double {
            s.evaluate(u, v, m)
            var d2 = 0.0
            for (c in 0..2) {
                val bl = (1 - fu) * (1 - fv) * c00[c] + fu * (1 - fv) * c10[c] + (1 - fu) * fv * c01[c] + fu * fv * c11[c]
                d2 += (m[c] - bl) * (m[c] - bl)
            }
            return sqrt(d2)
        }
        fun visit(ua: Double, ub: Double, va: Double, vb: Double, du: Int, dv: Int) {
            val cut = crossed(ua, ub, va, vb)
            if (!cut && !area.contains((ua + ub) / 2, (va + vb) / 2)) return
            s.evaluate(ua, va, c00); s.evaluate(ub, va, c10); s.evaluate(ua, vb, c01); s.evaluate(ub, vb, c11)
            val um = (ua + ub) / 2; val vm = (va + vb) / 2
            // Bending along u shows at the u-midpoints of the cell's v = const edges, and vice versa.
            val sagU = max(max(off(um, va, 0.5, 0.0), off(um, vb, 0.5, 1.0)), max(off(ua + (ub - ua) * 0.25, vm, 0.25, 0.5), off(ua + (ub - ua) * 0.75, vm, 0.75, 0.5)))
            val sagV = max(max(off(ua, vm, 0.0, 0.5), off(ub, vm, 1.0, 0.5)), max(off(um, va + (vb - va) * 0.25, 0.5, 0.25), off(um, va + (vb - va) * 0.75, 0.5, 0.75)))
            val twist = off(um, vm, 0.5, 0.5)
            var splitU = du < 12 && (sagU > tol || (du == 0 && s.degreeU > 1))
            var splitV = dv < 12 && (sagV > tol || (dv == 0 && s.degreeV > 1))
            if (!splitU && !splitV && twist > tol) { splitU = du < 12; splitV = dv < 12 }
            when {
                splitU && splitV -> {
                    visit(ua, um, va, vm, du + 1, dv + 1); visit(um, ub, va, vm, du + 1, dv + 1)
                    visit(ua, um, vm, vb, du + 1, dv + 1); visit(um, ub, vm, vb, du + 1, dv + 1)
                }
                splitU -> { visit(ua, um, va, vb, du + 1, dv); visit(um, ub, va, vb, du + 1, dv) }
                splitV -> { visit(ua, ub, va, vm, du, dv + 1); visit(ua, ub, vm, vb, du, dv + 1) }
                else -> out.add(Cell(ua, ub, va, vb, cut))
            }
        }
        for (i in 0 until su.size - 1) for (j in 0 until sv.size - 1) visit(su[i], su[i + 1], sv[j], sv[j + 1], 0, 0)
        return out
    }

    /** An Area's boundary as outer rings, each with the holes inside it. */
    private fun rings(area: Area): List<Pair<DoubleArray, List<DoubleArray>>> {
        val all = ArrayList<DoubleArray>()
        val it = area.getPathIterator(null)
        val c = DoubleArray(6)
        var current = ArrayList<Double>()
        while (!it.isDone) {
            when (it.currentSegment(c)) {
                PathIterator.SEG_MOVETO -> { if (current.size >= 6) all.add(current.toDoubleArray()); current = arrayListOf(c[0], c[1]) }
                PathIterator.SEG_LINETO -> { current.add(c[0]); current.add(c[1]) }
                PathIterator.SEG_CLOSE -> { if (current.size >= 6) all.add(current.toDoubleArray()); current = ArrayList() }
                else -> { current.add(c[0]); current.add(c[1]) } // Area of polygons yields only lines
            }
            it.next()
        }
        if (current.size >= 6) all.add(current.toDoubleArray())
        if (all.isEmpty()) return emptyList()
        // Area winds outer rings one way and holes the other; the largest ring is an outer one.
        val signed = all.map { signedArea2D(it) }
        val outerSign = Math.signum(signed[signed.indices.maxBy { abs(signed[it]) }])
        val outers = all.indices.filter { Math.signum(signed[it]) == outerSign }
        val holes = all.indices.filter { Math.signum(signed[it]) != outerSign && signed[it] != 0.0 }
        return outers.map { o ->
            val ring = all[o]
            ring to holes.filter { h -> pointInRing2D(ring, all[h][0], all[h][1]) }.map { all[it] }
        }
    }

    /**
     * Triangle corners `x y z nx ny nz`, three per triangle (the SubD form the wrapper hands over),
     * welded into an indexed mesh.
     */
    fun weldCorners(corners: DoubleArray): TriMesh? {
        val n = corners.size / 6
        if (n < 3) return null
        val positions = ArrayList<Double>(n * 3); val normals = ArrayList<Double>(n * 3)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (i in 0 until n) {
            for (c in 0..2) { positions.add(corners[6 * i + c]); lo = min(lo, corners[6 * i + c]); hi = max(hi, corners[6 * i + c]) }
            val l = sqrt((3..5).sumOf { corners[6 * i + it] * corners[6 * i + it] }).takeIf { it > 0 } ?: 1.0
            for (c in 3..5) normals.add(corners[6 * i + c] / l)
        }
        return weld(positions, normals, ArrayList((0 until n - n % 3).toList()), max(hi - lo, 1e-12) * 1e-6)
    }

    /** Merge vertices closer than [eps] whose normals agree; average the merged normals. */
    private fun weld(positions: ArrayList<Double>, normals: ArrayList<Double>, indices: ArrayList<Int>, eps: Double): TriMesh {
        val n = positions.size / 3
        val cellOf = HashMap<Triple<Long, Long, Long>, MutableList<Int>>()
        val remap = IntArray(n)
        val outP = ArrayList<Double>(); val outN = ArrayList<Double>()
        val inv = 1.0 / max(eps, 1e-300)
        for (i in 0 until n) {
            val x = positions[3 * i]; val y = positions[3 * i + 1]; val z = positions[3 * i + 2]
            val kx = floor(x * inv).toLong(); val ky = floor(y * inv).toLong(); val kz = floor(z * inv).toLong()
            var found = -1
            search@ for (dx in -1L..1L) for (dy in -1L..1L) for (dz in -1L..1L) {
                val list = cellOf[Triple(kx + dx, ky + dy, kz + dz)] ?: continue
                for (o in list) {
                    val ox = outP[3 * o] - x; val oy = outP[3 * o + 1] - y; val oz = outP[3 * o + 2] - z
                    if (ox * ox + oy * oy + oz * oz > eps * eps) continue
                    val dot = outN[3 * o] * normals[3 * i] + outN[3 * o + 1] * normals[3 * i + 1] + outN[3 * o + 2] * normals[3 * i + 2]
                    val len = sqrt(outN[3 * o] * outN[3 * o] + outN[3 * o + 1] * outN[3 * o + 1] + outN[3 * o + 2] * outN[3 * o + 2])
                    if (dot > 0.9 * len) { found = o; break@search }
                }
            }
            if (found < 0) {
                found = outP.size / 3
                outP.add(x); outP.add(y); outP.add(z)
                for (c in 0..2) outN.add(normals[3 * i + c])
                cellOf.getOrPut(Triple(kx, ky, kz)) { ArrayList() }.add(found)
            } else for (c in 0..2) outN[3 * found + c] += normals[3 * i + c]
            remap[i] = found
        }
        val normalArray = FloatArray(outN.size)
        for (v in 0 until outN.size / 3) {
            val l = sqrt(outN[3 * v] * outN[3 * v] + outN[3 * v + 1] * outN[3 * v + 1] + outN[3 * v + 2] * outN[3 * v + 2]).takeIf { it > 0 } ?: 1.0
            for (c in 0..2) normalArray[3 * v + c] = (outN[3 * v + c] / l).toFloat()
        }
        val tri = ArrayList<Int>(indices.size)
        var t = 0
        while (t < indices.size) {
            val a = remap[indices[t]]; val b = remap[indices[t + 1]]; val c = remap[indices[t + 2]]
            if (a != b && b != c && a != c) { tri.add(a); tri.add(b); tri.add(c) }
            t += 3
        }
        return TriMesh(FloatArray(outP.size) { outP[it].toFloat() }, tri.toIntArray(), normalArray)
    }
}

/** The FFM half for Breps: `field_opennurbs_brep.h`. */
internal object OpenNurbsBrepNative {

    private const val MESSAGE_CAPACITY = 2048
    private val linker: Linker = Linker.nativeLinker()

    private val lookup: SymbolLookup by lazy {
        val path = OpenNurbsLibrary.resolvedPath
            ?: throw UnsatisfiedLinkError("The openNURBS wrapper is not built. ${OpenNurbsLibrary.HOW_TO_BUILD}")
        SymbolLookup.libraryLookup(path, Arena.global())
    }

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("${OpenNurbsLibrary.fileName} has no symbol $name; rebuild it with ./gradlew buildOpenNurbsNative")
        }
        return linker.downcallHandle(symbol, descriptor)
    }

    /** Whether the loaded library has the Brep half (an older build does not). */
    val isAvailable: Boolean
        get() = OpenNurbsLibrary.isAvailable && runCatching { lookup.find("field_opennurbs_breps_open").isPresent }.getOrDefault(false)

    private val open by lazy { handle("field_opennurbs_breps_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)) }
    private val subdCount by lazy { handle("field_opennurbs_subds_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val subdId by lazy { handle("field_opennurbs_subds_id", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)) }
    private val subdLength by lazy { handle("field_opennurbs_subds_length", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) }
    private val subdData by lazy { handle("field_opennurbs_subds_data", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)) }
    private val close by lazy { handle("field_opennurbs_breps_close", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val count by lazy { handle("field_opennurbs_breps_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val id by lazy { handle("field_opennurbs_breps_id", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)) }
    private val length by lazy { handle("field_opennurbs_breps_length", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) }
    private val data by lazy { handle("field_opennurbs_breps_data", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)) }
    /**
     * What [read] returns: Brep faces and SubD triangle corners, each by object id, and how many
     * trims had no Brep edge to take their points from (their faces join by position, not topology).
     */
    class Contents(val breps: Map<String, List<BrepFace>>, val subds: Map<String, DoubleArray>, val untopologicalTrims: Int = 0)

    /**
     * Every top-level Brep (and extrusion) in [file], by object id, as its faces; and every SubD,
     * subdivided [subdLevel] times (negative: none), as limit-surface triangle corners.
     */
    fun read(file: File, subdLevel: Int): Contents = Arena.ofConfined().use { arena ->
        val message = arena.allocate(JAVA_BYTE, MESSAGE_CAPACITY.toLong())
        val out = arena.allocate(ADDRESS)
        val status = open.invokeExact(arena.allocateFrom(file.absolutePath), subdLevel, out, message, MESSAGE_CAPACITY) as Int
        if (status != 0) throw RhinoException("Could not read Breps from ${file.absolutePath} (status $status): ${message.getString(0)}")
        val h = out.get(ADDRESS, 0)
        try {
            fun packed(at: MemorySegment, len: Int): DoubleArray =
                if (len == 0 || at == MemorySegment.NULL) DoubleArray(0) else at.reinterpret(len * 8L).toArray(JAVA_DOUBLE)
            var untopological = 0
            val breps = (0 until count.invokeExact(h) as Int).associate { i ->
                val key = (id.invokeExact(h, i) as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)
                val faces = unpack(packed(data.invokeExact(h, i) as MemorySegment, length.invokeExact(h, i) as Int)) { untopological += it }
                key to faces
            }
            val subds = (0 until subdCount.invokeExact(h) as Int).associate { i ->
                val key = (subdId.invokeExact(h, i) as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)
                key to packed(subdData.invokeExact(h, i) as MemorySegment, subdLength.invokeExact(h, i) as Int)
            }
            Contents(breps, subds, untopological)
        } finally {
            close.invokeExact(h)
        }
    }

    /** The layout in `field_opennurbs_brep.h`; [untopological] receives the trailing count of trims without an edge. */
    internal fun unpack(d: DoubleArray, untopological: (Int) -> Unit = {}): List<BrepFace> {
        var p = 0
        fun next(): Double = d[p++]
        fun take(n: Int): DoubleArray = d.copyOfRange(p, p + n).also { p += n }
        val faces = next().toInt()
        return List(faces) {
            val hasMesh = next() != 0.0
            val reversed = next() != 0.0
            val du = next().toInt(); val dv = next().toInt(); val cu = next().toInt(); val cv = next().toInt()
            val surface = if (cu == 0 || cv == 0) null else {
                val ku = take(du + cu + 1); val kv = take(dv + cv + 1)
                NurbsSurface(du, dv, cu, cv, ku, kv, take(cu * cv * 4))
            }
            val loops = next().toInt()
            val outer = ArrayList<DoubleArray>(); val inner = ArrayList<DoubleArray>()
            val outerXyz = ArrayList<DoubleArray>(); val innerXyz = ArrayList<DoubleArray>()
            repeat(loops) {
                val type = next().toInt()
                val n = next().toInt()
                val packed = take(n * 5)
                val uv = DoubleArray(n * 2) { packed[5 * (it / 2) + it % 2] }
                val xyz = DoubleArray(n * 3) { packed[5 * (it / 3) + 2 + it % 3] }
                if (type == 2) { inner.add(uv); innerXyz.add(xyz) } else { outer.add(uv); outerXyz.add(xyz) }
            }
            BrepFace(surface, reversed, hasMesh, outer, inner, outerXyz, innerXyz)
        }.also { if (p < d.size) untopological(next().toInt()) }
    }
}
