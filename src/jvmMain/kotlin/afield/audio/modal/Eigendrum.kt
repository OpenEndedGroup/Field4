/**
 * Eigendrum: draw a closed shape and hear it as a drum. The region inside a polygon is
 * triangulated ([drumMesh]), the Dirichlet Laplacian −∇²u = λu (u = 0 on the rim) is discretised
 * with linear finite elements into a stiffness matrix K and a mass matrix M, and the smallest
 * generalized eigenpairs Kφ = λMφ are found by banded-Cholesky subspace iteration ([DrumMesh.modes]).
 * A strike projects a small Gaussian mallet onto the modes ([DrumModes.strike]) and [DrumModes.render]
 * sums one damped sinusoid per mode (modal synthesis). [DrumModes.nodalLines] and
 * [DrumModes.modeMesh] draw a mode back as FLines.
 *
 * The idea follows eigendrum.com; this is an independent textbook implementation (no code taken).
 */
package afield.audio.modal

import afield.audio.engine.WavFloatArrayWriter
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.ui.visualization.Table
import afield.ui.visualization.table
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.triangulate.DelaunayTriangulationBuilder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A triangulated drum head: node coordinates [x], [y], triangles as index triples in [tris], and
 * [boundary] marking the clamped rim nodes.
 */
class DrumMesh(
    /** Node x coordinates. */
    val x: DoubleArray,
    /** Node y coordinates. */
    val y: DoubleArray,
    /** Triangles, three node indices each, counter-clockwise. */
    val tris: IntArray,
    /** True for nodes on the clamped rim. */
    val boundary: BooleanArray,
) {
    /** Number of nodes. */
    val nodeCount: Int get() = x.size

    /** Number of triangles. */
    val triangleCount: Int get() = tris.size / 3

    /** Total area of the triangulated region. */
    val area: Double
        get() {
            var a = 0.0
            for (t in 0 until triangleCount) a += triArea(t)
            return a
        }

    internal fun triArea(t: Int): Double {
        val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
        return 0.5 * ((x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a]))
    }

    /** The triangle edges as one FLine, for a mesh view. */
    fun wireframe(): FLine {
        val f = FLine()
        for (t in 0 until triangleCount) {
            val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
            f.moveTo(x[a], y[a]); f.lineTo(x[b], y[b]); f.lineTo(x[c], y[c]); f.lineTo(x[a], y[a])
        }
        return f
    }

    /**
     * Solve for the [count] lowest modes of this drum.
     *
     * @param mass `"blend"` (default) averages consistent and lumped mass, which cancels most of the
     *   O(h²) error of linear elements; `"consistent"` gives upper bounds; `"lumped"` lower ones.
     * @param tolerance relative change in every wanted eigenvalue at which iteration stops.
     */
    @JvmOverloads
    fun modes(count: Int = 16, mass: String = "blend", tolerance: Double = 1e-10, maxIterations: Int = 400): DrumModes =
        solveDrumModes(this, count, mass, tolerance, maxIterations)
}

/**
 * The lowest modes of a [DrumMesh]: eigenvalues [lambda] ascending, and mass-normalised mode
 * shapes [shapes] (one value per mesh node, zero on the rim).
 */
class DrumModes(
    /** The mesh the modes live on. */
    val mesh: DrumMesh,
    /** Eigenvalues λ of −∇²u = λu, ascending. */
    val lambda: DoubleArray,
    /** Mode shapes, `shapes[i][node]`, normalised so φᵀMφ = 1. */
    val shapes: Array<DoubleArray>,
    /** Subspace iterations the solve took. */
    val iterations: Int,
) {
    /** Number of modes. */
    val count: Int get() = lambda.size

    /** Frequency of each mode in Hz when the fundamental sounds at [fundamental] Hz (f ∝ √λ). */
    @JvmOverloads
    fun frequencies(fundamental: Double = 110.0): DoubleArray =
        DoubleArray(count) { fundamental * sqrt(lambda[it] / lambda[0]) }

    /** Each mode's frequency as a ratio to the fundamental. */
    fun ratios(): DoubleArray = DoubleArray(count) { sqrt(lambda[it] / lambda[0]) }

    /** Interpolated value of mode [mode] at (px, py), 0 outside the mesh. */
    fun valueAt(mode: Int, px: Double, py: Double): Double {
        val m = mesh
        for (t in 0 until m.triangleCount) {
            val a = m.tris[3 * t]; val b = m.tris[3 * t + 1]; val c = m.tris[3 * t + 2]
            val d = (m.y[b] - m.y[c]) * (m.x[a] - m.x[c]) + (m.x[c] - m.x[b]) * (m.y[a] - m.y[c])
            val l1 = ((m.y[b] - m.y[c]) * (px - m.x[c]) + (m.x[c] - m.x[b]) * (py - m.y[c])) / d
            val l2 = ((m.y[c] - m.y[a]) * (px - m.x[c]) + (m.x[a] - m.x[c]) * (py - m.y[c])) / d
            val l3 = 1 - l1 - l2
            if (l1 >= -1e-9 && l2 >= -1e-9 && l3 >= -1e-9) {
                val s = shapes[mode]
                return l1 * s[a] + l2 * s[b] + l3 * s[c]
            }
        }
        return 0.0
    }

    /**
     * How strongly a strike at (px, py) excites each mode: the mallet, a Gaussian of width
     * [radius], projected onto every mode (aᵢ = φᵢᵀ M g). A strike on a nodal line of mode i gives
     * aᵢ ≈ 0.
     */
    @JvmOverloads
    fun strike(px: Double, py: Double, radius: Double = 0.03): DoubleArray {
        val m = mesh
        val g = DoubleArray(m.nodeCount) {
            val r2 = (m.x[it] - px).let { d -> d * d } + (m.y[it] - py).let { d -> d * d }
            exp(-r2 / (2 * radius * radius))
        }
        val mg = DoubleArray(m.nodeCount)
        for (t in 0 until m.triangleCount) {
            val ar = m.triArea(t)
            val idx = intArrayOf(m.tris[3 * t], m.tris[3 * t + 1], m.tris[3 * t + 2])
            for (i in 0..2) for (j in 0..2) mg[idx[i]] += ar / 12.0 * (if (i == j) 2.0 else 1.0) * g[idx[j]]
        }
        return DoubleArray(count) { k -> var s = 0.0; for (n in 0 until m.nodeCount) s += shapes[k][n] * mg[n]; s }
    }

    /**
     * Modal synthesis of one strike: Σ aᵢ e^(−σᵢ t) sin(2π fᵢ t), with Rayleigh-style decay
     * σᵢ = [alpha] + [beta]·(fᵢ/f₀)², so higher modes die faster.
     *
     * @param amplitudes from [strike] (or any weights, one per mode).
     * @param only if ≥ 0, render that single mode at unit amplitude instead.
     */
    @JvmOverloads
    fun render(
        amplitudes: DoubleArray,
        fundamental: Double = 110.0,
        seconds: Double = 2.5,
        sampleRate: Int = 44100,
        alpha: Double = 1.5,
        beta: Double = 0.25,
        only: Int = -1,
    ): FloatArray {
        val n = (seconds * sampleRate).toInt()
        val out = FloatArray(n)
        val f = frequencies(fundamental)
        for (k in 0 until count) {
            if (only >= 0 && k != only) continue
            val a = if (only >= 0) 1.0 else amplitudes[k]
            if (f[k] >= sampleRate * 0.45 || a == 0.0) continue
            val sigma = alpha + beta * (f[k] / fundamental).let { it * it }
            val w = 2 * PI * f[k] / sampleRate
            val decay = exp(-sigma / sampleRate)
            // recursive oscillator: y[t] = 2 r cos(w) y[t-1] - r² y[t-2]
            val c = 2 * decay * cos(w); val r2 = decay * decay
            var y1 = 0.0; var y2 = -a * decay * sin(w)
            for (t in 0 until n) {
                val y = c * y1 - r2 * y2
                y2 = y1; y1 = y
                out[t] += y.toFloat()
            }
        }
        var peak = 0f
        for (v in out) peak = max(peak, abs(v))
        if (peak > 0f) for (i in out.indices) out[i] *= 0.9f / peak
        return out
    }

    /**
     * The modes as a [Table], one row each: index, λ, ratio to the fundamental, frequency, and an
     * audio player that sounds that mode alone. The ratio column is heated, so clusters of
     * near-equal modes (a square's degenerate pairs) read as bands of one colour. Pass it to
     * `display(...)`.
     */
    @JvmOverloads
    fun modeTable(fundamental: Double = 110.0, seconds: Double = 1.5, sampleRate: Int = 22050): Table {
        val f = frequencies(fundamental)
        val players = (0 until count).map { k ->
            val wav = WavFloatArrayWriter.encodeMono16(render(DoubleArray(count), fundamental, seconds, sampleRate, only = k), sampleRate)
            "<audio controls preload='metadata' style='height:24px' src='data:audio/wav;base64," +
                java.util.Base64.getEncoder().encodeToString(wav) + "'></audio>"
        }
        return table(
            "mode" to (1..count).toList(),
            "λ" to lambda.toList(),
            "ratio" to (0 until count).map { f[it] / fundamental },
            "Hz" to f.toList(),
            "play" to players,
        )
            .format("λ") { "%.3f".format(it as Double) }
            .format("ratio") { "%.3f".format(it as Double) }
            .format("Hz") { "%.1f".format(it as Double) }
            .format("play") { it as String }
            .heat("ratio")
    }

    /** [modeTable] rendered to HTML, for callers that want the markup itself. */
    @JvmOverloads
    fun modeListHtml(fundamental: Double = 110.0, seconds: Double = 1.5, sampleRate: Int = 22050): String =
        modeTable(fundamental, seconds, sampleRate).toHTML()

    /** The nodal lines (φ = 0) of mode [mode] as one FLine of segments. */
    fun nodalLines(mode: Int): FLine {
        val m = mesh; val s = shapes[mode]; val f = FLine()
        for (t in 0 until m.triangleCount) {
            val v = intArrayOf(m.tris[3 * t], m.tris[3 * t + 1], m.tris[3 * t + 2])
            val pts = ArrayList<DoubleArray>(2)
            for (e in 0..2) {
                val i = v[e]; val j = v[(e + 1) % 3]
                if (m.boundary[i] || m.boundary[j]) continue
                if ((s[i] < 0) != (s[j] < 0)) {
                    val u = s[i] / (s[i] - s[j])
                    pts += doubleArrayOf(m.x[i] + u * (m.x[j] - m.x[i]), m.y[i] + u * (m.y[j] - m.y[i]))
                }
            }
            if (pts.size == 2) { f.moveTo(pts[0][0], pts[0][1]); f.lineTo(pts[1][0], pts[1][1]) }
        }
        return f
    }

    /**
     * Mode [mode] as filled triangles, warm for positive displacement and cool for negative,
     * brighter with amplitude.
     */
    fun modeMesh(mode: Int): List<FLine> {
        val m = mesh; val s = shapes[mode]
        var peak = 1e-30
        for (v in s) peak = max(peak, abs(v))
        return (0 until m.triangleCount).map { t ->
            val a = m.tris[3 * t]; val b = m.tris[3 * t + 1]; val c = m.tris[3 * t + 2]
            val v = (s[a] + s[b] + s[c]) / (3 * peak)
            val k = min(1.0, abs(v))
            FLine().apply {
                moveTo(m.x[a], m.y[a]); lineTo(m.x[b], m.y[b]); lineTo(m.x[c], m.y[c]); lineTo(m.x[a], m.y[a])
                filled = true
                stroked = false
                color = if (v >= 0) afield.math.linalg.vec(0.15 + 0.85 * k, 0.15 + 0.45 * k, 0.15, 1.0)
                else afield.math.linalg.vec(0.15, 0.15 + 0.45 * k, 0.15 + 0.85 * k, 1.0)
            }
        }
    }
}

/**
 * Triangulate the region inside a closed polygon given as alternating x, y coordinates (the
 * closing edge is implied). Boundary edges are subdivided to spacing ≈ [h] and the interior is
 * filled with a triangular lattice of spacing [h], then Delaunay-triangulated.
 */
fun drumMesh(polygonXY: DoubleArray, h: Double): DrumMesh {
    require(polygonXY.size >= 6 && polygonXY.size % 2 == 0) { "need at least 3 x,y vertices" }
    var px = DoubleArray(polygonXY.size / 2) { polygonXY[2 * it] }
    var py = DoubleArray(polygonXY.size / 2) { polygonXY[2 * it + 1] }
    if (hypot(px.last() - px[0], py.last() - py[0]) < 1e-12) { px = px.copyOf(px.size - 1); py = py.copyOf(py.size - 1) }
    val nv = px.size
    val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
    for (i in 0 until nv) {
        val j = (i + 1) % nv
        val len = hypot(px[j] - px[i], py[j] - py[i])
        if (len < 1e-12) continue
        val segs = max(1, ceil(len / h - 1e-9).toInt())
        for (s in 0 until segs) {
            val u = s.toDouble() / segs
            xs += px[i] + u * (px[j] - px[i]); ys += py[i] + u * (py[j] - py[i])
        }
    }
    val nb = xs.size
    val minX = px.min(); val maxX = px.max(); val minY = py.min(); val maxY = py.max()
    val dy = h * sqrt(3.0) / 2
    var row = 0
    var yy = minY + dy * 0.5
    while (yy < maxY) {
        var xx = minX + (if (row % 2 == 0) 0.25 else 0.75) * h
        while (xx < maxX) {
            if (insidePolygon(px, py, xx, yy) && distanceToPolygon(px, py, xx, yy) > 0.45 * h) { xs += xx; ys += yy }
            xx += h
        }
        yy += dy; row++
    }
    val gf = GeometryFactory()
    val builder = DelaunayTriangulationBuilder()
    builder.setSites(xs.indices.map { Coordinate(xs[it], ys[it]) })
    builder.setTolerance(0.0)
    val triGeom = builder.getTriangles(gf)
    val index = HashMap<Pair<Double, Double>, Int>(xs.size * 2)
    for (i in xs.indices) index[xs[i] to ys[i]] = i
    val tris = ArrayList<Int>()
    for (g in 0 until triGeom.numGeometries) {
        val c = (triGeom.getGeometryN(g) as Polygon).exteriorRing.coordinates
        val ids = IntArray(3) { index[c[it].x to c[it].y] ?: -1 }
        if (ids.any { it < 0 }) continue
        val cx = (xs[ids[0]] + xs[ids[1]] + xs[ids[2]]) / 3; val cy = (ys[ids[0]] + ys[ids[1]] + ys[ids[2]]) / 3
        if (!insidePolygon(px, py, cx, cy)) continue
        val area2 = (xs[ids[1]] - xs[ids[0]]) * (ys[ids[2]] - ys[ids[0]]) - (xs[ids[2]] - xs[ids[0]]) * (ys[ids[1]] - ys[ids[0]])
        if (abs(area2) < 1e-14 * h * h) continue
        if (area2 > 0) { tris += ids[0]; tris += ids[1]; tris += ids[2] } else { tris += ids[0]; tris += ids[2]; tris += ids[1] }
    }
    return DrumMesh(xs.toDoubleArray(), ys.toDoubleArray(), tris.toIntArray(), BooleanArray(xs.size) { it < nb })
}

/** Triangulate the inside of a closed [line], taking its node end points as the polygon. */
fun drumMesh(line: FLine, h: Double): DrumMesh {
    val pts = ArrayList<Double>()
    for (n in line.nodes) {
        val p = n.storage.lastOrNull() ?: continue
        pts += p.x.toDouble(); pts += p.y.toDouble()
    }
    return drumMesh(pts.toDoubleArray(), h)
}

/**
 * Triangulate the inside of a closed [line] drawn at any size (a drawing-layer stroke, say),
 * first scaled so its larger side is 1, with about [resolution] mesh cells across.
 */
@JvmOverloads
fun drumMeshFitted(line: FLine, resolution: Int = 32): DrumMesh {
    val pts = ArrayList<Double>()
    for (n in line.nodes) {
        val p = n.storage.lastOrNull() ?: continue
        pts += p.x.toDouble(); pts += p.y.toDouble()
    }
    return drumMesh(fitPolygonToUnit(pts.toDoubleArray()), 1.0 / resolution)
}

/** Scale and translate alternating x, y coordinates so the polygon's larger side spans [0, 1]. */
fun fitPolygonToUnit(xy: DoubleArray): DoubleArray {
    var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
    for (i in 0 until xy.size / 2) { x0 = min(x0, xy[2 * i]); x1 = max(x1, xy[2 * i]); y0 = min(y0, xy[2 * i + 1]); y1 = max(y1, xy[2 * i + 1]) }
    val s = 1.0 / max(x1 - x0, y1 - y0)
    return DoubleArray(xy.size) { if (it % 2 == 0) (xy[it] - x0) * s else (xy[it] - y0) * s }
}

/** A drum bounded by the polar curve r(θ) about the origin, sampled at [samples] angles. */
@JvmOverloads
fun polarDrumMesh(h: Double, samples: Int = 256, r: (Double) -> Double): DrumMesh =
    drumMesh(polarPolygon(samples, r), h)

/** The polar curve r(θ) as alternating x, y coordinates. */
fun polarPolygon(samples: Int, r: (Double) -> Double): DoubleArray {
    val out = DoubleArray(samples * 2)
    for (i in 0 until samples) {
        val t = 2 * PI * i / samples
        out[2 * i] = r(t) * cos(t); out[2 * i + 1] = r(t) * sin(t)
    }
    return out
}

/** A polygon given as alternating x, y coordinates, as a closed FLine. */
fun polygonFLine(polygonXY: DoubleArray): FLine = FLine().apply {
    moveTo(polygonXY[0], polygonXY[1])
    for (i in 1 until polygonXY.size / 2) lineTo(polygonXY[2 * i], polygonXY[2 * i + 1])
    lineTo(polygonXY[0], polygonXY[1])
}

/**
 * The Gordon–Webb–Wolpert isospectral pair (vertices as in Driscoll 1997 / Moler 2012): two
 * different drums, built from seven half-squares each, with the same spectrum.
 */
object KacPair {
    /** The first GWW drum. */
    val drumA = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 2.0, 2.0, 3.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 0.0)

    /** The second GWW drum. */
    val drumB = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 2.0, 2.0, 2.0, 2.0, 3.0, 3.0, 2.0, 2.0, 1.0, 1.0, 1.0)
}

internal fun insidePolygon(px: DoubleArray, py: DoubleArray, x: Double, y: Double): Boolean {
    var inside = false
    var j = px.size - 1
    for (i in px.indices) {
        if ((py[i] > y) != (py[j] > y) && x < (px[j] - px[i]) * (y - py[i]) / (py[j] - py[i]) + px[i]) inside = !inside
        j = i
    }
    return inside
}

internal fun distanceToPolygon(px: DoubleArray, py: DoubleArray, x: Double, y: Double): Double {
    var best = Double.MAX_VALUE
    for (i in px.indices) {
        val j = (i + 1) % px.size
        val ex = px[j] - px[i]; val ey = py[j] - py[i]
        val l2 = ex * ex + ey * ey
        val u = if (l2 == 0.0) 0.0 else ((x - px[i]) * ex + (y - py[i]) * ey) / l2
        val c = u.coerceIn(0.0, 1.0)
        best = min(best, hypot(x - px[i] - c * ex, y - py[i] - c * ey))
    }
    return best
}

/** Symmetric banded matrix, lower band stored row-wise: a(i, j) for i - b ≤ j ≤ i. */
internal class Band(val n: Int, val b: Int) {
    val v = DoubleArray(n * (b + 1))
    fun add(i: Int, j: Int, value: Double) { if (j <= i) v[i * (b + 1) + (i - j)] += value else v[j * (b + 1) + (j - i)] += value }
    fun get(i: Int, j: Int): Double = if (j <= i) v[i * (b + 1) + (i - j)] else v[j * (b + 1) + (j - i)]

    fun times(x: DoubleArray, out: DoubleArray) {
        out.fill(0.0)
        for (i in 0 until n) {
            out[i] += v[i * (b + 1)] * x[i]
            for (d in 1..min(b, i)) {
                val a = v[i * (b + 1) + d]
                if (a != 0.0) { out[i] += a * x[i - d]; out[i - d] += a * x[i] }
            }
        }
    }

    /** In-place Cholesky A = L Lᵀ (L stored in the band). */
    fun cholesky() {
        for (i in 0 until n) {
            for (j in max(0, i - b)..i) {
                var s = get(i, j)
                for (k in max(max(0, i - b), j - b) until j) s -= v[i * (b + 1) + (i - k)] * v[j * (b + 1) + (j - k)]
                if (i == j) {
                    require(s > 0) { "matrix not positive definite at $i" }
                    v[i * (b + 1)] = sqrt(s)
                } else v[i * (b + 1) + (i - j)] = s / v[j * (b + 1)]
            }
        }
    }

    fun choleskySolve(rhs: DoubleArray) {
        for (i in 0 until n) {
            var s = rhs[i]
            for (k in max(0, i - b) until i) s -= v[i * (b + 1) + (i - k)] * rhs[k]
            rhs[i] = s / v[i * (b + 1)]
        }
        for (i in n - 1 downTo 0) {
            var s = rhs[i]
            for (k in i + 1..min(n - 1, i + b)) s -= v[k * (b + 1) + (k - i)] * rhs[k]
            rhs[i] = s / v[i * (b + 1)]
        }
    }
}

/** Cyclic Jacobi for a small dense symmetric matrix; returns eigenvalues, [vecs] columns are eigenvectors. */
internal fun jacobiEigen(a0: Array<DoubleArray>, vecs: Array<DoubleArray>): DoubleArray {
    val n = a0.size
    val a = Array(n) { a0[it].copyOf() }
    for (i in 0 until n) { vecs[i].fill(0.0); vecs[i][i] = 1.0 }
    repeat(100) {
        var off = 0.0
        for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
        if (off < 1e-30) return@repeat
        for (p in 0 until n) for (q in p + 1 until n) {
            if (abs(a[p][q]) < 1e-300) continue
            val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val c = 1 / sqrt(t * t + 1); val s = t * c
            for (k in 0 until n) {
                val akp = a[k][p]; val akq = a[k][q]
                a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq
            }
            for (k in 0 until n) {
                val apk = a[p][k]; val aqk = a[q][k]
                a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk
            }
            for (k in 0 until n) {
                val vkp = vecs[k][p]; val vkq = vecs[k][q]
                vecs[k][p] = c * vkp - s * vkq; vecs[k][q] = s * vkp + c * vkq
            }
        }
    }
    return DoubleArray(n) { a[it][it] }
}

internal fun solveDrumModes(mesh: DrumMesh, count: Int, mass: String, tol: Double, maxIt: Int): DrumModes {
    // free nodes, ordered by x then y to keep the band narrow
    val free = (0 until mesh.nodeCount).filter { !mesh.boundary[it] }.sortedWith(compareBy({ mesh.x[it] }, { mesh.y[it] }))
    val n = free.size
    require(n > count) { "mesh has $n interior nodes, need more than $count; use a smaller h" }
    val pos = IntArray(mesh.nodeCount) { -1 }
    free.forEachIndexed { i, node -> pos[node] = i }
    var bw = 0
    for (t in 0 until mesh.triangleCount) for (i in 0..2) for (j in 0..2) {
        val a = pos[mesh.tris[3 * t + i]]; val c = pos[mesh.tris[3 * t + j]]
        if (a >= 0 && c >= 0) bw = max(bw, abs(a - c))
    }
    val k = Band(n, bw); val m = Band(n, bw)
    val wc = when (mass) { "consistent" -> 1.0; "lumped" -> 0.0; else -> 0.5 }
    for (t in 0 until mesh.triangleCount) {
        val id = IntArray(3) { mesh.tris[3 * t + it] }
        val ar = mesh.triArea(t)
        val bx = DoubleArray(3) { mesh.y[id[(it + 1) % 3]] - mesh.y[id[(it + 2) % 3]] }
        val cy = DoubleArray(3) { mesh.x[id[(it + 2) % 3]] - mesh.x[id[(it + 1) % 3]] }
        for (i in 0..2) {
            val pi = pos[id[i]]; if (pi < 0) continue
            for (j in 0..2) {
                val pj = pos[id[j]]; if (pj < 0 || pj > pi) continue
                k.add(pi, pj, (bx[i] * bx[j] + cy[i] * cy[j]) / (4 * ar))
                val consistent = ar / 12.0 * (if (i == j) 2.0 else 1.0)
                val lumped = if (i == j) ar / 3.0 else 0.0
                m.add(pi, pj, wc * consistent + (1 - wc) * lumped)
            }
        }
    }
    val kc = Band(n, bw); k.v.copyInto(kc.v); kc.cholesky()
    val p = min(n, max(count + 8, 2 * count))
    val rnd = java.util.Random(1)
    var x = Array(p) { DoubleArray(n) { rnd.nextDouble() - 0.5 } }
    var lam = DoubleArray(p) { Double.MAX_VALUE }
    val tmp = DoubleArray(n)
    var iterations = 0
    val vecs = Array(p) { DoubleArray(p) }
    while (iterations < maxIt) {
        iterations++
        val y = Array(p) { c -> val r = DoubleArray(n); m.times(x[c], r); kc.choleskySolve(r); r }
        val ky = Array(p) { c -> val r = DoubleArray(n); k.times(y[c], r); r }
        val my = Array(p) { c -> val r = DoubleArray(n); m.times(y[c], r); r }
        val kr = Array(p) { i -> DoubleArray(p) { j -> dot(y[i], ky[j]) } }
        val mr = Array(p) { i -> DoubleArray(p) { j -> dot(y[i], my[j]) } }
        // reduce small generalized problem with Cholesky of mr: C = L⁻¹ kr L⁻ᵀ
        val l = Array(p) { DoubleArray(p) }
        for (i in 0 until p) for (j in 0..i) {
            var s = mr[i][j]
            for (q in 0 until j) s -= l[i][q] * l[j][q]
            l[i][j] = if (i == j) sqrt(max(s, 1e-300)) else s / l[j][j]
        }
        val linvK = Array(p) { DoubleArray(p) }
        for (c in 0 until p) for (i in 0 until p) {
            var s = kr[i][c]; for (q in 0 until i) s -= l[i][q] * linvK[q][c]; linvK[i][c] = s / l[i][i]
        }
        val cm = Array(p) { DoubleArray(p) }
        for (r in 0 until p) for (i in 0 until p) {
            var s = linvK[r][i]; for (q in 0 until i) s -= l[i][q] * cm[r][q]; cm[r][i] = s / l[i][i]
        }
        for (i in 0 until p) for (j in 0 until i) { val s = 0.5 * (cm[i][j] + cm[j][i]); cm[i][j] = s; cm[j][i] = s }
        val ev = jacobiEigen(cm, vecs)
        val order = ev.indices.sortedBy { ev[it] }
        // back-substitute: q = L⁻ᵀ v
        val qcols = order.map { col ->
            val v = DoubleArray(p) { vecs[it][col] }
            for (i in p - 1 downTo 0) { var s = v[i]; for (q in i + 1 until p) s -= l[q][i] * v[q]; v[i] = s / l[i][i] }
            v
        }
        val newX = Array(p) { c -> val r = DoubleArray(n); for (j in 0 until p) { val w = qcols[c][j]; if (w != 0.0) for (i in 0 until n) r[i] += w * y[j][i] }; r }
        val newLam = DoubleArray(p) { ev[order[it]] }
        var maxRel = 0.0
        for (i in 0 until count) maxRel = max(maxRel, abs(newLam[i] - lam[i]) / abs(newLam[i]))
        x = newX; lam = newLam
        if (maxRel < tol) break
    }
    val shapes = Array(count) { c ->
        m.times(x[c], tmp)
        val norm = sqrt(dot(x[c], tmp))
        val full = DoubleArray(mesh.nodeCount)
        for (i in 0 until n) full[free[i]] = x[c][i] / norm
        // sign convention: largest-magnitude value positive
        var big = 0.0; for (v in full) if (abs(v) > abs(big)) big = v
        if (big < 0) for (i in full.indices) full[i] = -full[i]
        full
    }
    return DrumModes(mesh, lam.copyOf(count), shapes, iterations)
}

private fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
