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
 * which Choi, Chan, Lui, Mahadevan and co-authors use to compare leaf and wing outlines ("Planar
 * morphometrics using Teichmüller maps", Proc. R. Soc. A 474, 2018).
 *
 *     import afield.geometry.conformal.*
 *
 *     val mesh = PlanarMesh.grid(16)
 *     val fixed = mesh.boundaryIdentity() + (mesh.nearest(0.35, 0.5) to doubleArrayOf(0.6, 0.55))
 *     val t = teichmullerMap(mesh, fixed)
 *     println("K = ${t.dilatation}, distance ${t.distance}")
 *
 * Coordinates are plain `DoubleArray`s (`x`, `y`, `u`, `v`), and `μ` is stored as interleaved real
 * and imaginary parts, one pair per triangle. [MeshPicture] draws a mesh and its `|μ|` into ARGB
 * pixels for a box output.
 */

package afield.geometry.conformal

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
         * A star-shaped region: every point of its outline is seen from `(cx, cy)`, at distance
         * `radius(θ)` in direction `θ`. The mesh is [rings] rings of vertices, the outer one with
         * [sectors] vertices and inner ones with proportionally fewer, so the triangles stay close to
         * equilateral.
         */
        fun star(radius: (Double) -> Double, rings: Int = 12, sectors: Int = 64, cx: Double = 0.0, cy: Double = 0.0): PlanarMesh {
            val xs = arrayListOf(cx)
            val ys = arrayListOf(cy)
            val ringStart = IntArray(rings + 1)
            val ringCount = IntArray(rings + 1)
            ringCount[0] = 1
            for (k in 1..rings) {
                val count = max(6, (sectors.toDouble() * k / rings).roundToInt())
                ringStart[k] = xs.size
                ringCount[k] = count
                repeat(count) { s ->
                    val theta = 2 * PI * s / count
                    val r = radius(theta) * k / rings
                    xs.add(cx + r * cos(theta))
                    ys.add(cy + r * sin(theta))
                }
            }
            val tris = ArrayList<Int>()
            // The fan round the centre.
            for (s in 0 until ringCount[1]) tris.addAll(listOf(0, ringStart[1] + s, ringStart[1] + (s + 1) % ringCount[1]))
            // Each pair of rings, joined by walking both by angle.
            for (k in 1 until rings) {
                val a0 = ringStart[k]
                val na = ringCount[k]
                val b0 = ringStart[k + 1]
                val nb = ringCount[k + 1]
                var i = 0
                var j = 0
                while (i < na || j < nb) {
                    val nextA = (i + 1).toDouble() / na
                    val nextB = (j + 1).toDouble() / nb
                    if (j >= nb || (i < na && nextA < nextB)) {
                        tris.addAll(listOf(a0 + i % na, b0 + j % nb, a0 + (i + 1) % na))
                        i++
                    } else {
                        tris.addAll(listOf(a0 + i % na, b0 + j % nb, b0 + (j + 1) % nb))
                        j++
                    }
                }
            }
            val xa = xs.toDoubleArray()
            val ya = ys.toDoubleArray()
            val t = tris.toIntArray()
            // Make every triangle counter-clockwise.
            for (f in 0 until t.size / 3) {
                val a = t[3 * f]
                val b = t[3 * f + 1]
                val c = t[3 * f + 2]
                if ((xa[b] - xa[a]) * (ya[c] - ya[a]) - (xa[c] - xa[a]) * (ya[b] - ya[a]) < 0) {
                    t[3 * f + 1] = c
                    t[3 * f + 2] = b
                }
            }
            return PlanarMesh(xa, ya, t, IntArray(ringCount[rings]) { ringStart[rings] + it })
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
         * A colour for a distortion `|μ| = k` on a scale that reaches its top at [top]: dark blue at
         * zero, through cyan, to orange and white.
         */
        fun distortionColor(k: Double, top: Double = 0.6): Int {
            val s = (k / top).coerceIn(0.0, 1.0)
            val stops = arrayOf(
                doubleArrayOf(0.05, 0.08, 0.25),
                doubleArrayOf(0.15, 0.45, 0.75),
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
