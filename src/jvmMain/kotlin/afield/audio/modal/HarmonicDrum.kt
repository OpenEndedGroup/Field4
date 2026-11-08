/**
 * Raman's harmonic drum. A uniform circular membrane rings at 1 : 1.59 : 2.14 : 2.30 ..., but the
 * tabla and mridangam load the centre of the head with a heavy paste (the syahi), and C. V. Raman
 * showed (Nature 1920; Proc. Indian Acad. Sci. A 1, 1935) that this pulls the first nine modes into
 * five nearly harmonic tones, 1 : 2 : 3 : 4 : 5. This file puts that load into the finite-element
 * drum as a per-triangle density ([withDensity], [syahi], [loadedDisk]), names each mode of a
 * circular drum by its nodal diameters and circles ([circularLabel]), scores a spectrum against
 * Raman's harmonic series ([ramanHarmonicity]) and sweeps loading radius × mass ratio into a
 * harmonicity map ([harmonicityMap]). The reference numbers are in [Raman].
 */
package afield.audio.modal

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.ui.visualization.Table
import afield.ui.visualization.table
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Raman's account of the harmonic drum, and the numbers later work measured and computed for it.
 */
object Raman {
    /**
     * Raman's identification (1935, sections 4 to 7) of which normal modes make each of the five
     * tones, as (nodal diameters, nodal circles counting the rim, harmonic). The third, fourth and
     * fifth tones are each two or three different modes tuned to one pitch.
     */
    val families: List<Triple<Int, Int, Int>> = listOf(
        Triple(0, 1, 1),
        Triple(1, 1, 2),
        Triple(2, 1, 3), Triple(0, 2, 3),
        Triple(3, 1, 4), Triple(1, 2, 4),
        Triple(4, 1, 5), Triple(2, 2, 5), Triple(0, 3, 5),
    )

    /**
     * The fifteen lowest eigenvalues of a harmonic drum as frequency multiples, counting each
     * degenerate pair (a mode with nodal diameters comes as two rotated copies) twice: one
     * fundamental, two at the octave, three at the twelfth, four at the double octave, five above.
     */
    val target: DoubleArray = doubleArrayOf(1.0, 2.0, 2.0, 3.0, 3.0, 3.0, 4.0, 4.0, 4.0, 4.0, 5.0, 5.0, 5.0, 5.0, 5.0)

    /**
     * Measured frequencies of the nine modes of [families], in that order, scaled so the (1,1) mode
     * is 2: the "Experimental" column of Sathej & Adhikari's Table I (JASA 125, 831, 2009).
     */
    val measured: DoubleArray = doubleArrayOf(1.03, 2.00, 3.00, 3.00, 4.00, 4.00, 5.03, 5.08, 5.04)

    /** Ramakrishna & Sondhi's composite membrane (JASA 26, 523, 1954): loaded radius / rim radius. */
    const val compositeRadius = 0.4

    /**
     * Ramakrishna & Sondhi's density ratio, loaded / bare. They quote σ = 3.125, the ratio of wave
     * speeds, which is the square root of the density ratio.
     */
    const val compositeMassRatio = 3.125 * 3.125

    /**
     * The composite membrane's exact frequencies for the nine modes of [families], scaled so (1,1)
     * is 2 (the "Composite" column of Sathej & Adhikari's Table I).
     */
    val compositeExact: DoubleArray = doubleArrayOf(1.0309, 2.0000, 3.0412, 3.1546, 4.0928, 4.2268, 5.1134, 5.3093, 4.9794)

    /**
     * Sathej & Adhikari's most harmonic smooth loading (their eq. 1): radius 0.492, density ratio
     * 2.57² ≈ 6.6, edge width 0.091.
     */
    val sathejAdhikari: Triple<Double, Double, Double> = Triple(0.492, 2.57 * 2.57, 0.091)
}

/** A mode of a circular drum named by [diameters] (nodal lines through the centre) and [circles] (nodal circles, the rim included). */
data class CircularLabel(val diameters: Int, val circles: Int) {
    override fun toString(): String = "($diameters,$circles)"
}

/**
 * This mesh with a density for every triangle: [rho] at (x, y), averaged over 21 points inside
 * each triangle, so a load whose edge cuts a triangle gives it the area-weighted mix.
 */
fun DrumMesh.withDensity(rho: (Double, Double) -> Double): DrumMesh = withDensity(6, rho)

/** [withDensity] averaging [rho] over a grid of about [samples]² / 2 points per triangle. */
fun DrumMesh.withDensity(samples: Int, rho: (Double, Double) -> Double): DrumMesh {
    require(samples >= 1)
    val d = DoubleArray(triangleCount) { t ->
        val a = tris[3 * t]; val b = tris[3 * t + 1]; val c = tris[3 * t + 2]
        var s = 0.0; var n = 0
        for (i in 0 until samples) for (j in 0 until samples - i) {
            val u = (i + 1.0 / 3) / samples; val v = (j + 1.0 / 3) / samples
            if (u + v > 1) continue
            val w = 1 - u - v
            s += rho(w * x[a] + u * x[b] + v * x[c], w * y[a] + u * y[b] + v * y[c]); n++
        }
        s / n
    }
    return DrumMesh(x, y, tris, boundary, d)
}

/** This mesh with an explicit density per triangle. */
fun DrumMesh.withTriangleDensity(perTriangle: DoubleArray): DrumMesh = DrumMesh(x, y, tris, boundary, perTriangle)

/**
 * A syahi: density [massRatio] inside [radius] of ([cx], [cy]) and 1 outside. With [edge] = 0 the
 * step is sharp (Ramakrishna & Sondhi's composite membrane); with [edge] > 0 it falls off as
 * Sathej & Adhikari's 1 + (μ − 1)/2 · (1 − tanh((r − radius)/edge)).
 */
@JvmOverloads
fun syahi(radius: Double, massRatio: Double, edge: Double = 0.0, cx: Double = 0.0, cy: Double = 0.0): (Double, Double) -> Double =
    { x, y ->
        val r = hypot(x - cx, y - cy)
        if (edge <= 0) (if (r < radius) massRatio else 1.0)
        else 1 + (massRatio - 1) / 2 * (1 - tanh((r - radius) / edge))
    }

/** A unit-radius circular drum loaded with a [syahi] of [radius] and [massRatio], meshed at spacing [h]. */
@JvmOverloads
fun loadedDisk(radius: Double, massRatio: Double, edge: Double = 0.0, h: Double = 0.05): DrumMesh =
    polarDrumMesh(h) { 1.0 }.withDensity(syahi(radius, massRatio, edge))

/**
 * Name mode [mode] of a drum centred on ([cx], [cy]) as (nodal diameters, nodal circles): the
 * angular order n is the Fourier harmonic in θ that holds most of the mode's energy, and the circle
 * count is the number of sign changes, plus one for the rim, of that harmonic's radial profile.
 */
@JvmOverloads
fun DrumModes.circularLabel(mode: Int, cx: Double = 0.0, cy: Double = 0.0, maxDiameters: Int = 10, bins: Int = 14): CircularLabel {
    val m = mesh; val s = shapes[mode]
    val w = DoubleArray(m.nodeCount)
    for (t in 0 until m.triangleCount) { val a = m.triArea(t) / 3; for (i in 0..2) w[m.tris[3 * t + i]] += a }
    var rMax = 0.0
    for (i in 0 until m.nodeCount) rMax = max(rMax, hypot(m.x[i] - cx, m.y[i] - cy))
    val re = Array(maxDiameters + 1) { DoubleArray(bins) }
    val im = Array(maxDiameters + 1) { DoubleArray(bins) }
    for (i in 0 until m.nodeCount) {
        if (m.boundary[i]) continue
        val dx = m.x[i] - cx; val dy = m.y[i] - cy
        val b = min(bins - 1, (hypot(dx, dy) / rMax * bins).toInt())
        val th = atan2(dy, dx)
        for (n in 0..maxDiameters) { re[n][b] += w[i] * s[i] * cos(n * th); im[n][b] -= w[i] * s[i] * sin(n * th) }
    }
    var best = 0; var bestE = -1.0
    for (n in 0..maxDiameters) {
        var e = 0.0
        for (b in 0 until bins) e += re[n][b] * re[n][b] + im[n][b] * im[n][b]
        if (n > 0) e *= 2
        if (e > bestE) { bestE = e; best = n }
    }
    // project every bin on the phase of the strongest one, then count sign changes outward
    var bigB = 0
    for (b in 0 until bins) if (hypot(re[best][b], im[best][b]) > hypot(re[best][bigB], im[best][bigB])) bigB = b
    val pr = re[best][bigB]; val pi = im[best][bigB]; val pn = hypot(pr, pi)
    val prof = DoubleArray(bins) { (re[best][it] * pr + im[best][it] * pi) / pn }
    val floor = 0.08 * prof.maxOf { abs(it) }
    var changes = 0; var last = 0
    for (v in prof) {
        if (abs(v) < floor) continue
        val sg = if (v > 0) 1 else -1
        if (last != 0 && sg != last) changes++
        last = sg
    }
    return CircularLabel(best, changes + 1)
}

/** [circularLabel] for every mode. */
@JvmOverloads
fun DrumModes.circularLabels(cx: Double = 0.0, cy: Double = 0.0): List<CircularLabel> = (0 until count).map { circularLabel(it, cx, cy) }

/**
 * How far a set of [ratios] is from a [target] set, both as frequency multiples in matching order:
 * the common [scale] that best tunes one to the other (least squares in log frequency), each
 * ratio's error in cents after that tuning, and their [rms].
 */
class HarmonicFit(
    /** Multiplier applied to the computed ratios before comparing. */
    val scale: Double,
    /** Error of each ratio in cents (1/100 of a semitone), sharp positive. */
    val cents: DoubleArray,
) {
    /** Root-mean-square error in cents. */
    val rms: Double get() = sqrt(cents.sumOf { it * it } / cents.size)

    /** Largest absolute error in cents. */
    val worst: Double get() = cents.maxOf { abs(it) }

    override fun toString(): String = "rms %.1f cents (worst %.1f)".format(rms, worst)
}

/** Fit [ratios] (or frequencies, at any scale) to [target]; compares the first `min(sizes)` entries. */
fun harmonicFit(ratios: DoubleArray, target: DoubleArray): HarmonicFit {
    val n = min(ratios.size, target.size)
    require(n >= 1)
    var mean = 0.0
    for (i in 0 until n) mean += ln(target[i] / ratios[i])
    mean /= n
    val c = DoubleArray(n) { 1200 / ln(2.0) * (ln(ratios[it] / target[it]) + mean) }
    return HarmonicFit(kotlin.math.exp(mean), c)
}

/**
 * How close this drum is to Raman's harmonic drum: its lowest eigenvalues (up to fifteen, sorted,
 * degenerate pairs counted twice) against [Raman.target], 1 : 2 2 : 3 3 3 : 4 4 4 4 : 5 5 5 5 5.
 */
fun DrumModes.ramanHarmonicity(): HarmonicFit = harmonicFit(ratios(), Raman.target)

/**
 * The frequency of each of Raman's nine mode families ([Raman.families]) in this circular drum,
 * found by [circularLabel] and scaled so the (1,1) mode is 2, as in Sathej & Adhikari's Table I.
 * NaN where a family is not among the computed modes.
 */
fun DrumModes.ramanFamilies(): DoubleArray {
    val labels = circularLabels()
    val r = ratios()
    fun f(n: Int, m: Int): Double {
        val idx = labels.indices.filter { labels[it].diameters == n && labels[it].circles == m }
        return if (idx.isEmpty()) Double.NaN else idx.sumOf { r[it] } / idx.size
    }
    val ref = f(1, 1)
    return DoubleArray(Raman.families.size) { val (n, m, _) = Raman.families[it]; 2 * f(n, m) / ref }
}

/**
 * Harmonicity over a grid of loadings: [cents]`[i][j]` is the rms distance from Raman's harmonic
 * series ([ramanHarmonicity]) of a unit drum loaded to [massRatios]`[i]` inside [radii]`[j]`.
 */
class HarmonicityMap(
    /** Loading radii, as fractions of the rim radius (columns). */
    val radii: DoubleArray,
    /** Density ratios, loaded / bare (rows). */
    val massRatios: DoubleArray,
    /** Rms cents from 1:2:3:4:5 for each (mass ratio, radius). */
    val cents: Array<DoubleArray>,
) {
    /** The (row, column) of the most harmonic loading on the grid. */
    val best: Pair<Int, Int>
        get() {
            var bi = 0; var bj = 0
            for (i in massRatios.indices) for (j in radii.indices) if (cents[i][j] < cents[bi][bj]) { bi = i; bj = j }
            return bi to bj
        }

    /** The map as a heated [Table]: one row per mass ratio, one column per radius, darker is more harmonic. */
    fun table(): Table {
        val cols = radii.map { "k %.2f".format(it) }
        val t = table(
            "μ" to massRatios.toList(),
            *cols.mapIndexed { j, name -> name to massRatios.indices.map { cents[it][j] } }.toTypedArray(),
        ).format("μ") { "%.1f".format(it as Double) }
        cols.forEach { name -> t.format(name) { "%.0f".format(it as Double) } }
        return t.heat(*cols.toTypedArray(), by = afield.ui.visualization.HeatScope.TABLE)
    }

    /**
     * The map as filled cells for `lines(...)` (whose y runs down), radius across and mass ratio up
     * (log spaced rows are drawn evenly),
     * coloured from bright (harmonic, below [good] cents) to dark (above [bad] cents), in a [w] × [h]
     * box. [marks] are extra (radius, mass ratio) points drawn as white rings, interpolated
     * between grid lines.
     */
    @JvmOverloads
    fun cells(w: Double = 1.0, h: Double = 1.0, good: Double = 15.0, bad: Double = 250.0, marks: List<Pair<Double, Double>> = emptyList()): List<FLine> {
        val out = ArrayList<FLine>()
        val cw = w / radii.size; val ch = h / massRatios.size
        for (i in massRatios.indices) for (j in radii.indices) {
            val u = ((ln(cents[i][j]) - ln(good)) / (ln(bad) - ln(good))).coerceIn(0.0, 1.0)
            out += FLine().apply {
                val x0 = j * cw; val y0 = (massRatios.size - 1 - i) * ch
                moveTo(x0, y0); lineTo(x0 + cw * 0.96, y0); lineTo(x0 + cw * 0.96, y0 + ch * 0.96); lineTo(x0, y0 + ch * 0.96); lineTo(x0, y0)
                filled = true; stroked = false
                color = heatColour(1 - u)
            }
        }
        for ((k, mu) in marks) {
            val px = (interpIndex(radii, k) + 0.5) * cw
            val py = (massRatios.size - 1 - interpIndex(massRatios, mu) + 0.5) * ch
            out += FLine().apply {
                val rr = 0.35 * min(cw, ch)
                moveTo(px + rr, py)
                for (q in 1..32) lineTo(px + rr * cos(2 * PI * q / 32), py + rr * sin(2 * PI * q / 32))
                color = afield.math.linalg.vec(1.0, 1.0, 1.0, 1.0)
                thickness = 2.0
            }
        }
        return out
    }

    private fun interpIndex(a: DoubleArray, v: Double): Double {
        if (v <= a.first()) return 0.0
        for (i in 0 until a.size - 1) if (v <= a[i + 1]) {
            val logScale = a.all { it > 0 } && a.last() / a.first() > 4
            val f = if (logScale) ln(v / a[i]) / ln(a[i + 1] / a[i]) else (v - a[i]) / (a[i + 1] - a[i])
            return i + f
        }
        return a.size - 1.0
    }
}

/** Dark purple → orange → pale yellow for u in 0..1 (u = 1 brightest). */
internal fun heatColour(u: Double): afield.math.linalg.Vector4f {
    val stops = arrayOf(
        doubleArrayOf(0.05, 0.03, 0.15), doubleArrayOf(0.45, 0.10, 0.45), doubleArrayOf(0.85, 0.30, 0.30),
        doubleArrayOf(0.98, 0.62, 0.20), doubleArrayOf(0.99, 0.95, 0.65),
    )
    val p = u.coerceIn(0.0, 1.0) * (stops.size - 1)
    val i = min(p.toInt(), stops.size - 2); val f = p - i
    return afield.math.linalg.vec(
        stops[i][0] + f * (stops[i + 1][0] - stops[i][0]),
        stops[i][1] + f * (stops[i + 1][1] - stops[i][1]),
        stops[i][2] + f * (stops[i + 1][2] - stops[i][2]),
        1.0,
    )
}

/**
 * Sweep loading radius × mass ratio on one disk mesh of spacing [h], solving [count] modes for each
 * loading (in parallel) and scoring it with [ramanHarmonicity].
 */
@JvmOverloads
fun harmonicityMap(radii: DoubleArray, massRatios: DoubleArray, h: Double = 0.07, edge: Double = 0.0, count: Int = 15): HarmonicityMap {
    val disk = polarDrumMesh(h) { 1.0 }
    val cents = Array(massRatios.size) { DoubleArray(radii.size) }
    val jobs = massRatios.indices.flatMap { i -> radii.indices.map { j -> i to j } }
    jobs.parallelStream().forEach { (i, j) ->
        val modes = disk.withDensity(syahi(radii[j], massRatios[i], edge)).modes(count, tolerance = 1e-8)
        cents[i][j] = harmonicFit(modes.ratios(), Raman.target).rms
    }
    return HarmonicityMap(radii, massRatios, cents)
}

/** [n] values from [a] to [b] spaced evenly in log. */
fun logSpace(a: Double, b: Double, n: Int): DoubleArray = DoubleArray(n) { a * Math.pow(b / a, if (n == 1) 0.0 else it / (n - 1.0)) }

/** [n] values from [a] to [b] spaced evenly. */
fun linSpace(a: Double, b: Double, n: Int): DoubleArray = DoubleArray(n) { if (n == 1) a else a + (b - a) * it / (n - 1.0) }

/** Mode [a] plus [weight] × mode [b], for drawing Raman's superposition figures with [DrumModes.nodalLines]-style output. */
fun DrumModes.superpose(a: Int, b: Int, weight: Double): DoubleArray = DoubleArray(mesh.nodeCount) { shapes[a][it] + weight * shapes[b][it] }

/** The nodal lines (zero set) of an arbitrary field [s] on this mesh, one value per node. */
fun DrumMesh.zeroLines(s: DoubleArray): FLine {
    val f = FLine()
    for (t in 0 until triangleCount) {
        val v = intArrayOf(tris[3 * t], tris[3 * t + 1], tris[3 * t + 2])
        val pts = ArrayList<DoubleArray>(2)
        for (e in 0..2) {
            val i = v[e]; val j = v[(e + 1) % 3]
            if (boundary[i] || boundary[j]) continue
            if ((s[i] < 0) != (s[j] < 0)) {
                val u = s[i] / (s[i] - s[j])
                pts += doubleArrayOf(x[i] + u * (x[j] - x[i]), y[i] + u * (y[j] - y[i]))
            }
        }
        if (pts.size == 2) { f.moveTo(pts[0][0], pts[0][1]); f.lineTo(pts[1][0], pts[1][1]) }
    }
    return f
}

/** The density of each triangle as grey-to-dark filled triangles (bare skin pale, heaviest load darkest). */
fun DrumMesh.densityMesh(levels: Int = 12): List<FLine> {
    val d = density ?: DoubleArray(triangleCount) { 1.0 }
    val top = max(d.max(), 1.0 + 1e-9)
    val bins = sortedMapOf<Int, FLine>()
    for (t in 0 until triangleCount) {
        val u = ln(d[t]) / ln(top)
        val b = (u * levels).toInt().coerceIn(0, levels)
        val f = bins.getOrPut(b) {
            val g = 0.85 - 0.75 * b / levels
            FLine().apply { filled = true; stroked = false; color = afield.math.linalg.vec(g, g * 0.95, g * 0.9, 1.0) }
        }
        val a = tris[3 * t]; val bb = tris[3 * t + 1]; val c = tris[3 * t + 2]
        f.moveTo(x[a], y[a]); f.lineTo(x[bb], y[bb]); f.lineTo(x[c], y[c]); f.lineTo(x[a], y[a])
    }
    return bins.values.toList()
}
