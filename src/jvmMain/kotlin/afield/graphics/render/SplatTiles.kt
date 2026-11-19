package afield.graphics.render

import afield.graphics.scene.fabricated.GaussianSplatRenderer
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A tile-binned Gaussian splat rasterizer on the CPU, with a per-tile workload count and two ways of
 * scheduling the tiles across threads.
 *
 * [GaussianSplatRenderer] draws a sorted list of oriented quads and lets the GPU's blender composite
 * them. The other common design, the one the original 3D Gaussian Splatting paper uses, is a compute
 * rasterizer: every splat is binned into the 16×16 screen tiles its 3σ bound overlaps, each tile's
 * list is sorted front to back, and one workgroup per tile blends its own list. [bin] and [render]
 * are that design, built on [SplatRaster]'s vertex stage so the two rasterizers project a splat the
 * same way and [SplatTilesTest] can require the same picture from both.
 *
 * ## Why a tile count matters
 *
 * Tiles are not equally expensive. A tile over the dense centre of a cloud can hold a thousand
 * splats while one over the background holds none, and whoever is given the heavy tile finishes
 * last while everyone else waits. [Binning.counts] is that workload per tile; [Schedule.Balanced]
 * is the fix described by Mitigating SM-Level Load Imbalance in 3D Gaussian Splatting on GPUs
 * (arXiv:2611.12319): hand the tiles out heaviest first, and cut any tile whose list is longer
 * than a chunk into pieces that run on different workers and are merged afterwards. Front-to-back
 * compositing makes the merge exact — a piece is a premultiplied colour and a transmittance, and
 * two consecutive pieces combine as `C = C₀ + T₀·C₁`, `T = T₀·T₁`.
 *
 * Here the workers are CPU threads, not GPU streaming multiprocessors, so the timings [render]
 * reports describe this rasterizer on this machine. The scheduling problem is the same shape.
 *
 * Only [SplatRaster.Compose.Over] is binned: it is the only mode whose result depends on order.
 */
object SplatTiles {

    /** Tile edge in pixels, matching the 16×16 tiles of the 3DGS compute rasterizer. */
    const val TILE = 16

    private const val P_FLOATS = 15

    /**
     * Every drawn splat, projected once, and the per-tile lists that point at them.
     *
     * @property counts how many splats each tile blends, row-major, `tilesX` wide. The workload map.
     * @property tileStart where tile `t`'s list begins in [entries]; it ends at `tileStart[t + 1]`.
     * @property entries projected-splat indices, each tile's run sorted front to back.
     */
    class Binning internal constructor(
        val width: Int,
        val height: Int,
        val tilesX: Int,
        val tilesY: Int,
        val counts: IntArray,
        val tileStart: IntArray,
        val entries: IntArray,
        internal val projected: FloatArray,
        internal val bounds: IntArray,
        val drawable: Int,
        val alphaCutoff: Float
    ) {
        /** The total number of (tile, splat) pairs, the length of [entries]. */
        val pairs: Int get() = entries.size

        /** The heaviest tile's count. */
        val maxCount: Int get() = counts.maxOrNull() ?: 0

        /** The mean count over tiles that hold at least one splat. */
        val meanOccupied: Double
            get() = counts.filter { it > 0 }.let { if (it.isEmpty()) 0.0 else it.average() }

        /** The [q] quantile (0..1) of the per-tile counts, over all tiles. */
        fun quantile(q: Double): Int {
            val sorted = counts.sorted()
            return if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * q).toInt()]
        }

        /** [counts] as `[tileRow][tileColumn]`, row 0 at the top, ready for `showHeatmap(..., flipY = false)`. */
        fun countRows(): Array<DoubleArray> =
            Array(tilesY) { ty -> DoubleArray(tilesX) { tx -> counts[ty * tilesX + tx].toDouble() } }

        override fun toString(): String =
            "${tilesX}x$tilesY tiles, $drawable splats, $pairs pairs, max $maxCount, " +
                "mean occupied %.1f, p99 ${quantile(0.99)}".format(meanOccupied)
    }

    /** How tiles are handed to worker threads. */
    sealed class Schedule {
        /** One work item per tile, taken in raster order. */
        object Naive : Schedule()

        /**
         * Heaviest tiles first, and tiles longer than [chunk] splats cut into chunks of [chunk].
         *
         * [chunk] 0, the default, picks the chunk from the binning: half of one worker's fair share
         * of all pairs, never below 256. Only a tile heavy enough to hold up the whole frame is cut
         * then, which matters because a chunk starts at full transmittance and cannot stop early on
         * what the chunk in front of it already covered.
         */
        data class Balanced(val chunk: Int = 0) : Schedule() {
            init { require(chunk >= 0) { "chunk must be non-negative, got $chunk" } }

            /** The chunk this schedule uses on [binning] with [threads] workers. */
            fun chunkFor(binning: Binning, threads: Int): Int =
                if (chunk > 0) chunk else max(256, binning.pairs / (2 * threads))
        }
    }

    /**
     * What [render] did.
     *
     * @property renderNanos wall time of the blend and merge, after binning.
     * @property workerNanos busy time per worker thread.
     * @property items how many work items the schedule produced.
     */
    class Result(
        val image: SplatRaster.SplatImage,
        val renderNanos: Long,
        val workerNanos: LongArray,
        val items: Int
    ) {
        /** The busiest worker's time over the mean, 1.0 when perfectly balanced. */
        val imbalance: Double
            get() = if (workerNanos.isEmpty()) 1.0
            else workerNanos.max().toDouble() / max(1.0, workerNanos.average())
    }

    /**
     * Project [count] packed splats and bin them into [TILE]-pixel tiles.
     *
     * Order inside a tile follows [SplatRaster.RasterConfig.depthOrder] reversed, so blending each
     * list front to back composites in exactly the order [SplatRaster.rasterize] uses back to front.
     */
    fun bin(
        packed: FloatBuffer,
        count: Int,
        view: SplatRaster.View,
        config: SplatRaster.RasterConfig = SplatRaster.RasterConfig()
    ): Binning {
        require(config.compose == SplatRaster.Compose.Over) {
            "SplatTiles bins Compose.Over only; ${config.compose} does not depend on order, use SplatRaster.rasterize"
        }
        require(packed.capacity() >= count.toLong() * GaussianSplatRenderer.SPLAT_FLOAT_STRIDE) {
            "buffer holds fewer than $count splats"
        }
        val tilesX = (view.width + TILE - 1) / TILE
        val tilesY = (view.height + TILE - 1) / TILE
        val p = SplatRaster.Projected()
        val proj = FloatArray(count * P_FLOATS)
        val bounds = IntArray(count * 4)
        val keys = LongArray(count)
        var n = 0
        val counts = IntArray(tilesX * tilesY)
        for (splat in 0 until count) {
            if (SplatRaster.project(packed, splat * GaussianSplatRenderer.SPLAT_FLOAT_STRIDE, view, config, p) != 0) continue
            val o = n * P_FLOATS
            proj[o] = p.centreX; proj[o + 1] = p.centreY
            proj[o + 2] = p.axis1x; proj[o + 3] = p.axis1y; proj[o + 4] = p.axis2x; proj[o + 5] = p.axis2y
            proj[o + 6] = p.invSigma1; proj[o + 7] = p.invSigma2
            proj[o + 8] = p.red; proj[o + 9] = p.green; proj[o + 10] = p.blue; proj[o + 11] = p.peakAlpha
            val b = n * 4
            bounds[b] = p.colLow; bounds[b + 1] = p.colHigh; bounds[b + 2] = p.rowLow; bounds[b + 3] = p.rowHigh
            // Front to back: ascending (depth, index) is the reverse of the reference's descending
            // order; in buffer order the reference draws ascending index, so front to back descends.
            val primary = when (config.depthOrder) {
                SplatRaster.DepthOrder.BackToFront -> orderableBits(p.depth).toLong() and 0xffffffffL
                SplatRaster.DepthOrder.BufferOrder -> (count - 1 - splat).toLong()
            }
            keys[n] = (primary shl 32) or n.toLong()
            for (ty in p.rowLow / TILE..p.rowHigh / TILE) for (tx in p.colLow / TILE..p.colHigh / TILE) {
                counts[ty * tilesX + tx]++
            }
            n++
        }
        // Sorting once globally and then scattering keeps every tile's run sorted: a stable
        // counting sort by tile over a depth-sorted list, the CPU form of the GPU's (tile, depth) key.
        java.util.Arrays.sort(keys, 0, n)
        val tileStart = IntArray(counts.size + 1)
        for (t in counts.indices) tileStart[t + 1] = tileStart[t] + counts[t]
        val cursor = tileStart.copyOf(counts.size)
        val entries = IntArray(tileStart[counts.size])
        for (k in 0 until n) {
            val s = (keys[k] and 0xffffffffL).toInt()
            val b = s * 4
            for (ty in bounds[b + 2] / TILE..bounds[b + 3] / TILE) for (tx in bounds[b] / TILE..bounds[b + 1] / TILE) {
                val t = ty * tilesX + tx
                entries[cursor[t]++] = s
            }
        }
        return Binning(
            view.width, view.height, tilesX, tilesY, counts, tileStart, entries,
            proj, bounds, n, config.alphaCutoff
        )
    }

    /**
     * Blend every tile of [binning] front to back on [threads] workers, scheduled by [schedule].
     *
     * @param transmittanceCutoff a pixel whose remaining transmittance falls below this stops taking
     *   splats, as a compute rasterizer does. 0 blends everything and matches [SplatRaster.rasterize]
     *   to float rounding; the default 1e-4 changes no pixel by more than that much linear light.
     */
    fun render(
        binning: Binning,
        schedule: Schedule = Schedule.Balanced(),
        threads: Int = Runtime.getRuntime().availableProcessors(),
        transmittanceCutoff: Float = 1e-4f
    ): Result {
        require(threads > 0) { "threads must be positive, got $threads" }
        val tiles = binning.counts.size
        val (itemTile, itemStart, itemEnd) = workItems(binning, schedule, threads)
        val items = itemTile.size
        val partial = arrayOfNulls<FloatArray>(items)
        val next = AtomicInteger(0)
        val busy = LongArray(threads)
        val done = CountDownLatch(threads)
        val pool = pool(threads)
        val started = System.nanoTime()
        for (w in 0 until threads) {
            pool.execute {
                val t0 = System.nanoTime()
                try {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= items) break
                        partial[i] = blendItem(binning, itemTile[i], itemStart[i], itemEnd[i], transmittanceCutoff)
                    }
                } finally {
                    busy[w] = System.nanoTime() - t0
                    done.countDown()
                }
            }
        }
        done.await()

        val image = assemble(binning, itemTile, itemStart, partial)
        return Result(image, System.nanoTime() - started, busy, items)
    }

    /**
     * The work items [schedule] makes of [binning] for [threads] workers, in hand-out order:
     * tile index, first entry and end entry of each.
     */
    internal fun workItems(binning: Binning, schedule: Schedule, threads: Int): Triple<IntArray, IntArray, IntArray> {
        val tiles = binning.counts.size
        // A work item is (tile, first entry, end entry).
        val itemTile: IntArray
        val itemStart: IntArray
        val itemEnd: IntArray
        when (schedule) {
            Schedule.Naive -> {
                itemTile = IntArray(tiles) { it }
                itemStart = IntArray(tiles) { binning.tileStart[it] }
                itemEnd = IntArray(tiles) { binning.tileStart[it + 1] }
            }
            is Schedule.Balanced -> {
                val chunk = schedule.chunkFor(binning, threads)
                val order = (0 until tiles).sortedByDescending { binning.counts[it] }
                val t = ArrayList<Int>(); val s = ArrayList<Int>(); val e = ArrayList<Int>()
                for (tile in order) {
                    val from = binning.tileStart[tile]
                    val to = binning.tileStart[tile + 1]
                    var at = from
                    do {
                        t += tile; s += at; e += min(to, at + chunk)
                        at += chunk
                    } while (at < to)
                }
                itemTile = t.toIntArray(); itemStart = s.toIntArray(); itemEnd = e.toIntArray()
            }
        }
        return Triple(itemTile, itemStart, itemEnd)
    }

    /**
     * Merge each tile's pieces in list order and write the tiles into an image. [partial] holds one
     * `TILE * TILE * 4` array per work item, premultiplied colour then transmittance.
     */
    internal fun assemble(
        binning: Binning, itemTile: IntArray, itemStart: IntArray, partial: Array<FloatArray?>
    ): SplatRaster.SplatImage {
        val tiles = binning.counts.size
        val items = itemTile.size
        // Merge each tile's pieces in list order, then write the tile out.
        val image = SplatRaster.SplatImage(binning.width, binning.height)
        val byTile = arrayOfNulls<MutableList<Int>>(tiles)
        for (i in 0 until items) (byTile[itemTile[i]] ?: mutableListOf<Int>().also { byTile[itemTile[i]] = it }) += i
        for (tile in 0 until tiles) {
            val pieces = byTile[tile] ?: continue
            pieces.sortBy { itemStart[it] }
            val acc = partial[pieces[0]]!!
            for (k in 1 until pieces.size) {
                val piece = partial[pieces[k]]!!
                for (px in 0 until TILE * TILE) {
                    val o = px * 4
                    val t = acc[o + 3]
                    acc[o] += t * piece[o]; acc[o + 1] += t * piece[o + 1]; acc[o + 2] += t * piece[o + 2]
                    acc[o + 3] = t * piece[o + 3]
                }
            }
            val x0 = (tile % binning.tilesX) * TILE
            val y0 = (tile / binning.tilesX) * TILE
            for (ly in 0 until TILE) {
                val y = y0 + ly
                if (y >= binning.height) break
                for (lx in 0 until TILE) {
                    val x = x0 + lx
                    if (x >= binning.width) break
                    val o = (ly * TILE + lx) * 4
                    val d = (y * binning.width + x) * 4
                    image.linear[d] = acc[o]; image.linear[d + 1] = acc[o + 1]; image.linear[d + 2] = acc[o + 2]
                    image.linear[d + 3] = 1f - acc[o + 3]
                }
            }
        }
        return image
    }

    /**
     * One piece of one tile, blended front to back into premultiplied colour and transmittance,
     * four floats per pixel with the transmittance in the fourth.
     */
    private fun blendItem(b: Binning, tile: Int, from: Int, to: Int, cutoff: Float): FloatArray {
        val acc = FloatArray(TILE * TILE * 4)
        for (px in 0 until TILE * TILE) acc[px * 4 + 3] = 1f
        val x0 = (tile % b.tilesX) * TILE
        val y0 = (tile / b.tilesX) * TILE
        val x1 = min(x0 + TILE, b.width) - 1
        val y1 = min(y0 + TILE, b.height) - 1
        val height = b.height
        val proj = b.projected
        var live = (x1 - x0 + 1) * (y1 - y0 + 1)
        for (e in from until to) {
            val s = b.entries[e]
            val o = s * P_FLOATS
            val bo = s * 4
            val centreX = proj[o]; val centreY = proj[o + 1]
            val axis1x = proj[o + 2]; val axis1y = proj[o + 3]; val axis2x = proj[o + 4]; val axis2y = proj[o + 5]
            val invSigma1 = proj[o + 6]; val invSigma2 = proj[o + 7]
            val red = proj[o + 8]; val green = proj[o + 9]; val blue = proj[o + 10]; val peakAlpha = proj[o + 11]
            val colLow = max(x0, b.bounds[bo]); val colHigh = min(x1, b.bounds[bo + 1])
            val rowLow = max(y0, b.bounds[bo + 2]); val rowHigh = min(y1, b.bounds[bo + 3])
            for (row in rowLow..rowHigh) {
                // The same sample arithmetic as SplatRaster.rasterize, so coverage agrees exactly.
                val dy = height - row - 0.5f - centreY
                val rowBase = (row - y0) * TILE
                for (col in colLow..colHigh) {
                    val a = (rowBase + col - x0) * 4
                    val t = acc[a + 3]
                    if (t < cutoff) continue
                    val dx = col + 0.5f - centreX
                    val u = (dx * axis1x + dy * axis1y) * invSigma1
                    val v = (dx * axis2x + dy * axis2y) * invSigma2
                    if (abs(u) > SplatRaster.QUAD_SIGMAS || abs(v) > SplatRaster.QUAD_SIGMAS) continue
                    val alpha = (peakAlpha * exp(-0.5f * (u * u + v * v))).coerceIn(0f, 1f)
                    if (alpha < b.alphaCutoff) continue
                    val w = t * alpha
                    acc[a] += red * w; acc[a + 1] += green * w; acc[a + 2] += blue * w
                    val after = t * (1f - alpha)
                    acc[a + 3] = after
                    if (after < cutoff) live--
                }
            }
            if (live <= 0) break
        }
        return acc
    }

    /**
     * Time [Schedule.Naive] against [schedule] on the same binning: [repeats] alternating runs of
     * each after one warm-up, reporting the median render time.
     *
     * @return one line per schedule: median milliseconds, items and worker imbalance.
     */
    fun compare(
        binning: Binning,
        schedule: Schedule.Balanced = Schedule.Balanced(),
        threads: Int = Runtime.getRuntime().availableProcessors(),
        repeats: Int = 9,
        transmittanceCutoff: Float = 1e-4f
    ): List<Timing> {
        render(binning, Schedule.Naive, threads, transmittanceCutoff)
        render(binning, schedule, threads, transmittanceCutoff)
        val naive = ArrayList<Result>(); val balanced = ArrayList<Result>()
        repeat(repeats) {
            naive += render(binning, Schedule.Naive, threads, transmittanceCutoff)
            balanced += render(binning, schedule, threads, transmittanceCutoff)
        }
        fun summarize(name: String, runs: List<Result>): Timing {
            val sorted = runs.sortedBy { it.renderNanos }
            val median = sorted[sorted.size / 2]
            return Timing(name, median.renderNanos / 1e6, median.items, median.imbalance, threads)
        }
        return listOf(summarize("naive", naive), summarize("balanced(chunk=${schedule.chunkFor(binning, threads)})", balanced))
    }

    /** One row of [compare]: median render milliseconds, work items, busiest-over-mean worker time. */
    data class Timing(val schedule: String, val medianMs: Double, val items: Int, val imbalance: Double, val threads: Int) {
        override fun toString(): String =
            "%-22s %8.2f ms  %6d items  imbalance %.2f  (%d threads)".format(schedule, medianMs, items, imbalance, threads)
    }

    /**
     * A test cloud with a dense core and a sparse halo in [GaussianSplatRenderer]'s 16-float layout:
     * the arrangement that makes tile workloads uneven. Deterministic in [count].
     *
     * @param coreFraction the share of splats packed into the core, a tenth of the halo's radius.
     */
    fun clusteredCloud(count: Int, coreFraction: Float = 0.6f): FloatBuffer {
        val packed = SplatRaster.allocatePacked(count)
        val core = (count * coreFraction).toInt()
        for (i in 0 until count) {
            val radius = if (i < core) 0.12f else 1f
            val o = i * GaussianSplatRenderer.SPLAT_FLOAT_STRIDE
            val x = (sin(i * 12.9898) * 43758.5453).let { (it - kotlin.math.floor(it)).toFloat() * 2f - 1f }
            val y = (sin(i * 78.233) * 12345.678).let { (it - kotlin.math.floor(it)).toFloat() * 2f - 1f }
            val z = (sin(i * 37.719) * 9876.54321).let { (it - kotlin.math.floor(it)).toFloat() * 2f - 1f }
            val size = if (i < core) 0.01f else 0.025f
            packed.put(o, x * radius); packed.put(o + 1, y * radius); packed.put(o + 2, z * radius)
            packed.put(o + 3, if (i < core) 0.5f else 0.3f)
            val t = (x + 1f) * 0.5f
            packed.put(o + 4, if (i < core) 0.95f else 0.2f + 0.6f * t)
            packed.put(o + 5, if (i < core) 0.75f else 0.45f)
            packed.put(o + 6, if (i < core) 0.3f else 0.9f - 0.5f * t)
            packed.put(o + 7, 0f)
            // Isotropic covariance: size² on the diagonal.
            val c = size * size
            packed.put(o + 8, c); packed.put(o + 9, 0f); packed.put(o + 10, 0f)
            packed.put(o + 11, c); packed.put(o + 12, 0f); packed.put(o + 13, c)
            packed.put(o + 14, 0f); packed.put(o + 15, 0f)
        }
        return packed
    }

    private var sharedPool: ExecutorService? = null
    private var sharedPoolSize = 0

    @Synchronized
    private fun pool(threads: Int): ExecutorService {
        if (sharedPool == null || sharedPoolSize < threads) {
            sharedPool?.shutdown()
            sharedPool = Executors.newFixedThreadPool(threads) { r ->
                Thread(r, "splat-tiles").also { it.isDaemon = true }
            }
            sharedPoolSize = threads
        }
        return sharedPool!!
    }

    private fun orderableBits(value: Float): Int {
        val bits = value.toRawBits()
        return if (bits < 0) bits.inv() else bits xor Int.MIN_VALUE
    }
}
