package afield.geometry.vectorize.polyvector

import afield.geometry.network.Channel
import afield.geometry.network.GeometryCurve
import afield.geometry.network.GeometryNetwork
import afield.geometry.network.GeometryNodes
import afield.geometry.path.FLine
import afield.math.linalg.Vec2
import afield.math.linalg.Vec4
import afield.media.image.Img
import afield.media.image.luma
import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.RunScope
import java.lang.foreign.MemorySegment
import java.lang.ref.Cleaner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Turning a raster line drawing into curves, by the polyvector-field method of Bessmeltsev and
 * Solomon (2019).
 *
 * The problem this solves is the junction: where two strokes cross, a tracer that follows ink alone
 * has no way to tell which outgoing branch continues which incoming one. A [PolyvectorField] gives
 * every ink pixel *two* unsigned directions rather than one, so a crossing is a pixel where both
 * strokes are represented and each can be followed through it. [Polyvector] is the entry point:
 * [Polyvector.fitField] solves for the field, [PolyvectorField.trace] reads curves off it as a
 * [PolyvectorDrawing], and [Polyvector.vectorize] does both for a dark-on-light scan.
 *
 * Fitting the field is the expensive step and tracing is not, which is why the controls come in two
 * parts: [FrameFieldParameters] for the field, [TraceParameters] for the trace. A drawing can be
 * re-traced at several prune ratios without the field being solved again.
 *
 * The input is *evidence*, 0 to 1, for how much each pixel is ink, not a picture:
 * [Polyvector.inkOf] gets it from a dark-on-light drawing as `1 - luminance`, and an edge or ridge
 * detector's output can be passed to [Polyvector.fitField] instead. Coordinates throughout are
 * pixels at pixel centres, x right and y down. The worked account, including which drawings the
 * method suits, is `public/docs/polyvector.md`.
 *
 * The solver is the optional native library `field_polyvector` ([PolyvectorLibrary]), so a field
 * holds native memory and is closed by the box's next run.
 */

/**
 * Controls for building the frame field. [inkThreshold] is the evidence level (0..1) above which a
 * pixel counts as ink. [regularizer] pushes the two directions towards a right angle (larger gives
 * sharper junctions, smaller follows narrow junctions more closely). [smoothness] trades fidelity to
 * the image's local direction for smoothness; raise it only for very noisy input. [repairPasses]
 * fill single-pixel holes in the ink mask. The defaults are the authors'.
 */
data class FrameFieldParameters(
    val inkThreshold: Double = 90.0 / 255.0,
    val regularizer: Double = 0.1,
    val smoothness: Double = 50.0,
    val repairPasses: Int = 3,
)

/**
 * Controls for tracing curves on a field. [pruneRatio] (0..1) is how readily short side branches
 * are removed; [maxLoopPixels] is the largest hole, in background pixels, that is closed over rather
 * than kept as a loop; [simplifyTolerance] simplifies the final polylines (pixels); [smooth] applies
 * the authors' final smoothing. The defaults are the authors'.
 */
data class TraceParameters(
    val pruneRatio: Double = 0.75,
    val maxLoopPixels: Int = 4,
    val simplifyTolerance: Double = 0.01,
    val smooth: Boolean = true,
)

/**
 * The polyvector field of a line drawing: at every ink pixel, two unsigned directions that the lines
 * through that pixel follow. Where one stroke runs, the two directions are the stroke's direction and
 * one across it; where strokes cross, they are the two strokes' directions. Building the field is the
 * expensive step; [trace] reads curves off it, and can be called again with different
 * [TraceParameters] without rebuilding.
 *
 * Holds native memory. One built while a box runs is closed when the box runs again.
 */
class PolyvectorField internal constructor(
    val width: Int,
    val height: Int,
    private val evidence: FloatArray,
    val parameters: FrameFieldParameters,
    handle: MemorySegment,
) : AutoCloseable {
    private class Owner(var handle: MemorySegment?) : Runnable {
        override fun run() { synchronized(this) { handle?.let(PolyvectorNative::free); handle = null } }
    }

    private val owner = Owner(handle)
    private val cleanable = CLEANER.register(this, owner)
    private val maps = PolyvectorNative.maps(handle, width, height)

    /** 1 where the evidence counts as ink, 0 elsewhere; row-major. */
    val mask: ByteArray get() = maps.mask

    /** Per pixel, the authors' confidence that one direction dominates there (0..1); row-major. */
    val weight: FloatArray get() = maps.weight

    /** Per pixel, the two field directions as unit vectors (x0, y0, x1, y1); zero off the ink. */
    val directions: FloatArray get() = maps.directions

    /** Points where the field cannot be continued smoothly, in pixels. */
    val singularities: List<Vec2> = List(maps.singularities.size / 2) { Vec2(maps.singularities[2 * it], maps.singularities[2 * it + 1]) }

    /** Connected pieces of ink; each is vectorized on its own. */
    val componentCount: Int get() = maps.components

    /** Milliseconds spent building the field. */
    val fieldMs: Double get() = maps.fieldMs

    /** The evidence at (x, y) in pixels, bilinear, clamped. */
    fun evidenceAt(x: Double, y: Double): Double {
        val cx = (x - 0.5).coerceIn(0.0, width - 1.0); val cy = (y - 0.5).coerceIn(0.0, height - 1.0)
        val x0 = cx.toInt().coerceAtMost(width - 2); val y0 = cy.toInt().coerceAtMost(height - 2)
        val fx = cx - x0; val fy = cy - y0
        fun e(i: Int, j: Int) = evidence[j * width + i].toDouble()
        return (e(x0, y0) * (1 - fx) + e(x0 + 1, y0) * fx) * (1 - fy) + (e(x0, y0 + 1) * (1 - fx) + e(x0 + 1, y0 + 1) * fx) * fy
    }

    /**
     * The field drawn as small crosses, one every [step] pixels on the ink: each cross is the two
     * directions there, [length] pixels long, the first in [color0] and the second in [color1],
     * [thickness] wide.
     */
    fun directionFLines(step: Int = 4, length: Double = 3.0, color0: Vec4 = Vec4(0.95f, 0.45f, 0.1f, 1f), color1: Vec4 = Vec4(0.15f, 0.5f, 0.95f, 1f), thickness: Double = 0.8): List<FLine> {
        val a = FLine(); val b = FLine()
        for (y in 0 until height step step) for (x in 0 until width step step) {
            val i = (y * width + x) * 4
            if (maps.mask[y * width + x].toInt() == 0) continue
            val cx = x + 0.5; val cy = y + 0.5
            for ((line, k) in listOf(a to 0, b to 2)) {
                val dx = maps.directions[i + k] * length / 2; val dy = maps.directions[i + k + 1] * length / 2
                line.moveTo(cx - dx, cy - dy).lineTo(cx + dx, cy + dy)
            }
        }
        a.properties["color"] = color0; a.properties["thickness"] = thickness
        b.properties["color"] = color1; b.properties["thickness"] = thickness
        return listOf(a, b)
    }

    /** The singular points as one FLine of dots. */
    fun singularitiesFLine(pointSize: Double = 6.0, color: Vec4 = Vec4(1f, 0.1f, 0.6f, 1f)): FLine = FLine().also { f ->
        singularities.forEach { f.moveTo(it.x.toDouble(), it.y.toDouble()) }
        f.properties["pointed"] = true; f.properties["stroked"] = false; f.properties["pointSize"] = pointSize; f.properties["color"] = color
    }

    /** Traces curves on this field. Repeatable with different [parameters]; the field is not rebuilt. */
    fun trace(parameters: TraceParameters = TraceParameters()): PolyvectorDrawing = synchronized(owner) {
        val h = owner.handle ?: throw IllegalStateException("this PolyvectorField has been closed")
        PolyvectorNative.trace(h, parameters)
        PolyvectorDrawing(this, parameters, PolyvectorNative.polylines(h, 0), PolyvectorNative.polylines(h, 1),
            PolyvectorNative.droppedPoints(h), PolyvectorNative.traceMs(h))
    }

    val isClosed: Boolean get() = synchronized(owner) { owner.handle == null }

    override fun close() = cleanable.clean()

    override fun toString() = "PolyvectorField(${width}x$height, $componentCount components, ${singularities.size} singularities)"

    companion object {
        private val CLEANER: Cleaner = Cleaner.create()
    }
}

/**
 * A vectorized drawing: the [curves] traced on a [field], and the [streamlines] they were built from.
 * Coordinates are pixels at pixel centres, x right, y down.
 */
class PolyvectorDrawing internal constructor(
    val field: PolyvectorField,
    val parameters: TraceParameters,
    streamlines: List<FloatArray>,
    curves: List<FloatArray>,
    /** Non-finite points the reference produced and that were removed by splitting curves there. */
    val droppedPoints: Int,
    val traceMs: Double,
) {
    /** The final curves, each as x, y floats. */
    val curves: List<FloatArray> = curves

    /** Streamlines traced along the field before any topology processing, each as x, y floats. */
    val streamlines: List<FloatArray> = streamlines

    /**
     * The curves as a [GeometryNetwork]. A node is placed only where two or more curve ends coincide
     * (within [junctionTolerance] pixels); other ends have none, and a curve that ends against
     * another curve's side stays unjoined. Per-vertex sample `evidence`: the input's value under the
     * curve. Curve attribute `meanEvidence`.
     */
    fun network(junctionTolerance: Double = 0.5): GeometryNetwork {
        val ends = ArrayList<Pair<Double, Double>>()
        curves.forEach { c -> ends.add(c[0].toDouble() to c[1].toDouble()); ends.add(c[c.size - 2].toDouble() to c[c.size - 1].toDouble()) }
        val cluster = IntArray(ends.size) { -1 }
        val centres = ArrayList<Pair<Double, Double>>()
        val counts = ArrayList<Int>()
        for (i in ends.indices) {
            val found = centres.indexOfFirst { hypot(it.first - ends[i].first, it.second - ends[i].second) <= junctionTolerance }
            if (found >= 0) { cluster[i] = found; counts[found] = counts[found] + 1 } else { cluster[i] = centres.size; centres.add(ends[i]); counts.add(1) }
        }
        val shared = centres.indices.filter { counts[it] >= 2 }
        val idOf = shared.withIndex().associate { (k, c) -> c to k }
        val nodes = GeometryNodes(2, IntArray(shared.size) { it }, DoubleArray(shared.size * 2) { i -> centres[shared[i / 2]].let { if (i % 2 == 0) it.first else it.second } },
            mapOf("degree" to Channel.scalar(DoubleArray(shared.size) { counts[shared[it]].toDouble() })))
        val geometry = curves.mapIndexed { i, c ->
            val n = c.size / 2
            val ev = DoubleArray(n) { field.evidenceAt(c[2 * it].toDouble(), c[2 * it + 1].toDouble()) }
            GeometryCurve(i, 2, DoubleArray(c.size) { c[it].toDouble() }, idOf[cluster[2 * i]], idOf[cluster[2 * i + 1]],
                attributes = mapOf("meanEvidence" to ev.average()), samples = mapOf("evidence" to Channel.scalar(ev)))
        }
        return GeometryNetwork(2, nodes, geometry, mapOf(
            "source" to "polyvector", "units" to "pixels", "library" to PolyvectorNative.version(),
            "pruneRatio" to parameters.pruneRatio.toString(), "inkThreshold" to field.parameters.inkThreshold.toString(),
        ))
    }

    /** Ink area divided by total curve length: the drawing's mean stroke width, in pixels. */
    val meanStrokeWidth: Double by lazy {
        val ink = field.mask.count { it.toInt() != 0 }
        val length = curves.sumOf { c -> (1 until c.size / 2).sumOf { k -> hypot((c[2 * k] - c[2 * k - 2]).toDouble(), (c[2 * k + 1] - c[2 * k - 1]).toDouble()) } }
        if (length > 0) ink / length else 0.0
    }

    /**
     * How well the curves explain the ink: the share of ink pixels within [radius] pixels of a curve.
     * The default radius is half the [meanStrokeWidth] plus a pixel and a half, so a stroke's full
     * width counts as explained. 1 means every inked pixel is accounted for; a dropped stroke shows
     * as a lower number.
     */
    fun coverage(radius: Double = meanStrokeWidth / 2 + 1.5): Double {
        val w = field.width; val h = field.height
        val near = BooleanArray(w * h)
        val r = radius
        curves.forEach { c ->
            for (k in 0 until c.size / 2 - 1) {
                val ax = c[2 * k]; val ay = c[2 * k + 1]; val bx = c[2 * k + 2]; val by = c[2 * k + 3]
                val x0 = (minOf(ax, bx) - r).toInt().coerceAtLeast(0); val x1 = (maxOf(ax, bx) + r).toInt().coerceAtMost(w - 1)
                val y0 = (minOf(ay, by) - r).toInt().coerceAtLeast(0); val y1 = (maxOf(ay, by) + r).toInt().coerceAtMost(h - 1)
                val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                for (y in y0..y1) for (x in x0..x1) {
                    val px = x + 0.5f - ax; val py = y + 0.5f - ay
                    val t = if (l2 > 0) ((px * dx + py * dy) / l2).coerceIn(0f, 1f) else 0f
                    if (hypot((px - t * dx).toDouble(), (py - t * dy).toDouble()) <= r) near[y * w + x] = true
                }
            }
        }
        var ink = 0; var covered = 0
        for (i in near.indices) if (field.mask[i].toInt() != 0) { ink++; if (near[i]) covered++ }
        return if (ink == 0) 1.0 else covered.toDouble() / ink
    }

    /** The curves as FLines, each carrying `curveId`, `startNode`, `endNode` and `geometryNetwork`. */
    fun toFLines(smooth: Boolean = false, color: Vec4 = Vec4(0.85f, 0.15f, 0.1f, 1f), thickness: Double = 1.5): List<FLine> {
        val net = network()
        return net.toFLines(smooth).onEach { it.properties["color"] = color; it.properties["thickness"] = thickness; it.properties["geometryNetwork"] = net }
    }

    /** The streamlines as one FLine, for seeing what the topology stage started from. */
    fun streamlinesFLine(color: Vec4 = Vec4(0.2f, 0.5f, 0.9f, 0.35f), thickness: Double = 0.6): FLine = FLine().also { f ->
        streamlines.forEach { s -> if (s.size >= 4) { f.moveTo(s[0], s[1], 0); for (k in 1 until s.size / 2) f.lineTo(s[2 * k], s[2 * k + 1], 0) } }
        f.properties["color"] = color; f.properties["thickness"] = thickness
    }

    override fun toString() = "PolyvectorDrawing(${curves.size} curves from ${streamlines.size} streamlines)"
}

/**
 * Vectorization of line drawings by polyvector fields (Bessmeltsev & Solomon 2019): turns a raster
 * drawing into smooth curves that follow its strokes through junctions and crossings. Needs the
 * optional native library `field_polyvector` (see [PolyvectorLibrary]).
 *
 * ```
 * val drawing = Polyvector.vectorize(imgFromFile("sketch.png"))
 * display("vectors", drawing.toFLines())
 * ```
 *
 * The input is *evidence*: how much each pixel is ink, 0 to 1. A dark-on-light drawing is
 * `1 - luminance`, which [vectorize] computes; [fitField] takes evidence directly, so an edge or
 * ridge detector's output can be used instead.
 */
object Polyvector {
    private val workers = Executors.newSingleThreadExecutor { r -> Thread(r, "Field-polyvector").apply { isDaemon = true } }

    val isAvailable: Boolean get() = PolyvectorLibrary.isAvailable

    /** Builds the field of [evidence] (row-major, [width] × [height], 1 = ink). */
    fun fitField(evidence: FloatArray, width: Int, height: Int, parameters: FrameFieldParameters = FrameFieldParameters()): PolyvectorField {
        require(evidence.size == width * height) { "evidence has ${evidence.size} values for a ${width}x$height image" }
        val f = PolyvectorField(width, height, evidence.copyOf(), parameters, PolyvectorNative.field(evidence, width, height, parameters))
        RunScope.current()?.own(f, ReleasePolicy.ACCUMULATING)
        return f
    }

    /** Builds the field of an evidence image (channel 0, 1 = ink). */
    fun fitField(evidence: Img, parameters: FrameFieldParameters = FrameFieldParameters()): PolyvectorField =
        fitField(FloatArray(evidence.width * evidence.height) { evidence.data[it * evidence.channels] }, evidence.width, evidence.height, parameters)

    /** The evidence of a dark-on-light drawing: 1 - luminance. */
    fun inkOf(drawing: Img): Img = drawing.luma().let { l -> Img(l.width, l.height, 1, FloatArray(l.data.size) { 1f - l.data[it] }) }

    /** Field and trace in one call, for a dark-on-light drawing. */
    fun vectorize(drawing: Img, field: FrameFieldParameters = FrameFieldParameters(), trace: TraceParameters = TraceParameters()): PolyvectorDrawing =
        fitField(inkOf(drawing), field).trace(trace)

    /** [vectorize] on a worker thread, for `runAsync`. */
    fun vectorizeAsync(drawing: Img, field: FrameFieldParameters = FrameFieldParameters(), trace: TraceParameters = TraceParameters()): CompletableFuture<PolyvectorDrawing> =
        CompletableFuture.supplyAsync({ vectorize(drawing, field, trace) }, workers)
}
