@file:afield.CompletionCategory("ALife")

package afield.stdlib

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Continuous spatial ALife: reaction–diffusion (Gray–Scott / Turing) and Lenia, all shaped as
 * `Img -> Img` steps so they compose with the imaging family.
 *
 * See `alife.kt` for the family overview.
 *
 * Every system here is a **field**, not a lattice of labels, so the measurements that matter are
 * different from the discrete half: not "is this pattern periodic" but "what shape are its features"
 * ([Img.blobShapes]) and "what length scale did it choose" ([Img.correlationLength]). The one exception,
 * and the reason the two halves live in the same family, is [leniaFromLifeRule]: Lenia in its discrete
 * limit *is* a life-like CA, and that gives this file's float convolution and `alife_ca.kt`'s integer
 * neighbour counting a claim they must agree on exactly.
 */

// ==============================================================================================
// Diffusion
// ==============================================================================================

/**
 * The 9-point Laplacian with the weights the reaction–diffusion literature uses:
 * `0.05` on the diagonals, `0.2` on the edges, `-1` at the centre.
 *
 * It sums to **exactly zero**, which is the property that makes `u + rate * Lap(u)` conserve total
 * mass on a torus — the invariant [diffuseTimes] is tested against. It is also more isotropic than
 * [LAPLACIAN_4]: with only the four edge neighbours, a diffusing blob visibly grows into a diamond,
 * and a Turing pattern picks up a grid-aligned bias that looks like structure and is not.
 */
val REACTION_DIFFUSION_LAPLACIAN: Kernel2D = Kernel2D.of(
    floatArrayOf(0.05f, 0.20f, 0.05f),
    floatArrayOf(0.20f, -1.00f, 0.20f),
    floatArrayOf(0.05f, 0.20f, 0.05f)
)

/**
 * One explicit-Euler diffusion step: `u + rate * Lap(u)`, on a torus by default.
 *
 * @param rate the diffusion coefficient times the timestep. Stability needs `rate <= 0.5` for this
 *   kernel (the usual explicit-scheme CFL condition); above that the field oscillates and diverges,
 *   which is a property of the scheme rather than a bug, so it is required rather than clamped.
 * @param border **[Border.WRAP] and [Border.CLAMP] both conserve mass; only [Border.ZERO] loses it.**
 *   WRAP is a torus and CLAMP is a *reflecting*, zero-flux (Neumann) boundary, so neither creates nor
 *   destroys material — measured at `1.2e-6` relative over 500 steps for both. ZERO is absorbing and
 *   loses 3.7% over the same span. So if diffusion appears to be "leaking", this parameter is the first
 *   thing to check and `ZERO` is the value to suspect. (This line previously said CLAMP "invents
 *   material at the edges", which is false and which `AlifeGallery`'s diffusion figure has asserted to
 *   be false since the day it was written.)
 */
fun Img.diffuse(rate: Double = 0.2, border: Border = Border.WRAP): Img {
    require(rate > 0.0 && rate <= 0.5) { "diffusion rate must be in (0, 0.5] for stability, got $rate" }
    val lap = convolve2D(REACTION_DIFFUSION_LAPLACIAN, border)
    return zip(lap) { u, l -> u + rate.toFloat() * l }
}

/** [diffuse] applied [steps] times. */
fun Img.diffuseTimes(steps: Int, rate: Double = 0.2, border: Border = Border.WRAP): Img {
    require(steps >= 0) { "steps must be >= 0, got $steps" }
    var f = this
    repeat(steps) { f = f.diffuse(rate, border) }
    return f
}

// ==============================================================================================
// Gray–Scott reaction–diffusion
// ==============================================================================================

/**
 * A Gray–Scott reaction–diffusion state: two coupled fields `u` (the substrate) and `v` (the
 * autocatalyst), both `Img`s of the same shape.
 *
 * The reaction is `U + 2V -> 3V` with `V -> P`, and the equations are
 *
 * ```
 * du/dt = Du * Lap(u) - u*v²   + F * (1 - u)
 * dv/dt = Dv * Lap(v) + u*v²   - (F + k) * v
 * ```
 *
 * `F` is the feed rate of fresh `u` and `k` is the kill rate of `v`. The entire zoo of morphologies —
 * spots, stripes, labyrinths, dividing blobs, travelling waves — is a function of those two numbers,
 * which is why the system is a canonical morphogenesis model rather than merely a pretty one.
 *
 * @property u substrate field, 1-channel; starts at 1 everywhere
 * @property v autocatalyst field, 1-channel; starts at 0 except where you seed it
 */
class GrayScottState(val u: Img, val v: Img) : afield.ToHTML {
    init {
        require(u.channels == 1 && v.channels == 1) { "Gray–Scott fields must be 1-channel" }
        require(u.sameShapeAs(v)) { "u and v must have the same shape" }
    }

    /** Field width in cells. */
    val width: Int get() = u.width

    /** Field height in cells. */
    val height: Int get() = u.height

    override fun toString(): String =
        "GrayScottState(${width}x$height, v in ${"%.3f".format(v.min())}..${"%.3f".format(v.max())})"

    /**
     * Inline view: both reagent fields on **fixed** `0..1` scales plus the morphology reading. Body in
     * `alife_field.kt` ([alifeGrayScottHtml]); the fixed scale is the point, because `Img`'s own
     * auto-normalising thumbnail turns an unseeded `v = 0` control arm into a rich texture.
     */
    override fun toHTML(): String = alifeGrayScottHtml(this)
}

/**
 * Gray–Scott parameters.
 *
 * `Du = 0.16`, `Dv = 0.08` and `dt = 1` are the standard values used with the [REACTION_DIFFUSION_LAPLACIAN]
 * weights; the interesting axis is `(feed, kill)`.
 *
 * **On the preset names.** The named presets below are labelled by the morphology this codebase
 * *measures* them to produce ([Img.blobShapes] and [Img.correlationLength], reported by
 * `AlifeGallery`), not by a label copied from a paper. The `(F, k)` values sit in the regions of
 * Pearson's phase diagram usually reported for those morphologies, but the label is earned by
 * measurement here rather than by citation — see `public/docs/alife_examples.md` for the numbers.
 *
 * @property feed `F`, the rate fresh substrate is supplied at
 * @property kill `k`, the extra rate the autocatalyst is removed at
 * @property du substrate diffusion coefficient
 * @property dv autocatalyst diffusion coefficient (must be smaller than [du] for a Turing instability)
 * @property dt timestep
 */
data class GrayScottParams(
    val feed: Double,
    val kill: Double,
    val du: Double = 0.16,
    val dv: Double = 0.08,
    val dt: Double = 1.0
) {
    init {
        require(feed >= 0.0) { "feed must be >= 0, got $feed" }
        require(kill >= 0.0) { "kill must be >= 0, got $kill" }
        require(du > 0.0 && dv > 0.0) { "diffusion coefficients must be > 0, got $du / $dv" }
        require(dt > 0.0) { "dt must be > 0, got $dt" }
        require(du * dt <= 0.5 && dv * dt <= 0.5) {
            "explicit Euler needs D*dt <= 0.5 for this Laplacian, got ${du * dt} / ${dv * dt}"
        }
    }
}

/** `F = 0.030, k = 0.062` — isolated round spots that neither grow nor merge. */
val GRAY_SCOTT_SPOTS: GrayScottParams = GrayScottParams(feed = 0.030, kill = 0.062)

/** `F = 0.037, k = 0.060` — elongated worms that wander and branch into a labyrinth. */
val GRAY_SCOTT_WORMS: GrayScottParams = GrayScottParams(feed = 0.037, kill = 0.060)

/** `F = 0.029, k = 0.057` — a space-filling maze of connected corridors. */
val GRAY_SCOTT_MAZE: GrayScottParams = GrayScottParams(feed = 0.029, kill = 0.057)

/**
 * `F = 0.0367, k = 0.0649` — the dividing/sparse-spot regime.
 *
 * What `AlifeGallery` **measures** it to do, which is narrower than the "mitosis" name suggests: a
 * square seed patch pinches into four spots within about 80 steps, all seeds doing so inside the same
 * 20-step window, and the spot count is then stable for thousands of steps at a coverage of ~1 %. So it
 * is one synchronous division from this initial condition, not sustained mitosis. The name is kept
 * because the mechanism is the dividing instability; the measured behaviour is stated here so the name
 * is not read as a claim.
 */
val GRAY_SCOTT_MITOSIS: GrayScottParams = GrayScottParams(feed = 0.0367, kill = 0.0649)

/** `F = 0.039, k = 0.058` — the inverse morphology: round *holes* in a filled field. */
val GRAY_SCOTT_HOLES: GrayScottParams = GrayScottParams(feed = 0.039, kill = 0.058)

/**
 * Start a Gray–Scott run: `u = 1` everywhere, `v = 0` except inside a few square seed patches of
 * random noise.
 *
 * Seeding **patches** rather than the whole field is how the classic pictures are made, and it is not
 * only aesthetic: `v = 0` is a stable fixed point of the equations, so a field with no seed stays
 * blank forever (a fact worth knowing before concluding the solver is broken). Localised seeds also
 * let the pattern's own front speed be visible.
 *
 * @param patches how many seed squares
 * @param patchSize side of each seed square in cells
 */
fun grayScottSeed(
    width: Int,
    height: Int,
    patches: Int = 5,
    patchSize: Int = 12,
    seed: Long = 1L
): GrayScottState {
    require(width >= 3 && height >= 3) { "Gray–Scott field must be at least 3x3, got ${width}x$height" }
    require(patches >= 0) { "patches must be >= 0, got $patches" }
    require(patchSize >= 1) { "patchSize must be >= 1, got $patchSize" }
    val rng = kotlin.random.Random(seed)
    val u = Img.filled(width, height, 1, 1f)
    val v = Img.zeros(width, height, 1)
    repeat(patches) {
        val cx = rng.nextInt(width)
        val cy = rng.nextInt(height)
        for (dy in -patchSize / 2..patchSize / 2) for (dx in -patchSize / 2..patchSize / 2) {
            val x = Math.floorMod(cx + dx, width)
            val y = Math.floorMod(cy + dy, height)
            u.data[y * width + x] = 0.50f + 0.1f * rng.nextFloat()
            v.data[y * width + x] = 0.25f + 0.2f * rng.nextFloat()
        }
    }
    return GrayScottState(u, v)
}

/**
 * One Gray–Scott timestep, returning a new [GrayScottState].
 *
 * The two Laplacians go through [Img.convolve2D] with [Border.WRAP], so the domain is a torus and the
 * only mass leaving the system is what the reaction removes.
 */
fun GrayScottState.step(p: GrayScottParams): GrayScottState {
    val lapU = u.convolve2D(REACTION_DIFFUSION_LAPLACIAN, Border.WRAP)
    val lapV = v.convolve2D(REACTION_DIFFUSION_LAPLACIAN, Border.WRAP)
    val du = p.du.toFloat(); val dv = p.dv.toFloat()
    val f = p.feed.toFloat(); val k = p.kill.toFloat(); val dt = p.dt.toFloat()
    val nu = FloatArray(u.data.size)
    val nv = FloatArray(v.data.size)
    for (i in u.data.indices) {
        val uu = u.data[i]
        val vv = v.data[i]
        val reaction = uu * vv * vv
        nu[i] = (uu + dt * (du * lapU.data[i] - reaction + f * (1f - uu))).coerceIn(0f, 1f)
        nv[i] = (vv + dt * (dv * lapV.data[i] + reaction - (f + k) * vv)).coerceIn(0f, 1f)
    }
    return GrayScottState(
        Img(u.width, u.height, 1, nu),
        Img(v.width, v.height, 1, nv)
    )
}

/** [step] applied [steps] times, returning only the final state. */
fun GrayScottState.stepTimes(p: GrayScottParams, steps: Int): GrayScottState {
    require(steps >= 0) { "steps must be >= 0, got $steps" }
    var s = this
    repeat(steps) { s = s.step(p) }
    return s
}

/**
 * Every `every`-th state from this one out to [steps], inclusive of both ends — a filmstrip of the
 * run without stepping it twice.
 */
fun GrayScottState.filmstrip(p: GrayScottParams, steps: Int, every: Int): List<GrayScottState> {
    require(steps >= 0) { "steps must be >= 0, got $steps" }
    require(every >= 1) { "every must be >= 1, got $every" }
    val out = ArrayList<GrayScottState>()
    var s = this
    out.add(s)
    for (i in 1..steps) {
        s = s.step(p)
        if (i % every == 0 || i == steps) out.add(s)
    }
    return out
}

/**
 * What the morphology of a reaction–diffusion field measures as — the numbers a claim about a *regime*
 * has to be made out of, rather than a look at a thumbnail.
 *
 * @property coverage fraction of the field above the threshold
 * @property blobCount how many connected features there are
 * @property meanArea mean feature area in cells
 * @property medianIsoperimetric median `P²/4πA` over the features: ~1 for round spots, large for worms
 * @property correlationLength the length scale from [Img.correlationLength]
 * @property largestShare share of the covered area held by the single largest feature — the number
 *   that separates "many separate spots" from "one connected labyrinth"
 */
data class MorphologyReport(
    val coverage: Double,
    val blobCount: Int,
    val meanArea: Double,
    val medianIsoperimetric: Double,
    val correlationLength: Double,
    val largestShare: Double
) {
    override fun toString(): String =
        "coverage %.3f, %d blobs, mean area %.1f, median P²/4πA %.2f, correlation length %.2f, largest share %.2f"
            .format(coverage, blobCount, meanArea, medianIsoperimetric, correlationLength, largestShare)
}

/**
 * Measure a 1-channel field's morphology at [threshold] — the single call every regime claim in this
 * family is made through.
 *
 * *Not* clever: threshold, label, and report shape statistics. A regime claim is then a
 * claim about these numbers, and a control arm (diffusion off, the other preset, a shuffled field)
 * produces different ones. See [MorphologyReport] for what each field discriminates.
 *
 * @param level `null` uses [Img.otsuThreshold], which is the right default for a field whose two
 *   phases are genuinely bimodal — and if they are not, that is itself worth knowing
 * @param minArea features smaller than this are ignored, so single-cell numerical noise does not
 *   dominate the blob count
 */
fun Img.morphology(level: Float? = null, minArea: Int = 4): MorphologyReport {
    require(channels == 1) { "morphology expects a 1-channel field" }
    val t = level ?: otsuThreshold()
    val mask = threshold(t)
    val shapes = mask.blobShapes(minArea)
    val covered = mask.data.count { it > 0.5f }
    val areas = shapes.map { it.area }
    val ratios = shapes.map { it.isoperimetricRatio }.sorted()
    return MorphologyReport(
        coverage = covered.toDouble() / pixelCount,
        blobCount = shapes.size,
        meanArea = if (areas.isEmpty()) 0.0 else areas.average(),
        medianIsoperimetric = if (ratios.isEmpty()) 0.0 else ratios[ratios.size / 2],
        correlationLength = correlationLength(maxLag = min(48, min(width, height) / 3)),
        largestShare = if (covered == 0 || areas.isEmpty()) 0.0 else areas.max().toDouble() / covered
    )
}

/**
 * A Turing-instability field in the simplest form that shows the essential fact: two species, one
 * diffusing fast and inhibiting, one diffusing slowly and activating, produce a pattern whose
 * **wavelength is set by the diffusion ratio and not by the initial condition**.
 *
 * This is the Gierer–Meinhardt / activator–inhibitor scheme, integrated explicitly:
 *
 * ```
 * da/dt = Da * Lap(a) + rho * (a²/h - a)
 * dh/dt = Dh * Lap(h) + rho * (a²   - h)
 * ```
 *
 * @param scale multiplies both diffusion coefficients. The point of exposing it: linear stability
 *   theory says the selected wavelength grows like `sqrt(scale)`, so running the same seed at two
 *   scales and measuring [Img.correlationLength] is a *prediction test*, which is what the gallery
 *   does. A run that ignores `scale` (or a measurement that reads the seed's own scale rather than
 *   the pattern's) fails it.
 * @param rho reaction rate. The selected wavelength goes as `sqrt(D / rho)`, so this is the *other*
 *   handle on the pattern's scale — and the useful one, because [scale] is capped near 1.25 by the
 *   explicit scheme's stability limit while `rho` is not. At the default `0.010` the pattern's
 *   correlation length is a little over one cell at `scale = 1`; raising `rho` toward `0.04` packs the
 *   spots to a couple of cells apart, which is still a correct Turing pattern and a worse picture.
 *   (This line used to say "at the default 0.04"; the default was lowered to 0.010 and the prose was
 *   not, so every number in it was calibrated against a value the code no longer used.)
 * @param steps integration steps. A smaller [rho] is a slower reaction and needs more of them.
 */
fun turingPattern(
    width: Int,
    height: Int,
    scale: Double = 1.0,
    steps: Int = 4000,
    rho: Double = 0.010,
    seed: Long = 1L
): Img {
    require(width >= 8 && height >= 8) { "Turing field must be at least 8x8, got ${width}x$height" }
    require(scale > 0.0) { "scale must be > 0, got $scale" }
    require(steps >= 1) { "steps must be >= 1, got $steps" }
    require(rho > 0.0) { "rho must be > 0, got $rho" }
    val da = (0.02 * scale)
    val dh = (0.40 * scale)
    require(dh <= 0.5) { "diffusion scale too large for an explicit step: Dh*dt = $dh must be <= 0.5" }
    val rng = kotlin.random.Random(seed)
    var a = Img.generate(width, height) { _, _ -> (1f + 0.05f * (rng.nextFloat() - 0.5f)) }
    var h = Img.filled(width, height, 1, 1f)
    val r = rho.toFloat()
    repeat(steps) {
        val lapA = a.convolve2D(REACTION_DIFFUSION_LAPLACIAN, Border.WRAP)
        val lapH = h.convolve2D(REACTION_DIFFUSION_LAPLACIAN, Border.WRAP)
        val na = FloatArray(a.data.size)
        val nh = FloatArray(h.data.size)
        for (i in a.data.indices) {
            val av = a.data[i]
            val hv = max(1e-4f, h.data[i])
            na[i] = (av + (da.toFloat() * lapA.data[i] + r * (av * av / hv - av))).coerceIn(0f, 8f)
            nh[i] = (hv + (dh.toFloat() * lapH.data[i] + r * (av * av - hv))).coerceIn(1e-4f, 64f)
        }
        a = Img(width, height, 1, na)
        h = Img(width, height, 1, nh)
    }
    return a
}

// ==============================================================================================
// Lenia
// ==============================================================================================

/**
 * A Lenia rule: a **continuous** cellular automaton. One field `A` in `0..1`, one convolution kernel
 * `K`, one growth mapping `G`, and the update
 *
 * ```
 * A <- clamp(A + dt * G(K * A), 0, 1)
 * ```
 *
 * That is the whole system. Its interest is that with a smooth ring kernel and a smooth bell-shaped
 * growth mapping it supports *localised, self-maintaining, moving* structures — solitons — in a way
 * the discrete Life family does not, because a continuous field can hold a shape that a lattice
 * quantises away.
 *
 * The kernel is a sum of concentric annular shells: shell `b` of `shellPeaks.size` is centred at
 * radius `(b + 0.5) / shellPeaks.size * R` with a Gaussian cross-section, weighted by
 * `shellPeaks[b]`. A single peak gives one ring, `[1, 0.5]` gives a strong inner ring and a weak
 * outer one, and so on. The whole kernel is normalised to sum 1, so `K * A` is a weighted mean in
 * `0..1` and the growth mapping's domain is the unit interval whatever `R` is.
 *
 * @property radius `R`, kernel radius in cells
 * @property dt `1/T` in the usual notation — the integration step. Smaller is smoother and slower.
 * @property mu growth mapping centre: the neighbourhood mean that produces maximum growth
 * @property sigma growth mapping width
 * @property shellPeaks relative weights of the concentric kernel shells
 * @property shellWidth Gaussian cross-section width of each shell, as a fraction of the shell spacing
 * @property growth the growth mapping `G`, defaulting to the standard bell
 *   `2*exp(-(u-mu)²/(2σ²)) - 1`; overridden by [leniaFromLifeRule] to recover a discrete rule
 */
class LeniaRule(
    val radius: Int = 12,
    val dt: Double = 0.1,
    val mu: Double = 0.15,
    val sigma: Double = 0.017,
    val shellPeaks: DoubleArray = doubleArrayOf(1.0),
    val shellWidth: Double = 0.15,
    val growth: (Float) -> Float = { u ->
        val d = u - mu
        (2.0 * exp(-d * d / (2.0 * sigma * sigma)) - 1.0).toFloat()
    },
    val kernelTaps: LeniaTaps? = null
) {
    init {
        require(radius >= 1) { "kernel radius must be >= 1, got $radius" }
        require(dt > 0.0 && dt <= 1.0) { "dt must be in (0, 1], got $dt" }
        require(sigma > 0.0) { "sigma must be > 0, got $sigma" }
        require(shellPeaks.isNotEmpty()) { "shellPeaks needs at least one shell" }
        require(shellWidth > 0.0) { "shellWidth must be > 0, got $shellWidth" }
    }

    /**
     * The kernel as a sparse tap list: `(dx, dy, weight)` for every tap whose weight is worth
     * evaluating, weights summing to 1 — [kernelTaps] when one was supplied, otherwise built from the
     * shell parameters.
     *
     * Sparse rather than dense because a ring kernel of radius 12 has 625 dense taps of which the
     * centre and the corners contribute nothing measurable, and the convolution is the entire cost of
     * a Lenia step. Taps below `1e-6` of the peak are dropped, which is well below the precision the
     * growth mapping can distinguish.
     */
    val taps: LeniaTaps = kernelTaps ?: buildTaps()

    private fun buildTaps(): LeniaTaps {
        val b = shellPeaks.size
        val dx = ArrayList<Int>()
        val dy = ArrayList<Int>()
        val w = ArrayList<Float>()
        var total = 0.0
        val raw = ArrayList<Double>()
        for (j in -radius..radius) for (i in -radius..radius) {
            val r = hypot(i.toDouble(), j.toDouble()) / radius
            if (r > 1.0 || (i == 0 && j == 0)) continue
            val shell = min(b - 1, (r * b).toInt())
            val centre = (shell + 0.5) / b
            val s = shellWidth / b
            val d = (r - centre) / s
            val v = shellPeaks[shell] * exp(-0.5 * d * d)
            if (v < 1e-6) continue
            dx.add(i); dy.add(j); raw.add(v); total += v
        }
        require(total > 0.0) { "Lenia kernel is empty — shellWidth $shellWidth too small for radius $radius?" }
        for (v in raw) w.add((v / total).toFloat())
        return LeniaTaps(dx.toIntArray(), dy.toIntArray(), w.toFloatArray())
    }

    /** The kernel rendered as an [Img] for inspection — the picture that explains the rule. */
    fun kernelImg(): Img {
        val n = 2 * radius + 1
        val out = Img.zeros(n, n, 1)
        val t = taps
        for (i in t.dx.indices) out.data[(t.dy[i] + radius) * n + (t.dx[i] + radius)] = t.weight[i]
        return out
    }

    /** The growth mapping sampled over `0..1`, for plotting it beside the kernel. */
    fun growthCurve(samples: Int = 256): DoubleArray {
        require(samples >= 2) { "samples must be >= 2, got $samples" }
        return DoubleArray(samples) { growth(it.toFloat() / (samples - 1)).toDouble() }
    }

    override fun toString(): String =
        "LeniaRule(R=$radius, dt=$dt, mu=$mu, sigma=$sigma, shells=${shellPeaks.joinToString(",")}, ${taps.dx.size} taps)"
}

/**
 * A Lenia kernel as parallel arrays of offsets and weights.
 *
 * @property dx x offsets
 * @property dy y offsets
 * @property weight normalised weights, summing to 1
 */
class LeniaTaps(val dx: IntArray, val dy: IntArray, val weight: FloatArray) {
    init {
        require(dx.size == dy.size && dx.size == weight.size) { "LeniaTaps arrays must be the same length" }
    }

    /** Number of taps. */
    val size: Int get() = dx.size
}

/**
 * One Lenia step over a 1-channel field, toroidal. Returns a new [Img].
 *
 * The neighbourhood mean `K * A` is accumulated directly from the sparse taps rather than through
 * [Img.convolve2D], because the kernel is a ring rather than a separable box and the dense form would
 * evaluate several hundred zero taps per cell.
 */
fun Img.leniaStep(rule: LeniaRule): Img {
    require(channels == 1) { "leniaStep expects a 1-channel field" }
    val t = rule.taps
    val out = FloatArray(data.size)
    val dt = rule.dt.toFloat()
    val n = t.size
    for (y in 0 until height) {
        for (x in 0 until width) {
            var acc = 0f
            for (i in 0 until n) {
                val sx = x + t.dx[i]
                val sy = y + t.dy[i]
                val wx = if (sx in 0 until width) sx else Math.floorMod(sx, width)
                val wy = if (sy in 0 until height) sy else Math.floorMod(sy, height)
                acc += data[wy * width + wx] * t.weight[i]
            }
            val i0 = y * width + x
            out[i0] = (data[i0] + dt * rule.growth(acc)).coerceIn(0f, 1f)
        }
    }
    return Img(width, height, 1, out)
}

/** [leniaStep] applied [steps] times. */
fun Img.leniaTimes(rule: LeniaRule, steps: Int): Img {
    require(steps >= 0) { "steps must be >= 0, got $steps" }
    var f = this
    repeat(steps) { f = f.leniaStep(rule) }
    return f
}

/**
 * A [LeniaRule] that reproduces the two-state life-like rule [rule] **exactly** — Lenia's discrete
 * limit, and the cross-check that ties this file to `alife_ca.kt`.
 *
 * How it works, and why it is exact:
 *
 * - The kernel is the uniform Moore ring: 8 taps of weight `1/8`, so `K * A` for a two-state field is
 *   exactly `liveNeighbours / 8`.
 * - `dt = 1`, so the update is `clamp(A + G, 0, 1)`.
 * - `G` is three-valued: `+1` at neighbour counts in `rule.birth`, `0` at counts in
 *   `rule.survive - rule.birth`, `-1` elsewhere. With `A ∈ {0, 1}` and the clamp: `G = +1` forces the
 *   cell alive from either state, `G = 0` leaves it as it was (survival), and `G = -1` forces it dead.
 *   That is precisely outer-totalistic `B/S` semantics.
 *
 * **The one restriction, and it is a real fact rather than an implementation limit:** this requires
 * `birth ⊆ survive`. If a count is a birth count but not a survival count, then a live cell with that
 * count must die while a dead cell with it must be born — and no function of the neighbour count alone
 * can do both. Conway's `B3/S23` satisfies it (`3 ∈ {2,3}`); a rule like `B3/S2` does not, and this
 * function rejects it rather than silently producing a different automaton.
 *
 * @throws IllegalArgumentException when [rule] has a radius other than 1, or when `birth ⊄ survive`
 */
fun leniaFromLifeRule(rule: LifeRule): LeniaRule {
    require(rule.radius == 1 && !rule.vonNeumann) {
        "the discrete limit is defined for the radius-1 Moore neighbourhood, got $rule"
    }
    require(rule.survive.containsAll(rule.birth)) {
        "Lenia's discrete limit needs birth ⊆ survive (a single growth mapping cannot both birth a dead " +
            "cell and kill a live one at the same neighbour count); ${rule.notation} has " +
            "${rule.birth - rule.survive} in birth but not survive"
    }
    val birth = rule.birth
    val survive = rule.survive
    // The taps are given explicitly rather than built from the shell parameters. The shell builder
    // measures radius in Euclidean units and drops anything past `radius`, so at R = 1 the four
    // diagonals (at distance sqrt(2)) fall outside and the kernel would be the von Neumann cross —
    // which is a different automaton, and the first version of this function shipped that bug until a
    // tap count of 4 gave it away.
    val dx = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
    val dy = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
    return LeniaRule(
        radius = 1,
        dt = 1.0,
        mu = 0.5,
        sigma = 0.1,
        shellPeaks = doubleArrayOf(1.0),
        growth = { u ->
            // u is exactly count/8 for a two-state field; round to the nearest count.
            val count = Math.round(u * 8f)
            when {
                count in birth -> 1f
                count in survive -> 0f
                else -> -1f
            }
        },
        kernelTaps = LeniaTaps(dx, dy, FloatArray(8) { 1f / 8f })
    )
}

/**
 * Named continuous-Lenia parameter sets. These are labelled by the behaviour this codebase
 * **measures** ([leniaPhase]), not by a creature name from the literature: the family does
 * not claim to reproduce a named Lenia lifeform, because reproducing one requires its exact
 * published initial pattern and this file does not ship one.
 */
object LeniaPresets {

    /** The reference rule shape — one ring, `R = 12`, `T = 10` — with `mu` left for a sweep. */
    fun ring(mu: Double, sigma: Double = 0.017, radius: Int = 12, dt: Double = 0.1): LeniaRule =
        LeniaRule(radius = radius, dt = dt, mu = mu, sigma = sigma)

    /** A three-shell kernel, the shape Lenia's richer lifeforms use. */
    fun threeShell(mu: Double, sigma: Double = 0.025, radius: Int = 12, dt: Double = 0.1): LeniaRule =
        LeniaRule(
            radius = radius, dt = dt, mu = mu, sigma = sigma,
            shellPeaks = doubleArrayOf(0.5, 1.0, 0.667)
        )
}

/** Which of the three things a Lenia run can do. */
enum class LeniaPhase {
    /** The field decayed to (almost) nothing. */
    DEATH,

    /** The field settled into a bounded, non-trivial pattern — the interesting case. */
    BOUNDED,

    /** The field saturated: (almost) every cell pinned at 1. */
    SATURATION
}

/**
 * The outcome of a Lenia run, as measured rather than as looked at.
 *
 * @property phase which of [LeniaPhase] the run ended in
 * @property finalMass mean field value at the end (0 = empty, 1 = saturated)
 * @property massHistory mean field value at each recorded step, so the *shape* of the approach is
 *   inspectable and not only its endpoint
 * @property blobCount connected features at the end
 * @property centroidDrift distance the field's centre of mass moved over the last half of the run, in
 *   cells; a stationary pattern is ~0 and a travelling soliton is proportional to the run length
 * @property field the final field
 */
class LeniaRun(
    val phase: LeniaPhase,
    val finalMass: Double,
    val massHistory: DoubleArray,
    val blobCount: Int,
    val centroidDrift: Double,
    val field: Img
) : afield.ToHTML {
    override fun toString(): String =
        "LeniaRun($phase, mass %.4f, %d blob(s), drift %.2f cells)".format(finalMass, blobCount, centroidDrift)

    /**
     * Inline view: the final field on a **fixed** `0..1` scale, the measured phase, and the mass history
     * as a sparkline. Body in `alife_field.kt` ([alifeLeniaRunHtml]). A saturated field is constant at 1
     * and an auto-ranged colour map draws it black, i.e. identical to death — so this view is the one
     * place in the family where the range policy changes the result rather than tidying it.
     */
    override fun toHTML(): String = alifeLeniaRunHtml(this)
}

/**
 * Run a Lenia rule from [initial] for [steps] and **classify** the outcome.
 *
 * This is the defensible form of a Lenia claim. Rather than asserting that a particular parameter set
 * produces a named creature, it reports which of [LeniaPhase]'s three outcomes happened and the
 * numbers behind the verdict. A `mu` sweep then produces a measured phase boundary, and the death and
 * saturation cells *are* the control arms for the bounded ones: the same code, the same seed, one
 * parameter moved, a qualitatively different measured outcome.
 *
 * @param deathMass mean field value below which the run counts as [LeniaPhase.DEATH]
 * @param saturationMass mean field value above which it counts as [LeniaPhase.SATURATION]
 * @param sampleEvery record the mean field value every this many steps
 */
fun leniaRun(
    initial: Img,
    rule: LeniaRule,
    steps: Int = 200,
    deathMass: Double = 0.005,
    saturationMass: Double = 0.80,
    sampleEvery: Int = 10
): LeniaRun {
    require(initial.channels == 1) { "leniaRun expects a 1-channel field" }
    require(steps >= 1) { "steps must be >= 1, got $steps" }
    require(sampleEvery >= 1) { "sampleEvery must be >= 1, got $sampleEvery" }
    var f = initial
    val history = ArrayList<Double>()
    val centroids = ArrayList<Pair<Double, Double>>()
    history.add(f.mean().toDouble())
    centroids.add(fieldCentroid(f))
    for (i in 1..steps) {
        f = f.leniaStep(rule)
        if (i % sampleEvery == 0 || i == steps) {
            history.add(f.mean().toDouble())
            centroids.add(fieldCentroid(f))
        }
    }
    val mass = f.mean().toDouble()
    val phase = when {
        mass < deathMass -> LeniaPhase.DEATH
        mass > saturationMass -> LeniaPhase.SATURATION
        else -> LeniaPhase.BOUNDED
    }
    val half = centroids.size / 2
    val a = centroids[half]
    val b = centroids.last()
    val drift = if (mass < deathMass) 0.0 else hypot(
        wrappedDelta(a.first, b.first, f.width.toDouble()),
        wrappedDelta(a.second, b.second, f.height.toDouble())
    )
    val blobs = if (mass < deathMass) 0 else f.threshold(0.15f).blobShapes(minArea = 8).size
    return LeniaRun(phase, mass, history.toDoubleArray(), blobs, drift, f)
}

/**
 * Mass-weighted centroid of a field, as `(x, y)` in cells. `(0, 0)` for an empty field.
 *
 * Not toroidally corrected: a single localised blob gives the right answer, and a pattern wrapped
 * across the seam gives a meaningless one. That is why [leniaRun] measures drift as a *wrapped* delta
 * between two centroids rather than trusting either absolutely, and why the gallery seeds a blob in
 * the middle.
 */
fun fieldCentroid(f: Img): Pair<Double, Double> {
    require(f.channels == 1) { "fieldCentroid expects a 1-channel field" }
    var sx = 0.0; var sy = 0.0; var m = 0.0
    for (y in 0 until f.height) for (x in 0 until f.width) {
        val v = f.data[y * f.width + x].toDouble()
        if (v <= 0.0) continue
        sx += x * v; sy += y * v; m += v
    }
    return if (m <= 0.0) 0.0 to 0.0 else (sx / m) to (sy / m)
}

/**
 * A Lenia seed: a smooth radial blob of noise of radius `rule.radius * blobRadii`, centred, on an
 * otherwise empty field.
 *
 * Sized in units of the *kernel* radius rather than in cells — a seed much smaller than the
 * kernel cannot excite it and dies whatever the parameters, so a `mu` sweep seeded in cells is really
 * a sweep of "was the blob big enough", which is not the question.
 */
fun leniaSeed(width: Int, height: Int, rule: LeniaRule, blobRadii: Double = 1.4, seed: Long = 1L): Img {
    require(blobRadii > 0.0) { "blobRadii must be > 0, got $blobRadii" }
    val rng = kotlin.random.Random(seed)
    val cx = (width - 1) / 2.0
    val cy = (height - 1) / 2.0
    val r = rule.radius * blobRadii
    return Img.generate(width, height) { x, y ->
        val d = hypot(x - cx, y - cy)
        if (d > r) 0f else {
            // A cosine taper to zero at the rim, times noise: a hard-edged seed rings.
            val taper = (0.5 * (1.0 + kotlin.math.cos(PI * d / r))).toFloat()
            (taper * (0.4f + 0.6f * rng.nextFloat())).coerceIn(0f, 1f)
        }
    }
}

/**
 * Sweep `mu` and report the [LeniaPhase] and mass at each value — the measured phase diagram of a
 * Lenia rule along its most consequential axis.
 *
 * @param mus the `mu` values to try
 * @param makeRule builds the rule for a given `mu` (e.g. [LeniaPresets.ring])
 * @return one [LeniaRun] per value of [mus], in order
 */
fun leniaMuSweep(
    width: Int,
    height: Int,
    mus: DoubleArray,
    steps: Int = 200,
    seed: Long = 1L,
    makeRule: (Double) -> LeniaRule = { LeniaPresets.ring(it) }
): List<LeniaRun> {
    require(mus.isNotEmpty()) { "leniaMuSweep needs at least one mu" }
    return mus.map { mu ->
        val rule = makeRule(mu)
        leniaRun(leniaSeed(width, height, rule, seed = seed), rule, steps)
    }
}

/**
 * **Largest** absolute per-cell difference between two same-shape fields: an L-infinity norm, and
 * not a sum. This is the residual a claim of "these two implementations agree exactly" asserts to
 * be zero, and a per-cell tolerance compares against it directly. A total would be larger by up to
 * the pixel count, so read the name carefully before choosing a tolerance.
 */
fun fieldMaxAbsDiff(a: Img, b: Img): Double {
    require(a.sameShapeAs(b)) { "fieldMaxAbsDiff needs matching shapes" }
    var m = 0.0
    for (i in a.data.indices) {
        val d = kotlin.math.abs(a.data[i] - b.data[i]).toDouble()
        if (d > m) m = d
    }
    return m
}
