@file:afield.runtime.completion.CompletionCategory("ALife")

package afield.simulation.agents

import afield.media.image.Img
import afield.media.image.colormap
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Particle Lenia: Lenia with the grid taken away.
 *
 * After Mordvintsev, Niklasson and Randazzo, *Particle Lenia and the energy-based formulation*
 * (Google Research, 2022, `google-research.github.io/self-organising-systems/particle-lenia`; live
 * demo at `znah.net/lenia`). A fixed set of particles (so mass is conserved by construction) defines
 * two fields over the plane:
 *
 * - the Lenia potential `U(x) = Σ K(|x − p_i|)`, with a Gaussian ring kernel
 *   `K(r) = wK · exp(−((r − muK) / sigmaK)²)`;
 * - a short-range repulsion `R(x) = (cRep / 2) Σ max(1 − |x − p_i|, 0)²`.
 *
 * The energy is `E = R − G(U)` with the Gaussian growth `G(u) = exp(−((u − muG) / sigmaG)²)`, and
 * every particle slides downhill, `dp/dt = −∇E(p)`, the gradient taken analytically. The defaults
 * are the article's.
 */
data class ParticleLeniaParams(
    /** Radius of the kernel's ring. */
    val muK: Double = 4.0,
    /** Width of the kernel's ring. */
    val sigmaK: Double = 1.0,
    /** Height of the kernel; sets how many neighbours make `U` reach [muG]. */
    val wK: Double = 0.022,
    /** The value of `U` the growth curve rewards. */
    val muG: Double = 0.6,
    /** Width of the growth curve. */
    val sigmaG: Double = 0.15,
    /** Strength of the repulsion inside unit distance. */
    val cRep: Double = 1.0,
    /** Euler step. */
    val dt: Double = 0.1,
) {
    /** The kernel at distance [r]. */
    fun kernel(r: Double): Double { val t = (r - muK) / sigmaK; return wK * exp(-t * t) }

    /** The growth curve at potential [u]. */
    fun growth(u: Double): Double { val t = (u - muG) / sigmaG; return exp(-t * t) }

    companion object {
        /**
         * The [wK] that makes the kernel integrate to one over the plane, for a ring of radius [muK] and
         * width [sigmaK]: the article's normalisation, computed the way the demo computes it (trapezoid
         * rule over `muK ± 3 sigmaK` in steps of `sigmaK / 10`). It gives 0.0225 for the defaults,
         * against the article's rounded 0.022.
         */
        fun normalisedWK(muK: Double, sigmaK: Double): Double {
            val dr = 0.1 * sigmaK
            var acc = 0.0
            var prev = Double.NaN
            var r = max(muK - 3 * sigmaK, 0.0)
            while (r < muK + 3 * sigmaK) {
                val y = (r - muK) / sigmaK
                val v = r * exp(-y * y)
                if (!prev.isNaN()) acc += (prev + v) * 0.5
                prev = v
                r += dr
            }
            return 1.0 / (acc * dr * 2.0 * Math.PI)
        }
    }
}

/**
 * The fields of a [ParticleLenia] at one point: the potential [u], repulsion [r], growth [g], energy
 * [e] `= r − g`, and the energy gradient ([gx], [gy]).
 */
class ParticleLeniaSample(
    /** Lenia potential `U`. */
    val u: Double,
    /** Repulsion `R`. */
    val r: Double,
    /** Growth `G(U)`. */
    val g: Double,
    /** Energy `R − G(U)`. */
    val e: Double,
    /** x component of `∇E`. */
    val gx: Double,
    /** y component of `∇E`. */
    val gy: Double,
)

/**
 * A population of Particle Lenia particles in the plane. Mutable: [step] moves them in place.
 *
 * ```
 * val pl = ParticleLenia.random(200, side = 12.0, seed = 1L)
 * pl.stepTimes(2000)
 * println(pl)                                   // energy, spread, rotation
 * showImg(pl.render(12.0, 480))                 // particles over the energy field
 * ```
 */
class ParticleLenia(
    /** Positions, interleaved `x0, y0, x1, y1, …`. */
    val positions: DoubleArray,
    /** The rule. */
    val params: ParticleLeniaParams = ParticleLeniaParams(),
) {
    init { require(positions.size % 2 == 0 && positions.isNotEmpty()) { "positions must be x,y pairs" } }

    /** Number of particles. */
    val count: Int get() = positions.size / 2

    /** Steps taken so far. */
    var steps: Int = 0
        private set

    /**
     * Mean particle energy, one entry per state: index `k` is the energy of the arrangement after `k`
     * steps. [step] records the energy of the state it starts from (it has those samples anyway), so the
     * latest state's entry is appended by the next step; [currentEnergy] measures it now.
     */
    val energyHistory: MutableList<Double> = mutableListOf()

    /** The last step's velocities, interleaved like [positions]. */
    val velocities: DoubleArray = DoubleArray(positions.size)

    /**
     * Fraction of the last step's displacement carried into the next. `0`, the default, is the
     * article's Euler step `p += dt · v`. `0.5` is the integrator of the article's live demo,
     * `p += 0.5 (p − p_prev) + 0.5 dt · v`, which reaches the same velocity `dt · v` once the motion is
     * steady, so a steady glider has the same speed under both.
     */
    var inertia: Double = 0.0

    private val lastMove = DoubleArray(positions.size)

    /** The fields at `(x, y)`, summed over every particle. */
    fun sample(x: Double, y: Double): ParticleLeniaSample {
        val p = params
        var u = 0.0; var ux = 0.0; var uy = 0.0
        var r = 0.0; var rx = 0.0; var ry = 0.0
        for (j in 0 until count) {
            val dx = x - positions[2 * j]
            val dy = y - positions[2 * j + 1]
            val d = sqrt(dx * dx + dy * dy)
            val k = p.kernel(d)
            u += k
            if (d > 1e-10) {
                // dK/dr = −2 (r − muK) / sigmaK² · K
                val dk = -2.0 * (d - p.muK) / (p.sigmaK * p.sigmaK) * k / d
                ux += dk * dx; uy += dk * dy
                if (d < 1.0) {
                    val s = 1.0 - d
                    r += 0.5 * p.cRep * s * s
                    val dr = -p.cRep * s / d
                    rx += dr * dx; ry += dr * dy
                }
            } else r += 0.5 * p.cRep
        }
        val g = p.growth(u)
        val dg = -2.0 * (u - p.muG) / (p.sigmaG * p.sigmaG) * g
        return ParticleLeniaSample(u, r, g, r - g, rx - dg * ux, ry - dg * uy)
    }

    /** Mean energy `E` over the particles' own positions; it falls as the population settles. */
    fun meanEnergy(): Double {
        var s = 0.0
        for (i in 0 until count) s += sample(positions[2 * i], positions[2 * i + 1]).e
        return s / count
    }

    /** One Euler step of `dp/dt = −∇E`, all particles from the same old positions. */
    fun step(): ParticleLenia {
        var e = 0.0
        for (i in 0 until count) {
            val s = sample(positions[2 * i], positions[2 * i + 1])
            e += s.e
            velocities[2 * i] = -s.gx
            velocities[2 * i + 1] = -s.gy
        }
        for (k in positions.indices) {
            val move = inertia * lastMove[k] + (1.0 - inertia) * params.dt * velocities[k]
            positions[k] += move
            lastMove[k] = move
        }
        steps++
        energyHistory += e / count
        return this
    }

    /** Mean energy of the current arrangement, measured now (the entry the next [step] will record). */
    fun currentEnergy(): Double = meanEnergy()

    /** [step] [n] times. */
    fun stepTimes(n: Int): ParticleLenia { repeat(n) { step() }; return this }

    /** Centre of mass `(x, y)`. */
    fun centroid(): Pair<Double, Double> {
        var x = 0.0; var y = 0.0
        for (i in 0 until count) { x += positions[2 * i]; y += positions[2 * i + 1] }
        return x / count to y / count
    }

    /** RMS distance of the particles from their centroid. */
    fun spread(): Double {
        val (cx, cy) = centroid()
        var s = 0.0
        for (i in 0 until count) {
            val dx = positions[2 * i] - cx; val dy = positions[2 * i + 1] - cy
            s += dx * dx + dy * dy
        }
        return sqrt(s / count)
    }

    /**
     * Mean angular velocity about the centroid from the last step's velocities, radians per unit time;
     * near zero for a still pattern, steady and non-zero for a rotator.
     */
    fun angularVelocity(): Double {
        val (cx, cy) = centroid()
        var l = 0.0; var i2 = 0.0
        for (i in 0 until count) {
            val dx = positions[2 * i] - cx; val dy = positions[2 * i + 1] - cy
            l += dx * velocities[2 * i + 1] - dy * velocities[2 * i]
            i2 += dx * dx + dy * dy
        }
        return if (i2 < 1e-12) 0.0 else l / i2
    }

    /** Speed of the centroid from the last step's velocities; non-zero for a glider. */
    fun centroidSpeed(): Double {
        var vx = 0.0; var vy = 0.0
        for (i in 0 until count) { vx += velocities[2 * i]; vy += velocities[2 * i + 1] }
        return sqrt(vx * vx + vy * vy) / count
    }

    /**
     * A field sampled on a [size] × [size] grid covering `extent` world units centred on the
     * centroid (or on ([cx], [cy])), as a one-channel image, top row = largest y.
     *
     * @param which `"E"`, `"U"`, `"G"` or `"R"`
     */
    fun fieldImg(
        extent: Double,
        size: Int,
        which: String = "E",
        cx: Double = centroid().first,
        cy: Double = centroid().second,
    ): Img {
        val out = FloatArray(size * size)
        for (py in 0 until size) for (px in 0 until size) {
            val x = cx + (px + 0.5) / size * extent - extent / 2
            val y = cy - (py + 0.5) / size * extent + extent / 2
            val s = sample(x, y)
            out[py * size + px] = when (which) {
                "U" -> s.u; "G" -> s.g; "R" -> s.r; else -> s.e
            }.toFloat()
        }
        return Img(size, size, 1, out)
    }

    /**
     * The particles drawn as white dots over the energy field, on a [size]-pixel square covering [extent]
     * world units round the centroid. The field is drawn as `−E` with [colormap] from [lo] to [hi], so
     * empty space (`E = 0`) is the dark end and the energy wells the particles sit in are bright.
     */
    fun render(
        extent: Double,
        size: Int = 480,
        colormap: String = "inferno",
        lo: Float = 0f,
        hi: Float = 0.8f,
        dotRadius: Double = 2.5,
        cx: Double = centroid().first,
        cy: Double = centroid().second,
    ): Img {
        val e = fieldImg(extent, size, "E", cx, cy)
        for (k in e.data.indices) e.data[k] = -e.data[k]
        val field = e.colormap(colormap, lo, hi)
        return splat(field, extent, cx, cy, dotRadius)
    }

    /** White dots for the particles over an existing [size]-square RGB image [img] of the same framing. */
    fun splat(img: Img, extent: Double, cx: Double, cy: Double, dotRadius: Double = 2.5): Img =
        splatDots(img, List(count) { positions[2 * it] to positions[2 * it + 1] }, extent, cx, cy, dotRadius)

    /** An independent copy, history included. */
    fun copy(): ParticleLenia {
        val c = ParticleLenia(positions.copyOf(), params)
        c.energyHistory.clear(); c.energyHistory += energyHistory; c.steps = steps
        velocities.copyInto(c.velocities)
        lastMove.copyInto(c.lastMove)
        c.inertia = inertia
        return c
    }

    override fun toString(): String =
        "ParticleLenia(%d particles, step %d, mean E %.4f, spread %.2f, rotation %.4f rad/t, drift %.4f/t)".format(
            count, steps, meanEnergy(), spread(), angularVelocity(), centroidSpeed()
        )

    companion object {
        /**
         * [n] particles uniform in a square of side [side] centred on the origin; the article's seed is
         * 200 particles in a 12-unit square.
         */
        fun random(n: Int = 200, side: Double = 12.0, seed: Long = 1L, params: ParticleLeniaParams = ParticleLeniaParams()): ParticleLenia {
            val rnd = java.util.Random(seed)
            return ParticleLenia(DoubleArray(2 * n) { (rnd.nextDouble() - 0.5) * side }, params)
        }
    }
}

/**
 * White dots of radius [dotRadius] pixels at world [points] over a copy of the square image [img],
 * which frames [extent] world units centred on ([cx], [cy]) with +y up — the framing
 * [ParticleLenia.render] uses, so a path (a centroid track, say) can be drawn over a render.
 */
fun splatDots(img: Img, points: List<Pair<Double, Double>>, extent: Double, cx: Double, cy: Double, dotRadius: Double = 2.5): Img {
    val size = img.width
    val d = img.data.copyOf()
    val scale = size / extent
    val rr = dotRadius * dotRadius
    val ri = dotRadius.toInt() + 1
    for ((px, py) in points) {
        val sx = (px - cx + extent / 2) * scale
        val sy = (cy - py + extent / 2) * scale
        for (oy in -ri..ri) for (ox in -ri..ri) {
            val x = (sx + ox).toInt(); val y = (sy + oy).toInt()
            if (x !in 0 until size || y !in 0 until img.height) continue
            val ddx = x + 0.5 - sx; val ddy = y + 0.5 - sy
            if (ddx * ddx + ddy * ddy > rr) continue
            for (c in 0 until img.channels) d[(y * size + x) * img.channels + c] = 1f
        }
    }
    return Img(size, img.height, img.channels, d)
}

/** A [width] × [height] line plot of [values] (white on dark), y range auto with a small margin. */
fun energyPlotImg(values: List<Double>, width: Int = 480, height: Int = 160): Img {
    val d = FloatArray(width * height * 3) { 0.08f }
    if (values.size < 2) return Img(width, height, 3, d)
    var lo = values.min(); var hi = values.max()
    if (hi - lo < 1e-12) { lo -= 0.5; hi += 0.5 }
    val m = (hi - lo) * 0.08; lo -= m; hi += m
    var prevY = -1
    for (px in 0 until width) {
        val v = values[min(values.size - 1, (px.toDouble() / (width - 1) * (values.size - 1)).toInt())]
        val y = ((hi - v) / (hi - lo) * (height - 1)).toInt().coerceIn(0, height - 1)
        val a = if (prevY < 0) y else min(prevY, y)
        val b = if (prevY < 0) y else max(prevY, y)
        for (yy in a..b) for (c in 0 until 3) d[(yy * width + px) * 3 + c] = 1f
        prevY = y
    }
    return Img(width, height, 3, d)
}
