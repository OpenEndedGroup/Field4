/**
 * Inverse design for the eigendrum: given the pitches you want, find the drum. Two knobs, each
 * fitted with Field's Levenberg–Marquardt solver ([afield.math.optimization.leastSquares]):
 *
 * - [designShape] bends the outline of a uniform drum, r(θ) as a short Fourier series
 *   ([PolarShape]), so its lowest modes sound a target chord. The mesh is one unit disk morphed
 *   radially ([polarMorphed]), so eigenvalues move smoothly with the coefficients and finite
 *   differences give a clean Jacobian. A drawn outline can be the starting guess
 *   ([PolarShape.fromPolygon]).
 * - [designSyahi] keeps the circle and fits a syahi of a few stacked layers (each a disk with its
 *   own radius and added density). Its Jacobian is exact and nearly free: for a mass-normalised
 *   mode, ∂ ln λᵢ / ∂ ln ρ(patch) = −(share of the mode's kinetic energy that sits on the patch).
 *
 * Shape alone cannot reach a harmonic series: for any uniform membrane the second eigenvalue is at
 * most (j₁,₁/j₀,₁)² ≈ 2.539 times the first (Ashbaugh & Benguria, Annals of Math. 135, 1992), so
 * f₂/f₁ ≤ 1.593, a long way short of the octave. That bound is why the tabla is loaded.
 */
package afield.audio.modal

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.optimization.LeastSquaresOptions
import afield.math.optimization.leastSquares
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh

/** Cents per natural-log unit of frequency. */
private val CENTS = 1200 / ln(2.0)

/**
 * A star-shaped outline about the origin, r(θ) = Σₖ [cosine]ₖ cos kθ + [sine]ₖ sin kθ, with
 * `cosine[0]` the mean radius. Arrays run k = 0..[harmonics].
 */
class PolarShape(val cosine: DoubleArray, val sine: DoubleArray) {
    init { require(cosine.size == sine.size && cosine.isNotEmpty()) }

    /** Highest harmonic. */
    val harmonics: Int get() = cosine.size - 1

    /** r(θ). */
    fun radius(theta: Double): Double {
        var r = 0.0
        for (k in cosine.indices) r += cosine[k] * cos(k * theta) + sine[k] * sin(k * theta)
        return r
    }

    /** Smallest radius over [samples] angles, as a fraction of the mean radius. */
    fun minRadius(samples: Int = 360): Double = (0 until samples).minOf { radius(2 * PI * it / samples) } / cosine[0]

    /** The outline as alternating x, y coordinates. */
    @JvmOverloads
    fun polygon(samples: Int = 256): DoubleArray = polarPolygon(samples) { radius(it) }

    /** The outline as a closed FLine. */
    @JvmOverloads
    fun outline(samples: Int = 256): FLine = polygonFLine(polygon(samples))

    /** A copy with the given coefficients changed. */
    fun copy(cosine: DoubleArray = this.cosine.copyOf(), sine: DoubleArray = this.sine.copyOf()) = PolarShape(cosine, sine)

    override fun toString(): String = (2..harmonics).joinToString(" ", "r = 1") { k ->
        "%+.3f cos%dθ %+.3f sin%dθ".format(cosine[k] / cosine[0], k, sine[k] / cosine[0], k)
    }

    companion object {
        /** The unit circle, with room for [harmonics] harmonics. */
        @JvmStatic
        fun circle(harmonics: Int): PolarShape =
            PolarShape(DoubleArray(harmonics + 1).also { it[0] = 1.0 }, DoubleArray(harmonics + 1))

        /** r = 1 + Σ cos-only terms `cosines[k - 2]` for k = 2.., a mirror-symmetric shape. */
        @JvmStatic
        fun of(vararg cosines: Double): PolarShape {
            val s = circle(cosines.size + 1)
            for (i in cosines.indices) s.cosine[i + 2] = cosines[i]
            return s
        }

        /** Fit a closed FLine (a drawing-layer stroke, say), taking its node end points as the polygon; see [fromPolygon]. */
        @JvmStatic
        @JvmOverloads
        fun fromLine(line: FLine, harmonics: Int = 3): PolarShape {
            val pts = ArrayList<Double>()
            for (nd in line.nodes) {
                val p = nd.storage.lastOrNull() ?: continue
                pts += p.x.toDouble(); pts += p.y.toDouble()
            }
            return fromPolygon(pts.toDoubleArray(), harmonics)
        }

        /**
         * Fit a closed polygon (alternating x, y) with [harmonics] harmonics about its area
         * centroid, scaled to mean radius 1: rays from the centroid at [samples] angles take the
         * farthest crossing of the outline, so a star-shaped outline is captured exactly.
         */
        @JvmStatic
        @JvmOverloads
        fun fromPolygon(xy: DoubleArray, harmonics: Int = 6, samples: Int = 360): PolarShape {
            val n = xy.size / 2
            var a = 0.0; var cx = 0.0; var cy = 0.0
            for (i in 0 until n) {
                val j = (i + 1) % n
                val cr = xy[2 * i] * xy[2 * j + 1] - xy[2 * j] * xy[2 * i + 1]
                a += cr; cx += (xy[2 * i] + xy[2 * j]) * cr; cy += (xy[2 * i + 1] + xy[2 * j + 1]) * cr
            }
            cx /= 3 * a; cy /= 3 * a
            val r = DoubleArray(samples) { s ->
                val th = 2 * PI * s / samples
                val dx = cos(th); val dy = sin(th)
                var best = 0.0
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val ax = xy[2 * i] - cx; val ay = xy[2 * i + 1] - cy
                    val ex = xy[2 * j] - xy[2 * i]; val ey = xy[2 * j + 1] - xy[2 * i + 1]
                    val den = dx * ey - dy * ex
                    if (kotlin.math.abs(den) < 1e-14) continue
                    val t = (ax * ey - ay * ex) / den
                    val u = (ax * dy - ay * dx) / den
                    if (t > 0 && u >= -1e-9 && u <= 1 + 1e-9) best = max(best, t)
                }
                best
            }
            val c = DoubleArray(harmonics + 1); val sn = DoubleArray(harmonics + 1)
            for (k in 0..harmonics) for (s in 0 until samples) {
                val th = 2 * PI * s / samples
                val w = if (k == 0) 1.0 / samples else 2.0 / samples
                c[k] += w * r[s] * cos(k * th); sn[k] += w * r[s] * sin(k * th)
            }
            val m = c[0]
            for (k in 0..harmonics) { c[k] /= m; sn[k] /= m }
            return PolarShape(c, sn)
        }
    }
}

/**
 * This mesh, taken to be of a unit disk about the origin, stretched radially so the rim follows
 * [shape]: a node at (ρ, θ) moves to ρ·r(θ). Connectivity and density are kept, so drums made this
 * way differ smoothly as the shape changes.
 */
fun DrumMesh.polarMorphed(shape: PolarShape): DrumMesh {
    val nx = DoubleArray(nodeCount); val ny = DoubleArray(nodeCount)
    for (i in 0 until nodeCount) {
        val th = atan2(y[i], x[i]); val r = shape.radius(th)
        nx[i] = x[i] * r; ny[i] = y[i] * r
    }
    return DrumMesh(nx, ny, tris, boundary, density)
}

/** The result of [designShape]. */
class ShapeDesign(
    /** The target ratios, lowest first. */
    val target: DoubleArray,
    /** The shape the fit started from. */
    val start: PolarShape,
    /** The fitted shape. */
    val shape: PolarShape,
    /** The fitted drum's modes. */
    val modes: DrumModes,
    /** How close the starting shape was. */
    val startFit: HarmonicFit,
    /** How close the fitted shape is. */
    val fit: HarmonicFit,
    /** Levenberg–Marquardt iterations. */
    val iterations: Int,
    /** Why the solver stopped. */
    val reason: String,
) {
    override fun toString(): String = "shape fit: $startFit → $fit after $iterations iterations ($reason)"
}

/**
 * Bend a uniform drum's outline so its lowest `target.size` modes sound [target] (ratios, any
 * scale, lowest first). Free parameters are the cos (and, unless [symmetric], sin) coefficients of
 * harmonics 2..`start.harmonics`; the mean radius and the k = 1 terms (a shift, to first order)
 * stay as in [start]. Outlines whose radius dips below 0.25 of the mean are rejected. [stayNear]
 * (cents per unit change of a coefficient) pulls the fit toward [start], for the nearest drum to a
 * drawn outline rather than any drum that plays the chord.
 */
@JvmOverloads
fun designShape(
    target: DoubleArray,
    start: PolarShape = PolarShape.of(0.12, 0.05),
    h: Double = 0.07,
    symmetric: Boolean = false,
    maxIterations: Int = 30,
    stayNear: Double = 0.0,
): ShapeDesign {
    val disk = polarDrumMesh(h) { 1.0 }
    val ks = (2..start.harmonics).toList()
    val perK = if (symmetric) 1 else 2
    fun shapeOf(p: DoubleArray): PolarShape {
        val s = start.copy()
        ks.forEachIndexed { i, k ->
            s.cosine[k] = p[perK * i]
            if (!symmetric) s.sine[k] = p[perK * i + 1]
        }
        return s
    }
    val p0 = DoubleArray(ks.size * perK) { i -> val k = ks[i / perK]; if (i % perK == 0) start.cosine[k] else start.sine[k] }
    val bad = DoubleArray(target.size + if (stayNear > 0) p0.size else 0) { 1e4 }
    var last: DrumModes? = null
    fun solve(p: DoubleArray): DrumModes? {
        val s = shapeOf(p)
        if (s.minRadius() < 0.25) return null
        return try {
            disk.polarMorphed(s).modes(target.size, tolerance = 1e-11, warm = last).also { last = it }
        } catch (e: IllegalArgumentException) { null }
    }
    val startModes = solve(p0) ?: error("starting shape is not a valid drum")
    val res = leastSquares(p0, LeastSquaresOptions(maxIterations = maxIterations, finiteDifferenceStep = 1e-5)) { p ->
        val m = solve(p) ?: return@leastSquares bad
        val c = harmonicFit(m.ratios(), target).cents
        if (stayNear > 0) c + DoubleArray(p.size) { stayNear * (p[it] - p0[it]) } else c
    }
    val shape = shapeOf(res.x)
    val modes = disk.polarMorphed(shape).modes(target.size)
    return ShapeDesign(target, start, shape, modes, harmonicFit(startModes.ratios(), target), harmonicFit(modes.ratios(), target), res.iterations, res.reason)
}

/** The result of [designSyahi]: a syahi of stacked layers fitted to a target. */
class SyahiDesign(
    /** The target ratios, lowest first. */
    val target: DoubleArray,
    /** Radius of each layer, as a fraction of the rim radius. */
    val radii: DoubleArray,
    /** Density each layer adds to the bare skin's 1. */
    val loads: DoubleArray,
    /** Width of each layer's tanh edge. */
    val edge: Double,
    /** The loaded mesh. */
    val mesh: DrumMesh,
    /** Its modes. */
    val modes: DrumModes,
    /** How close the bare (unloaded) drum is. */
    val bareFit: HarmonicFit,
    /** How close the starting syahi was. */
    val startFit: HarmonicFit,
    /** How close the fitted syahi is. */
    val fit: HarmonicFit,
    /** Levenberg–Marquardt iterations. */
    val iterations: Int,
    /** Why the solver stopped. */
    val reason: String,
) {
    /** Number of layers. */
    val layers: Int get() = radii.size

    /** Density at radius [r]: 1 plus every layer that covers it. */
    fun density(r: Double): Double = 1 + radii.indices.sumOf { loads[it] * layerShape(r, radii[it], edge) }

    /** Density at the centre, the heaviest point. */
    val peak: Double get() = density(0.0)

    /** The density profile ρ(r) as a line, r across [w] and ρ / [top] up [h]. */
    @JvmOverloads
    fun profile(w: Double = 1.0, h: Double = 1.0, top: Double = peak): FLine = FLine().apply {
        for (i in 0..200) {
            val r = i / 200.0
            val y = h * density(r) / top
            if (i == 0) moveTo(0.0, y) else lineTo(w * r, y)
        }
        color = afield.math.linalg.vec(1.0, 0.85, 0.3, 1.0)
        thickness = 2.0
    }

    override fun toString(): String =
        "$layers-layer syahi: bare $bareFit, start $startFit → $fit after $iterations iterations ($reason); " +
            radii.indices.joinToString(", ", "layers ") { "r %.3f +%.2f".format(radii[it], loads[it]) }
}

/** ½(1 − tanh((r − radius)/edge)): 1 inside a layer, 0 outside. */
internal fun layerShape(r: Double, radius: Double, edge: Double) = 0.5 * (1 - tanh((r - radius) / edge))

/**
 * Fit a syahi of [layers] stacked disks to [target] (default [Raman.target], the harmonic drum):
 * layer j covers radius rⱼ and adds density dⱼ ≥ 0, with a tanh edge of width [edge], so the paste
 * is thickest in the middle and steps down outward, as Raman describes (1935, section 2: "stepped
 * down by three, five or seven stages towards the margin"). The mesh is a unit disk at spacing [h];
 * each triangle's density is averaged over six points inside it. Starts from [startRadii] and
 * [startLoads] (by default spread about Ramakrishna & Sondhi's composite, radius 0.4, +8.77). The
 * Jacobian is exact (the energy-share formula), so each iteration costs one eigen-solve.
 *
 * Each layer adds at most (`maxDensity` − 1) / [layers], so the centre is never more than
 * [maxDensity] times the bare skin (16 is the top of the range Sathej & Adhikari give for real
 * dayans). Unbounded, the fit runs to ever heavier pastes: past a few tens the ratios stop
 * depending on the mass at all, and a two-layer syahi of a billion times the skin's density
 * scores best.
 */
@JvmOverloads
fun designSyahi(
    layers: Int = 3,
    target: DoubleArray = Raman.target,
    edge: Double = 0.04,
    h: Double = 0.06,
    startRadii: DoubleArray = if (layers == 1) doubleArrayOf(Raman.compositeRadius) else DoubleArray(layers) { 0.3 + 0.2 * it / (layers - 1.0) },
    startLoads: DoubleArray = DoubleArray(layers) { (Raman.compositeMassRatio - 1) / layers },
    maxIterations: Int = 40,
    maxDensity: Double = 16.0,
): SyahiDesign {
    require(layers >= 1 && startRadii.size == layers && startLoads.size == layers && maxDensity > 1)
    val cap = (maxDensity - 1) / layers
    val mesh = polarDrumMesh(h) { 1.0 }
    val n = target.size
    val nt = mesh.triangleCount
    // six sample radii inside each triangle (the same grid withDensity uses at samples = 3)
    val bary = ArrayList<DoubleArray>()
    for (i in 0 until 3) for (j in 0 until 3 - i) {
        val u = (i + 1.0 / 3) / 3; val v = (j + 1.0 / 3) / 3
        if (u + v <= 1) bary += doubleArrayOf(1 - u - v, u, v)
    }
    val sr = Array(nt) { t ->
        val a = mesh.tris[3 * t]; val b = mesh.tris[3 * t + 1]; val c = mesh.tris[3 * t + 2]
        DoubleArray(bary.size) { q ->
            val w = bary[q]
            hypot(w[0] * mesh.x[a] + w[1] * mesh.x[b] + w[2] * mesh.x[c], w[0] * mesh.y[a] + w[1] * mesh.y[b] + w[2] * mesh.y[c])
        }
    }
    // p = [q₁..q_L, r₁..r_L] with dⱼ = cap · qⱼ² / (1 + qⱼ²), so 0 ≤ dⱼ < cap
    fun loads(p: DoubleArray) = DoubleArray(layers) { cap * p[it] * p[it] / (1 + p[it] * p[it]) }
    fun radii(p: DoubleArray) = DoubleArray(layers) { p[layers + it] }
    fun rhoOf(p: DoubleArray): DoubleArray {
        val d = loads(p); val r = radii(p)
        return DoubleArray(nt) { t ->
            var s = 1.0
            for (j in 0 until layers) s += d[j] * sr[t].sumOf { layerShape(it, r[j], edge) } / sr[t].size
            s
        }
    }
    val cache = LinkedHashMap<List<Double>, DrumModes>()
    var last: DrumModes? = null
    fun solve(p: DoubleArray): DrumModes? {
        if (radii(p).any { it < 0.03 || it > 0.97 }) return null
        return cache.getOrPut(p.toList()) {
            if (cache.size > 6) cache.remove(cache.keys.first())
            mesh.withTriangleDensity(rhoOf(p)).modes(n, tolerance = 1e-8, warm = last).also { last = it }
        }
    }
    val bad = DoubleArray(n) { 1e4 }
    fun residuals(p: DoubleArray): DoubleArray = solve(p)?.let { harmonicFit(it.ratios(), target).cents } ?: bad
    fun jacobian(p: DoubleArray): Array<DoubleArray> {
        val modes = solve(p) ?: error("jacobian at an invalid syahi")
        val d = loads(p); val r = radii(p)
        // ∂ρₜ/∂qⱼ = cap · 2qⱼ / (1 + qⱼ²)² · ⟨Sⱼ⟩ₜ and ∂ρₜ/∂rⱼ = dⱼ ⟨½ sech²((r − rⱼ)/edge) / edge⟩ₜ
        val dRho = Array(2 * layers) { DoubleArray(nt) }
        for (t in 0 until nt) for (j in 0 until layers) {
            var s = 0.0; var ds = 0.0
            for (rq in sr[t]) {
                s += layerShape(rq, r[j], edge)
                val c = kotlin.math.cosh((rq - r[j]) / edge)
                ds += 0.5 / (c * c) / edge
            }
            dRho[j][t] = cap * 2 * p[j] / ((1 + p[j] * p[j]) * (1 + p[j] * p[j])) * s / sr[t].size
            dRho[layers + j][t] = d[j] * ds / sr[t].size
        }
        // g[i][k] = ∂ ln λᵢ / ∂ pₖ = −Σₜ (∂ρₜ/∂pₖ) φᵢᵀ M̂ₜ φᵢ
        val g = Array(n) { i ->
            val e = DoubleArray(nt) { t -> elementEnergy(mesh, t, modes.shapes[i]) }
            DoubleArray(2 * layers) { k -> var s = 0.0; for (t in 0 until nt) s -= dRho[k][t] * e[t]; s }
        }
        return Array(n) { i -> DoubleArray(2 * layers) { k -> CENTS * 0.5 * (g[i][k] - (0 until n).sumOf { g[it][k] } / n) } }
    }
    val p0 = DoubleArray(2 * layers) {
        if (it < layers) { val d = startLoads[it].coerceIn(0.0, 0.98 * cap); kotlin.math.sqrt(d / (cap - d)) } else startRadii[it - layers]
    }
    val bareFit = harmonicFit(mesh.modes(n).ratios(), target)
    val startFit = harmonicFit((solve(p0) ?: error("starting syahi is invalid")).ratios(), target)
    val res = leastSquares(p0, LeastSquaresOptions(maxIterations = maxIterations, initialDamping = 0.1), jacobian = ::jacobian, residuals = ::residuals)
    val modes = solve(res.x)!!
    return SyahiDesign(target, radii(res.x), loads(res.x), edge, mesh.withTriangleDensity(rhoOf(res.x)), modes, bareFit, startFit, harmonicFit(modes.ratios(), target), res.iterations, res.reason)
}

/** φᵀ M̂ₜ φ on triangle [t] at unit density, with the solver's blended (½ consistent + ½ lumped) mass. */
internal fun elementEnergy(mesh: DrumMesh, t: Int, s: DoubleArray): Double {
    val a = s[mesh.tris[3 * t]]; val b = s[mesh.tris[3 * t + 1]]; val c = s[mesh.tris[3 * t + 2]]
    val ar = mesh.triArea(t)
    val consistent = ar / 12.0 * (2 * (a * a + b * b + c * c) + 2 * (a * b + b * c + c * a))
    val lumped = ar / 3.0 * (a * a + b * b + c * c)
    return 0.5 * (consistent + lumped)
}

/**
 * The share of mode [mode]'s kinetic energy carried by the triangles [inside] picks (by index):
 * Σ ρₜ φᵀM̂ₜφ over them, with the solver's default blended mass, so all triangles give 1. It is
 * also the gradient designs use: making those triangles 1% heavier lowers λ by about this share
 * of 1%.
 */
fun DrumModes.energyShare(mode: Int, inside: (Int) -> Boolean): Double {
    var s = 0.0
    val d = mesh.density
    for (t in 0 until mesh.triangleCount) if (inside(t)) s += (d?.get(t) ?: 1.0) * elementEnergy(mesh, t, shapes[mode])
    return s
}
