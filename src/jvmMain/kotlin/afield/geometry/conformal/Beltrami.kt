@file:afield.runtime.completion.CompletionCategory("Math")

/**
 * Quasi-conformal maps of planar triangle meshes: the Beltrami coefficient, the linear Beltrami solver
 * and the quasi-conformal iteration that finds a Teichmüller map.
 *
 * A map `f` of the plane carries an infinitesimal circle to an infinitesimal ellipse. The Beltrami
 * coefficient `μ = f_z̄ / f_z` says which ellipse: `|μ|` sets its shape (axis ratio
 * `K = (1 + |μ|) / (1 − |μ|)`) and `arg μ / 2` the direction of its short axis before the map. A
 * conformal map has `μ = 0` everywhere. On a [PlanarMesh] a map is a target position per vertex, and
 * [beltrami] gives one `μ` per triangle.
 *
 * Going the other way, from `μ` to a map, is the linear Beltrami solver of Lui, Wong, Thompson, Chan,
 * Gu and Yau ("Shape-based diffeomorphic registration on hippocampal surfaces using Beltrami
 * holomorphic flow", 2010; the solver as written here follows Lui, Lam, Yau and Gu, "Teichmüller
 * mapping (T-map) and its applications to landmark matching registration", SIAM J. Imaging Sciences
 * 7(1), 2014). Each coordinate of `f = u + iv` solves `∇·(A ∇u) = 0`, an elliptic equation whose
 * matrix `A` comes from `μ`; [LinearBeltramiSolver] assembles it with linear finite elements and
 * solves it with the chosen vertices held fixed.
 *
 * A Teichmüller map is the map with the least worst-case distortion among all maps that meet the
 * constraints, and its `|μ|` is the same everywhere. [teichmullerMap] finds one by the
 * quasi-conformal iteration of the T-map paper: solve, measure `μ`, smooth it, make its modulus the
 * mean modulus, solve again. The Teichmüller distance between two shapes is `½ log K` of that map,
 * which Choi and Mahadevan use to compare leaf and wing outlines ("Planar
 * morphometrics using Teichmüller maps", Proc. R. Soc. A 474, 2018).
 *
 *     import afield.geometry.conformal.*
 *
 *     val mesh = PlanarMesh.grid(16)
 *     val fixed = mesh.boundaryIdentity() + (mesh.nearest(0.35, 0.5) to doubleArrayOf(0.6, 0.55))
 *     val t = teichmullerMap(mesh, fixed)
 *     println("K = ${t.dilatation}, distance ${t.distance}")
 *
 * When the outline may slide between landmarks, four landmarks make a region a conformal
 * quadrilateral, which [RectangleMap] sends onto a rectangle; the Teichmüller map between two such
 * regions is a stretch between their rectangles, and the distance is exact
 * ([quadrilateralTeichmullerMap]). [MirrorShapeComparison] uses this for mirror-symmetric shapes
 * such as leaves, with tip, base and two pairs of margin landmarks. [PlanarMesh.outline] meshes any
 * simple outline.
 *
 * Coordinates are plain `DoubleArray`s (`x`, `y`, `u`, `v`), and `μ` is stored as interleaved real
 * and imaginary parts, one pair per triangle. [MeshPicture] draws a mesh and its `|μ|` into ARGB
 * pixels for a box output.
 */

package afield.geometry.conformal

import afield.geometry.mesh.TriMesh
import afield.geometry.mesh.topology

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A triangle mesh of a region of the plane.
 *
 * @param x vertex x coordinates.
 * @param y vertex y coordinates.
 * @param triangles three vertex indices per triangle, counter-clockwise.
 * @param boundary the boundary vertices in order round the outline, counter-clockwise.
 */
class PlanarMesh(val x: DoubleArray, val y: DoubleArray, val triangles: IntArray, val boundary: IntArray) {
    /** The number of vertices. */
    val vertexCount get() = x.size

    /** The number of triangles. */
    val triangleCount get() = triangles.size / 3

    /** The area of each triangle. */
    val areas: DoubleArray = DoubleArray(triangleCount) { t ->
        val a = triangles[3 * t]
        val b = triangles[3 * t + 1]
        val c = triangles[3 * t + 2]
        0.5 * abs((x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a]))
    }

    /** The vertex closest to `(px, py)`. */
    fun nearest(px: Double, py: Double): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in 0 until vertexCount) {
            val d = (x[i] - px) * (x[i] - px) + (y[i] - py) * (y[i] - py)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /**
     * A copy with each interior vertex moved, [passes] times, to the mean of its neighbours, which
     * evens out thin triangles. A move that would turn a triangle over is skipped.
     */
    fun smoothed(passes: Int = 8): PlanarMesh {
        val nx = x.copyOf()
        val ny = y.copyOf()
        val onBoundary = BooleanArray(vertexCount).also { b -> boundary.forEach { b[it] = true } }
        val neighbours = Array(vertexCount) { HashSet<Int>() }
        val incident = Array(vertexCount) { ArrayList<Int>() }
        for (t in 0 until triangleCount) for (k in 0..2) {
            val a = triangles[3 * t + k]
            neighbours[a].add(triangles[3 * t + (k + 1) % 3])
            neighbours[a].add(triangles[3 * t + (k + 2) % 3])
            incident[a].add(t)
        }
        fun positive(t: Int): Boolean {
            val a = triangles[3 * t]
            val b = triangles[3 * t + 1]
            val c = triangles[3 * t + 2]
            return (nx[b] - nx[a]) * (ny[c] - ny[a]) - (nx[c] - nx[a]) * (ny[b] - ny[a]) > 0
        }
        repeat(passes) {
            for (i in 0 until vertexCount) {
                if (onBoundary[i] || neighbours[i].isEmpty()) continue
                val ox = nx[i]
                val oy = ny[i]
                nx[i] = neighbours[i].sumOf { nx[it] } / neighbours[i].size
                ny[i] = neighbours[i].sumOf { ny[it] } / neighbours[i].size
                if (incident[i].any { !positive(it) }) {
                    nx[i] = ox
                    ny[i] = oy
                }
            }
        }
        return PlanarMesh(nx, ny, triangles, boundary)
    }

    /** The boundary vertex closest to `(px, py)`. */
    fun nearestBoundary(px: Double, py: Double): Int = boundary.minBy { (x[it] - px) * (x[it] - px) + (y[it] - py) * (y[it] - py) }

    /** Constraints that hold every boundary vertex where it is. */
    fun boundaryIdentity(): Map<Int, DoubleArray> = boundary.associateWith { doubleArrayOf(x[it], y[it]) }

    companion object {
        /** A square `[x0, x0 + size] × [y0, y0 + size]` cut into `n × n` cells, each split in two along alternating diagonals. */
        fun grid(n: Int, x0: Double = 0.0, y0: Double = 0.0, size: Double = 1.0): PlanarMesh {
            val m = n + 1
            val xs = DoubleArray(m * m) { x0 + size * (it % m) / n }
            val ys = DoubleArray(m * m) { y0 + size * (it / m) / n }
            val tris = ArrayList<Int>()
            for (j in 0 until n) for (i in 0 until n) {
                val a = j * m + i
                val b = a + 1
                val c = a + m + 1
                val d = a + m
                if ((i + j) % 2 == 0) tris.addAll(listOf(a, b, c, a, c, d)) else tris.addAll(listOf(a, b, d, b, c, d))
            }
            val loop = ArrayList<Int>()
            for (i in 0 until n) loop.add(i)
            for (j in 0 until n) loop.add(j * m + n)
            for (i in n downTo 1) loop.add(n * m + i)
            for (j in n downTo 1) loop.add(j * m)
            return PlanarMesh(xs, ys, tris.toIntArray(), loop.toIntArray())
        }

        /**
         * The inside of any simple closed outline, given as polygon vertices [outlineX], [outlineY]
         * (either direction; the closing edge is implied), triangulated with spacing about [h].
         * The outline is first resampled evenly at spacing about [h]. This uses the drum-head mesher `afield.audio.modal.drumMesh`: the outline is cut into
         * edges of length about [h], the inside is filled with a triangular lattice, and the
         * points are Delaunay-triangulated. [smoothing] passes of [smoothed] then even out the
         * triangles next to the outline. [boundary] runs counter-clockwise round the outline,
         * starting at the first outline vertex.
         */
        fun outline(outlineX: DoubleArray, outlineY: DoubleArray, h: Double, smoothing: Int = 8): PlanarMesh {
            val n = outlineX.size
            val signed = (0 until n).sumOf { outlineX[it] * outlineY[(it + 1) % n] - outlineX[(it + 1) % n] * outlineY[it] }
            val order = if (signed >= 0) (0 until n).toList() else listOf(0) + (n - 1 downTo 1)
            // Resample the outline evenly at spacing about h, so the boundary is as fine as the inside.
            val arc = DoubleArray(n + 1)
            for (k in 1..n) {
                val p = order[k - 1]
                val q = order[k % n]
                arc[k] = arc[k - 1] + hypot(outlineX[q] - outlineX[p], outlineY[q] - outlineY[p])
            }
            val count = max(8, (arc[n] / h).roundToInt())
            val xy = DoubleArray(2 * count)
            var seg = 0
            for (k in 0 until count) {
                val want = arc[n] * k / count
                while (seg < n - 1 && arc[seg + 1] < want) seg++
                val f = ((want - arc[seg]) / (arc[seg + 1] - arc[seg]).coerceAtLeast(1e-300)).coerceIn(0.0, 1.0)
                val p = order[seg]
                val q = order[(seg + 1) % n]
                xy[2 * k] = outlineX[p] + f * (outlineX[q] - outlineX[p])
                xy[2 * k + 1] = outlineY[p] + f * (outlineY[q] - outlineY[p])
            }
            val drum = afield.audio.modal.drumMesh(xy, h)
            val loop = (0 until drum.nodeCount).filter { drum.boundary[it] }.toIntArray()
            return PlanarMesh(drum.x, drum.y, drum.tris, loop).smoothed(passes = smoothing)
        }
    }
}

/**
 * The Beltrami coefficient of the piecewise-linear map that sends each vertex `i` of [mesh] to
 * `(u[i], v[i])`: one complex number per triangle, real and imaginary parts interleaved. A triangle
 * the map turns over has `|μ| > 1`.
 */
fun beltrami(mesh: PlanarMesh, u: DoubleArray, v: DoubleArray): DoubleArray {
    val mu = DoubleArray(2 * mesh.triangleCount)
    val g = DoubleArray(6)
    for (t in 0 until mesh.triangleCount) {
        gradients(mesh, t, g)
        var ux = 0.0
        var uy = 0.0
        var vx = 0.0
        var vy = 0.0
        for (k in 0..2) {
            val i = mesh.triangles[3 * t + k]
            ux += u[i] * g[2 * k]
            uy += u[i] * g[2 * k + 1]
            vx += v[i] * g[2 * k]
            vy += v[i] * g[2 * k + 1]
        }
        // f_z = ((ux + vy) + i(vx − uy)) / 2, f_z̄ = ((ux − vy) + i(vx + uy)) / 2.
        val zr = 0.5 * (ux + vy)
        val zi = 0.5 * (vx - uy)
        val br = 0.5 * (ux - vy)
        val bi = 0.5 * (vx + uy)
        val d = zr * zr + zi * zi
        if (d < 1e-300) {
            mu[2 * t] = 1.0
            mu[2 * t + 1] = 0.0
        } else {
            mu[2 * t] = (br * zr + bi * zi) / d
            mu[2 * t + 1] = (bi * zr - br * zi) / d
        }
    }
    return mu
}

/** `|μ|` of each triangle, from interleaved real and imaginary parts. */
fun modulus(mu: DoubleArray) = DoubleArray(mu.size / 2) { hypot(mu[2 * it], mu[2 * it + 1]) }

/** The largest axis ratio of an infinitesimal ellipse under a map whose Beltrami coefficient has modulus [k]: `(1 + k) / (1 − k)`. */
fun dilatation(k: Double) = (1 + k) / (1 - k)

/** The Teichmüller distance `½ log K` for a map of uniform `|μ| = k`. */
fun teichmullerDistance(k: Double) = 0.5 * ln(dilatation(k))

/** The gradients of the three hat functions of triangle [t], `(gx0, gy0, gx1, gy1, gx2, gy2)`. */
private fun gradients(mesh: PlanarMesh, t: Int, out: DoubleArray) {
    val x = mesh.x
    val y = mesh.y
    val a = mesh.triangles[3 * t]
    val b = mesh.triangles[3 * t + 1]
    val c = mesh.triangles[3 * t + 2]
    val area2 = (x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a])
    out[0] = (y[b] - y[c]) / area2
    out[1] = (x[c] - x[b]) / area2
    out[2] = (y[c] - y[a]) / area2
    out[3] = (x[a] - x[c]) / area2
    out[4] = (y[a] - y[b]) / area2
    out[5] = (x[b] - x[a]) / area2
}

/**
 * Builds a map from its Beltrami coefficient: the linear Beltrami solver.
 *
 * Writing `μ = ρ + iτ`, both coordinates of `f = u + iv` satisfy `∇·(A ∇u) = 0` with
 * `A = [[(ρ−1)² + τ², −2τ], [−2τ, (1+ρ)² + τ²]] / (1 − ρ² − τ²)`, symmetric and positive definite
 * while `|μ| < 1`. This class assembles that equation with linear elements on [mesh] (the sparsity
 * pattern is built once) and solves it by Jacobi-preconditioned conjugate gradients. With `μ = 0`
 * the matrix is the cotangent Laplacian and the result is the harmonic map with the given
 * constraints.
 */
class LinearBeltramiSolver(val mesh: PlanarMesh) {
    private val rowStart: IntArray
    private val cols: IntArray
    private val slots: IntArray // 9 per triangle: where K[i][j] lives
    private val values: DoubleArray

    init {
        val neighbours = Array(mesh.vertexCount) { sortedSetOf(it) }
        for (t in 0 until mesh.triangleCount) for (p in 0..2) for (q in 0..2)
            neighbours[mesh.triangles[3 * t + p]].add(mesh.triangles[3 * t + q])
        rowStart = IntArray(mesh.vertexCount + 1)
        for (i in 0 until mesh.vertexCount) rowStart[i + 1] = rowStart[i] + neighbours[i].size
        cols = IntArray(rowStart.last())
        for (i in 0 until mesh.vertexCount) neighbours[i].forEachIndexed { k, j -> cols[rowStart[i] + k] = j }
        slots = IntArray(9 * mesh.triangleCount)
        for (t in 0 until mesh.triangleCount) for (p in 0..2) for (q in 0..2) {
            val i = mesh.triangles[3 * t + p]
            val j = mesh.triangles[3 * t + q]
            slots[9 * t + 3 * p + q] = cols.binarySearch(j, rowStart[i], rowStart[i + 1])
        }
        values = DoubleArray(cols.size)
    }

    /**
     * Solves for the map with Beltrami coefficient [mu] (interleaved, one pair per triangle) that
     * sends each vertex in [fixed] to its given `(u, v)`. [start], if given, is a starting guess
     * `(u, v)`. Moduli are clamped to [maxModulus] so the equation stays elliptic.
     */
    fun solve(mu: DoubleArray, fixed: Map<Int, DoubleArray>, start: Pair<DoubleArray, DoubleArray>? = null, maxModulus: Double = 0.95): Pair<DoubleArray, DoubleArray> {
        assemble(mu, maxModulus)
        val isFixed = BooleanArray(mesh.vertexCount)
        fixed.keys.forEach { isFixed[it] = true }
        val u = start?.first?.copyOf() ?: mesh.x.copyOf()
        val v = start?.second?.copyOf() ?: mesh.y.copyOf()
        for ((i, p) in fixed) {
            u[i] = p[0]
            v[i] = p[1]
        }
        conjugateGradient(u, isFixed)
        conjugateGradient(v, isFixed)
        return u to v
    }

    /** The energy `xᵀ K x` of [x] under the system last assembled; for `μ = 0`, the Dirichlet energy. */
    fun energy(x: DoubleArray): Double {
        val kx = DoubleArray(x.size)
        multiply(x, kx)
        return x.indices.sumOf { x[it] * kx[it] }
    }

    /** Assembles the system for Beltrami coefficient [mu], moduli clamped to [maxModulus]. [solve] does this itself. */
    fun assemble(mu: DoubleArray, maxModulus: Double = 0.95) {
        values.fill(0.0)
        val g = DoubleArray(6)
        for (t in 0 until mesh.triangleCount) {
            var rho = mu[2 * t]
            var tau = mu[2 * t + 1]
            val m = hypot(rho, tau)
            if (m > maxModulus) {
                rho *= maxModulus / m
                tau *= maxModulus / m
            }
            val s = 1 - rho * rho - tau * tau
            val a11 = ((rho - 1) * (rho - 1) + tau * tau) / s
            val a12 = -2 * tau / s
            val a22 = ((1 + rho) * (1 + rho) + tau * tau) / s
            gradients(mesh, t, g)
            val area = mesh.areas[t]
            for (p in 0..2) for (q in 0..2) {
                val gx = g[2 * p]
                val gy = g[2 * p + 1]
                val hx = g[2 * q]
                val hy = g[2 * q + 1]
                values[slots[9 * t + 3 * p + q]] += area * (gx * (a11 * hx + a12 * hy) + gy * (a12 * hx + a22 * hy))
            }
        }
    }

    private fun multiply(x: DoubleArray, out: DoubleArray) {
        for (i in out.indices) {
            var s = 0.0
            for (k in rowStart[i] until rowStart[i + 1]) s += values[k] * x[cols[k]]
            out[i] = s
        }
    }

    private fun conjugateGradient(x: DoubleArray, isFixed: BooleanArray, tolerance: Double = 1e-11, maxIterations: Int = 4000) {
        val n = x.size
        val diag = DoubleArray(n) { i -> (rowStart[i] until rowStart[i + 1]).first { cols[it] == i }.let { values[it] } }
        val r = DoubleArray(n)
        multiply(x, r)
        for (i in 0 until n) r[i] = if (isFixed[i]) 0.0 else -r[i]
        val z = DoubleArray(n) { if (isFixed[it]) 0.0 else r[it] / diag[it] }
        val p = z.copyOf()
        val q = DoubleArray(n)
        var rz = (0 until n).sumOf { r[it] * z[it] }
        val r0 = sqrt((0 until n).sumOf { r[it] * r[it] }).coerceAtLeast(1e-300)
        for (iteration in 0 until maxIterations) {
            if (sqrt((0 until n).sumOf { r[it] * r[it] }) <= tolerance * max(1.0, r0)) break
            multiply(p, q)
            for (i in 0 until n) if (isFixed[i]) q[i] = 0.0
            val pq = (0 until n).sumOf { p[it] * q[it] }
            if (pq <= 0) break
            val alpha = rz / pq
            for (i in 0 until n) {
                x[i] += alpha * p[i]
                r[i] -= alpha * q[i]
                z[i] = if (isFixed[i]) 0.0 else r[i] / diag[i]
            }
            val rzNew = (0 until n).sumOf { r[it] * z[it] }
            val beta = rzNew / rz
            rz = rzNew
            for (i in 0 until n) p[i] = z[i] + beta * p[i]
        }
    }
}

/**
 * A map found by [teichmullerMap] or [harmonicMap].
 *
 * @param u target x of each vertex.
 * @param v target y of each vertex.
 * @param mu the Beltrami coefficient of the map, interleaved, one pair per triangle.
 * @param iterations how many quasi-conformal iterations ran.
 */
class QuasiConformalMap(val mesh: PlanarMesh, val u: DoubleArray, val v: DoubleArray, val mu: DoubleArray, val iterations: Int) {
    /** `|μ|` per triangle. */
    val moduli = modulus(mu)

    /** The area-weighted mean of `|μ|`. */
    val meanModulus = moduli.indices.sumOf { moduli[it] * mesh.areas[it] } / mesh.areas.sum()

    /** The area-weighted median of `|μ|`: half the region distorts less, half more. */
    val medianModulus: Double = run {
        val order = moduli.indices.sortedBy { moduli[it] }
        val half = mesh.areas.sum() / 2
        var acc = 0.0
        order.firstOrNull { acc += mesh.areas[it]; acc >= half }?.let { moduli[it] } ?: 0.0
    }

    /** The fraction of the region's area where `|μ|` is within [tolerance] of [k]. */
    fun areaNear(k: Double, tolerance: Double) = moduli.indices.filter { abs(moduli[it] - k) <= tolerance }.sumOf { mesh.areas[it] } / mesh.areas.sum()

    /** The largest `|μ|`. */
    val maxModulus = moduli.max()

    /** The smallest `|μ|`. */
    val minModulus = moduli.min()

    /** The number of triangles the map turns over (`|μ| ≥ 1`); zero for a bijective map. */
    val flipped = moduli.count { it >= 1 }

    /** `K` of the mean modulus: for a Teichmüller map, the dilatation it has everywhere. */
    val dilatation get() = dilatation(min(meanModulus, 0.999))

    /** The Teichmüller distance `½ log K` of the mean modulus. */
    val distance get() = teichmullerDistance(min(meanModulus, 0.999))
}

/** The harmonic map with the given constraints: the linear Beltrami solver with `μ = 0`. */
fun harmonicMap(mesh: PlanarMesh, fixed: Map<Int, DoubleArray>, solver: LinearBeltramiSolver = LinearBeltramiSolver(mesh)): QuasiConformalMap {
    val (u, v) = solver.solve(DoubleArray(2 * mesh.triangleCount), fixed)
    return QuasiConformalMap(mesh, u, v, beltrami(mesh, u, v), 0)
}

/**
 * A Teichmüller map with the given constraints, by the quasi-conformal iteration of Lui, Lam, Yau and
 * Gu (2014).
 *
 * It starts from the harmonic map. Each iteration measures the map's `μ`, smooths it [smoothing]
 * times by averaging over neighbouring triangles, replaces each modulus by the area-weighted mean
 * modulus (keeping each argument), and solves for the map with that `μ`. It stops after
 * [iterations], or earlier once the mean modulus changes by less than [tolerance].
 *
 * @param fixed vertex index to target `(u, v)`; the boundary and any landmarks.
 */
fun teichmullerMap(
    mesh: PlanarMesh,
    fixed: Map<Int, DoubleArray>,
    iterations: Int = 60,
    smoothing: Int = 1,
    tolerance: Double = 1e-6,
    solver: LinearBeltramiSolver = LinearBeltramiSolver(mesh),
): QuasiConformalMap {
    var (u, v) = solver.solve(DoubleArray(2 * mesh.triangleCount), fixed)
    var mu = beltrami(mesh, u, v)
    var last = Double.MAX_VALUE
    var done = 0
    val incident = vertexTriangles(mesh)
    for (iteration in 1..iterations) {
        repeat(smoothing) { mu = smooth(mesh, mu, incident) }
        val m = modulus(mu)
        val mean = m.indices.sumOf { min(m[it], 0.95) * mesh.areas[it] } / mesh.areas.sum()
        for (t in 0 until mesh.triangleCount) {
            val a = atan2(mu[2 * t + 1], mu[2 * t])
            mu[2 * t] = mean * cos(a)
            mu[2 * t + 1] = mean * sin(a)
        }
        val next = solver.solve(mu, fixed, u to v)
        u = next.first
        v = next.second
        mu = beltrami(mesh, u, v)
        done = iteration
        if (abs(mean - last) < tolerance) break
        last = mean
    }
    return QuasiConformalMap(mesh, u, v, mu, done)
}

private fun vertexTriangles(mesh: PlanarMesh): Array<IntArray> {
    val lists = Array(mesh.vertexCount) { ArrayList<Int>() }
    for (t in 0 until mesh.triangleCount) for (k in 0..2) lists[mesh.triangles[3 * t + k]].add(t)
    return Array(mesh.vertexCount) { lists[it].toIntArray() }
}

/** One pass of smoothing: each vertex takes the area-weighted mean `μ` of its triangles, each triangle the mean of its vertices. */
private fun smooth(mesh: PlanarMesh, mu: DoubleArray, incident: Array<IntArray>): DoubleArray {
    val vr = DoubleArray(mesh.vertexCount)
    val vi = DoubleArray(mesh.vertexCount)
    for (i in 0 until mesh.vertexCount) {
        var w = 0.0
        for (t in incident[i]) {
            vr[i] += mu[2 * t] * mesh.areas[t]
            vi[i] += mu[2 * t + 1] * mesh.areas[t]
            w += mesh.areas[t]
        }
        vr[i] /= w
        vi[i] /= w
    }
    return DoubleArray(mu.size) { k ->
        val t = k / 2
        val src = if (k % 2 == 0) vr else vi
        (src[mesh.triangles[3 * t]] + src[mesh.triangles[3 * t + 1]] + src[mesh.triangles[3 * t + 2]]) / 3
    }
}

/**
 * Where the boundary of [mesh] lands on the closed polygon [targetX], [targetY] (counter-clockwise),
 * with landmarks matched: boundary vertex `landmarks[k]` goes to polygon point `targetLandmarks[k]`,
 * and the boundary vertices between two landmarks are spread along the polygon between their
 * targets in proportion to arc length. Landmarks must be listed in counter-clockwise order on both.
 */
fun boundaryCorrespondence(mesh: PlanarMesh, landmarks: IntArray, targetX: DoubleArray, targetY: DoubleArray, targetLandmarks: IntArray): Map<Int, DoubleArray> {
    val b = mesh.boundary
    val n = b.size
    val m = targetX.size
    val position = IntArray(landmarks.size) { k -> b.indexOf(landmarks[k]).also { require(it >= 0) { "landmark ${landmarks[k]} is not on the boundary" } } }
    val out = HashMap<Int, DoubleArray>()
    for (k in landmarks.indices) {
        val s0 = position[k]
        val s1 = position[(k + 1) % landmarks.size]
        val t0 = targetLandmarks[k]
        val t1 = targetLandmarks[(k + 1) % landmarks.size]
        // Source arc lengths from s0 to s1.
        val sCount = ((s1 - s0) % n + n) % n
        val sLen = DoubleArray(sCount + 1)
        for (j in 1..sCount) {
            val p = b[(s0 + j - 1) % n]
            val q = b[(s0 + j) % n]
            sLen[j] = sLen[j - 1] + hypot(mesh.x[q] - mesh.x[p], mesh.y[q] - mesh.y[p])
        }
        val tCount = ((t1 - t0) % m + m) % m
        val tLen = DoubleArray(tCount + 1)
        for (j in 1..tCount) {
            val p = (t0 + j - 1) % m
            val q = (t0 + j) % m
            tLen[j] = tLen[j - 1] + hypot(targetX[q] - targetX[p], targetY[q] - targetY[p])
        }
        for (j in 0 until sCount) {
            val want = sLen[j] / sLen[sCount] * tLen[tCount]
            var seg = 0
            while (seg < tCount - 1 && tLen[seg + 1] < want) seg++
            val span = (tLen[seg + 1] - tLen[seg]).coerceAtLeast(1e-300)
            val f = ((want - tLen[seg]) / span).coerceIn(0.0, 1.0)
            val p = (t0 + seg) % m
            val q = (t0 + seg + 1) % m
            out[b[(s0 + j) % n]] = doubleArrayOf(targetX[p] + f * (targetX[q] - targetX[p]), targetY[p] + f * (targetY[q] - targetY[p]))
        }
    }
    return out
}

/**
 * The conformal map of a region with four marked boundary points onto a rectangle `[0, 1] × [0, modulus]`,
 * the marked points going to the corners.
 *
 * A region bounded by one outline with four marked points (a conformal quadrilateral) can be
 * mapped conformally onto exactly one shape of rectangle; the ratio of its sides is the region's
 * conformal modulus. Here `x` is the harmonic function that is 0 on the side from landmark 3 to
 * landmark 0 and 1 on the side from landmark 1 to landmark 2, free (zero normal derivative) on the
 * other two sides, and the modulus is its Dirichlet energy. `y` is the companion harmonic function
 * for the other pair of sides, scaled by the modulus.
 *
 * @param landmarks four boundary vertices, counter-clockwise.
 */
class RectangleMap(val mesh: PlanarMesh, val landmarks: IntArray, solver: LinearBeltramiSolver = LinearBeltramiSolver(mesh)) {
    /** Where each vertex lands across the rectangle, 0 to 1. */
    val x: DoubleArray

    /** Where each vertex lands up the rectangle, 0 to [modulus]. */
    val y: DoubleArray

    /** The rectangle's height over its width: the conformal modulus of the marked region. */
    val modulus: Double

    /** The modulus measured the other way, as the reciprocal energy of the companion function; equal to [modulus] up to discretisation. */
    val dualModulus: Double

    init {
        require(landmarks.size == 4) { "a quadrilateral needs four landmarks" }
        val zero = DoubleArray(2 * mesh.triangleCount)
        val sides = landmarks.indices.map { side(it) }
        fun harmonic(low: IntArray, high: IntArray): DoubleArray {
            val fixed = HashMap<Int, DoubleArray>()
            low.forEach { fixed[it] = doubleArrayOf(0.0, 0.0) }
            high.forEach { fixed[it] = doubleArrayOf(1.0, 0.0) }
            return solver.solve(zero, fixed, DoubleArray(mesh.vertexCount) { 0.5 } to DoubleArray(mesh.vertexCount)).first
        }
        val u = harmonic(sides[3], sides[1])
        solver.assemble(zero)
        modulus = solver.energy(u)
        var w = harmonic(sides[0], sides[2])
        dualModulus = 1 / solver.energy(w)
        x = u
        // Pick the direction of y that keeps the map orientation-preserving.
        val t = (0 until mesh.triangleCount).maxBy { mesh.areas[it] }
        val (a, b, c) = Triple(mesh.triangles[3 * t], mesh.triangles[3 * t + 1], mesh.triangles[3 * t + 2])
        val det = (u[b] - u[a]) * (w[c] - w[a]) - (u[c] - u[a]) * (w[b] - w[a])
        if (det < 0) w = DoubleArray(w.size) { 1 - w[it] }
        y = DoubleArray(w.size) { modulus * w[it] }
    }

    /** The boundary vertices from landmark [k] to landmark k + 1, both included. */
    private fun side(k: Int): IntArray {
        val b = mesh.boundary
        val n = b.size
        val s0 = b.indexOf(landmarks[k])
        val s1 = b.indexOf(landmarks[(k + 1) % 4])
        require(s0 >= 0 && s1 >= 0) { "landmarks must be boundary vertices" }
        val count = ((s1 - s0) % n + n) % n
        return IntArray(count + 1) { b[(s0 + it) % n] }
    }
}

/**
 * The Teichmüller map between two regions with four matched landmarks, the outline free to slide
 * between them, and the Teichmüller distance it gives.
 *
 * Each region goes conformally onto its rectangle ([RectangleMap]); between two rectangles with
 * corners matched, the map of least distortion is the affine stretch of one height onto the other
 * (Grötzsch). So the Teichmüller map is: region A onto its rectangle, stretch, back from B's
 * rectangle onto B. Its `|μ|` is the same everywhere, `|H_B − H_A| / (H_B + H_A)`, and the
 * Teichmüller distance is `½ |log(H_B / H_A)|` exactly; [QuasiConformalMap.distance] measured from the
 * mesh map agrees with it up to discretisation.
 *
 * @return the map on [a]'s mesh, and the exact distance from the two moduli.
 */
fun quadrilateralTeichmullerMap(a: PlanarMesh, aLandmarks: IntArray, b: PlanarMesh, bLandmarks: IntArray): Pair<QuasiConformalMap, Double> {
    val ra = RectangleMap(a, aLandmarks)
    val rb = RectangleMap(b, bLandmarks)
    val stretch = rb.modulus / ra.modulus
    val u = DoubleArray(a.vertexCount)
    val v = DoubleArray(a.vertexCount)
    val t = b.triangles
    for (i in 0 until a.vertexCount) {
        val px = ra.x[i]
        val py = ra.y[i] * stretch
        // Find the triangle of B's rectangle picture holding the point (or the nearest one).
        var best = 0
        var bestScore = -Double.MAX_VALUE
        var bw = doubleArrayOf(1.0, 0.0, 0.0)
        for (f in 0 until b.triangleCount) {
            val i0 = t[3 * f]
            val i1 = t[3 * f + 1]
            val i2 = t[3 * f + 2]
            val area = (rb.x[i1] - rb.x[i0]) * (rb.y[i2] - rb.y[i0]) - (rb.x[i2] - rb.x[i0]) * (rb.y[i1] - rb.y[i0])
            if (abs(area) < 1e-300) continue
            val w0 = ((rb.x[i1] - px) * (rb.y[i2] - py) - (rb.x[i2] - px) * (rb.y[i1] - py)) / area
            val w1 = ((rb.x[i2] - px) * (rb.y[i0] - py) - (rb.x[i0] - px) * (rb.y[i2] - py)) / area
            val w2 = 1 - w0 - w1
            val score = minOf(w0, w1, w2)
            if (score > bestScore) {
                bestScore = score
                best = f
                bw = doubleArrayOf(w0, w1, w2)
                if (score >= 0) break
            }
        }
        if (bestScore < 0) {
            // Just outside: clamp to the triangle.
            for (k in 0..2) bw[k] = max(0.0, bw[k])
            val s = bw.sum()
            for (k in 0..2) bw[k] /= s
        }
        u[i] = (0..2).sumOf { bw[it] * b.x[t[3 * best + it]] }
        v[i] = (0..2).sumOf { bw[it] * b.y[t[3 * best + it]] }
    }
    return QuasiConformalMap(a, u, v, beltrami(a, u, v), 0) to 0.5 * abs(ln(stretch))
}

/**
 * The Teichmüller map between two shapes that are mirror-symmetric about the y axis, matching the tip
 * (top), the base (bottom) and two pairs of mirrored landmarks on the margin, the outline free to
 * slide between landmarks; and the Teichmüller distance between them.
 *
 * Each outline is the curve `radius(θ)` round the origin, symmetric under `θ ↦ π − θ`. The margin
 * landmarks are the outline points at fractions [heights] of the way from base to tip. Because the
 * extremal map is unique, it is mirror-symmetric too and carries the midrib to the midrib, so it is
 * the extremal map of the right halves, doubled. A right half with base, both landmarks and tip
 * marked is a quadrilateral, so its Teichmüller map and the exact distance come from
 * [quadrilateralTeichmullerMap]. Leaves of this kind are what Choi and Mahadevan (2018) compare.
 *
 * @param h the mesh spacing.
 */
class MirrorShapeComparison(radiusA: (Double) -> Double, radiusB: (Double) -> Double, val heights: List<Double> = [1.0 / 3, 2.0 / 3], h: Double = 0.05) {
    /** The mesh of shape A's right half. */
    val half: PlanarMesh

    /** The landmarks of [half]: base, the two margin landmarks, tip. */
    val landmarks: IntArray

    /** The Teichmüller map from A's right half onto B's. */
    val map: QuasiConformalMap

    /** The Teichmüller distance between the shapes, `½ |log(H_B / H_A)|` of the half quadrilaterals. */
    val distance: Double

    /** Where B's landmarks are: base, the two margin landmarks, tip, as `(x, y)` pairs. */
    val targetLandmarks: List<DoubleArray>

    init {
        require(heights.size == 2) { "two margin landmarks a side make each half a quadrilateral" }
        val (meshA, marksA) = halfShape(radiusA, h)
        val (meshB, marksB) = halfShape(radiusB, h)
        half = meshA
        landmarks = marksA
        val (m, d) = quadrilateralTeichmullerMap(meshA, marksA, meshB, marksB)
        map = m
        distance = d
        targetLandmarks = marksB.map { doubleArrayOf(meshB.x[it], meshB.y[it]) }
    }

    /** The right half of a shape (x ≥ 0) meshed, with its base, margin landmarks and tip. */
    private fun halfShape(radius: (Double) -> Double, h: Double): Pair<PlanarMesh, IntArray> {
        val samples = 600
        // The margin from base (θ = −π/2) up to tip (θ = π/2), then the midrib straight back down.
        val margin = (0..samples).map { k ->
            val t = -PI / 2 + PI * k / samples
            doubleArrayOf(radius(t) * cos(t), radius(t) * sin(t))
        }
        val base = margin.first()
        val tip = margin.last()
        val rib = (1 until samples / 4).map { k -> doubleArrayOf(0.0, tip[1] + (base[1] - tip[1]) * k / (samples / 4)) }
        val outline = margin + rib
        val mesh = PlanarMesh.outline(DoubleArray(outline.size) { outline[it][0] }, DoubleArray(outline.size) { outline[it][1] }, h)
        fun marginAt(f: Double): DoubleArray {
            val y = base[1] + f * (tip[1] - base[1])
            return margin.minBy { abs(it[1] - y) + (if (it[0] < 0) 10.0 else 0.0) }
        }
        val marks = listOf(base, marginAt(heights[0]), marginAt(heights[1]), tip).map { mesh.nearestBoundary(it[0], it[1]) }
        return mesh to marks.toIntArray()
    }
}

/**
 * ARGB pixels showing a planar mesh: filled triangles, edges and dots, in a world rectangle mapped
 * onto the image with y pointing up. Encode with `PngEncoder.encode(picture.pixels, w, h)`.
 */
class MeshPicture(val width: Int, val height: Int, val x0: Double, val y0: Double, val x1: Double, val y1: Double, background: Int = 0xFF000000.toInt()) {
    /** The pixels, row by row from the top. */
    val pixels = IntArray(width * height) { background }

    private fun px(x: Double) = (x - x0) / (x1 - x0) * width
    private fun py(y: Double) = (y1 - y) / (y1 - y0) * height

    /** Fills the triangle with corners `(ax, ay)`, `(bx, by)`, `(cx, cy)` in world coordinates. */
    fun triangle(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, argb: Int) {
        val pax = px(ax)
        val pay = py(ay)
        val pbx = px(bx)
        val pby = py(by)
        val pcx = px(cx)
        val pcy = py(cy)
        val area = (pbx - pax) * (pcy - pay) - (pcx - pax) * (pby - pay)
        if (abs(area) < 1e-12) return
        val minX = max(0, minOf(pax, pbx, pcx).toInt())
        val maxX = min(width - 1, maxOf(pax, pbx, pcx).toInt() + 1)
        val minY = max(0, minOf(pay, pby, pcy).toInt())
        val maxY = min(height - 1, maxOf(pay, pby, pcy).toInt() + 1)
        for (j in minY..maxY) for (i in minX..maxX) {
            val sx = i + 0.5
            val sy = j + 0.5
            val w0 = ((pbx - sx) * (pcy - sy) - (pcx - sx) * (pby - sy)) / area
            val w1 = ((pcx - sx) * (pay - sy) - (pax - sx) * (pcy - sy)) / area
            val w2 = 1 - w0 - w1
            if (w0 >= 0 && w1 >= 0 && w2 >= 0) pixels[j * width + i] = argb
        }
    }

    /** Draws a one-pixel line, blended at [alpha]. */
    fun line(ax: Double, ay: Double, bx: Double, by: Double, argb: Int, alpha: Double = 1.0) {
        val sx = px(ax)
        val sy = py(ay)
        val ex = px(bx)
        val ey = py(by)
        val steps = max(1, max(abs(ex - sx), abs(ey - sy)).toInt())
        for (s in 0..steps) {
            val i = (sx + (ex - sx) * s / steps).toInt()
            val j = (sy + (ey - sy) * s / steps).toInt()
            if (i in 0 until width && j in 0 until height) pixels[j * width + i] = blend(pixels[j * width + i], argb, alpha)
        }
    }

    /** Draws a filled dot of [radius] pixels. */
    fun dot(x: Double, y: Double, radius: Double, argb: Int) {
        val cx = px(x)
        val cy = py(y)
        for (j in (cy - radius).toInt()..(cy + radius).toInt()) for (i in (cx - radius).toInt()..(cx + radius).toInt())
            if (i in 0 until width && j in 0 until height && (i + 0.5 - cx) * (i + 0.5 - cx) + (j + 0.5 - cy) * (j + 0.5 - cy) <= radius * radius)
                pixels[j * width + i] = argb
    }

    /**
     * Draws [mesh] placed at `(u, v)`: each triangle filled with [colors] (one ARGB per triangle, or
     * none), then its edges in [edge] at [edgeAlpha].
     */
    fun mesh(mesh: PlanarMesh, u: DoubleArray = mesh.x, v: DoubleArray = mesh.y, colors: IntArray? = null, edge: Int = 0xFFFFFFFF.toInt(), edgeAlpha: Double = 0.35) {
        val t = mesh.triangles
        if (colors != null) for (f in 0 until mesh.triangleCount)
            triangle(u[t[3 * f]], v[t[3 * f]], u[t[3 * f + 1]], v[t[3 * f + 1]], u[t[3 * f + 2]], v[t[3 * f + 2]], colors[f])
        if (edgeAlpha > 0) for (f in 0 until mesh.triangleCount) for (k in 0..2) {
            val a = t[3 * f + k]
            val b = t[3 * f + (k + 1) % 3]
            if (a < b) line(u[a], v[a], u[b], v[b], edge, edgeAlpha)
        }
    }

    private fun blend(under: Int, over: Int, alpha: Double): Int {
        if (alpha >= 1) return over
        fun ch(shift: Int) = (((under shr shift) and 255) * (1 - alpha) + ((over shr shift) and 255) * alpha).toInt() and 255
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /**
         * A colour for a distortion `|μ| = k` on a scale that reaches its top at [top]: blue at
         * zero, through cyan, to orange and white.
         */
        fun distortionColor(k: Double, top: Double = 0.6): Int {
            val s = (k / top).coerceIn(0.0, 1.0)
            val stops = arrayOf(
                doubleArrayOf(0.2, 0.3, 0.85),
                doubleArrayOf(0.25, 0.6, 0.95),
                doubleArrayOf(0.4, 0.9, 1.0),
                doubleArrayOf(1.0, 0.6, 0.2),
                doubleArrayOf(1.0, 0.95, 0.85),
            )
            val f = s * (stops.size - 1)
            val i = min(stops.size - 2, f.toInt())
            val w = f - i
            fun c(k: Int) = ((stops[i][k] * (1 - w) + stops[i + 1][k] * w) * 255).roundToInt().coerceIn(0, 255)
            return (0xFF shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
        }
    }
}

/**
 * This planar mesh as a flat [TriMesh] at `z = 0`, triangles in the same order. The way back is
 * [TriMesh.toPlanarMesh]; between the two, the conformal maps here and the mesh tools in
 * `afield.geometry.mesh` (smoothing, geodesics, the shared cotangent Laplacian) work on one mesh.
 */
fun PlanarMesh.toTriMesh(): TriMesh {
    val p = FloatArray(vertexCount * 3)
    for (i in 0 until vertexCount) { p[3 * i] = x[i].toFloat(); p[3 * i + 1] = y[i].toFloat() }
    return TriMesh(p, triangles.copyOf())
}

/**
 * A flat [TriMesh] — a disc: welded, manifold, with one boundary loop — as a [PlanarMesh] for
 * [harmonicMap], [teichmullerMap] and [LinearBeltramiSolver]. Its `x` and `y` are kept and `z` is
 * dropped, so project a mesh into the `xy` plane first. Triangles are rewound counter-clockwise
 * and the boundary is ordered counter-clockwise, which is what [PlanarMesh] expects.
 */
fun TriMesh.toPlanarMesh(): PlanarMesh {
    val loops = topology().boundaryLoops
    require(loops.size == 1) { "toPlanarMesh needs a disc (one boundary loop), got ${loops.size}" }
    val x = DoubleArray(vertexCount) { x(it).toDouble() }
    val y = DoubleArray(vertexCount) { y(it).toDouble() }
    val tri = indices.copyOf()
    for (t in 0 until triangleCount) {
        val a = tri[3 * t]; val b = tri[3 * t + 1]; val c = tri[3 * t + 2]
        if ((x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a]) < 0) { tri[3 * t + 1] = c; tri[3 * t + 2] = b }
    }
    val loop = loops[0].toIntArray()
    var signed = 0.0
    for (k in loop.indices) { val i = loop[k]; val j = loop[(k + 1) % loop.size]; signed += x[i] * y[j] - x[j] * y[i] }
    if (signed < 0) loop.reverse()
    return PlanarMesh(x, y, tri, loop)
}

