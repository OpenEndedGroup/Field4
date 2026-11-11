@file:afield.runtime.completion.CompletionCategory("ALife")

package afield.simulation.agents

import afield.media.image.Img
import afield.study.search.Evaluation
import afield.study.search.GenomeOps
import afield.study.search.Objective
import afield.study.search.PopulationSearch
import afield.study.search.Variation
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

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
    val sigma: Double,
    /** The ring radius the taps were built with by [ring], or `0` for taps given directly. */
    val radius: Int = 0,
    /** The shell peaks the taps were built with by [ring], or `null` for taps given directly. */
    val shellPeaks: DoubleArray? = null,
    /** The shell cross-section width the taps were built with by [ring]. */
    val shellWidth: Double = 0.15
) {
    init {
        require(sigma > 0.0) { "sigma must be > 0, got $sigma" }
    }

    /** The same taps with a different growth curve. */
    fun with(mu: Double = this.mu, sigma: Double = this.sigma): FlowLeniaKernel =
        FlowLeniaKernel(taps, mu, sigma, radius, shellPeaks, shellWidth)

    /**
     * The same ring at [k] times the radius (rounded), so the same creature is drawn with `k²` as many
     * cells. Only a kernel built by [ring] can be rebuilt at another size.
     */
    fun scaled(k: Double): FlowLeniaKernel {
        val peaks = requireNotNull(shellPeaks) { "only a ring kernel (FlowLeniaKernel.ring) can be scaled" }
        require(k > 0.0) { "scale must be > 0, got $k" }
        return ring(max(1, Math.round(radius * k).toInt()), peaks, mu, sigma, shellWidth)
    }

    /** This kernel as the Kotlin expression that builds it, for pasting a found rule into a box. */
    fun code(): String {
        val peaks = shellPeaks ?: return "/* direct taps */"
        return "FlowLeniaKernel.ring($radius, doubleArrayOf(${peaks.joinToString(", ") { fmt(it) }}), ${fmt(mu)}, ${fmt(sigma)})"
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
            FlowLeniaKernel(
                LeniaRule(radius = radius, mu = mu, sigma = sigma, shellPeaks = shellPeaks, shellWidth = shellWidth).taps,
                mu, sigma, radius, shellPeaks.copyOf(), shellWidth
            )

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

/** How [FlowLeniaWorld.affinity] convolves the matter with the kernels. Both give the same `U` to rounding. */
enum class FlowLeniaConvolution {
    /**
     * Scatter each occupied cell through every tap. Costs occupied cells times taps, so it is the fast
     * one for a small or sparse world, and slows with the square of the kernel radius.
     */
    SPARSE,

    /**
     * Multiply in the frequency domain: one transform of the matter and one inverse per kernel. Costs
     * about `n log n` per kernel whatever the radius or how full the world is. Any size works; sides
     * built from small primes (64, 96, 288, 384) are the fast ones.
     */
    FFT,

    /** Whichever of [SPARSE] and [FFT] is estimated cheaper for the current state, chosen every step. */
    AUTO
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
    val mixing: FlowLeniaMixing = FlowLeniaMixing.MEAN,
    /** How the affinity's convolutions are computed. */
    val convolution: FlowLeniaConvolution = FlowLeniaConvolution.AUTO
) {
    init {
        require(kernels.isNotEmpty()) { "a Flow Lenia rule needs at least one kernel" }
        require(dt > 0.0) { "dt must be > 0, got $dt" }
        require(spread > 0.0) { "spread must be > 0, got $spread" }
        require(maxStep >= 0.0) { "maxStep must be >= 0, got $maxStep" }
        require(crowding > 0.0) { "crowding must be > 0, got $crowding" }
    }

    /** The same kernels and constants with a different [mixing] rule. */
    fun withMixing(mixing: FlowLeniaMixing): FlowLeniaRule =
        FlowLeniaRule(kernels, dt, spread, maxStep, crowding, crowdingExponent, mixing, convolution)

    /** The same rule with a different [convolution]. */
    fun withConvolution(convolution: FlowLeniaConvolution): FlowLeniaRule =
        FlowLeniaRule(kernels, dt, spread, maxStep, crowding, crowdingExponent, mixing, convolution)

    /**
     * The same rule at [k] times the size, every length times [k]: kernel radii, [spread] and
     * [maxStep]. The 3x3 Sobel gradients of a `k` times larger pattern are `1/k` as steep, so [dt]
     * grows by `k²`, and a step then moves matter `k` times as many cells: the same fraction of a
     * creature as before. Keeping [spread] at one cell instead leaves a cell-sized speckle over every
     * creature, because the reintegration square is what smooths the matter at the finest scale.
     */
    fun scaled(k: Double): FlowLeniaRule {
        require(k > 0.0) { "scale must be > 0, got $k" }
        return FlowLeniaRule(kernels.map { it.scaled(k) }, dt * k * k, spread * k, maxStep * k, crowding, crowdingExponent, mixing, convolution)
    }

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

    /**
     * The lineage of each cell's matter: an index into [lineages]. A cell takes the lineage of the
     * arriving parcel whose parameters it takes under [FlowLeniaMixing.SAMPLED], and of the largest
     * arriving parcel under [FlowLeniaMixing.MEAN]. Empty cells keep their last value.
     */
    var lineage = IntArray(n)
        private set

    /** Every lineage made so far, by [seed], [mutate] or [paintWeights], in order of [FlowLeniaLineage.id]. */
    val lineages = ArrayList<FlowLeniaLineage>()

    /** Mutation events [step] applies, or `null` (the default) for none. */
    var mutation: FlowLeniaMutation? = null

    /** Steps taken. */
    var time = 0
        private set

    /**
     * `[total, species 0, species 1, ...]` mass after every step (and once for the state [massPlot] first
     * sees), so a run can be plotted afterwards. Recorded by [step].
     */
    val massHistory = ArrayList<DoubleArray>()

    /**
     * The mass of every lineage alive at each recorded step, indexed by lineage id (an array as long as
     * [lineages] was at that step). Recorded by [step] alongside [massHistory].
     */
    val lineageHistory = ArrayList<DoubleArray>()

    private fun record() {
        massHistory.add(doubleArrayOf(totalMass()) + speciesMasses())
        lineageHistory.add(lineageMasses())
    }

    private fun gauss(): Double {
        val u = 1.0 - rng.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * rng.nextDouble())
    }

    /**
     * Drop a disc of smooth noise of [radius] cells centred at ([cx], [cy]) whose matter belongs to
     * species [speciesIndex] and carries kernel weights [h] (one per kernel in [rule]). Matter already
     * in the disc is replaced. The disc starts a new lineage.
     *
     * @param density peak value of the noise
     */
    fun seed(speciesIndex: Int, h: DoubleArray, cx: Double, cy: Double, radius: Double = 12.0, density: Double = 1.0): FlowLeniaWorld {
        require(speciesIndex in 0 until speciesCount) { "species $speciesIndex outside 0 until $speciesCount" }
        require(h.size == kCount) { "h has ${h.size} weights but the rule has $kCount kernels" }
        val id = lineages.size
        lineages += FlowLeniaLineage(id, -1, time, h.copyOf())
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
            lineage[c] = id
        }
        return this
    }

    /**
     * Give the matter in a disc of [radius] cells about ([cx], [cy]) new kernel weights, leaving the
     * matter where it is: the rule is painted onto what is already there. Cells holding no matter are
     * left alone, since the weights of arriving matter replace theirs anyway. The painted matter
     * becomes a new lineage, returned; `-1` if the disc held no matter.
     *
     * @param h the new weights for each cell, from the cell index and its current weights
     */
    fun paintWeights(cx: Double, cy: Double, radius: Double, h: (cell: Int, current: DoubleArray) -> DoubleArray): Int {
        require(radius > 0.0) { "radius must be > 0, got $radius" }
        val r = radius.toInt() + 1
        val cells = ArrayList<Int>()
        val byLineage = HashMap<Int, Double>()
        for (j in -r..r) for (i in -r..r) {
            val px = floor(cx).toInt() + i
            val py = floor(cy).toInt() + j
            if (hypot(px + 0.5 - cx, py + 0.5 - cy) >= radius) continue
            val c = Math.floorMod(py, height) * width + Math.floorMod(px, width)
            if (matter[c] <= 0.0) continue
            cells += c
            byLineage.merge(lineage[c], matter[c], Double::plus)
        }
        if (cells.isEmpty()) return -1
        val id = lineages.size
        val mean = DoubleArray(kCount)
        var mass = 0.0
        val cur = DoubleArray(kCount)
        for (c in cells) {
            for (k in 0 until kCount) cur[k] = weights[k * n + c]
            val nh = h(c, cur.copyOf())
            require(nh.size == kCount) { "h returned ${nh.size} weights but the rule has $kCount kernels" }
            for (k in 0 until kCount) { weights[k * n + c] = nh[k]; mean[k] += matter[c] * nh[k] }
            mass += matter[c]
            lineage[c] = id
        }
        for (k in 0 until kCount) mean[k] /= mass
        lineages += FlowLeniaLineage(id, byLineage.maxBy { it.value }.key, time, mean)
        return id
    }

    /** [paintWeights] with the same weights [h] for every cell in the disc. */
    fun paintWeights(cx: Double, cy: Double, radius: Double, h: DoubleArray): Int {
        require(h.size == kCount) { "h has ${h.size} weights but the rule has $kCount kernels" }
        return paintWeights(cx, cy, radius) { _, _ -> h }
    }

    /**
     * One mutation: add the same Gaussian offset, standard deviation [scale] per kernel, to the weights
     * of the matter in a disc of [radius] about ([cx], [cy]), making it a new lineage (returned, or `-1`
     * if the disc held no matter). The offset is not clipped, so a weight may go negative; that kernel
     * then repels rather than attracts. `scale = 0` makes a lineage with unchanged weights.
     */
    fun mutate(cx: Double, cy: Double, radius: Double = 6.0, scale: Double = 0.1): Int {
        require(scale >= 0.0) { "scale must be >= 0, got $scale" }
        val dh = DoubleArray(kCount) { gauss() * scale }
        return paintWeights(cx, cy, radius) { _, cur -> DoubleArray(kCount) { cur[it] + dh[it] } }
    }

    private fun applyMutations(m: FlowLeniaMutation) {
        val whole = floor(m.rate).toInt()
        val events = whole + if (rng.nextDouble() < m.rate - whole) 1 else 0
        repeat(events) {
            val total = totalMass()
            if (total <= 0.0) return
            var pick = rng.nextDouble() * total
            var c = 0
            while (c < n - 1 && pick >= matter[c]) { pick -= matter[c]; c++ }
            mutate(c % width + 0.5, c / width + 0.5, m.radius, m.scale)
        }
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

    /** The matter of every lineage, indexed by lineage id. They sum to [totalMass]. */
    fun lineageMasses(): DoubleArray {
        val out = DoubleArray(lineages.size)
        for (c in 0 until n) if (matter[c] > 0.0) out[lineage[c]] += matter[c]
        return out
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

    private val fft by lazy { FlowLeniaFft(width, height, rule.kernels) }

    /**
     * Whether [affinity] uses the frequency-domain convolution for the current state: always under
     * [FlowLeniaConvolution.FFT], never under [FlowLeniaConvolution.SPARSE], and under
     * [FlowLeniaConvolution.AUTO] when the occupied cells times the taps outnumber the transforms'
     * estimated work.
     */
    fun usesFft(): Boolean = when (rule.convolution) {
        FlowLeniaConvolution.SPARSE -> false
        FlowLeniaConvolution.FFT -> true
        FlowLeniaConvolution.AUTO -> {
            var occupied = 0
            for (c in 0 until n) if (matter[c] > 0.0) occupied++
            val sparse = occupied.toDouble() * rule.kernels.sumOf { it.taps.size }
            // One forward and one inverse transform per kernel, plus the spectrum products.
            sparse > 2.0 * (kCount + 1) * fft.work
        }
    }

    /**
     * The affinity `U = Σ h_k G_k(K_k * A)` of the current state, as computed by [step].
     *
     * Computed where it can matter: [step] only reads `U` through its 3x3 gradient at cells holding
     * matter, so `U` is evaluated on the occupied cells dilated by one, and is `0` elsewhere. Under
     * [FlowLeniaConvolution.SPARSE] the convolution is scattered from occupied cells rather than gathered
     * at every cell, which costs the occupied fraction of a dense pass; under [FlowLeniaConvolution.FFT]
     * it is a product of transforms, whose cost does not grow with the kernel radius ([usesFft]).
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
        val parts = arrayOfNulls<DoubleArray>(kCount)
        if (usesFft()) {
            val convs = fft.convolveAll(matter)
            for (k in 0 until kCount) parts[k] = convs[k]
        } else {
            // Kernels are independent, so each is convolved on its own thread into its own buffer.
            java.util.stream.IntStream.range(0, kCount).parallel().forEach { k ->
                val t = rule.kernels[k].taps
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
                parts[k] = conv
            }
        }
        java.util.stream.IntStream.range(0, kCount).parallel().forEach { k ->
            val kernel = rule.kernels[k]
            val conv = parts[k]!!
            val ko = k * n
            for (c in 0 until n) conv[c] = if (need[c]) weights[ko + c] * kernel.growth(conv[c]) else 0.0
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

    /** Advance one step in place, then apply any [mutation] events. */
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
        val sampled = rule.mixing == FlowLeniaMixing.SAMPLED
        val acc = if (sampled) DoubleArray(0) else DoubleArray(pDim * n)
        // SAMPLED: the source currently holding each target. MEAN: the largest source so far, for the lineage.
        val holder = IntArray(n) { -1 }
        val biggest = if (sampled) DoubleArray(0) else DoubleArray(n)
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
                        if (m > biggest[t]) { biggest[t] = m; holder[t] = c }
                    }
                }
            }
        }
        val nw = DoubleArray(kCount * n)
        val ns = DoubleArray(speciesCount * n)
        val nl = IntArray(n)
        for (t in 0 until n) {
            val m = out[t]
            if (sampled) {
                val src = if (m > 0.0) holder[t] else t
                for (p in 0 until kCount) nw[p * n + t] = weights[p * n + src]
                for (p in 0 until speciesCount) ns[p * n + t] = species[p * n + src]
                nl[t] = lineage[src]
            } else if (m > 1e-300) {
                for (p in 0 until kCount) nw[p * n + t] = acc[p * n + t] / m
                for (p in 0 until speciesCount) ns[p * n + t] = acc[(kCount + p) * n + t] / m
                nl[t] = lineage[holder[t]]
            } else {
                for (p in 0 until kCount) nw[p * n + t] = weights[p * n + t]
                for (p in 0 until speciesCount) ns[p * n + t] = species[p * n + t]
                nl[t] = lineage[t]
            }
        }
        if (massHistory.isEmpty()) record()
        matter = out
        weights = nw
        species = ns
        lineage = nl
        time++
        mutation?.let { applyMutations(it) }
        record()
        return this
    }

    /** [step] applied [steps] times. */
    fun steps(steps: Int): FlowLeniaWorld {
        require(steps >= 0) { "steps must be >= 0, got $steps" }
        repeat(steps) { step() }
        return this
    }

    /**
     * A copy of this world's state (matter, weights, tracers, lineages, [mutation]) under [rule], which
     * must have as many kernels. Histories start afresh. With a rule that differs only in
     * [FlowLeniaRule.convolution] the copy steps exactly as this world would, to rounding.
     */
    fun copy(rule: FlowLeniaRule = this.rule, randomSeed: Long = 1L): FlowLeniaWorld {
        require(rule.kernels.size == kCount) { "the rule has ${rule.kernels.size} kernels, this world $kCount" }
        val w = FlowLeniaWorld(width, height, rule, speciesCount, randomSeed)
        matter.copyInto(w.matter); weights.copyInto(w.weights); species.copyInto(w.species); lineage.copyInto(w.lineage)
        w.lineages.addAll(lineages)
        w.mutation = mutation
        w.time = time
        return w
    }

    /**
     * This world at [k] times the resolution: a `k·width` by `k·height` world under [FlowLeniaRule.scaled],
     * every cell's matter, weights, tracers and lineage copied into the `k x k` block it becomes. The
     * matter density is kept, so the total mass grows by `k²`. Find something small and fast, then watch
     * it large: the copy carries on with the same creatures drawn in `k²` as many cells. Histories start
     * afresh.
     */
    fun scaledCopy(k: Int, randomSeed: Long = 1L): FlowLeniaWorld {
        require(k >= 1) { "scale must be >= 1, got $k" }
        val w = FlowLeniaWorld(width * k, height * k, rule.scaled(k.toDouble()), speciesCount, randomSeed)
        val bn = w.width * w.height
        for (y in 0 until w.height) for (x in 0 until w.width) {
            val src = (y / k) * width + x / k
            val dst = y * w.width + x
            w.matter[dst] = matter[src]
            for (p in 0 until kCount) w.weights[p * bn + dst] = weights[p * n + src]
            for (p in 0 until speciesCount) w.species[p * bn + dst] = species[p * n + src]
            w.lineage[dst] = lineage[src]
        }
        w.lineages.addAll(lineages)
        w.mutation = mutation
        return w
    }

    /**
     * The connected bodies of matter, largest first: 8-connected cells holding more than [threshold]
     * matter, joined across the seams of the torus. Bodies under [minMass] are left out.
     */
    fun bodies(threshold: Double = 0.05, minMass: Double = 0.5): List<FlowLeniaBody> {
        val parent = IntArray(n) { it }
        fun find(a: Int): Int {
            var r = a
            while (parent[r] != r) r = parent[r]
            var c = a
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        for (y in 0 until height) for (x in 0 until width) {
            val c = y * width + x
            if (matter[c] <= threshold) continue
            // Half the 8-neighbourhood is enough: the other half joins from the far side.
            for (q in 0 until 4) {
                val o = Math.floorMod(y + HALF_DY[q], height) * width + Math.floorMod(x + HALF_DX[q], width)
                if (matter[o] > threshold) {
                    val a = find(c); val b = find(o)
                    if (a != b) parent[a] = b
                }
            }
        }
        class Acc {
            var mass = 0.0; var cells = 0
            var sx = 0.0; var cx = 0.0; var sy = 0.0; var cy = 0.0
            val sp = DoubleArray(speciesCount)
            val lin = HashMap<Int, Double>()
        }
        val accs = HashMap<Int, Acc>()
        val tx = 2.0 * Math.PI / width; val ty = 2.0 * Math.PI / height
        for (c in 0 until n) {
            val m = matter[c]
            if (m <= threshold) continue
            val a = accs.getOrPut(find(c)) { Acc() }
            val x = c % width + 0.5; val y = c / width + 0.5
            a.mass += m; a.cells++
            a.sx += m * kotlin.math.sin(x * tx); a.cx += m * kotlin.math.cos(x * tx)
            a.sy += m * kotlin.math.sin(y * ty); a.cy += m * kotlin.math.cos(y * ty)
            for (s in 0 until speciesCount) a.sp[s] += m * species[s * n + c]
            a.lin.merge(lineage[c], m, Double::plus)
        }
        fun wrap(v: Double, period: Int): Double { val r = v % period; return if (r < 0) r + period else r }
        return accs.values.filter { it.mass >= minMass }.map { a ->
            FlowLeniaBody(
                a.mass,
                wrap(kotlin.math.atan2(a.sx, a.cx) / tx, width),
                wrap(kotlin.math.atan2(a.sy, a.cy) / ty, height),
                a.cells,
                a.sp,
                a.lin.maxBy { it.value }.key
            )
        }.sortedByDescending { it.mass }
    }

    /**
     * Step [steps] times, following the largest body: each step the tracked body is the one nearest
     * (on the torus) to where it was, among bodies holding at least half its mass, or else the largest.
     * The path is unwrapped, so a body that crosses the seam keeps counting its distance.
     */
    fun trackLargest(steps: Int, threshold: Double = 0.05): FlowLeniaTrack {
        require(steps >= 0) { "steps must be >= 0, got $steps" }
        var cur = bodies(threshold).firstOrNull() ?: return FlowLeniaTrack(listOf(), DoubleArray(0))
        val path = ArrayList<Pair<Double, Double>>(steps + 1)
        val masses = DoubleArray(steps + 1)
        var ux = cur.x; var uy = cur.y
        path += ux to uy; masses[0] = cur.mass
        fun d(a: Double, b: Double, p: Int): Double {
            var v = (b - a) % p
            if (v > p / 2.0) v -= p
            if (v < -p / 2.0) v += p
            return v
        }
        for (i in 1..steps) {
            step()
            val bs = bodies(threshold)
            if (bs.isEmpty()) return FlowLeniaTrack(path, masses.copyOf(i))
            val near = bs.filter { it.mass >= 0.5 * cur.mass }
                .minByOrNull { hypot(d(cur.x, it.x, width), d(cur.y, it.y, height)) } ?: bs[0]
            ux += d(cur.x, near.x, width); uy += d(cur.y, near.y, height)
            cur = near
            path += ux to uy; masses[i] = cur.mass
        }
        return FlowLeniaTrack(path, masses)
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

    /**
     * An RGB picture coloured by lineage ([flowLeniaLineageColor]), brightness `matter / peak` clipped
     * to 1. The first lineages are the seeds, in the species colours of [speciesImg].
     */
    fun lineageImg(peak: Double = 1.2): Img {
        val d = FloatArray(n * 3)
        for (c in 0 until n) {
            val v = min(1.0, matter[c] / peak)
            if (v <= 0.0) continue
            val col = flowLeniaLineageColor(lineage[c])
            for (ch in 0 until 3) d[c * 3 + ch] = (v * col[ch]).toFloat()
        }
        return Img(width, height, 3, d)
    }

    /**
     * [lineageHistory] as stacked shares: one column per recorded step, each lineage's share of the
     * total mass a band in its [flowLeniaLineageColor], lineages stacked in order of id. A lineage that
     * takes over the world widens until it fills the column.
     */
    fun lineagePlot(w: Int = 360, h: Int = 140): Img {
        val out = Img.zeros(w, h, 3)
        val hist = if (lineageHistory.isEmpty()) listOf(lineageMasses()) else lineageHistory
        for (x in 0 until w) {
            val row = hist[if (hist.size == 1) 0 else (x.toDouble() / (w - 1) * (hist.size - 1)).toInt()]
            val total = row.sum()
            if (total <= 0.0) continue
            var acc = 0.0
            for ((id, m) in row.withIndex()) {
                if (m <= 0.0) continue
                val y0 = (acc / total * h).toInt(); acc += m
                val y1 = (acc / total * h).toInt()
                val col = flowLeniaLineageColor(id)
                for (y in y0 until min(h, y1)) for (ch in 0 until 3) out.data[((h - 1 - y) * w + x) * 3 + ch] = col[ch].toFloat()
            }
        }
        return out
    }

    private companion object {
        val HALF_DX = intArrayOf(1, -1, 0, 1)
        val HALF_DY = intArrayOf(0, 1, 1, 1)
    }

    override fun toString(): String =
        "FlowLeniaWorld(${width}x$height, step $time, mass %.6f, species %s)".format(
            totalMass(), speciesMasses().joinToString(", ") { "%.3f".format(it) }
        )
}

/**
 * One connected piece of matter in a [FlowLeniaWorld], as [FlowLeniaWorld.bodies] finds it.
 */
class FlowLeniaBody(
    /** Matter in the body's cells. */
    val mass: Double,
    /**
     * Mass-weighted centroid column, as a circular mean on the torus, so a body lying across the seam
     * is not reported halfway round the world.
     */
    val x: Double,
    /** Mass-weighted centroid row, a circular mean like [x]. */
    val y: Double,
    /** How many cells the body covers. */
    val cells: Int,
    /** The body's matter belonging to each species tracer. */
    val speciesMass: DoubleArray,
    /** The lineage holding the most of the body's matter ([FlowLeniaWorld.lineage]). */
    val lineage: Int
) {
    override fun toString(): String = "FlowLeniaBody(mass %.2f at (%.1f, %.1f), %d cells, lineage %d)".format(mass, x, y, cells, lineage)
}

/**
 * The largest body of a world followed over a run, from [FlowLeniaWorld.trackLargest].
 */
class FlowLeniaTrack(
    /** The body's centroid after each step, unwrapped: crossing the seam keeps counting instead of jumping back. */
    val path: List<Pair<Double, Double>>,
    /** The body's mass after each step. */
    val masses: DoubleArray
) {
    /** Steps the track covers. */
    val steps: Int get() = max(0, path.size - 1)

    /** Straight-line distance from the first centroid to the last, per step: drift, not wandering. */
    val speed: Double
        get() = if (path.size < 2) 0.0 else hypot(path.last().first - path.first().first, path.last().second - path.first().second) / (path.size - 1)

    /** Smallest mass over the track divided by the first: 1 for a body that kept all of itself. */
    val massKept: Double
        get() = if (masses.isEmpty() || masses[0] <= 0.0) 0.0 else masses.min() / masses[0]

    override fun toString(): String = "FlowLeniaTrack(%d steps, speed %.4f cells/step, mass kept %.3f, mass %.1f -> %.1f)".format(
        steps, speed, massKept, masses.firstOrNull() ?: 0.0, masses.lastOrNull() ?: 0.0
    )
}

/**
 * One lineage of a [FlowLeniaWorld]: the matter given one set of kernel weights at one moment, by
 * [FlowLeniaWorld.seed], [FlowLeniaWorld.mutate] or [FlowLeniaWorld.paintWeights].
 */
class FlowLeniaLineage(
    /** Index into [FlowLeniaWorld.lineages], and the value [FlowLeniaWorld.lineage] holds. */
    val id: Int,
    /** The lineage holding most of the matter it was cut from, or `-1` for a seed. */
    val parent: Int,
    /** [FlowLeniaWorld.time] when it was made. */
    val born: Int,
    /** The mass-weighted mean `h` it started with. */
    val h: DoubleArray
) {
    override fun toString(): String = "FlowLeniaLineage($id, parent $parent, born $born)"
}

/**
 * Mutation events applied by [FlowLeniaWorld.step] when [FlowLeniaWorld.mutation] is set: [rate] events
 * per step on average, each a [FlowLeniaWorld.mutate] of a disc of [radius] cells centred on a cell
 * drawn in proportion to its matter, offsetting `h` there by Gaussian noise of standard deviation
 * [scale] per kernel. `scale = 0` cuts new lineages without changing any rule: the neutral control.
 */
class FlowLeniaMutation(
    /** Expected mutation events per step. */
    val rate: Double = 0.02,
    /** Radius of each mutated disc, in cells. */
    val radius: Double = 6.0,
    /** Standard deviation of the offset added to each kernel weight. */
    val scale: Double = 0.1
) {
    init {
        require(rate >= 0.0) { "rate must be >= 0, got $rate" }
        require(radius > 0.0) { "radius must be > 0, got $radius" }
        require(scale >= 0.0) { "scale must be >= 0, got $scale" }
    }

    override fun toString(): String = "FlowLeniaMutation(rate=$rate, radius=$radius, scale=$scale)"
}

/**
 * The colour [FlowLeniaWorld.lineageImg] and [FlowLeniaWorld.lineagePlot] draw lineage [id] in: the
 * [FLOW_LENIA_COLORS] for the first four, then hues a golden angle apart.
 */
fun flowLeniaLineageColor(id: Int): DoubleArray {
    if (id in FLOW_LENIA_COLORS.indices) return FLOW_LENIA_COLORS[id]
    val hue = (id * 0.6180339887498949) % 1.0
    val h6 = hue * 6.0
    val i = floor(h6).toInt() % 6
    val f = h6 - floor(h6)
    val v = 1.0; val sat = 0.75
    val p = v * (1 - sat); val q = v * (1 - sat * f); val t = v * (1 - sat * (1 - f))
    return when (i) {
        0 -> doubleArrayOf(v, t, p)
        1 -> doubleArrayOf(q, v, p)
        2 -> doubleArrayOf(p, v, t)
        3 -> doubleArrayOf(p, q, v)
        4 -> doubleArrayOf(t, p, v)
        else -> doubleArrayOf(v, p, q)
    }
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

    /** The same species at [k] times the kernel radii ([FlowLeniaKernel.scaled]). */
    fun scaled(k: Double): FlowLeniaSpecies = FlowLeniaSpecies(name, kernels.map { it.scaled(k) }, h.copyOf())

    /** This species as the Kotlin expression that builds it, so a rule a search found can be pasted into a box. */
    fun code(): String =
        "FlowLeniaSpecies(\"$name\", listOf(\n" + kernels.joinToString(",\n") { "    " + it.code() } +
            "\n), doubleArrayOf(${h.joinToString(", ") { fmt(it) }}))"

    override fun toString(): String = "FlowLeniaSpecies($name, ${kernels.size} kernels)"
}

private fun fmt(v: Double): String = v.toString()

/**
 * A world holding [species], one seeded disc each at [positions] (default: spaced on a circle of 0.28
 * of the field about its centre). The world's rule has every species' kernels side by side, and each
 * species weights only its own, so with nothing nearby each one runs exactly its own rule; where their
 * matter mixes, so do their weights.
 *
 * [scale] draws every species at that many times its size, under [FlowLeniaRule.scaled]: the world
 * is seeded at `width / scale` by `height / scale` and grown with [FlowLeniaWorld.scaledCopy], so the
 * seed noise is as coarse, relative to the creatures, as at scale 1, and a scale-3 world of 288 cells
 * starts as the 96-cell one does, with nine cells for each. [positions] and [seedRadius] are in the
 * cells of the scale-1 world; the sides must divide by [scale].
 */
fun flowLeniaWorld(
    width: Int,
    height: Int,
    species: List<FlowLeniaSpecies>,
    positions: List<Pair<Double, Double>>? = null,
    seedRadius: Double = 12.0,
    mixing: FlowLeniaMixing = FlowLeniaMixing.MEAN,
    randomSeed: Long = 1L,
    scale: Int = 1
): FlowLeniaWorld {
    require(species.isNotEmpty()) { "at least one species" }
    require(scale >= 1 && width % scale == 0 && height % scale == 0) { "scale $scale must be >= 1 and divide ${width}x$height" }
    if (scale > 1) return flowLeniaWorld(width / scale, height / scale, species, positions, seedRadius, mixing, randomSeed).scaledCopy(scale, randomSeed)
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

    /**
     * Seeded from a small disc (radius 6), can fold into a crescent that glides, convex side first, and
     * keeps its mass; from other noise the same rule settles into a still round body instead. Found by
     * a random search of 900 rules scored like [flowLeniaGliderObjective].
     */
    fun crescent(): FlowLeniaSpecies = FlowLeniaSpecies(
        "crescent",
        listOf(
            k(4, doubleArrayOf(0.9261, 0.26), 0.377, 0.1202),
            k(5, doubleArrayOf(0.7788, 0.1065), 0.1887, 0.094),
            k(12, doubleArrayOf(0.7627, 0.0561, 0.6258), 0.1119, 0.0764),
            k(7, doubleArrayOf(0.2739, 0.1034, 0.9273), 0.146, 0.11),
            k(4, doubleArrayOf(0.945), 0.3883, 0.146),
            k(11, doubleArrayOf(0.3617), 0.4351, 0.06),
            k(4, doubleArrayOf(0.8558), 0.1713, 0.1106)
        ),
        doubleArrayOf(0.1987, 0.1299, 0.4975, 0.6427, 0.7717, 0.9327, 0.5246)
    )

    /**
     * A small hooked body that darts across the torus at about 0.1 cells per step and keeps its mass,
     * seeded from a radius-6 disc with noise seed 1 on a 64-cell torus (where it sheds two still rings
     * first). Found by [PopulationSearch.random] over [FlowLeniaGenome] with [flowLeniaGliderObjective]
     * on three seeds, 100 draws; its parameters are the draw's, unrounded.
     */
    fun dart(): FlowLeniaSpecies = FlowLeniaSpecies(
        "dart",
        listOf(
            k(7, doubleArrayOf(0.6825022617117669, 0.04880570995255851, 0.3804095998363488), 0.2847530221472747, 0.1285560642644265),
            k(10, doubleArrayOf(0.27628230259385067), 0.07125256880896896, 0.10595917658879259),
            k(9, doubleArrayOf(0.9706892347389171, 0.3283622334019689), 0.22006869955049368, 0.08405535176223075),
            k(4, doubleArrayOf(0.15788596707788516, 0.2840697916992533, 0.4972674420954122), 0.29373496363704893, 0.0986918178549788),
            k(6, doubleArrayOf(0.43637677780149486), 0.30415187674224237, 0.17593884467535958),
            k(6, doubleArrayOf(0.3281533861881044, 0.31888082776768545, 0.6339960045658815), 0.1914675399564122, 0.03643051175126947)
        ),
        doubleArrayOf(0.016024253110050825, 0.794017714232939, 0.5196614675888053, 0.1206450177520428, 0.9561818682428135, 0.6013567699679174)
    )
}

/**
 * Flow Lenia species as a genome for [PopulationSearch]: a list of ring kernels, each with its own
 * radius, shell peaks, `mu` and `sigma`, and a weight `h` on each. [random] draws from the paper's rule
 * space, the same one [FlowLeniaPresets] were found in, so [PopulationSearch.random] over it is the
 * control arm for any search that mutates within it.
 */
class FlowLeniaGenome(
    /** How many kernels a random species has. */
    val kernelCounts: IntRange = 3..7,
    /** Kernel radii. */
    val radii: IntRange = 3..12,
    /** Growth curve centres. */
    val muRange: ClosedFloatingPointRange<Double> = 0.05..0.5,
    /** Growth curve widths. */
    val sigmaRange: ClosedFloatingPointRange<Double> = 0.01..0.18,
    /** Kernel weights `h`. */
    val weightRange: ClosedFloatingPointRange<Double> = 0.01..1.0,
    /** Size of a `nudge`, as a fraction of each range. */
    val nudge: Double = 0.08
) : GenomeOps<FlowLeniaSpecies> {
    init {
        require(kernelCounts.first >= 1) { "a species needs at least one kernel" }
        require(radii.first >= 1) { "kernel radius must be >= 1" }
        require(sigmaRange.start > 0.0) { "sigma must stay positive" }
    }

    private fun draw(rng: Random, r: ClosedFloatingPointRange<Double>) = r.start + (r.endInclusive - r.start) * rng.nextDouble()

    /** One kernel drawn from the ranges: 1 to 3 shells with peaks in `0..1`. */
    fun randomKernel(rng: Random): FlowLeniaKernel = FlowLeniaKernel.ring(
        radii.first + rng.nextInt(radii.last - radii.first + 1),
        DoubleArray(1 + rng.nextInt(3)) { rng.nextDouble() },
        draw(rng, muRange),
        draw(rng, sigmaRange)
    )

    override fun random(rng: Random): FlowLeniaSpecies {
        val k = kernelCounts.first + rng.nextInt(kernelCounts.last - kernelCounts.first + 1)
        return FlowLeniaSpecies("random", List(k) { randomKernel(rng) }, DoubleArray(k) { draw(rng, weightRange) })
    }

    private fun gauss(rng: Random): Double {
        val u = 1.0 - rng.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * rng.nextDouble())
    }

    private fun span(r: ClosedFloatingPointRange<Double>) = r.endInclusive - r.start

    override val mutations: List<Variation<FlowLeniaSpecies>> = listOf(
        // Every mu, sigma and h moves a little: the search's small step.
        Variation("nudge") { g, rng ->
            FlowLeniaSpecies(
                "evolved",
                g.kernels.map { k ->
                    k.with(
                        mu = k.mu + gauss(rng) * nudge * span(muRange),
                        sigma = max(1e-4, k.sigma + gauss(rng) * nudge * span(sigmaRange))
                    )
                },
                DoubleArray(g.h.size) { g.h[it] + gauss(rng) * nudge * span(weightRange) }
            )
        },
        // One kernel replaced by a fresh draw.
        Variation("rekernel") { g, rng ->
            val i = rng.nextInt(g.kernels.size)
            FlowLeniaSpecies("evolved", g.kernels.toMutableList().also { it[i] = randomKernel(rng) }, g.h.copyOf())
        },
        // A kernel added, or one dropped, keeping within kernelCounts.
        Variation("addOrDrop") { g, rng ->
            val ks = g.kernels.toMutableList(); val hs = g.h.toMutableList()
            val canAdd = ks.size < kernelCounts.last; val canDrop = ks.size > kernelCounts.first
            if (canAdd && (!canDrop || rng.nextBoolean())) { ks += randomKernel(rng); hs += draw(rng, weightRange) }
            else if (canDrop) { val i = rng.nextInt(ks.size); ks.removeAt(i); hs.removeAt(i) }
            FlowLeniaSpecies("evolved", ks, hs.toDoubleArray())
        }
    )

    /** Each kernel, with its weight, from one parent or the other; the length is either parent's. */
    override fun crossover(a: FlowLeniaSpecies, b: FlowLeniaSpecies, rng: Random): FlowLeniaSpecies {
        val len = if (rng.nextBoolean()) a.kernels.size else b.kernels.size
        val ks = ArrayList<FlowLeniaKernel>(); val hs = DoubleArray(len)
        for (i in 0 until len) {
            val fromA = when {
                i >= a.kernels.size -> false
                i >= b.kernels.size -> true
                else -> rng.nextBoolean()
            }
            val p = if (fromA) a else b
            ks += p.kernels[i]; hs[i] = p.h[i]
        }
        return FlowLeniaSpecies("evolved", ks, hs)
    }

    override fun signature(g: FlowLeniaSpecies): String = g.code()
}


/** A species' glider run, from [flowLeniaGliderRun]: the world afterwards, the track, and the bodies at the end. */
class FlowLeniaGliderRun(
    /** The world, [FlowLeniaGliderRun.track]'s steps after the warm-up. */
    val world: FlowLeniaWorld,
    /** The largest body followed over the window. */
    val track: FlowLeniaTrack,
    /** Every body at the end of the window, largest first. */
    val bodies: List<FlowLeniaBody>
) {
    override fun toString(): String = "FlowLeniaGliderRun(${bodies.size} bodies, $track)"
}

/**
 * Seed [species] as one noise disc of [seedRadius] cells in the middle of a [size] torus, run [warmup]
 * steps, then follow its largest body for [window] steps ([FlowLeniaWorld.trackLargest]).
 */
fun flowLeniaGliderRun(
    species: FlowLeniaSpecies,
    size: Int = 64,
    warmup: Int = 200,
    window: Int = 200,
    seedRadius: Double = 12.0,
    initialSeed: Long = 1L
): FlowLeniaGliderRun {
    require(warmup >= 0 && window >= 0) { "warmup and window must be >= 0" }
    val world = flowLeniaWorld(size, size, listOf(species), seedRadius = seedRadius, randomSeed = initialSeed)
    world.steps(warmup)
    val track = world.trackLargest(window)
    return FlowLeniaGliderRun(world, track, world.bodies())
}

/**
 * Score a species on how far its largest body travels while holding together, averaged over
 * [seeds] noise seeds ([flowLeniaGliderRun] with `initialSeed` `1..seeds`). Each run scores the
 * tracked body's speed in cells per step times its [FlowLeniaTrack.massKept] squared, so a body that
 * sheds matter as it goes scores less than one that keeps it, and a body covering more than [maxCover]
 * of the world scores zero, since the centroid of a film that fills the torus moving is not a creature
 * moving. Averaging over seeds rewards a rule whose glider appears from most starts rather than one.
 * The behaviour is the mean `[speed, mass kept]`.
 */
fun flowLeniaGliderObjective(
    size: Int = 64,
    warmup: Int = 200,
    window: Int = 200,
    seedRadius: Double = 6.0,
    seeds: Int = 1,
    maxCover: Double = 0.4
): Objective<FlowLeniaSpecies> {
    require(seeds >= 1) { "seeds must be >= 1, got $seeds" }
    return Objective { sp ->
        var fit = 0.0; var speed = 0.0; var kept = 0.0
        for (s in 1..seeds) {
            val run = flowLeniaGliderRun(sp, size, warmup, window, seedRadius, s.toLong())
            val big = run.bodies.firstOrNull()
            val t = run.track
            if (big != null && big.cells <= maxCover * size * size) fit += t.speed * t.massKept * t.massKept
            speed += t.speed; kept += t.massKept
        }
        Evaluation(fit / seeds, behaviour = doubleArrayOf(speed / seeds, kept / seeds))
    }
}

/**
 * Toroidal convolution of one field with every kernel of a rule, through 2-D mixed-radix FFTs. The kernel
 * spectra are computed once; each call transforms the field once and inverts once per kernel, the
 * kernels on parallel threads.
 */
internal class FlowLeniaFft(private val w: Int, private val h: Int, kernels: List<FlowLeniaKernel>) {
    private val n = w * h
    private val rows = FlowLeniaFftPlan(w)
    private val cols = FlowLeniaFftPlan(h)

    /** Estimated multiply-adds for one 2-D transform. */
    val work: Double get() = n.toDouble() * (rows.work + cols.work)
    private val specRe = ArrayList<DoubleArray>()
    private val specIm = ArrayList<DoubleArray>()

    init {
        for (k in kernels) {
            val re = DoubleArray(n); val im = DoubleArray(n)
            val t = k.taps
            // (K * A)(p) = Σ w_i A(p + d_i) is a circular convolution with the taps placed at −d_i.
            for (i in 0 until t.size) re[Math.floorMod(-t.dy[i], h) * w + Math.floorMod(-t.dx[i], w)] += t.weight[i].toDouble()
            fft2(re, im, false)
            specRe += re; specIm += im
        }
    }

    /** `K_k * a` for every kernel `k`, in kernel order. */
    fun convolveAll(a: DoubleArray): Array<DoubleArray> {
        val ar = a.copyOf(); val ai = DoubleArray(n)
        fft2(ar, ai, false)
        val out = arrayOfNulls<DoubleArray>(specRe.size)
        java.util.stream.IntStream.range(0, specRe.size).parallel().forEach { k ->
            val sr = specRe[k]; val si = specIm[k]
            val re = DoubleArray(n); val im = DoubleArray(n)
            for (c in 0 until n) {
                re[c] = ar[c] * sr[c] - ai[c] * si[c]
                im[c] = ar[c] * si[c] + ai[c] * sr[c]
            }
            fft2(re, im, true)
            val inv = 1.0 / n
            for (c in 0 until n) re[c] *= inv
            out[k] = re
        }
        return Array(specRe.size) { out[it]!! }
    }

    private fun fft2(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val tr = DoubleArray(w); val ti = DoubleArray(w)
        for (y in 0 until h) {
            val o = y * w
            System.arraycopy(re, o, tr, 0, w); System.arraycopy(im, o, ti, 0, w)
            rows.transform(tr, ti, inverse)
            System.arraycopy(tr, 0, re, o, w); System.arraycopy(ti, 0, im, o, w)
        }
        val cr = DoubleArray(h); val ci = DoubleArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) { cr[y] = re[y * w + x]; ci[y] = im[y * w + x] }
            cols.transform(cr, ci, inverse)
            for (y in 0 until h) { re[y * w + x] = cr[y]; im[y * w + x] = ci[y] }
        }
    }
}

/**
 * A mixed-radix Cooley-Tukey transform of one length: any length, at a cost of `len` times the sum of
 * its prime factors, so lengths built from small primes (64, 96, 288, 384) are fast.
 */
internal class FlowLeniaFftPlan(val len: Int) {
    private val cosT = DoubleArray(len) { kotlin.math.cos(2.0 * Math.PI * it / len) }
    private val sinT = DoubleArray(len) { kotlin.math.sin(2.0 * Math.PI * it / len) }

    /** Sum of the prime factors of [len], with multiplicity: the transform's work per element. */
    val work: Int = run {
        var m = len; var acc = 0; var p = 2
        while (m > 1) { while (m % p == 0) { acc += p; m /= p }; p++ }
        acc
    }

    /** Transform in place; [inverse] conjugates the twiddles and does not divide by the length. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val yr = DoubleArray(len); val yi = DoubleArray(len)
        rec(re, im, 0, 1, len, yr, yi, 0, if (inverse) 1.0 else -1.0)
        System.arraycopy(yr, 0, re, 0, len); System.arraycopy(yi, 0, im, 0, len)
    }

    private fun smallestFactor(m: Int): Int {
        if (m % 2 == 0) return 2
        var p = 3
        while (p * p <= m) { if (m % p == 0) return p; p += 2 }
        return m
    }

    private fun rec(xr: DoubleArray, xi: DoubleArray, off: Int, stride: Int, l: Int, yr: DoubleArray, yi: DoubleArray, yoff: Int, sign: Double) {
        if (l == 1) { yr[yoff] = xr[off]; yi[yoff] = xi[off]; return }
        val p = smallestFactor(l)
        val m = l / p
        for (r in 0 until p) rec(xr, xi, off + r * stride, stride * p, m, yr, yi, yoff + r * m, sign)
        val step = len / l
        val tr = DoubleArray(l); val ti = DoubleArray(l)
        for (q in 0 until p) for (k in 0 until m) {
            val idx = k + m * q
            var sr = 0.0; var si = 0.0
            for (r in 0 until p) {
                val a = yr[yoff + r * m + k]; val b = yi[yoff + r * m + k]
                val t = ((r.toLong() * idx) % l).toInt() * step
                val c = cosT[t]; val s = sign * sinT[t]
                sr += a * c - b * s
                si += a * s + b * c
            }
            tr[idx] = sr; ti[idx] = si
        }
        System.arraycopy(tr, 0, yr, yoff, l); System.arraycopy(ti, 0, yi, yoff, l)
    }
}
