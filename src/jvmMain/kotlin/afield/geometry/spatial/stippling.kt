@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.spatial

import afield.animation.keyframes.h
import afield.data.collections.get
import afield.data.collections.times
import afield.data.graph.main
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.document.workspace.path
import afield.geometry.mesh.centroid
import afield.geometry.mesh.contains
import afield.graphics.render.label
import afield.graphics.render.rangeTo
import afield.graphics.shader.compute
import afield.io.osc.main
import afield.lang.stacksl.h
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.dot
import afield.math.autodiff.mean
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.control.main
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.statistics.main
import afield.math.statistics.noise
import afield.math.statistics.random
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.channel
import afield.media.image.imgFromFile
import afield.media.image.showImg
import afield.media.image.split
import afield.runtime.execution.cell
import afield.runtime.execution.end
import afield.runtime.execution.go
import afield.runtime.execution.start
import afield.runtime.lifecycle.once
import afield.simulation.automata.main
import afield.ui.output.h

import afield.audio.synth.*
import afield.data.collections.invoke
import afield.data.collections.m
import afield.data.collections.s
import afield.graphics.render.moveTo
import afield.graphics.render.pointSize
import afield.graphics.render.pointed
import afield.math.linalg.Vec2
import afield.math.linalg.d
import afield.math.linalg.f
import afield.math.linalg.i
import afield.math.linalg.invoke
import afield.math.linalg.x
import afield.media.image.Img
import afield.media.image.luma
import afield.media.image.resize
import afield.media.video.image
import afield.simulation.agents.step
import afield.simulation.automata.step
import afield.geometry.path.FLine
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RecursiveAction
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Stippling: place exactly N points so that they follow an image's brightness *and* keep an even,
 * low-correlation ("blue-noise-ish") spacing from one another.
 *
 * ```
 * val dots = imgFromFile("face.png").stipple(5000)        // List<Vec2>, pixel coordinates
 * ```
 *
 * Sampling the brightness as a probability distribution gets the density right but clumps: two
 * independent samples are as likely to land on top of each other as anywhere else. This file instead
 * computes a **weighted centroidal Voronoi tessellation** (Secord's stippling) by Lloyd relaxation on
 * the CPU. Each iteration
 *
 * 1. rasterises the points into the pixel grid,
 * 2. finds every pixel's nearest point with a separable exact Euclidean distance transform
 *    (a two-scan nearest-seed pass along each row, then Felzenszwalb–Huttenlocher's lower envelope of
 *    parabolas down each column), which yields the Voronoi cells as a label raster in `O(pixels)`
 *    regardless of the point count,
 * 3. moves each point to the density-weighted centroid of its cell.
 *
 * The rows and columns are split across a fork-join pool of their own, and nothing on the per-iteration
 * path allocates: the label rasters, the column scratch and the per-worker centroid accumulators are
 * all allocated once, in the constructor.
 *
 * # Where the points go, and what relaxation does not change
 *
 * The *number* of points in a region is decided by the starting sample. Relaxation evens out the
 * spacing between neighbours and moves hardly any points from one region to another, so the point
 * density is whatever the start gave, `brightness^densityPower`. With the default of 1, points follow
 * brightness, and a larger power exaggerates contrast.
 *
 * That makes the start matter. An independent weighted random sample has the right density on
 * average and random clumps everywhere, and relaxation keeps the clumps' counts: on a flat field the
 * count variance in 48-pixel windows is 0.60 of a random scatter's after relaxing, which reads as
 * blotches in a mid tone. So the start is stratified along a Hilbert curve instead. The curve visits
 * the image in a way that keeps nearby pixels nearby, it is cut into `count` stretches of equal
 * density, and each stretch gets one point. Every region the curve covers then starts with its exact
 * share, and the same measurement after relaxing is 0.028.
 *
 * Theory predicts a different density: the *optimal* centroidal tessellation of a density `ρ` in 2-D
 * has point density `ρ^(1/2)`. Lloyd iteration from a weighted start does not travel there. On a
 * field half at brightness 1 and half at 0.5 the bright/dim count ratio stays at 2 through hundreds of
 * iterations, against an optimum of 1.41, until the iteration reaches a fixed point on the raster
 * where [Stippler.step] returns 0. `StipplingTest` pins both powers, the drift and the lumpiness.
 *
 * # Memory
 *
 * Besides five rasters of `supersample² * width * height` pixels, each worker keeps its own three
 * `DoubleArray(count)` centroid accumulators so that workers never contend. That is cheap at ten or a hundred thousand
 * points, and 384 MB at a million points on 16 workers — pass a smaller `threads` there.
 */

/**
 * [count] stipple points following this image's brightness, after [iterations] rounds of weighted
 * Lloyd relaxation. Points are in pixel coordinates: origin top-left, `y` down, a pixel's centre at
 * `+0.5`. Use [Stippler] directly to relax incrementally, for instance one step per frame.
 *
 * @param count how many points; exactly this many come back
 * @param iterations Lloyd rounds; 20 is visually settled for most images
 * @param densityPower point density goes as `brightness^densityPower`; raise it for more contrast
 * @param invert stipple the dark regions instead — what ink on paper wants
 * @param seed the initial placement's random seed; same seed, same image, same points
 */
fun Img.stipple(
    count: Int,
    iterations: Int = 20,
    densityPower: Float = 1f,
    invert: Boolean = false,
    seed: Int = 1,
): List<Vec2> = Stippler(this, count, densityPower, invert, seed).relax(iterations).points()

/**
 * Stipple points as one pointed [FLine], ready for `lines(...)`: a `moveTo` per point and no
 * segments, so it draws dots and nothing between them.
 *
 * @param size the `pointSize` of each dot
 * @param z the depth every point is placed at
 */
fun stipplesToFLine(points: List<Vec2>, size: Double = 1.0, z: Number = 0): FLine {
    val f = FLine()
    for (p in points) f.moveTo(p.x, p.y, z)
    f.pointed = true
    f.pointSize = size
    return f
}

/**
 * Stipple points rasterised as anti-aliased discs: a 1-channel [Img], for `showImg(...)`.
 *
 * Use this one to *look at* many dots, and [stipplesToFLine] when you want the dots as geometry (for
 * a plotter, an SVG, or the GPU). Measured on 6000 dots over 480 x 360: `lines(...)` sends 15 MB of
 * SVG, about 2.5 kB a dot, and this raster's PNG is 95 kB. A box that redraws every frame needs
 * the raster.
 *
 * @param width the raster width in pixels; use the stippled image's, so the coordinates line up
 * @param height the raster height in pixels
 * @param radius dot radius in pixels
 * @param ink `true` draws black dots on white paper, `false` white dots on black
 */
fun stipplesToImg(points: List<Vec2>, width: Int, height: Int, radius: Double = 1.0, ink: Boolean = true): Img {
    val cover = FloatArray(width * height)
    val reach = radius + 0.5
    for (p in points) {
        val x0 = max(0, (p.x - reach).toInt())
        val x1 = min(width - 1, (p.x + reach).toInt())
        val y0 = max(0, (p.y - reach).toInt())
        val y1 = min(height - 1, (p.y + reach).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x + 0.5 - p.x
            val dy = y + 0.5 - p.y
            val c = (reach - sqrt(dx * dx + dy * dy)).coerceIn(0.0, 1.0).toFloat()
            val i = y * width + x
            if (c > cover[i]) cover[i] = c
        }
    }
    if (ink) for (i in cover.indices) cover[i] = 1f - cover[i]
    return Img(width, height, 1, cover)
}

/**
 * An incremental weighted Lloyd relaxation of [count] points over an image's brightness — the engine
 * behind [stipple], kept as an object so a box can watch it settle:
 *
 * ```
 * val s = Stippler(photo, 4000)
 * loop { s.step(); lines(stipplesToFLine(s.points())) }
 * ```
 *
 * The points start as a sample stratified along a Hilbert curve through the image: the curve is cut
 * into [count] stretches of equal density and one point is dropped somewhere in each. Every [step]
 * then moves every point to the density-weighted centroid of its Voronoi cell. A point whose cell
 * holds no density at all (it fell into black) is re-placed by a weighted random sample.
 *
 * Results depend only on the image, the parameters, [seed] and [threads]: the per-worker sums are
 * reduced in a fixed order, so the same thread count reproduces the same points bit for bit.
 *
 * @param image the image whose brightness the points follow; 1-channel images are used as-is,
 *   colour images through BT.709 luma, values clamped to `0..1`
 * @param count how many points, `1..width*height`
 * @param densityPower point density goes as `brightness^densityPower`, see the file overview
 * @param invert follow darkness instead of brightness
 * @param seed seed for the initial placement and for re-placing empty cells
 * @param threads fork-join workers to split rows and columns across; small images run on one
 * @param supersample relax on a raster this many times finer than the image; 0 picks it, see
 *   [Stippler.supersample]
 */
class Stippler(
    image: Img,
    /** How many points; [points] always returns exactly this many. */
    val count: Int,
    densityPower: Float = 1f,
    invert: Boolean = false,
    /** The seed for the initial placement and for re-placing empty cells. */
    val seed: Int = 1,
    threads: Int = Runtime.getRuntime().availableProcessors(),
    supersample: Int = 0,
) {
    /** The image's width in pixels; points have `x` in `0..width`. */
    val width = image.width

    /** The image's height in pixels; points have `y` in `0..height`. */
    val height = image.height

    /**
     * How many times finer than the image the tessellation is computed. The centroid of a cell a few
     * pixels across is quantised to the pixel grid, and in dense regions that lines the dots up into
     * chains. So unless asked otherwise this picks the factor, up to 4, that gives an average cell at
     * least 128 raster pixels. Points are reported in image pixels regardless.
     */
    val supersample: Int =
        if (supersample > 0) supersample
        else sqrt(128.0 * count / (width.toDouble() * height)).let { ceil(it).toInt() }.coerceIn(1, 4)

    private val k = this.supersample
    private val w = width * k
    private val h = height * k
    private val size = w * h
    private val rng = Random(seed)

    /** Point `i`'s `x`, in pixels. Written by [step]; read it directly in a hot loop. */
    val xs = FloatArray(count)

    /** Point `i`'s `y`, in pixels, `y` down. Written by [step]. */
    val ys = FloatArray(count)

    /** How many [step]s have run. */
    var iterations = 0
        private set

    private val density = FloatArray(size)

    // Image pixels in Hilbert-curve order, and the running density total along that order.
    private val order = IntArray(width * height)
    private val cdf = DoubleArray(width * height)
    private val totalDensity: Double

    // Rasterised point ids (-1 empty), the row pass's squared distance and owner, the final owner.
    private val seedId = IntArray(size)
    private val rowDist = FloatArray(size)
    private val rowId = IntArray(size)
    private val nearestId = IntArray(size)

    private val mass = DoubleArray(count)
    private val sumX = DoubleArray(count)
    private val sumY = DoubleArray(count)

    private val workers = if (size < 64 * 64) 1 else threads.coerceIn(1, min(w, h))
    private val scratch = Array(workers) { Scratch(max(w, h), count) }
    private val bands = Array(workers) { Band(it) }
    private var phase = 0

    /** Forks the bands from inside [POOL], so the calling thread only waits for the whole phase. */
    private val phaseTask = object : RecursiveAction() {
        override fun compute() {
            for (i in 1 until workers) bands[i].fork()
            bands[0].invoke()
            for (i in 1 until workers) bands[i].join()
        }
    }

    private class Scratch(maxDimension: Int, count: Int) {
        val f = DoubleArray(maxDimension)
        val labels = IntArray(maxDimension)
        val v = IntArray(maxDimension)
        val z = DoubleArray(maxDimension + 1)
        val mass = DoubleArray(count)
        val sumX = DoubleArray(count)
        val sumY = DoubleArray(count)
    }

    /** One worker's share of a phase; reinitialised and re-forked every phase, never reallocated. */
    private inner class Band(val worker: Int) : RecursiveAction() {
        override fun compute() {
            when (phase) {
                ROWS -> span(this@Stippler.h) { from, to -> rowPass(from, to) }
                COLUMNS -> span(w) { from, to -> columnPass(scratch[worker], from, to) }
                CENTROIDS -> span(this@Stippler.h) { from, to -> accumulate(scratch[worker], from, to) }
            }
        }

        private inline fun span(n: Int, block: (Int, Int) -> Unit) =
            block(n * worker / workers, n * (worker + 1) / workers)
    }

    init {
        require(count in 1..width * height) {
            "count must be in 1..${width * height} (one point per pixel at most), got $count"
        }
        val luma = image.luma().let { if (k == 1) it else it.resize(w, h) }.data
        for (i in 0 until size) {
            val b = luma[i].coerceIn(0f, 1f)
            density[i] = (if (invert) 1f - b else b).pow(densityPower)
        }
        hilbertOrder(width, height, order)
        var total = 0.0
        for (j in order.indices) {
            val px = order[j] % width
            val py = order[j] / width
            for (sy in 0 until k) for (sx in 0 until k) total += density[(py * k + sy) * w + px * k + sx]
            cdf[j] = total
        }
        require(total > 0.0) { "the image has no ${if (invert) "darkness" else "brightness"} to stipple" }
        totalDensity = total
        // One point per equal share of the density along the curve, jittered within its share.
        for (i in 0 until count) placeAt(i, (i + rng.nextDouble()) * total / count)
    }

    /** The points as values, in pixel coordinates. Allocates a fresh list; see [xs] / [ys] to avoid it. */
    fun points(): List<Vec2> = List(count) { Vec2(xs[it], ys[it]) }

    /**
     * Run up to [iterations] [step]s, stopping early once the mean movement falls to [tolerance]
     * pixels or below. Returns this, so `Stippler(img, n).relax(20).points()` reads in one line.
     */
    fun relax(iterations: Int = 20, tolerance: Double = 0.0): Stippler {
        for (i in 0 until iterations) if (step() <= tolerance) break
        return this
    }

    /**
     * One Lloyd iteration: Voronoi cells by distance transform, then every point to its cell's
     * density-weighted centroid. Returns the mean distance the points moved, in pixels — it falls
     * towards zero as the tessellation settles, which makes it the thing to plot.
     */
    fun step(): Double {
        rasterise()
        run(ROWS)
        run(COLUMNS)
        for (s in scratch) {
            s.mass.fill(0.0); s.sumX.fill(0.0); s.sumY.fill(0.0)
        }
        run(CENTROIDS)
        mass.fill(0.0); sumX.fill(0.0); sumY.fill(0.0)
        for (s in scratch) for (i in 0 until count) {
            mass[i] += s.mass[i]; sumX[i] += s.sumX[i]; sumY[i] += s.sumY[i]
        }
        var moved = 0.0
        for (i in 0 until count) {
            val ox = xs[i]
            val oy = ys[i]
            val m = mass[i]
            if (m > 0.0) {
                xs[i] = (sumX[i] / m / k).toFloat()
                ys[i] = (sumY[i] / m / k).toFloat()
            } else {
                placeRandomly(i)
            }
            val dx = (xs[i] - ox).toDouble()
            val dy = (ys[i] - oy).toDouble()
            moved += sqrt(dx * dx + dy * dy)
        }
        iterations++
        return moved / count
    }

    private fun run(p: Int) {
        phase = p
        for (b in bands) b.reinitialize()
        if (workers == 1) {
            bands[0].invoke()
            return
        }
        phaseTask.reinitialize()
        POOL.invoke(phaseTask)
    }

    /** A brightness-weighted random pixel, jittered within it. */
    private fun placeRandomly(i: Int) = placeAt(i, rng.nextDouble() * totalDensity)

    /** Point [i] into the image pixel where the running density along the Hilbert curve reaches [q]. */
    private fun placeAt(i: Int, q: Double) {
        var lo = 0
        var hi = cdf.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cdf[mid] < q) lo = mid + 1 else hi = mid
        }
        xs[i] = (order[lo] % width) + rng.nextFloat()
        ys[i] = (order[lo] / width) + rng.nextFloat()
    }

    /**
     * Write each point's id into the pixel that contains it. Two points in one pixel would make one of
     * them vanish from the tessellation, so a collision takes the nearest free pixel instead, searching
     * outward ring by ring; `count <= width * height` guarantees one exists.
     */
    private fun rasterise() {
        seedId.fill(-1)
        for (id in 0 until count) {
            val px = (xs[id] * k).toInt().coerceIn(0, w - 1)
            val py = (ys[id] * k).toInt().coerceIn(0, h - 1)
            if (seedId[py * w + px] == -1) {
                seedId[py * w + px] = id
                continue
            }
            var radius = 1
            search@ while (true) {
                for (dy in -radius..radius) {
                    val y = py + dy
                    if (y < 0 || y >= h) continue
                    // the ring only: full rows top and bottom, the two end columns between
                    val step = if (dy == -radius || dy == radius) 1 else 2 * radius
                    var dx = -radius
                    while (dx <= radius) {
                        val x = px + dx
                        if (x in 0 until w && seedId[y * w + x] == -1) {
                            seedId[y * w + x] = id
                            break@search
                        }
                        dx += step
                    }
                }
                radius++
            }
        }
    }

    /**
     * Along a row the input is 0 at a seed and infinite elsewhere, so the general 1-D transform is
     * overkill: the nearest seed is the nearer of the last one seen scanning left-to-right and the
     * last one seen scanning right-to-left.
     */
    private fun rowPass(from: Int, to: Int) {
        for (y in from until to) {
            val base = y * w
            var lastX = -1
            var lastId = -1
            for (x in 0 until w) {
                val i = base + x
                if (seedId[i] >= 0) {
                    lastX = x; lastId = seedId[i]
                }
                if (lastId >= 0) {
                    val d = x - lastX
                    rowDist[i] = (d * d).toFloat()
                    rowId[i] = lastId
                } else {
                    rowDist[i] = Float.POSITIVE_INFINITY
                    rowId[i] = -1
                }
            }
            lastId = -1
            for (x in w - 1 downTo 0) {
                val i = base + x
                if (seedId[i] >= 0) {
                    lastX = x; lastId = seedId[i]
                }
                if (lastId >= 0) {
                    val d = lastX - x
                    val d2 = (d * d).toFloat()
                    if (d2 < rowDist[i]) {
                        rowDist[i] = d2
                        rowId[i] = lastId
                    }
                }
            }
        }
    }

    /**
     * Down each column, `D[q] = min_p (q - p)² + f[p]` where `f` is the row pass's squared distance:
     * Felzenszwalb–Huttenlocher's lower envelope of parabolas. Only the winning parabola's label is
     * kept — the cell, not the distance. Rows with no seed at all (`f` infinite) are skipped.
     */
    private fun columnPass(s: Scratch, from: Int, to: Int) {
        val f = s.f
        val labels = s.labels
        val v = s.v
        val z = s.z
        for (x in from until to) {
            var k = -1
            for (q in 0 until h) {
                val i = q * w + x
                val fq = rowDist[i].toDouble()
                if (fq == Double.POSITIVE_INFINITY) continue
                f[q] = fq
                labels[q] = rowId[i]
                if (k < 0) {
                    k = 0; v[0] = q; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY
                    continue
                }
                var s0: Double
                while (true) {
                    val p = v[k]
                    // ((f[q] + q²) - (f[p] + p²)) / 2(q - p), rearranged to keep the squares small
                    s0 = ((fq - f[p]) / (q - p) + (q + p)) * 0.5
                    if (s0 > z[k] || k == 0) break
                    k--
                }
                k++
                v[k] = q
                z[k] = s0
                z[k + 1] = Double.POSITIVE_INFINITY
            }
            // k < 0 cannot happen once rasterise has placed a seed: every column then sees some row
            // with a seed in it. Kept as a guard rather than an assertion.
            if (k < 0) {
                for (q in 0 until h) nearestId[q * w + x] = -1
                continue
            }
            var e = 0
            for (q in 0 until h) {
                while (e < k && z[e + 1] < q) e++
                nearestId[q * w + x] = labels[v[e]]
            }
        }
    }

    private fun accumulate(s: Scratch, from: Int, to: Int) {
        for (y in from until to) {
            val cy = y + 0.5
            var i = y * w
            for (x in 0 until w) {
                val d = density[i]
                if (d > 0f) {
                    val id = nearestId[i]
                    if (id >= 0) {
                        val dd = d.toDouble()
                        s.mass[id] += dd
                        s.sumX[id] += (x + 0.5) * dd
                        s.sumY[id] += cy * dd
                    }
                }
                i++
            }
        }
    }

    private companion object {
        /**
         * The stipplers' own workers, not the common pool: a box steps on Field's main thread, and
         * Field also runs audio work on the common pool, so waiting on that would let the box's frame
         * queue behind it. Daemon threads, created on first use, idle ones retired by the pool.
         */
        val POOL: ForkJoinPool by lazy { ForkJoinPool(Runtime.getRuntime().availableProcessors()) }

        /** Fill [out] with the pixel indices of a `width x height` image in Hilbert-curve order. */
        fun hilbertOrder(width: Int, height: Int, out: IntArray) {
            var n = 1
            while (n < max(width, height)) n = n shl 1
            var j = 0
            for (d in 0L until n.toLong() * n) {
                var t = d
                var x = 0
                var y = 0
                var s = 1
                while (s < n) {
                    val rx = ((t / 2) and 1L).toInt()
                    val ry = ((t xor rx.toLong()) and 1L).toInt()
                    if (ry == 0) {
                        if (rx == 1) {
                            x = s - 1 - x; y = s - 1 - y
                        }
                        val tmp = x; x = y; y = tmp
                    }
                    x += s * rx
                    y += s * ry
                    t /= 4
                    s = s shl 1
                }
                if (x < width && y < height) out[j++] = y * width + x
            }
        }

        const val ROWS = 0
        const val COLUMNS = 1
        const val CENTROIDS = 2
    }
}
