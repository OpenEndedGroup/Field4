@file:afield.runtime.completion.CompletionCategory("ALife")

package afield.simulation.agents

import afield.media.image.Img
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Flow Lenia: Lenia in which matter is moved rather than grown, so the total mass never changes, and
 * in which each cell carries its own rule parameters along with its matter.
 *
 * After Plantec, Hamon, Etcheverry, Oudeyer, Moulin-Frier and Chan, *Flow-Lenia: Towards open-ended
 * evolution in cellular automata through mass conservation and parameter localization* (ALIFE 2023,
 * [arXiv 2212.07906](https://arxiv.org/abs/2212.07906)). Written from the paper's method; no code is
 * copied from any implementation.
 *
 * One step, for a field `A` (no longer clamped to `0..1`) and per-cell kernel weights `h`:
 *
 * 1. **Affinity.** `U = Σ_k h_k · G_k(K_k * A)`: the same ring kernels and bell growth curves as
 *    [LeniaRule], but the sum is not added to `A`. It is a landscape the matter climbs.
 * 2. **Flow.** `F = (1 − α) ∇U − α ∇A`, with `α = clamp((A / θ)^n, 0, 1)`. Matter runs uphill on the
 *    affinity, and where it piles past `θ` it spreads down its own gradient instead.
 * 3. **Reintegration tracking.** Each cell's matter travels to `x + dt·F` (the step clipped to
 *    [FlowLeniaRule.maxStep]) as a square of half-side `s`, and is shared among the cells that square
 *    overlaps in proportion to the overlap area. The shares of one cell sum to one, so no mass is made
 *    or lost: the total is conserved up to floating-point rounding.
 * 4. **Parameter localization.** The `h` arriving at a cell is the mass-weighted mean of the `h` of the
 *    matter that arrived ([FlowLeniaMixing.MEAN]), or that of one arriving parcel drawn with
 *    probability proportional to its mass ([FlowLeniaMixing.SAMPLED], the paper's rule). Different `h`
 *    are different species that share one world and one set of kernels.
 *
 * Alongside `h` each cell carries [FlowLeniaWorld.species] tracers: the fraction of its matter that
 * started as each seeded species. They are moved by the same rule as `h` and never feed back, so they
 * colour the pictures and give a mass per species.
 */
class FlowLeniaKernel(
    /** The kernel taps, normalised to sum 1 (a [LeniaRule]'s [LeniaRule.taps]). */
    val taps: LeniaTaps,
    /** Growth curve centre for this kernel. */
    val mu: Double,
    /** Growth curve width for this kernel. */
    val sigma: Double
) {
    init {
        require(sigma > 0.0) { "sigma must be > 0, got $sigma" }
    }

    /** The bell `2·exp(−(u − mu)² / 2σ²) − 1`. */
    fun growth(u: Double): Double {
        val d = u - mu
        return 2.0 * exp(-d * d / (2.0 * sigma * sigma)) - 1.0
    }

    override fun toString(): String = "FlowLeniaKernel(${taps.size} taps, mu=$mu, sigma=$sigma)"

    companion object {
        /**
         * A kernel built the way [LeniaRule] builds one: [radius], concentric shells weighted by
         * [shellPeaks], Gaussian cross-section [shellWidth].
         */
        fun ring(radius: Int, shellPeaks: DoubleArray, mu: Double, sigma: Double, shellWidth: Double = 0.15): FlowLeniaKernel =
            FlowLeniaKernel(LeniaRule(radius = radius, mu = mu, sigma = sigma, shellPeaks = shellPeaks, shellWidth = shellWidth).taps, mu, sigma)

        /** The kernel and growth curve of an ordinary [LeniaRule]. */
        fun of(rule: LeniaRule): FlowLeniaKernel = FlowLeniaKernel(rule.taps, rule.mu, rule.sigma)
    }
}

/** How the parameters of matter arriving at one cell from several sources are combined. */
enum class FlowLeniaMixing {
    /** The mass-weighted mean of the arriving parameters. Species blend where they meet. */
    MEAN,

    /**
     * One arriving parcel's parameters, drawn with probability proportional to its mass: the paper's
     * rule. Species stay distinct; a cell changes hands rather than blending.
     */
    SAMPLED
}

/**
 * The shared part of a Flow Lenia world: the kernels and the flow constants. What differs between
 * species lives in the world, per cell ([FlowLeniaWorld.weights]).
 */
class FlowLeniaRule(
    /** The kernels, each with its growth curve. A species weights them with its own `h`. */
    val kernels: List<FlowLeniaKernel>,
    /** Integration step: matter moves `dt · F` per step. */
    val dt: Double = 0.2,
    /** Half-side `s` of the square each cell's matter is spread over when it lands. */
    val spread: Double = 0.65,
    /** Largest distance matter may travel in one step, per axis. */
    val maxStep: Double = 4.35,
    /** `θ`: the density above which matter is pushed apart rather than drawn up the affinity. */
    val crowding: Double = 2.0,
    /** `n`, the exponent of the crowding weight `α = (A/θ)^n`. */
    val crowdingExponent: Double = 2.0,
    /** How parameters combine when matter merges. */
    val mixing: FlowLeniaMixing = FlowLeniaMixing.MEAN
) {
    init {
        require(kernels.isNotEmpty()) { "a Flow Lenia rule needs at least one kernel" }
        require(dt > 0.0) { "dt must be > 0, got $dt" }
        require(spread > 0.0 && spread <= 1.0) { "spread must be in (0, 1], got $spread" }
        require(maxStep >= 0.0) { "maxStep must be >= 0, got $maxStep" }
        require(crowding > 0.0) { "crowding must be > 0, got $crowding" }
    }

    /** The same kernels and constants with a different [mixing] rule. */
    fun withMixing(mixing: FlowLeniaMixing): FlowLeniaRule =
        FlowLeniaRule(kernels, dt, spread, maxStep, crowding, crowdingExponent, mixing)

    override fun toString(): String = "FlowLeniaRule(${kernels.size} kernels, dt=$dt, s=$spread, mixing=$mixing)"
}

/**
 * A toroidal Flow Lenia world: matter, its per-cell kernel weights, and its species tracers.
 *
 * Mutable: [step] advances it in place. Build one with [FlowLeniaWorld], add matter with [seed], and
 * read it back with [matterImg], [speciesImg], [totalMass] and [speciesMass].
 *
 * @param randomSeed seed for [FlowLeniaMixing.SAMPLED] draws and for [seed]'s noise
 */
class FlowLeniaWorld(
    /** Field width in cells. */
    val width: Int,
    /** Field height in cells. */
    val height: Int,
    /** The rule; its kernel count fixes the length of each cell's weight vector. */
    val rule: FlowLeniaRule,
    /** How many species tracers each cell carries. */
    val speciesCount: Int = 1,
    randomSeed: Long = 1L
) {
    init {
        require(width >= 3 && height >= 3) { "a Flow Lenia world needs at least 3x3 cells" }
        require(speciesCount >= 1) { "speciesCount must be >= 1" }
    }

    private val n = width * height
    private val kCount = rule.kernels.size
    private val rng = kotlin.random.Random(randomSeed)

    /** Matter per cell, row-major. Unclamped: it may exceed 1. */
    var matter = DoubleArray(n)
        private set

    /** Kernel weights `h`, kernel-major: `weights[k * width * height + cell]`. */
    var weights = DoubleArray(kCount * n)
        private set

    /** Species tracers, species-major: `species[s * width * height + cell]`, fractions summing to 1 where there is matter. */
    var species = DoubleArray(speciesCount * n)
        private set

    /** Steps taken. */
    var time = 0
        private set

    /**
     * `[total, species 0, species 1, ...]` mass after every step (and once for the state [massPlot] first
     * sees), so a run can be plotted afterwards. Recorded by [step].
     */
    val massHistory = ArrayList<DoubleArray>()

    private fun record() {
        massHistory.add(doubleArrayOf(totalMass()) + speciesMasses())
    }

    /**
     * Drop a disc of smooth noise of [radius] cells centred at ([cx], [cy]) whose matter belongs to
     * species [speciesIndex] and carries kernel weights [h] (one per kernel in [rule]). Matter already
     * in the disc is replaced.
     *
     * @param density peak value of the noise
     */
    fun seed(speciesIndex: Int, h: DoubleArray, cx: Double, cy: Double, radius: Double = 12.0, density: Double = 1.0): FlowLeniaWorld {
        require(speciesIndex in 0 until speciesCount) { "species $speciesIndex outside 0 until $speciesCount" }
        require(h.size == kCount) { "h has ${h.size} weights but the rule has $kCount kernels" }
        val r = radius.toInt() + 3
        // Smooth noise: a random patch blurred by a 5x5 box twice, rescaled to 0..1, times fresh noise.
        val side = 2 * r + 1
        var patch = DoubleArray(side * side) { rng.nextDouble() }
        repeat(2) {
            val o = DoubleArray(side * side)
            for (y in 0 until side) for (x in 0 until side) {
                var acc = 0.0; var c = 0
                for (j in -2..2) for (i in -2..2) {
                    val xx = x + i; val yy = y + j
                    if (xx in 0 until side && yy in 0 until side) { acc += patch[yy * side + xx]; c++ }
                }
                o[y * side + x] = acc / c
            }
            patch = o
        }
        val lo = patch.min(); val hi = patch.max()
        for (j in -r..r) for (i in -r..r) {
            val px = floor(cx).toInt() + i
            val py = floor(cy).toInt() + j
            if (hypot(px + 0.5 - cx, py + 0.5 - cy) >= radius) continue
            val c = Math.floorMod(py, height) * width + Math.floorMod(px, width)
            val smooth = if (hi > lo) (patch[(j + r) * side + (i + r)] - lo) / (hi - lo) else 1.0
            matter[c] = density * 2.0 * smooth * rng.nextDouble()
            for (k in 0 until kCount) weights[k * n + c] = h[k]
            for (s in 0 until speciesCount) species[s * n + c] = if (s == speciesIndex) 1.0 else 0.0
        }
        return this
    }

    /** Total matter. Conserved by [step] up to rounding. */
    fun totalMass(): Double = matter.sum()

    /** Matter belonging to species [s] (matter times that species' tracer, summed). */
    fun speciesMass(s: Int): Double {
        require(s in 0 until speciesCount) { "species $s outside 0 until $speciesCount" }
        var acc = 0.0
        val o = s * n
        for (c in 0 until n) acc += matter[c] * species[o + c]
        return acc
    }

    /**
     * Matter in cells where no species holds more than `1 − margin` of the matter: the hybrid fraction.
     * Under [FlowLeniaMixing.MEAN] this is the matter whose parameters have blended; under
     * [FlowLeniaMixing.SAMPLED] it stays at zero, because a cell always takes one parcel's parameters whole.
     */
    fun mixedMass(margin: Double = 0.1): Double {
        var acc = 0.0
        for (c in 0 until n) {
            val m = matter[c]
            if (m <= 0.0) continue
            var top = 0.0
            for (s in 0 until speciesCount) top = max(top, species[s * n + c])
            if (top < 1.0 - margin) acc += m
        }
        return acc
    }

    /** Mass of every species, in order. */
    fun speciesMasses(): DoubleArray = DoubleArray(speciesCount) { speciesMass(it) }

    /**
     * The affinity `U = Σ h_k G_k(K_k * A)` of the current state, as computed by [step].
     *
     * Computed where it can matter: [step] only reads `U` through its 3x3 gradient at cells holding
     * matter, so `U` is evaluated on the occupied cells dilated by one, and is `0` elsewhere. The
     * convolution is scattered from occupied cells rather than gathered at every cell, which costs the
     * occupied fraction of a dense pass; a sparse world (most Flow Lenia worlds) runs several times
     * faster for the same result.
     */
    fun affinity(): DoubleArray {
        val occupied = IntArray(n)
        var count = 0
        for (c in 0 until n) if (matter[c] > 0.0) occupied[count++] = c
        val need = BooleanArray(n)
        for (q in 0 until count) {
            val c = occupied[q]
            val x = c % width; val y = c / width
            for (j in -1..1) for (i in -1..1) need[Math.floorMod(y + j, height) * width + Math.floorMod(x + i, width)] = true
        }
        // Kernels are independent, so each is convolved on its own thread into its own buffer.
        val parts = arrayOfNulls<DoubleArray>(kCount)
        java.util.stream.IntStream.range(0, kCount).parallel().forEach { k ->
            val kernel = rule.kernels[k]
            val t = kernel.taps
            val m = t.size
            val conv = DoubleArray(n)
            for (q in 0 until count) {
                val c = occupied[q]
                val a = matter[c]
                val x = c % width; val y = c / width
                // (K * A)(p) = Σ_i w_i A(p + d_i): the source at c reaches p = c − d_i.
                for (i in 0 until m) {
                    val px = x - t.dx[i]
                    val py = y - t.dy[i]
                    val wx = if (px in 0 until width) px else Math.floorMod(px, width)
                    val wy = if (py in 0 until height) py else Math.floorMod(py, height)
                    conv[wy * width + wx] += a * t.weight[i]
                }
            }
            val ko = k * n
            for (c in 0 until n) conv[c] = if (need[c]) weights[ko + c] * kernel.growth(conv[c]) else 0.0
            parts[k] = conv
        }
        val u = DoubleArray(n)
        for (k in 0 until kCount) {
            val part = parts[k]!!
            for (c in 0 until n) u[c] += part[c]
        }
        return u
    }

    /** Unnormalised 3x3 Sobel derivative of [f] along x into [gx] and along y into [gy], toroidal. */
    private fun sobel(f: DoubleArray, gx: DoubleArray, gy: DoubleArray) {
        for (y in 0 until height) {
            val ym = (if (y == 0) height - 1 else y - 1) * width
            val y0 = y * width
            val yp = (if (y == height - 1) 0 else y + 1) * width
            for (x in 0 until width) {
                val xm = if (x == 0) width - 1 else x - 1
                val xp = if (x == width - 1) 0 else x + 1
                gx[y0 + x] = (f[ym + xp] - f[ym + xm]) + 2.0 * (f[y0 + xp] - f[y0 + xm]) + (f[yp + xp] - f[yp + xm])
                gy[y0 + x] = (f[yp + xm] - f[ym + xm]) + 2.0 * (f[yp + x] - f[ym + x]) + (f[yp + xp] - f[ym + xp])
            }
        }
    }

    /** Advance one step in place. */
    fun step(): FlowLeniaWorld {
        val u = affinity()
        val gux = DoubleArray(n); val guy = DoubleArray(n)
        val gax = DoubleArray(n); val gay = DoubleArray(n)
        sobel(u, gux, guy)
        sobel(matter, gax, gay)
        val s = rule.spread
        val area = 4.0 * s * s
        val lim = rule.maxStep
        val out = DoubleArray(n)
        val pDim = kCount + speciesCount
        val acc = DoubleArray(pDim * n)
        // SAMPLED: index of the source currently holding each target, and mass seen so far.
        val sampled = rule.mixing == FlowLeniaMixing.SAMPLED
        val holder = if (sampled) IntArray(n) { -1 } else IntArray(0)
        for (y in 0 until height) for (x in 0 until width) {
            val c = y * width + x
            val a = matter[c]
            if (a <= 0.0) continue
            val alpha = min(1.0, max(0.0, (a / rule.crowding).pow(rule.crowdingExponent)))
            val fx = ((1.0 - alpha) * gux[c] - alpha * gax[c]) * rule.dt
            val fy = ((1.0 - alpha) * guy[c] - alpha * gay[c]) * rule.dt
            val cx = x + 0.5 + fx.coerceIn(-lim, lim)
            val cy = y + 0.5 + fy.coerceIn(-lim, lim)
            val x0 = floor(cx - s).toInt(); val x1 = floor(cx + s).toInt()
            val y0 = floor(cy - s).toInt(); val y1 = floor(cy + s).toInt()
            for (ty in y0..y1) {
                val wy = min(ty + 1.0, cy + s) - max(ty.toDouble(), cy - s)
                if (wy <= 0.0) continue
                val row = Math.floorMod(ty, height) * width
                for (tx in x0..x1) {
                    val wx = min(tx + 1.0, cx + s) - max(tx.toDouble(), cx - s)
                    if (wx <= 0.0) continue
                    val t = row + Math.floorMod(tx, width)
                    val m = a * wx * wy / area
                    val before = out[t]
                    out[t] = before + m
                    if (sampled) {
                        // Reservoir draw: keep this parcel with probability m / (mass so far).
                        if (holder[t] < 0 || rng.nextDouble() * (before + m) < m) holder[t] = c
                    } else {
                        for (p in 0 until kCount) acc[p * n + t] += m * weights[p * n + c]
                        for (p in 0 until speciesCount) acc[(kCount + p) * n + t] += m * species[p * n + c]
                    }
                }
            }
        }
        val nw = DoubleArray(kCount * n)
        val ns = DoubleArray(speciesCount * n)
        for (t in 0 until n) {
            val m = out[t]
            if (sampled) {
                val src = if (m > 0.0) holder[t] else t
                for (p in 0 until kCount) nw[p * n + t] = weights[p * n + src]
                for (p in 0 until speciesCount) ns[p * n + t] = species[p * n + src]
            } else if (m > 1e-300) {
                for (p in 0 until kCount) nw[p * n + t] = acc[p * n + t] / m
                for (p in 0 until speciesCount) ns[p * n + t] = acc[(kCount + p) * n + t] / m
            } else {
                for (p in 0 until kCount) nw[p * n + t] = weights[p * n + t]
                for (p in 0 until speciesCount) ns[p * n + t] = species[p * n + t]
            }
        }
        if (massHistory.isEmpty()) record()
        matter = out
        weights = nw
        species = ns
        time++
        record()
        return this
    }

    /** [step] applied [steps] times. */
    fun steps(steps: Int): FlowLeniaWorld {
        require(steps >= 0) { "steps must be >= 0, got $steps" }
        repeat(steps) { step() }
        return this
    }

    /** The matter as a 1-channel [Img]. */
    fun matterImg(): Img = Img(width, height, 1, FloatArray(n) { matter[it].toFloat() })

    /** Kernel weight [k] at every cell, as a 1-channel [Img]; 0 where there is no matter. */
    fun weightImg(k: Int): Img {
        require(k in 0 until kCount) { "kernel $k outside 0 until $kCount" }
        return Img(width, height, 1, FloatArray(n) { if (matter[it] > 0.01) weights[k * n + it].toFloat() else 0f })
    }

    /**
     * An RGB picture: each species' matter in its colour from [colors] (`0..1` RGB triples; a default
     * amber, cyan, pink, green cycle), brightness `matter / peak` clipped to 1.
     */
    fun speciesImg(colors: List<DoubleArray> = FLOW_LENIA_COLORS, peak: Double = 1.2): Img {
        val d = FloatArray(n * 3)
        for (c in 0 until n) {
            val v = min(1.0, matter[c] / peak)
            if (v <= 0.0) continue
            for (s in 0 until speciesCount) {
                val f = species[s * n + c] * v
                if (f <= 0.0) continue
                val col = colors[s % colors.size]
                for (ch in 0 until 3) d[c * 3 + ch] += (f * col[ch]).toFloat()
            }
            for (ch in 0 until 3) d[c * 3 + ch] = min(1f, d[c * 3 + ch])
        }
        return Img(width, height, 3, d)
    }

    /**
     * [massHistory] drawn as an RGB [Img]: total mass in white, each species in its colour from
     * [colors], against a `y` axis from 0 to 1.1 times the largest total, so a conserved total is a
     * flat white line near the top and a species that gains ground rises while another falls.
     */
    fun massPlot(w: Int = 360, h: Int = 140, colors: List<DoubleArray> = FLOW_LENIA_COLORS): Img {
        val out = Img.zeros(w, h, 3)
        val hist = if (massHistory.isEmpty()) listOf(doubleArrayOf(totalMass()) + speciesMasses()) else massHistory
        val top = 1.1 * hist.maxOf { it[0] }.coerceAtLeast(1e-12)
        fun put(x: Int, y: Int, col: DoubleArray) {
            if (x !in 0 until w || y !in 0 until h) return
            for (ch in 0 until 3) out.data[(y * w + x) * 3 + ch] = col[ch].toFloat()
        }
        val grey = doubleArrayOf(0.25, 0.25, 0.25)
        for (x in 0 until w) put(x, h - 1, grey)
        for (y in 0 until h) put(0, y, grey)
        val series = hist[0].size
        for (sIdx in series - 1 downTo 0) {
            val col = if (sIdx == 0) doubleArrayOf(1.0, 1.0, 1.0) else colors[(sIdx - 1) % colors.size]
            var px = -1; var py = 0
            for ((i, row) in hist.withIndex()) {
                val x = if (hist.size == 1) 0 else (i.toDouble() / (hist.size - 1) * (w - 1)).toInt()
                val y = (h - 1 - row[sIdx] / top * (h - 1)).toInt().coerceIn(0, h - 1)
                if (px >= 0) {
                    val lo = min(py, y); val hi = max(py, y)
                    for (yy in lo..hi) put(x, yy, col)
                }
                put(x, y, col); put(x, y - 1, col)
                px = x; py = y
            }
        }
        return out
    }

    override fun toString(): String =
        "FlowLeniaWorld(${width}x$height, step $time, mass %.6f, species %s)".format(
            totalMass(), speciesMasses().joinToString(", ") { "%.3f".format(it) }
        )
}

/** Amber, cyan, pink, green: the species colours [FlowLeniaWorld.speciesImg] uses by default. */
val FLOW_LENIA_COLORS: List<DoubleArray> = listOf(
    doubleArrayOf(1.0, 0.6, 0.1),
    doubleArrayOf(0.2, 0.8, 1.0),
    doubleArrayOf(1.0, 0.3, 0.7),
    doubleArrayOf(0.4, 1.0, 0.3)
)

/**
 * A Flow Lenia species: its kernels and growth curves and its kernel weights `h`. Several species are
 * put in one world with [flowLeniaWorld], which concatenates their kernels.
 */
class FlowLeniaSpecies(
    /** A short label for captions. */
    val name: String,
    /** This species' kernels. */
    val kernels: List<FlowLeniaKernel>,
    /** Its weight on each of [kernels]. */
    val h: DoubleArray
) {
    init {
        require(kernels.size == h.size) { "$name: ${kernels.size} kernels but ${h.size} weights" }
    }

    override fun toString(): String = "FlowLeniaSpecies($name, ${kernels.size} kernels)"
}

/**
 * A world holding [species], one seeded disc each at [positions] (default: spaced on a circle of 0.28
 * of the field about its centre). The world's rule has every species' kernels side by side, and each
 * species weights only its own, so with nothing nearby each one runs exactly its own rule; where their
 * matter mixes, so do their weights.
 */
fun flowLeniaWorld(
    width: Int,
    height: Int,
    species: List<FlowLeniaSpecies>,
    positions: List<Pair<Double, Double>>? = null,
    seedRadius: Double = 12.0,
    mixing: FlowLeniaMixing = FlowLeniaMixing.MEAN,
    randomSeed: Long = 1L
): FlowLeniaWorld {
    require(species.isNotEmpty()) { "at least one species" }
    val kernels = species.flatMap { it.kernels }
    val rule = FlowLeniaRule(kernels, mixing = mixing)
    val world = FlowLeniaWorld(width, height, rule, species.size, randomSeed)
    val at = positions ?: species.indices.map { i ->
        if (species.size == 1) width / 2.0 to height / 2.0 else {
            val ang = 2.0 * Math.PI * i / species.size
            width / 2.0 + 0.28 * width * kotlin.math.cos(ang) to height / 2.0 + 0.28 * height * kotlin.math.sin(ang)
        }
    }
    require(at.size == species.size) { "${species.size} species but ${at.size} positions" }
    var off = 0
    for ((i, sp) in species.withIndex()) {
        val h = DoubleArray(kernels.size)
        for (k in sp.h.indices) h[off + k] = sp.h[k]
        world.seed(i, h, at[i].first, at[i].second, seedRadius)
        off += sp.kernels.size
    }
    return world
}

/**
 * Three Flow Lenia species found by random search over the paper's rule space (several ring kernels
 * of radius 3 to 12, `mu` in `0.05..0.5`, `sigma` in `0.01..0.18`, weights in `0.01..1`), kept for what
 * they did from a noise disc on a 64-cell torus.
 */
object FlowLeniaPresets {
    private fun k(r: Int, peaks: DoubleArray, mu: Double, sigma: Double) = FlowLeniaKernel.ring(r, peaks, mu, sigma)

    /** Breaks into about a dozen compact round bodies that drift slowly. */
    fun wanderers(): FlowLeniaSpecies = FlowLeniaSpecies(
        "wanderers",
        listOf(
            k(10, doubleArrayOf(0.6812, 0.9296, 0.4365), 0.0625, 0.0821),
            k(5, doubleArrayOf(0.2326), 0.4215, 0.1517),
            k(7, doubleArrayOf(0.4404, 0.604), 0.2273, 0.0683),
            k(10, doubleArrayOf(0.6768, 0.6879, 0.3127), 0.1512, 0.0604),
            k(7, doubleArrayOf(0.334, 0.169, 0.7889), 0.1301, 0.1055),
            k(10, doubleArrayOf(0.7371, 0.7232), 0.3993, 0.1184),
            k(5, doubleArrayOf(0.2985, 0.9519, 0.6775), 0.3633, 0.0661)
        ),
        doubleArrayOf(0.4022, 0.1708, 0.2353, 0.3421, 0.7871, 0.9258, 0.3118)
    )

    /** Settles into small hollow rings that keep a distance from each other. */
    fun rings(): FlowLeniaSpecies = FlowLeniaSpecies(
        "rings",
        listOf(
            k(8, doubleArrayOf(0.704, 0.3398, 0.5174), 0.2614, 0.0806),
            k(6, doubleArrayOf(0.1971, 0.1247, 0.3086), 0.1186, 0.053),
            k(8, doubleArrayOf(0.6886, 0.8305), 0.1964, 0.0279),
            k(5, doubleArrayOf(0.1869, 0.7512, 0.2746), 0.3751, 0.0359),
            k(8, doubleArrayOf(0.4457), 0.268, 0.0494),
            k(6, doubleArrayOf(0.5742, 0.445, 0.7637), 0.4617, 0.0882)
        ),
        doubleArrayOf(0.3818, 0.2051, 0.2155, 0.7793, 0.7342, 0.0567)
    )

    /** One large membrane-bounded body with structure inside. */
    fun cell(): FlowLeniaSpecies = FlowLeniaSpecies(
        "cell",
        listOf(
            k(4, doubleArrayOf(0.3134, 0.8904, 0.9865), 0.3142, 0.0945),
            k(4, doubleArrayOf(0.2667, 0.3305), 0.2441, 0.0352),
            k(7, doubleArrayOf(0.1034), 0.2538, 0.0861),
            k(9, doubleArrayOf(0.1164, 0.677, 0.2547), 0.0642, 0.1664)
        ),
        doubleArrayOf(0.5814, 0.1988, 0.2842, 0.5691)
    )
}
