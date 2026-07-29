@file:afield.CompletionCategory("ALife")

package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spatial artificial life for the Field stdlib — the *continuous / grid / agent* half of ALife, the
 * counterpart to the graph-rewriting half already here (`growing.kt`, `dynamic_graph_systems.kt`,
 * `interactionnets.kt`, `booleannetworks.kt`) and to the constraint-based generator in `wfc.kt`.
 *
 * Five files:
 *
 * - **`alife.kt`** (this one) — the substrate: [IntGrid], the grid↔[Img] bridges, and the
 *   **measurement** layer. Everything else in the family makes its claims through the functions at
 *   the bottom of this file.
 * - **`alife_ca.kt`** — discrete cellular automata: [LifeRule] parsed from `B3/S23`, outer-totalistic
 *   and cyclic multi-state CA, Wolfram's elementary 1-D rules rendered as a space-time [Img], and a
 *   3-D life-like CA that marches out to a [TriMesh].
 * - **`alife_continuous.kt`** — Gray–Scott reaction–diffusion, Turing patterns, and Lenia
 *   (continuous CA: ring-kernel convolution plus a growth mapping).
 * - **`alife_agents.kt`** — boids flocking, a Physarum/slime-mould deposit–sense–move population over
 *   a trail [Img], and diffusion-limited aggregation.
 * - **`alife_lsystem.kt`** — a bracketed/parametric L-system with a turtle that emits
 *   [afield.graphics.FLine]s, and space-colonization branching.
 *
 * Design choices, all deliberate:
 *
 * - **Built on [Img], not on a private raster.** Every continuous system is an `Img -> Img` step, so a
 *   reaction–diffusion field pipelines straight into `gaussianBlur`, `colormap`, `connectedComponents`,
 *   `isoContourFLines` and `asHeightSdf` with no glue. Nothing here reimplements convolution.
 * - **A step is a value, not a side effect.** `step()` returns the next state; you keep the frames you
 *   want. The one exception is the deliberately-mutable agent populations, where copying 50 000
 *   agents per frame would be the whole cost.
 * - **Deterministic from a seed.** Every stochastic system takes a `seed: Long` and uses its own
 *   [kotlin.random.Random], so a figure in the docs is reproducible and a regression is a diff.
 * - **No external dependency.** Pure Kotlin over `FloatArray`/`IntArray`.
 * - **Measured, not asserted.** The functions under "Measurement" exist because "it produced pixels"
 *   is not evidence. A flock is a population whose [polarization] is high *and* whose
 *   [nearestNeighborDistances] have a floor; a DLA cluster is one whose [massRadiusDimension] is
 *   ~1.7; a Gray–Scott spot regime is one whose [blobShapes] are round. Each of those is a number a
 *   control arm gets wrong.
 *
 * Out of scope on purpose: GPU implementations (that is `afield.four` / StackSL / RectSL — these are
 * CPU reference implementations you can read), and any system needing a trained model.
 */

// ==============================================================================================
// IntGrid — the discrete substrate
// ==============================================================================================

/**
 * A rectangular lattice of integer cell states — the substrate every discrete automaton in the family
 * steps. Row-major, `cells[y * width + x]`, origin top-left (matching [Img]).
 *
 * Kept separate from `Img` on purpose: a CA cell is a *state label*, not a sample. Storing Conway's
 * Life in floats invites `0.999f == 1` questions that have no business existing, and a `2f` state in a
 * cyclic CA with 12 states would quietly lose precision at the top of the range. Convert at the
 * boundary with [toImg] / [Img.toIntGrid].
 *
 * @property width cells across, >= 1
 * @property height cells down, >= 1
 * @property cells state per cell, `cells[y * width + x]`
 * @property wrap `true` for a torus (out-of-range reads wrap), `false` for a bounded board whose
 *   outside counts as state 0. The distinction is not cosmetic: a glider crossing a torus seam
 *   reappears, and the R-pentomino's known 1103-generation history needs room, not a wrap.
 */
class IntGrid(
    val width: Int,
    val height: Int,
    val cells: IntArray,
    val wrap: Boolean = true
) : afield.ToHTML {
    init {
        require(width >= 1 && height >= 1) { "IntGrid must be at least 1x1, got ${width}x$height" }
        require(cells.size == width * height) {
            "IntGrid cells must be width*height = ${width * height}, got ${cells.size}"
        }
    }

    /** Number of cells. */
    val size: Int get() = width * height

    /** State at `(x, y)`; throws if out of bounds. See [get] for the border-resolving read. */
    fun at(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "($x, $y) outside ${width}x$height" }
        return cells[y * width + x]
    }

    /**
     * State at `(x, y)`, resolving out-of-range coordinates through [wrap]: wrapped on a torus,
     * otherwise `0`. This is the read every neighbourhood count goes through.
     */
    operator fun get(x: Int, y: Int): Int {
        if (x in 0 until width && y in 0 until height) return cells[y * width + x]
        if (!wrap) return 0
        return cells[Math.floorMod(y, height) * width + Math.floorMod(x, width)]
    }

    /** Write [v] at `(x, y)`, mutating in place; out-of-range writes are ignored on a bounded grid. */
    operator fun set(x: Int, y: Int, v: Int) {
        if (x in 0 until width && y in 0 until height) {
            cells[y * width + x] = v
        } else if (wrap) {
            cells[Math.floorMod(y, height) * width + Math.floorMod(x, width)] = v
        }
    }

    /** An independent copy. */
    fun copy(): IntGrid = IntGrid(width, height, cells.copyOf(), wrap)

    /** A same-shape grid of zeros. */
    fun blank(): IntGrid = IntGrid(width, height, IntArray(size), wrap)

    /** How many cells hold [state]. */
    fun count(state: Int): Int = cells.count { it == state }

    /** How many cells are non-zero — "population", for a two-state automaton. */
    fun population(): Int = cells.count { it != 0 }

    /** The largest state present, so a palette can be sized without being told. */
    fun maxState(): Int = cells.max()

    /** `true` when [other] has the same shape and **exactly** the same states. */
    fun sameCellsAs(other: IntGrid): Boolean =
        width == other.width && height == other.height && cells.contentEquals(other.cells)

    /**
     * How many of the eight surrounding cells hold [state] (the Moore neighbourhood, excluding the
     * centre) — the count Conway's Life and every other life-like rule is a function of.
     */
    fun mooreCount(x: Int, y: Int, state: Int = 1): Int {
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            if (this[x + dx, y + dy] == state) n++
        }
        return n
    }

    /** How many of the four edge-adjacent cells hold [state] (the von Neumann neighbourhood). */
    fun vonNeumannCount(x: Int, y: Int, state: Int = 1): Int {
        var n = 0
        if (this[x + 1, y] == state) n++
        if (this[x - 1, y] == state) n++
        if (this[x, y + 1] == state) n++
        if (this[x, y - 1] == state) n++
        return n
    }

    /**
     * How many cells within Chebyshev distance [radius] (excluding the centre) hold [state] — the
     * generalised Moore neighbourhood that "Larger than Life" rules and [cyclicStep] read.
     */
    fun neighborhoodCount(x: Int, y: Int, state: Int, radius: Int, vonNeumann: Boolean = false): Int {
        require(radius >= 1) { "radius must be >= 1, got $radius" }
        var n = 0
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx == 0 && dy == 0) continue
            if (vonNeumann && abs(dx) + abs(dy) > radius) continue
            if (this[x + dx, y + dy] == state) n++
        }
        return n
    }

    /**
     * Copy this grid shifted by `(dx, dy)`. On a torus nothing is lost; on a bounded grid whatever
     * leaves is dropped. Used to state "the pattern moved by exactly (1, 1)" as an equality rather
     * than as a claim about centroids — see [translationBetween].
     */
    fun translated(dx: Int, dy: Int): IntGrid {
        val out = blank()
        for (y in 0 until height) for (x in 0 until width) {
            val v = cells[y * width + x]
            if (v == 0) continue
            val nx = if (wrap) Math.floorMod(x + dx, width) else x + dx
            val ny = if (wrap) Math.floorMod(y + dy, height) else y + dy
            if (nx in 0 until width && ny in 0 until height) out.cells[ny * width + nx] = v
        }
        return out
    }

    /** Coordinates of every non-zero cell, row-major. */
    fun livePoints(): List<Vec2> {
        val out = ArrayList<Vec2>()
        for (y in 0 until height) for (x in 0 until width) {
            if (cells[y * width + x] != 0) out.add(Vec2(x.toFloat(), y.toFloat()))
        }
        return out
    }

    /**
     * Inclusive bounding box of the non-zero cells as `(minX, minY, maxX, maxY)`, or `null` when the
     * grid is empty. Bounding-box *displacement* is how a translating pattern is measured without
     * centroid rounding coming into it.
     */
    fun liveBounds(): IntArray? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (y in 0 until height) for (x in 0 until width) {
            if (cells[y * width + x] == 0) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return if (minX == Int.MAX_VALUE) null else intArrayOf(minX, minY, maxX, maxY)
    }

    override fun toString(): String =
        "IntGrid(${width}x$height, ${if (wrap) "torus" else "bounded"}, population ${population()})"

    /**
     * Inline view for the value visualizer: the lattice at one pixel per cell, plus population, border
     * mode and state entropy. The body lives in `alife_field.kt` ([alifeGridHtml]) so that this file
     * keeps its "integer lattices and nothing else" footprint.
     */
    override fun toHTML(): String = alifeGridHtml(this)

    companion object {
        /** All-zero grid. */
        fun zeros(width: Int, height: Int, wrap: Boolean = true): IntGrid =
            IntGrid(width, height, IntArray(width * height), wrap)

        /** Build from a per-cell function of `(x, y)`. */
        inline fun generate(width: Int, height: Int, wrap: Boolean = true, f: (x: Int, y: Int) -> Int): IntGrid {
            val out = IntArray(width * height)
            var i = 0
            for (y in 0 until height) for (x in 0 until width) out[i++] = f(x, y)
            return IntGrid(width, height, out, wrap)
        }

        /**
         * Uniform random states in `0 until states`, from [seed].
         *
         * @param density for a two-state grid, the probability a cell starts alive; ignored when
         *   [states] > 2 (then every state is equally likely).
         */
        fun random(
            width: Int,
            height: Int,
            states: Int = 2,
            density: Double = 0.5,
            seed: Long = 1L,
            wrap: Boolean = true
        ): IntGrid {
            require(states >= 2) { "states must be >= 2, got $states" }
            val rng = kotlin.random.Random(seed)
            val out = IntArray(width * height) {
                if (states == 2) (if (rng.nextDouble() < density) 1 else 0) else rng.nextInt(states)
            }
            return IntGrid(width, height, out, wrap)
        }

        /**
         * Parse an ASCII block into a two-state grid: any character in [liveChars] is state 1.
         *
         * The readable way to write down a named pattern, and the reason the Life evidence in
         * `AlifeGallery` can quote a glider as five characters rather than five indices:
         * ```
         * val glider = IntGrid.fromAscii(".O.", "..O", "OOO")
         * ```
         * Rows are padded to the longest.
         */
        fun fromAscii(vararg rows: String, liveChars: String = "O*#1X", wrap: Boolean = true): IntGrid {
            require(rows.isNotEmpty()) { "fromAscii needs at least one row" }
            val w = rows.maxOf { it.length }
            val out = IntArray(w * rows.size)
            for (y in rows.indices) for (x in rows[y].indices) {
                if (rows[y][x] in liveChars) out[y * w + x] = 1
            }
            return IntGrid(w, rows.size, out, wrap)
        }
    }
}

// ==============================================================================================
// Grid <-> Img bridges
// ==============================================================================================

/**
 * Render this grid as a 1-channel [Img] with state `s` mapped to `s / max(1, states - 1)`, so a
 * two-state grid becomes a clean 0/1 mask and a 12-state cyclic CA becomes a ramp ready for
 * [Img.colormap].
 *
 * @param states how many states to normalise against; `null` uses `maxState() + 1`
 */
fun IntGrid.toImg(states: Int? = null): Img {
    val n = states ?: (maxState() + 1)
    val d = max(1, n - 1).toFloat()
    val out = FloatArray(size)
    for (i in cells.indices) out[i] = cells[i] / d
    return Img(width, height, 1, out)
}

/**
 * Render this grid as RGB through an explicit palette, one `Triple(r, g, b)` per state — the honest
 * way to show a multi-state automaton, where a grayscale ramp implies an ordering between states that
 * may not exist. States beyond the palette wrap around it.
 */
fun IntGrid.toImg(palette: List<Triple<Float, Float, Float>>): Img {
    require(palette.isNotEmpty()) { "palette needs at least one colour" }
    val out = FloatArray(size * 3)
    for (i in cells.indices) {
        val c = palette[Math.floorMod(cells[i], palette.size)]
        out[i * 3] = c.first; out[i * 3 + 1] = c.second; out[i * 3 + 2] = c.third
    }
    return Img(width, height, 3, out)
}

/**
 * Threshold a 1-channel [Img] into a two-state [IntGrid] — the way a reaction–diffusion field, a
 * Physarum trail or an imported picture becomes CA seed material.
 */
fun Img.toIntGrid(threshold: Float = 0.5f, wrap: Boolean = true): IntGrid {
    val src = if (channels == 1) this else luma()
    return IntGrid(width, height, IntArray(pixelCount) { if (src.data[it] > threshold) 1 else 0 }, wrap)
}

/**
 * A dark-background categorical palette sized for multi-state automata: state 0 is near-black
 * background, and the rest sweep the hue circle at fixed saturation/value so no two adjacent states
 * look alike. Built from [hsvToRgb]'s maths directly so it needs no `Img` round trip.
 *
 * @param states total state count including 0
 */
fun cyclicPalette(states: Int): List<Triple<Float, Float, Float>> {
    require(states >= 1) { "states must be >= 1, got $states" }
    val out = ArrayList<Triple<Float, Float, Float>>(states)
    out.add(Triple(0.05f, 0.06f, 0.08f))
    for (s in 1 until states) {
        val h = (s - 1).toFloat() / max(1, states - 1) * 6f
        val sector = h.toInt()
        val f = h - sector
        val v = 0.95f
        val sat = 0.78f
        val p = v * (1f - sat)
        val q = v * (1f - sat * f)
        val t = v * (1f - sat * (1f - f))
        out.add(
            when (sector % 6) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
        )
    }
    return out
}

// ==============================================================================================
// Measurement — every claim this family makes goes through here
// ==============================================================================================

/**
 * The rigid translation from [a] to [b] as `(dx, dy)`, or `null` if [b] is not a translate of [a].
 *
 * This is the *strong* form of "the pattern moved": not "its centroid drifted by about one cell" but
 * "every live cell moved by the same integer offset and nothing else changed". It is what makes
 * "a glider translates by exactly (1, 1) in four generations" an assertion a wrong rule fails rather
 * than a statistic a wrong rule can fluke.
 *
 * Implemented by aligning bounding boxes and then requiring exact cell-by-cell equality of the
 * shifted grid, so a pattern that both moved *and* changed shape returns `null`.
 */
fun translationBetween(a: IntGrid, b: IntGrid): Pair<Int, Int>? {
    require(a.width == b.width && a.height == b.height) { "translationBetween needs matching shapes" }
    val ba = a.liveBounds() ?: return null
    val bb = b.liveBounds() ?: return null
    val dx = bb[0] - ba[0]
    val dy = bb[1] - ba[1]
    return if (a.translated(dx, dy).sameCellsAs(b)) dx to dy else null
}

/**
 * Area-weighted centroid of the non-zero cells, or `null` when the grid is empty. Fine for reporting;
 * prefer [translationBetween] when the claim is that a pattern moved rigidly.
 */
fun IntGrid.liveCentroid(): Vec2? {
    var sx = 0.0; var sy = 0.0; var n = 0
    for (y in 0 until height) for (x in 0 until width) {
        if (cells[y * width + x] == 0) continue
        sx += x; sy += y; n++
    }
    return if (n == 0) null else Vec2((sx / n).toFloat(), (sy / n).toFloat())
}

/**
 * The **polarization** (a.k.a. alignment) order parameter of a set of headings:
 * `|Σ v̂| / N`, the length of the mean unit velocity.
 *
 * 1 means every agent points the same way; a large population of independent uniform headings gives
 * `≈ sqrt(π/4N)`, i.e. it goes to 0 like `1/sqrt(N)`. This single number is what separates a flock
 * from a swarm of independent walkers, and it is the statistic the alignment-off control arm fails.
 * Zero-length velocities are skipped (a stationary agent has no heading to align).
 */
fun polarization(vx: FloatArray, vy: FloatArray): Double {
    require(vx.size == vy.size) { "polarization needs matching vx/vy, got ${vx.size} and ${vy.size}" }
    var sx = 0.0; var sy = 0.0; var n = 0
    for (i in vx.indices) {
        val m = hypot(vx[i].toDouble(), vy[i].toDouble())
        if (m < 1e-12) continue
        sx += vx[i] / m; sy += vy[i] / m; n++
    }
    if (n == 0) return 0.0
    return hypot(sx, sy) / n
}

/**
 * The expected [polarization] of `n` **independent** uniformly-random headings — the null hypothesis
 * to compare a measured value against.
 *
 * A mean of `n` random unit vectors has expected length `sqrt(π) / (2 sqrt(n))` (the mean of a
 * Rayleigh-distributed magnitude), so this is the level a *non*-flock sits at. Quoting a measured
 * polarization without it is quoting a number with no scale.
 */
fun randomPolarizationBaseline(n: Int): Double {
    require(n >= 1) { "n must be >= 1, got $n" }
    return sqrt(PI) / (2.0 * sqrt(n.toDouble()))
}

/**
 * Distance from every point to its nearest other point, in index order — the second half of "this is
 * a flock": a flock has a *floor* under this distribution, because separation keeps agents apart,
 * while a cohesion-only population collapses to a point and a random cloud has no floor at all.
 *
 * @param wrapWidth/[wrapHeight] pass the domain size for a toroidal metric; `null` for Euclidean
 */
fun nearestNeighborDistances(
    xs: FloatArray,
    ys: FloatArray,
    wrapWidth: Double? = null,
    wrapHeight: Double? = null
): DoubleArray {
    require(xs.size == ys.size) { "nearestNeighborDistances needs matching xs/ys" }
    val n = xs.size
    val out = DoubleArray(n) { Double.MAX_VALUE }
    for (i in 0 until n) for (j in i + 1 until n) {
        var dx = (xs[j] - xs[i]).toDouble()
        var dy = (ys[j] - ys[i]).toDouble()
        wrapWidth?.let { if (abs(dx) > it / 2) dx -= Math.signum(dx) * it }
        wrapHeight?.let { if (abs(dy) > it / 2) dy -= Math.signum(dy) * it }
        val d = hypot(dx, dy)
        if (d < out[i]) out[i] = d
        if (d < out[j]) out[j] = d
    }
    for (i in 0 until n) if (out[i] == Double.MAX_VALUE) out[i] = 0.0
    return out
}

/** The [p]-quantile of [values] (0 = min, 0.5 = median, 1 = max), by nearest-rank on a sorted copy. */
fun quantileOf(values: DoubleArray, p: Double): Double {
    require(values.isNotEmpty()) { "quantileOf needs at least one value" }
    require(p in 0.0..1.0) { "p must be in 0..1, got $p" }
    val s = values.sortedArray()
    return s[((s.size - 1) * p).roundToInt().coerceIn(0, s.size - 1)]
}

/**
 * Shape statistics of one connected blob: pixel [area], the **crack perimeter** (the number of unit
 * cell edges separating the blob from anything else, including the image border), and the
 * dimensionless [isoperimetricRatio].
 *
 * @property label the blob's label in the [ComponentLabelling] it came from
 * @property area foreground pixel count
 * @property crackPerimeter unit edges on the blob's boundary
 * @property isoperimetricRatio `P² / (4πA)` with the crack perimeter corrected by `π/4`; see
 *   [blobShapes] for why that factor is there and what the resulting scale means
 */
data class BlobShape(
    val label: Int,
    val area: Int,
    val crackPerimeter: Int,
    val isoperimetricRatio: Double
)

/**
 * Per-blob [BlobShape]s of a binary mask — the statistic that tells **spots from stripes**.
 *
 * `P² / (4πA)` is 1 for a disc and grows without bound as a region gets thinner and more convoluted:
 * a labyrinth of 3-pixel-wide worms scores in the tens. That makes it the right discriminator for a
 * Gray–Scott regime, where "spots" and "stripes" can have identical coverage, identical mean and
 * identical variance and differ only in shape.
 *
 * **The `π/4` correction, and why it is not a fudge.** A digital blob's crack perimeter over-estimates
 * the length of the smooth curve it approximates by a factor that tends to `4/π ≈ 1.273` for a
 * direction-averaged boundary (a digital disc of radius `r` has crack perimeter `≈ 8r`, not `2πr`).
 * Without the correction a *disc* scores 1.62 and the number stops being readable. With it, a disc
 * scores ≈ 1. `AlifeGallery` calibrates this on a synthetic disc and prints the measured value rather
 * than trusting this paragraph.
 *
 * The correction is exact only for a boundary that is smooth at the pixel scale; a one-pixel-wide
 * filament is *under*-corrected, which biases the statistic **toward** the null hypothesis (it makes
 * stripes look rounder than they are), so a passing "this is a labyrinth" claim is conservative.
 *
 * @param minArea ignore blobs smaller than this (single-pixel noise has perimeter 4 and area 1, which
 *   is a ratio of ~1 and would dilute a spot count with nonsense)
 */
fun Img.blobShapes(minArea: Int = 4, connectivity: Int = 8): List<BlobShape> {
    require(channels == 1) { "blobShapes expects a 1-channel binary mask" }
    val labelling = connectedComponents(connectivity)
    val labels = labelling.labels
    val perim = IntArray(labelling.count + 1)
    for (y in 0 until height) for (x in 0 until width) {
        val l = labels.at(x, y).roundToInt()
        if (l == 0) continue
        // Border-crossing counts as boundary: a blob cut off by the edge of the image really does
        // have an unknown continuation, and pretending otherwise would make edge blobs look rounder.
        if (x == 0 || labels.at(x - 1, y).roundToInt() != l) perim[l]++
        if (x == width - 1 || labels.at(x + 1, y).roundToInt() != l) perim[l]++
        if (y == 0 || labels.at(x, y - 1).roundToInt() != l) perim[l]++
        if (y == height - 1 || labels.at(x, y + 1).roundToInt() != l) perim[l]++
    }
    val k = PI / 4.0
    return labelling.components.filter { it.area >= minArea }.map { c ->
        val p = perim[c.label] * k
        BlobShape(c.label, c.area, perim[c.label], p * p / (4.0 * PI * c.area))
    }
}

/**
 * Direction-averaged spatial autocorrelation of a 1-channel image at integer lags `0..maxLag`,
 * normalised so index 0 is 1.
 *
 * Averaging over [directions] evenly spaced angles (wrapped border) makes this an *isotropic*
 * measurement, which is the point: an anisotropic estimator run along the x axis reports a stripe
 * pattern's wavelength only if the stripes happen to be vertical.
 *
 * **Why [bilinear] defaults to `false`, which was found by a control arm rather than by thinking.**
 * Bilinear sampling at a non-integer offset averages four neighbouring pixels, *one of which can be
 * the centre pixel itself*. That manufactures correlation out of nothing: with bilinear sampling this
 * function reports a correlation length near 2 for **shuffled white noise**, which has none. Nearest
 * sampling rounds each direction's offset to a lattice vector, so an uncorrelated field reads 0 at
 * every non-zero lag, as it must. The cost is that at small lags several directions collapse onto the
 * same offset — a real loss of angular resolution, and the right trade, because an estimator that
 * invents signal is worse than one that is coarse.
 */
fun Img.radialAutocorrelation(
    maxLag: Int = 32,
    directions: Int = 24,
    stride: Int = 2,
    bilinear: Boolean = false
): DoubleArray {
    require(channels == 1) { "radialAutocorrelation expects a 1-channel image" }
    require(maxLag >= 1) { "maxLag must be >= 1, got $maxLag" }
    require(directions >= 4) { "directions must be >= 4, got $directions" }
    require(stride >= 1) { "stride must be >= 1, got $stride" }
    val mean = mean().toDouble()
    var variance = 0.0
    var n = 0
    for (y in 0 until height step stride) for (x in 0 until width step stride) {
        val d = at(x, y) - mean; variance += d * d; n++
    }
    variance /= n
    val out = DoubleArray(maxLag + 1)
    out[0] = 1.0
    if (variance < 1e-18) return out
    for (lag in 1..maxLag) {
        var acc = 0.0
        var count = 0
        for (k in 0 until directions) {
            val a = 2.0 * PI * k / directions
            val ox = lag * cos(a)
            val oy = lag * sin(a)
            val ix = Math.round(ox).toInt()
            val iy = Math.round(oy).toInt()
            for (y in 0 until height step stride) for (x in 0 until width step stride) {
                val v0 = at(x, y) - mean
                val v1 = if (bilinear) sampleBilinear(x + ox, y + oy, 0, Border.WRAP)
                else get(x + ix, y + iy, 0, Border.WRAP)
                acc += v0 * (v1 - mean)
                count++
            }
        }
        out[lag] = (acc / count) / variance
    }
    return out
}

/**
 * The **correlation length** of a pattern: the lag at which [radialAutocorrelation] first falls to
 * [level], linearly interpolated, or [maxLag] if it never does.
 *
 * For a periodic pattern of wavelength `λ` this is a fixed fraction of `λ`, so it is a direct readout
 * of the length scale a Turing instability selects — the quantity linear stability theory says grows
 * with `sqrt(diffusion)`. For uncorrelated noise it is `< 1`.
 *
 * **Why [level] is `1/e` and not zero, which a control arm decided.** The obvious definition is the
 * first *zero* crossing. It is unusable: an uncorrelated field's autocorrelation does not sit at zero,
 * it fluctuates around zero at the estimator's noise floor (measured here: `0.004, 0.006, 0.006,
 * 0.001, 0.004, 0.002, 0.005, 0.003, -0.000, …`), so the first crossing is wherever the noise happens
 * to change sign — for shuffled white noise this function returned **8.97** under the zero-crossing
 * definition. An e-folding threshold is far above the noise floor, so a field with no structure reads
 * below 1 as it must, and a structured field's reading is unaffected by the change in any way that
 * matters (the two definitions differ by a constant factor for a smooth pattern).
 *
 * @param level the autocorrelation value whose first crossing defines the length; `1/e` is the
 *   standard e-folding convention
 */
fun Img.correlationLength(
    maxLag: Int = 32,
    directions: Int = 24,
    stride: Int = 2,
    bilinear: Boolean = false,
    level: Double = 1.0 / Math.E
): Double {
    require(level > 0.0 && level < 1.0) { "level must be in (0, 1), got $level" }
    val ac = radialAutocorrelation(maxLag, directions, stride, bilinear)
    for (lag in 1..maxLag) {
        if (ac[lag] <= level) {
            val prev = ac[lag - 1]
            val frac = if (prev - ac[lag] < 1e-18) 0.0 else (prev - level) / (prev - ac[lag])
            return (lag - 1) + frac
        }
    }
    return maxLag.toDouble()
}

/**
 * The share of an image's total mass carried by its brightest [fraction] of pixels — a concentration
 * measure (the Lorenz-curve reading behind a Gini coefficient).
 *
 * `topShare(0.05)` is `0.05` for a flat field and approaches 1 for a field where everything is in a
 * few pixels. That makes it the statistic that separates a Physarum *network* (deposit concentrated
 * into filaments) from the same agents doing an unguided random walk (deposit spread evenly), which
 * is precisely the sensing-off control arm.
 */
fun Img.topShare(fraction: Double = 0.05): Double {
    require(channels == 1) { "topShare expects a 1-channel image" }
    require(fraction > 0.0 && fraction <= 1.0) { "fraction must be in (0, 1], got $fraction" }
    val total = data.fold(0.0) { a, v -> a + max(0f, v) }
    if (total <= 0.0) return 0.0
    val sorted = data.copyOf()
    sorted.sort()
    val take = max(1, (sorted.size * fraction).roundToInt())
    var acc = 0.0
    for (i in sorted.size - take until sorted.size) acc += max(0f, sorted[i])
    return acc / total
}

/**
 * Result of a mass–radius fractal-dimension fit.
 *
 * @property dimension the fitted slope of `log N(r)` against `log r` — the mass dimension
 * @property points the `(log r, log N)` pairs that were fitted, so a caller can plot the fit and see
 *   whether it really is a line rather than trusting a single number
 * @property rSquared coefficient of determination of the fit; a genuine scaling regime is `> 0.99`,
 *   and a low value means "this object has no fractal dimension over this range", not "the dimension
 *   is uncertain"
 */
data class MassRadiusFit(
    val dimension: Double,
    val points: List<Pair<Double, Double>>,
    val rSquared: Double
)

/**
 * Fractal dimension by **mass–radius scaling**: fit `N(r) ∝ r^D` for the number of occupied points
 * within radius `r` of [center], over `rMin..rMax`, by least squares in log–log space.
 *
 * This is the measurement that makes "this is a DLA cluster" checkable. Diffusion-limited aggregation
 * in the plane has `D ≈ 1.71`; a filled disc and a uniform random sprinkle both give `D = 2`, and a
 * straight line gives 1. So the two obvious "it produced pixels" impostors are exactly the ones this
 * number rejects.
 *
 * [rSquared] is reported alongside because a slope through a curve is not a dimension — see
 * [MassRadiusFit.rSquared].
 *
 * @param samples how many logarithmically-spaced radii to evaluate
 * @param minCount when > 0, raise [rMin] until it encloses at least this many points. A fit that
 *   starts where `N(r)` is 3 is dominated by counting noise: the second run of this family measured a
 *   *uniform disc* at dimension 2.32 for exactly that reason, which is not a possible answer. Raising
 *   the inner radius until the statistics exist is the fix, and stating it as a parameter is how the
 *   fit stays honest about the range it is describing.
 */
fun massRadiusDimension(
    points: List<Vec2>,
    center: Vec2,
    rMin: Double = 4.0,
    rMax: Double = 64.0,
    samples: Int = 16,
    minCount: Int = 0
): MassRadiusFit {
    require(points.isNotEmpty()) { "massRadiusDimension needs at least one point" }
    require(rMin > 0.0 && rMax > rMin) { "need 0 < rMin < rMax, got $rMin..$rMax" }
    require(samples >= 3) { "samples must be >= 3, got $samples" }
    val d2 = points.map {
        val dx = (it.x - center.x).toDouble()
        val dy = (it.y - center.y).toDouble()
        dx * dx + dy * dy
    }.sorted()
    var lo = rMin
    if (minCount > 0 && d2.size >= minCount) {
        val needed = sqrt(d2[minCount - 1])
        if (needed > lo) lo = needed
    }
    require(rMax > lo) {
        "no fitting range left: rMin rose to $lo to enclose $minCount points, which is beyond rMax $rMax"
    }
    val fit = ArrayList<Pair<Double, Double>>(samples)
    for (i in 0 until samples) {
        val r = lo * Math.pow(rMax / lo, i.toDouble() / (samples - 1))
        val rr = r * r
        // Sorted squared distances: the count within r is the insertion point.
        var lo = 0; var hi = d2.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (d2[mid] <= rr) lo = mid + 1 else hi = mid }
        if (lo >= 1) fit.add(ln(r) to ln(lo.toDouble()))
    }
    require(fit.size >= 3) { "not enough occupied radii to fit — is the cluster inside $rMin..$rMax of $center?" }
    var sx = 0.0; var sy = 0.0
    for ((x, y) in fit) { sx += x; sy += y }
    val mx = sx / fit.size
    val my = sy / fit.size
    var sxy = 0.0; var sxx = 0.0; var syy = 0.0
    for ((x, y) in fit) { sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx); syy += (y - my) * (y - my) }
    val slope = if (sxx < 1e-18) 0.0 else sxy / sxx
    val r2 = if (sxx < 1e-18 || syy < 1e-18) 0.0 else (sxy * sxy) / (sxx * syy)
    return MassRadiusFit(slope, fit, r2)
}

/** Total of every sample, as a `Double` — the conserved quantity a pure-diffusion step must preserve. */
fun Img.totalMass(): Double = data.fold(0.0) { a, v -> a + v }

/**
 * Shannon entropy of the state histogram of a grid, in bits — `0` when every cell agrees and
 * `log2(states)` when the states are equally common. Used to say "the initial soup was near-maximal
 * and the settled pattern is not", quantitatively.
 */
fun IntGrid.stateEntropyBits(states: Int? = null): Double {
    val n = states ?: (maxState() + 1)
    val counts = IntArray(max(1, n))
    for (c in cells) counts[Math.floorMod(c, counts.size)]++
    var h = 0.0
    for (k in counts) {
        if (k == 0) continue
        val p = k.toDouble() / size
        h -= p * (ln(p) / ln(2.0))
    }
    return h
}

/**
 * A smooth pseudo-random scalar field in `0..1` — value noise at [cells] cells across, bilinearly
 * interpolated, tiling at the borders.
 *
 * Exists because seeding a continuous system with per-pixel white noise is usually wrong: Gray–Scott
 * and Lenia both have a characteristic length, and white noise excites everything above it and
 * nothing below, so a run seeded that way spends its first hundred steps smoothing rather than
 * patterning. A field with structure at roughly the right scale starts where the interesting part is.
 */
fun smoothNoiseImg(width: Int, height: Int, cells: Int = 8, seed: Long = 1L): Img {
    require(cells >= 1) { "cells must be >= 1, got $cells" }
    val rng = kotlin.random.Random(seed)
    val gw = cells + 1
    val g = FloatArray(gw * gw) { rng.nextFloat() }
    fun fade(t: Double) = t * t * (3.0 - 2.0 * t)
    return Img.generate(width, height) { x, y ->
        val fx = x.toDouble() * cells / width
        val fy = y.toDouble() * cells / height
        val x0 = fx.toInt().coerceIn(0, cells - 1)
        val y0 = fy.toInt().coerceIn(0, cells - 1)
        val tx = fade(fx - x0)
        val ty = fade(fy - y0)
        val v00 = g[y0 * gw + x0]; val v10 = g[y0 * gw + x0 + 1]
        val v01 = g[(y0 + 1) * gw + x0]; val v11 = g[(y0 + 1) * gw + x0 + 1]
        val top = v00 + (v10 - v00) * tx.toFloat()
        val bot = v01 + (v11 - v01) * tx.toFloat()
        (top + (bot - top) * ty.toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * A filled disc mask of [radius] centred in a `width x height` image — the calibration object the
 * gallery measures [blobShapes] and [massRadiusDimension] against, so both statistics are shown to
 * read the value theory says rather than assumed to.
 */
fun discMaskImg(width: Int, height: Int, radius: Double): Img {
    require(radius > 0.0) { "radius must be > 0, got $radius" }
    val cx = (width - 1) / 2.0
    val cy = (height - 1) / 2.0
    return Img.generate(width, height) { x, y ->
        if (hypot(x - cx, y - cy) <= radius) 1f else 0f
    }
}

/** Smallest of [a] and [b] on a torus of size [n] — the wrapped absolute difference. */
internal fun wrappedDelta(a: Double, b: Double, n: Double): Double {
    var d = b - a
    if (d > n / 2) d -= n
    if (d < -n / 2) d += n
    return d
}

/** [min]/[max] clamp for a `Double`, spelled out so the agent loops read as maths rather than calls. */
internal fun clampD(v: Double, lo: Double, hi: Double): Double = min(hi, max(lo, v))
