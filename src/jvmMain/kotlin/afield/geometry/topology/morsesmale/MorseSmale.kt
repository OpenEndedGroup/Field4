package afield.geometry.topology.morsesmale

import afield.geometry.network.Channel
import afield.geometry.network.GeometryCurve
import afield.geometry.network.GeometryNetwork
import afield.geometry.network.GeometryNodes
import afield.geometry.path.FLine
import afield.math.linalg.Vec4
import afield.media.image.Img
import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.RunScope
import java.lang.foreign.MemorySegment
import java.lang.ref.Cleaner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor

/**
 * The Morse–Smale complex of a 2-D scalar field: where its peaks, pits and passes are, how they
 * are joined, and which of them are significant.
 *
 * [MorseSmale] is the entry point. It builds a [MorseSmaleComplex] once, from an image channel or
 * a float grid, and the complex then answers for any persistence threshold without being rebuilt,
 * which is what makes a persistence slider cheap. Each threshold gives a [MorseSmaleResult]: the
 * surviving [CriticalPoint]s, the separatrices between them as a `GeometryNetwork`, and the basin
 * and peak segmentation as one id per pixel. The whole hierarchy is readable from the complex as
 * [PersistencePair]s and [Cancellation]s, and [MorseSmaleTimings] splits the build by stage.
 *
 * Persistence is in the field's own units and removes features by significance, so it is a
 * different thing from blurring the field or smoothing the drawn separatrices. What the threshold
 * does to a picture, and what the complex is good for once you have it, are in
 * `public/docs/morse_smale.md`; following the critical points through time or scale is
 * `afield.geometry.topology.tracking`.
 *
 * The complex is computed by the optional native library `field_morse_smale`
 * ([MorseSmaleLibrary]), so it holds native memory and is closed by the box's next run.
 */

/** What kind of critical point: a minimum is a vertex, a saddle an edge, a maximum a triangle of the grid. */
enum class CriticalType { MINIMUM, SADDLE, MAXIMUM }

/**
 * A critical point of the unsimplified complex.
 *
 * [id] is the grid cell it sits on, stable for as long as the feature stays on that cell, so it can key
 * downstream objects across recomputation. [x], [y] are in pixel units (a saddle sits on an edge
 * midpoint, a maximum on a triangle centroid). [cancelledAt] is the persistence threshold at which
 * simplification removes it, or infinity if none does.
 */
data class CriticalPoint(
    val id: Int,
    val type: CriticalType,
    val value: Double,
    val x: Double,
    val y: Double,
    val cancelledAt: Double,
)

/**
 * One pair of the persistence diagram. [dimension] 0 pairs a minimum ([birth]) with the saddle that
 * merges its basin away ([death]); dimension 1 pairs a saddle with the maximum that fills its cycle.
 * The global minimum's [death] is null and its [persistence] infinite.
 */
data class PersistencePair(val birth: Int, val death: Int?, val dimension: Int, val persistence: Double)

/** One step of the simplification hierarchy: [saddle] and [extremum] cancel at [persistence]. */
data class Cancellation(val saddle: Int, val extremum: Int, val persistence: Double)

/** Wall-clock milliseconds spent building a complex, by stage. */
data class MorseSmaleTimings(val gradientMs: Double, val separatricesMs: Double, val hierarchyMs: Double, val persistenceMs: Double) {
    val totalMs get() = gradientMs + separatricesMs + hierarchyMs + persistenceMs
}

/**
 * The discrete Morse–Smale complex of a scalar field, built once and simplified at any
 * persistence threshold without rebuilding.
 *
 * ```
 * val complex = MorseSmale.build(img)          // channel 0 of an Img
 * val coarse = complex.simplify(0.1)
 * display("ms", coarse.toFLines())
 * complex.close()
 * ```
 *
 * The complex holds native memory. One built while a box runs belongs to that run and is closed
 * when the box runs again, so a box that rebuilds on every run does not accumulate them; it stays
 * open after a stop, so a slider keeps working. Outside a box run, [close] it yourself; a [Cleaner]
 * frees a forgotten one eventually.
 */
class MorseSmaleComplex internal constructor(
    val width: Int,
    val height: Int,
    private val values: FloatArray,
    handle: MemorySegment,
) : AutoCloseable {
    private class Owner(var handle: MemorySegment?) : Runnable {
        override fun run() {
            synchronized(this) { handle?.let(MorseSmaleNative::free); handle = null }
        }
    }

    private val owner = Owner(handle)
    private val cleanable = CLEANER.register(this, owner)
    private val base = MorseSmaleNative.read(handle)

    /** Every critical point of the unsimplified complex. */
    val criticalPoints: List<CriticalPoint> = List(base.cell.size) { i ->
        CriticalPoint(
            base.cell[i], CriticalType.entries[base.type[i]], base.value[i].toDouble(),
            base.xy[2 * i].toDouble(), base.xy[2 * i + 1].toDouble(), base.cancelledAt[i],
        )
    }

    private val byId = criticalPoints.associateBy { it.id }

    /** The critical point with cell [id], or null. */
    fun critical(id: Int): CriticalPoint? = byId[id]

    /** The persistence diagram, computed independently of the complex by union-find. */
    val persistencePairs: List<PersistencePair> = List(base.pairBirth.size) { i ->
        PersistencePair(base.pairBirth[i], base.pairDeath[i].takeIf { it >= 0 }, base.pairDimension[i], base.pairPersistence[i])
    }

    /** The simplification hierarchy in the order it is applied; persistence never decreases along it. */
    val cancellations: List<Cancellation> = List(base.cancelSaddle.size) { i ->
        Cancellation(base.cancelSaddle[i], base.cancelExtremum[i], base.cancelPersistence[i])
    }

    val timings = MorseSmaleTimings(base.timings[0], base.timings[1], base.timings[2], base.timings[3])

    /** How many critical points survive at each threshold where the count changes: (threshold, count). */
    fun survivalCurve(): List<Pair<Double, Int>> {
        var alive = criticalPoints.size
        val out = mutableListOf(0.0 to alive)
        cancellations.forEach { c ->
            alive -= 2
            if (out.last().first == c.persistence) out[out.size - 1] = c.persistence to alive else out.add(c.persistence to alive)
        }
        return out
    }

    /** The field value at (x, y) in pixel units, bilinear, clamped at the edges. */
    fun sample(x: Double, y: Double): Double {
        val cx = x.coerceIn(0.0, width - 1.0)
        val cy = y.coerceIn(0.0, height - 1.0)
        val x0 = floor(cx).toInt().coerceAtMost(width - 2)
        val y0 = floor(cy).toInt().coerceAtMost(height - 2)
        val fx = cx - x0
        val fy = cy - y0
        fun v(i: Int, j: Int) = values[j * width + i].toDouble()
        return (v(x0, y0) * (1 - fx) + v(x0 + 1, y0) * fx) * (1 - fy) + (v(x0, y0 + 1) * (1 - fx) + v(x0 + 1, y0 + 1) * fx) * fy
    }

    /**
     * The complex after cancelling every saddle–extremum pair with persistence below [persistence],
     * in field units. 0 gives the unsimplified complex. Replays the stored hierarchy, so it is cheap
     * to call repeatedly from a slider.
     */
    fun simplify(persistence: Double = 0.0): MorseSmaleResult {
        require(!persistence.isNaN()) { "persistence threshold is NaN" }
        val view = synchronized(owner) {
            val h = owner.handle ?: throw IllegalStateException("this MorseSmaleComplex has been closed")
            MorseSmaleNative.simplify(h, persistence, width * height)
        }
        return MorseSmaleResult(this, persistence, view)
    }

    val isClosed: Boolean get() = synchronized(owner) { owner.handle == null }

    override fun close() = cleanable.clean()

    override fun toString() = "MorseSmaleComplex(${width}x$height, ${criticalPoints.size} critical points, ${cancellations.size} cancellations)"

    companion object {
        private val CLEANER: Cleaner = Cleaner.create()
    }
}

/**
 * A Morse–Smale complex at one persistence threshold.
 *
 * [network] holds the surviving critical points as nodes and the separatrices as curves, in pixel
 * units. Curves run from their saddle (`startNode`) to their extremum (`endNode`); an ascending
 * separatrix that leaves the grid has a null `endNode`. Node channels: `type` (0 minimum, 1 saddle,
 * 2 maximum), `value`, `cancelledAt`. Curve attributes: `kind` (0 descending to a minimum, 1
 * ascending to a maximum), `persistence` (|f(saddle) - f(extremum)|), `saddleValue`,
 * `extremumValue`. Per-vertex sample: `value`, the field along the curve.
 *
 * [network] is built on first access. [basins] and [peaks] give, per pixel, the id of the minimum whose descending manifold (basin)
 * holds it and of the maximum whose ascending manifold (peak) holds it (-1 for the outside).
 */
class MorseSmaleResult internal constructor(
    val complex: MorseSmaleComplex,
    val threshold: Double,
    view: MorseSmaleNative.View,
) {
    val width get() = complex.width
    val height get() = complex.height

    val criticalPoints: List<CriticalPoint> = view.nodes.map { complex.critical(it)!! }
    val basins: IntArray = view.basin
    val peaks: IntArray = view.peak

    /** How many separatrices survive; known without building [network]. */
    val separatrixCount: Int = view.arcSaddle.size

    /**
     * Built on first access, since an unsimplified noisy field can have millions of separatrices and
     * a slider that only reads [criticalPoints] or [basins] should not pay for them.
     */
    val network: GeometryNetwork by lazy {
        val nodes = GeometryNodes(
            2,
            IntArray(criticalPoints.size) { criticalPoints[it].id },
            DoubleArray(criticalPoints.size * 2) { i -> criticalPoints[i / 2].let { if (i % 2 == 0) it.x else it.y } },
            mapOf(
                "type" to Channel.scalar(DoubleArray(criticalPoints.size) { criticalPoints[it].type.ordinal.toDouble() }),
                "value" to Channel.scalar(DoubleArray(criticalPoints.size) { criticalPoints[it].value }),
                "cancelledAt" to Channel.scalar(DoubleArray(criticalPoints.size) { criticalPoints[it].cancelledAt }),
            ),
        )
        // Curve ids: saddle id * 4 + kind * 2 + ordinal. A saddle has at most two arcs of each kind,
        // and ordering them by extremum keeps the id stable for as long as both endpoints survive.
        val order = view.arcSaddle.indices.sortedWith(compareBy({ view.arcSaddle[it] }, { view.arcKind[it] }, { view.arcExtremum[it] }))
        val offsets = IntArray(view.arcLength.size + 1).also { o -> view.arcLength.forEachIndexed { i, l -> o[i + 1] = o[i] + l } }
        var previous = -1L
        var ordinal = 0
        val curves = order.map { a ->
            val key = view.arcSaddle[a].toLong() * 2 + view.arcKind[a]
            ordinal = if (key == previous) ordinal + 1 else 0
            previous = key
            val n = view.arcLength[a]
            val coords = DoubleArray(2 * n) { view.paths[2 * offsets[a] + it].toDouble() }
            val samples = DoubleArray(n) { complex.sample(coords[2 * it], coords[2 * it + 1]) }
            val saddle = complex.critical(view.arcSaddle[a])!!
            val extremum = view.arcExtremum[a].takeIf { it >= 0 }?.let { complex.critical(it)!! }
            GeometryCurve(
                id = view.arcSaddle[a] * 4 + view.arcKind[a] * 2 + ordinal,
                dimension = 2,
                coordinates = coords,
                startNode = saddle.id,
                endNode = extremum?.id,
                attributes = mapOf(
                    "kind" to view.arcKind[a].toDouble(),
                    "persistence" to (extremum?.let { abs(it.value - saddle.value) } ?: Double.POSITIVE_INFINITY),
                    "saddleValue" to saddle.value,
                    "extremumValue" to (extremum?.value ?: Double.POSITIVE_INFINITY),
                ),
                samples = mapOf("value" to Channel.scalar(samples)),
            )
        }
        GeometryNetwork(
            2, nodes, curves,
            mapOf(
                "source" to "morseSmale",
                "threshold" to threshold.toString(),
                "width" to width.toString(),
                "height" to height.toString(),
                "units" to "pixels",
                "library" to MorseSmaleNative.version(),
            ),
        )
    }

    fun count(type: CriticalType) = criticalPoints.count { it.type == type }

    /** Separatrices running down to minima. */
    val descending: GeometryNetwork get() = network.filterCurves { it.attributes["kind"] == 0.0 }

    /** Separatrices running up to maxima (or out of the grid). */
    val ascending: GeometryNetwork get() = network.filterCurves { it.attributes["kind"] == 1.0 }

    /**
     * Separatrices as FLines: descending ones in [descendingColor], ascending ones in
     * [ascendingColor]. With [smooth] the polylines become splines through the same vertices; the
     * topology does not change. Each line keeps `curveId`, `startNode`, `endNode`, and `geometryNetwork`,
     * which is the whole [network] even when one kind is left out.
     */
    fun toFLines(
        smooth: Boolean = false,
        traceDescending: Boolean = true,
        traceAscending: Boolean = true,
        descendingColor: Vec4 = Vec4(0.15f, 0.35f, 0.9f, 0.9f),
        ascendingColor: Vec4 = Vec4(0.9f, 0.25f, 0.15f, 0.9f),
        thickness: Double = 1.5,
    ): List<FLine> = network.filterCurves {
        (traceDescending && it.attributes["kind"] == 0.0) || (traceAscending && it.attributes["kind"] == 1.0)
    }.toFLines(smooth).onEach { line ->
        line.properties["geometryNetwork"] = network
        line.properties["color"] = if (line.properties["kind"] == 0.0) descendingColor else ascendingColor
        line.properties["thickness"] = thickness
    }

    /** The surviving critical points as one FLine of dots: minima blue, saddles green, maxima red. */
    fun criticalPointsFLine(pointSize: Double = 6.0): FLine = network.nodesFLine().also { f ->
        f.properties["pointed"] = true
        f.properties["stroked"] = false
        f.properties["pointSize"] = pointSize
        f.nodes.forEach { n ->
            n.properties["color"] = when ((n.properties["type"] as Double).toInt()) {
                0 -> Vec4(0.1f, 0.3f, 1f, 1f)
                1 -> Vec4(0.1f, 0.75f, 0.2f, 1f)
                else -> Vec4(1f, 0.2f, 0.1f, 1f)
            }
        }
    }

    /** The basins (or, with [peaks] true, the peaks) as an RGB image, one colour per region. */
    fun segmentationImage(peaks: Boolean = false): Img {
        val labels = if (peaks) this.peaks else basins
        val data = FloatArray(labels.size * 3)
        labels.forEachIndexed { i, l ->
            if (l < 0) return@forEachIndexed
            var h = l * -0x61c88647
            h = h xor (h ushr 15)
            data[3 * i] = 0.35f + 0.6f * ((h and 0xff) / 255f)
            data[3 * i + 1] = 0.35f + 0.6f * (((h ushr 8) and 0xff) / 255f)
            data[3 * i + 2] = 0.35f + 0.6f * (((h ushr 16) and 0xff) / 255f)
        }
        return Img(width, height, 3, data)
    }

    override fun toString() =
        "MorseSmaleResult(threshold $threshold: ${count(CriticalType.MINIMUM)} minima, ${count(CriticalType.SADDLE)} saddles, " +
            "${count(CriticalType.MAXIMUM)} maxima, ${network.curves.size} separatrices)"
}

/**
 * Morse–Smale complexes of 2D scalar fields: critical points, the separatrices between them, basin
 * and peak segmentation, and persistence-based simplification. Needs the optional native library
 * `field_morse_smale` (see [MorseSmaleLibrary]).
 *
 * ```
 * val result = MorseSmale.compute(img, persistence = 0.05)
 * display("ms", result.toFLines() + result.criticalPointsFLine())
 * ```
 *
 * Persistence is measured in the field's own units. It removes features by significance; it is not
 * geometric smoothing, and `toFLines(smooth = true)` smooths only the drawing of each separatrix.
 */
object MorseSmale {
    private val workers = Executors.newFixedThreadPool(maxOf(1, Runtime.getRuntime().availableProcessors() / 2)) { r ->
        Thread(r, "Field-morse-smale").apply { isDaemon = true }
    }

    val isAvailable: Boolean get() = MorseSmaleLibrary.isAvailable

    /**
     * Builds the complex of a field with [width] × [height] samples, where sample (x, y) is
     * `values[offset + y * rowStride + x * pixelStride]`. Nothing is simplified yet: call
     * [MorseSmaleComplex.simplify].
     */
    fun build(
        values: FloatArray,
        width: Int,
        height: Int,
        pixelStride: Int = 1,
        rowStride: Int = width * pixelStride,
        offset: Int = 0,
    ): MorseSmaleComplex {
        val last = offset.toLong() + (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride
        require(offset >= 0 && last < values.size) { "a ${width}x$height field with these strides needs ${last + 1} values, got ${values.size}" }
        val handle = MorseSmaleNative.build(values, offset, width, height, pixelStride, rowStride)
        val copy = FloatArray(width * height) { i -> values[offset + (i / width) * rowStride + (i % width) * pixelStride] }
        val complex = MorseSmaleComplex(width, height, copy, handle)
        // released by the box's next run, not by a stop (see ReleasePolicy.ACCUMULATING)
        RunScope.current()?.own(complex, ReleasePolicy.ACCUMULATING)
        return complex
    }

    /** Builds the complex of one [channel] of [img], read in place. */
    fun build(img: Img, channel: Int = 0): MorseSmaleComplex {
        require(channel in 0 until img.channels) { "channel $channel out of range for a ${img.channels}-channel image" }
        return build(img.data, img.width, img.height, img.channels, img.width * img.channels, channel)
    }

    /** Builds, simplifies at [persistence], and frees the native complex. */
    fun compute(values: FloatArray, width: Int, height: Int, persistence: Double = 0.0): MorseSmaleResult =
        build(values, width, height).use { it.simplify(persistence) }

    /** Builds, simplifies at [persistence], and frees the native complex. */
    fun compute(img: Img, persistence: Double = 0.0, channel: Int = 0): MorseSmaleResult =
        build(img, channel).use { it.simplify(persistence) }

    /**
     * [build] on a worker thread, for `runAsync`. A complex built off the box's thread is not owned
     * by the run, so close it when you are done:
     *
     * ```
     * runAsync(onResult = { c: MorseSmaleComplex -> lines(c.simplify(0.05).toFLines()); c.close() }) {
     *     MorseSmale.buildAsync(img)
     * }
     * ```
     */
    fun buildAsync(img: Img, channel: Int = 0): CompletableFuture<MorseSmaleComplex> =
        CompletableFuture.supplyAsync({ build(img, channel) }, workers)

    /** [compute] on a worker thread. */
    fun computeAsync(img: Img, persistence: Double = 0.0, channel: Int = 0): CompletableFuture<MorseSmaleResult> =
        CompletableFuture.supplyAsync({ compute(img, persistence, channel) }, workers)
}
