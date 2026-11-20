package afield.geometry.rhino

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
 */
class BrepFace(
    val surface: NurbsSurface?,
    val reversed: Boolean,
    val hasSavedMesh: Boolean,
    val outer: List<DoubleArray>,
    val inner: List<DoubleArray>,
)

/**
 * Tessellates trimmed NURBS Brep faces into a [TriMesh].
 *
 * Per face: a tensor grid over the trim loops' parameter box, with a line at every knot and each
 * knot span split in half until the surface strays from its chord by less than [tolerance]
 * (checked along several lines across the face). Grid cells wholly inside the trimmed region
 * become two triangles each; cells a trim crosses are intersected with the region
 * (`java.awt.geom.Area`) and the pieces handed to [FloatEarcut]. Every vertex is evaluated on the
 * exact surface, so the mesh is inscribed in it. Faces are then welded where their vertices meet
 * within a millionth of the Brep's size and their normals agree, so a smooth seam shades smoothly
 * and a crease stays sharp.
 *
 * @param tolerance the largest allowed chord deviation, in model units; `null` for a thousandth of
 *   the Brep's control-point bounding box diagonal.
 */
class BrepTessellator(val tolerance: Double? = null) {

    /** Every face in [faces] for which [include] holds, as one mesh with normals; `null` if none produced triangles. */
    fun tessellate(faces: List<BrepFace>, include: (BrepFace) -> Boolean = { true }): TriMesh? {
        val chosen = faces.filter { it.surface != null && include(it) }
        if (chosen.isEmpty()) return null
        val diagonal = diagonal(chosen.mapNotNull { it.surface })
        val tol = tolerance ?: (diagonal * 1e-3)
        val positions = ArrayList<Double>()
        val normals = ArrayList<Double>()
        val indices = ArrayList<Int>()
        for (face in chosen) face(face, tol, positions, normals, indices)
        if (indices.isEmpty()) return null
        return weld(positions, normals, indices, diagonal * 1e-6)
    }

    private fun diagonal(surfaces: List<NurbsSurface>): Double {
        val lo = DoubleArray(3) { Double.MAX_VALUE }; val hi = DoubleArray(3) { -Double.MAX_VALUE }
        for (s in surfaces) for (i in 0 until s.countU * s.countV) {
            val w = s.cvs[i * 4 + 3]
            for (c in 0..2) { val x = s.cvs[i * 4 + c] / w; lo[c] = min(lo[c], x); hi[c] = max(hi[c], x) }
        }
        return sqrt((0..2).sumOf { (hi[it] - lo[it]).let { d -> d * d } }).takeIf { it > 0 } ?: 1.0
    }

    private fun face(face: BrepFace, tol: Double, positions: ArrayList<Double>, normals: ArrayList<Double>, indices: ArrayList<Int>) {
        val s = face.surface!!
        val loops = face.outer + face.inner
        if (face.outer.isEmpty()) return
        val du = s.domainU; val dv = s.domainV
        var u0 = Double.MAX_VALUE; var u1 = -Double.MAX_VALUE; var v0 = Double.MAX_VALUE; var v1 = -Double.MAX_VALUE
        for (l in loops) for (k in 0 until l.size / 2) {
            u0 = min(u0, l[2 * k]); u1 = max(u1, l[2 * k]); v0 = min(v0, l[2 * k + 1]); v1 = max(v1, l[2 * k + 1])
        }
        u0 = max(u0, du[0]); u1 = min(u1, du[1]); v0 = max(v0, dv[0]); v1 = min(v1, dv[1])
        if (u1 <= u0 || v1 <= v0) return

        val gu = gridLines(s, true, u0, u1, v0, v1, tol)
        val gv = gridLines(s, false, v0, v1, u0, u1, tol)

        val region = Path2D.Double(Path2D.WIND_EVEN_ODD)
        for (l in loops) {
            if (l.size < 6) continue
            region.moveTo(l[0], l[1])
            for (k in 1 until l.size / 2) region.lineTo(l[2 * k], l[2 * k + 1])
            region.closePath()
        }
        val area = Area(region)

        // Which cells a trim segment's bounding box touches; the rest are wholly in or out.
        val nu = gu.size - 1; val nv = gv.size - 1
        val crossed = BooleanArray(nu * nv)
        for (l in loops) {
            val n = l.size / 2
            for (k in 0 until n) {
                val a = k; val b = (k + 1) % n
                val iu0 = cell(gu, min(l[2 * a], l[2 * b])); val iu1 = cell(gu, max(l[2 * a], l[2 * b]))
                val iv0 = cell(gv, min(l[2 * a + 1], l[2 * b + 1])); val iv1 = cell(gv, max(l[2 * a + 1], l[2 * b + 1]))
                for (i in iu0..iu1) for (j in iv0..iv1) crossed[i * nv + j] = true
            }
        }

        val vertexOf = HashMap<Long, Int>()
        val keyScaleU = 1e9 / (u1 - u0); val keyScaleV = 1e9 / (v1 - v0)
        val eval = DoubleArray(6)
        fun vertex(u: Double, v: Double): Int {
            val key = (Math.round((u - u0) * keyScaleU) shl 31) xor Math.round((v - v0) * keyScaleV)
            return vertexOf.getOrPut(key) {
                s.evaluate(min(max(u, du[0]), du[1]), min(max(v, dv[0]), dv[1]), eval)
                val sign = if (face.reversed) -1.0 else 1.0
                val len = sqrt(eval[3] * eval[3] + eval[4] * eval[4] + eval[5] * eval[5]).takeIf { it > 0 } ?: 1.0
                val index = positions.size / 3
                for (c in 0..2) positions.add(eval[c])
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

        for (i in 0 until nu) for (j in 0 until nv) {
            val ua = gu[i]; val ub = gu[i + 1]; val va = gv[j]; val vb = gv[j + 1]
            if (!crossed[i * nv + j]) {
                if (!area.contains((ua + ub) / 2, (va + vb) / 2)) continue
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

    private fun cell(grid: DoubleArray, x: Double): Int {
        var lo = 0; var hi = grid.size - 1
        if (x <= grid[0]) return 0
        if (x >= grid[hi]) return hi - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (x < grid[m]) hi = m else lo = m }
        return lo
    }

    /** Grid lines in one direction: every knot, then each span halved until its sag is under [tol]. */
    private fun gridLines(s: NurbsSurface, alongU: Boolean, a: Double, b: Double, c0: Double, c1: Double, tol: Double): DoubleArray {
        val knots = (if (alongU) s.breaksU() else s.breaksV()).filter { it > a && it < b }
        val stops = (listOf(a) + knots + listOf(b))
        val across = DoubleArray(7) { c0 + (c1 - c0) * it / 6.0 }
        val degree = if (alongU) s.degreeU else s.degreeV
        val out = ArrayList<Double>()
        val p = DoubleArray(6); val q = DoubleArray(6); val m = DoubleArray(6)
        fun at(t: Double, c: Double, into: DoubleArray) = if (alongU) s.evaluate(t, c, into) else s.evaluate(c, t, into)
        fun sag(t0: Double, t1: Double): Double {
            var worst = 0.0
            for (c in across) {
                at(t0, c, p); at(t1, c, q)
                for (f in doubleArrayOf(0.25, 0.5, 0.75)) {
                    at(t0 + (t1 - t0) * f, c, m)
                    val x = m[0] - (p[0] + (q[0] - p[0]) * f)
                    val y = m[1] - (p[1] + (q[1] - p[1]) * f)
                    val z = m[2] - (p[2] + (q[2] - p[2]) * f)
                    worst = max(worst, sqrt(x * x + y * y + z * z))
                }
            }
            return worst
        }
        fun split(t0: Double, t1: Double, depth: Int) {
            if (depth < 8 && (sag(t0, t1) > tol || (depth == 0 && degree > 1))) {
                val mid = (t0 + t1) / 2
                split(t0, mid, depth + 1); split(mid, t1, depth + 1)
            } else out.add(t0)
        }
        for (k in 0 until stops.size - 1) split(stops[k], stops[k + 1], 0)
        out.add(b)
        return out.toDoubleArray()
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
        val signed = all.map { signedArea(it) }
        val outerSign = Math.signum(signed[signed.indices.maxBy { abs(signed[it]) }])
        val outers = all.indices.filter { Math.signum(signed[it]) == outerSign }
        val holes = all.indices.filter { Math.signum(signed[it]) != outerSign && signed[it] != 0.0 }
        return outers.map { o ->
            val ring = all[o]
            ring to holes.filter { h -> contains(ring, all[h][0], all[h][1]) }.map { all[it] }
        }
    }

    private fun signedArea(r: DoubleArray): Double {
        var a = 0.0
        val n = r.size / 2
        for (k in 0 until n) { val l = (k + 1) % n; a += r[2 * k] * r[2 * l + 1] - r[2 * l] * r[2 * k + 1] }
        return a / 2
    }

    private fun contains(r: DoubleArray, x: Double, y: Double): Boolean {
        var inside = false
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = r[2 * i]; val yi = r[2 * i + 1]; val xj = r[2 * j]; val yj = r[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
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
    private val writeFixture by lazy { handle("field_opennurbs_write_brep_fixture", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)) }

    fun writeFixture(file: File) = Arena.ofConfined().use { arena ->
        val message = arena.allocate(JAVA_BYTE, MESSAGE_CAPACITY.toLong())
        val status = writeFixture.invokeExact(arena.allocateFrom(file.absolutePath), message, MESSAGE_CAPACITY) as Int
        if (status != 0) throw RhinoException("openNURBS could not write ${file.absolutePath}: ${message.getString(0)}")
    }

    /** What [read] returns: Brep faces and SubD triangle corners, each by object id. */
    class Contents(val breps: Map<String, List<BrepFace>>, val subds: Map<String, DoubleArray>)

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
            val breps = (0 until count.invokeExact(h) as Int).associate { i ->
                val key = (id.invokeExact(h, i) as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)
                key to unpack(packed(data.invokeExact(h, i) as MemorySegment, length.invokeExact(h, i) as Int))
            }
            val subds = (0 until subdCount.invokeExact(h) as Int).associate { i ->
                val key = (subdId.invokeExact(h, i) as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)
                key to packed(subdData.invokeExact(h, i) as MemorySegment, subdLength.invokeExact(h, i) as Int)
            }
            Contents(breps, subds)
        } finally {
            close.invokeExact(h)
        }
    }

    /** The layout in `field_opennurbs_brep.h`. */
    internal fun unpack(d: DoubleArray): List<BrepFace> {
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
            repeat(loops) {
                val type = next().toInt()
                val points = take(next().toInt() * 2)
                if (type == 2) inner.add(points) else outer.add(points)
            }
            BrepFace(surface, reversed, hasMesh, outer, inner)
        }
    }
}
