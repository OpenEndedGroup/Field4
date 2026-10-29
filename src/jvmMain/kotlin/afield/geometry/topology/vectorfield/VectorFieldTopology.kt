package afield.geometry.topology.vectorfield

import afield.geometry.network.Channel
import afield.geometry.network.GeometryCurve
import afield.geometry.network.GeometryNetwork
import afield.geometry.network.GeometryNodes
import afield.geometry.path.FLine
import afield.geometry.topology.morsesmale.CriticalType
import afield.geometry.topology.tracking.Feature
import afield.math.linalg.Vec4
import afield.media.image.Img
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The topology of a 2-D flow field: where it stands still, and the curves that divide it up.
 *
 * [VectorFieldTopology.compute] reads the flow either as two images, u and v in their first
 * channel, or as one RGB image with u in channel 0 and v in channel 1. It finds the field's zeros
 * as [FlowCriticalPoint]s, classified by [FlowCriticalType] into sources, sinks, saddles, the two
 * kinds of focus and centres, together with the [Separatrix] curves that leave each saddle along
 * its eigen-directions.
 * Those curves cut the frame into regions inside which every streamline starts at the same place
 * and ends at the same place, so they summarise the whole field rather than sampling it.
 *
 * What comes back is ready to draw: [VectorFieldTopology.streamlines] for a grid-seeded picture of
 * the flow, [VectorFieldTopology.toFLines] for the separatrices and
 * [VectorFieldTopology.criticalPointsFLine] for the points, coloured by type.
 * [VectorFieldTopology.features] hands the critical points to
 * [afield.geometry.topology.tracking.FeatureTracker], so a flow that changes can be followed frame
 * by frame, and [VectorFieldTopology.network] gives the same topology as a [GeometryNetwork],
 * which is what the other geometry modules read.
 *
 * Everything here is Kotlin, with no optional native library behind it. A scalar field can be read
 * the same way through [VectorFieldTopology.gradientOf], which turns one into its gradient, or into
 * the flow along its contours. The worked account is `public/docs/vector_field_topology.md`.
 */

/**
 * What a flow does near a critical point: flows out ([SOURCE]), in ([SINK]), spirals out or in
 * ([REPELLING_FOCUS], [ATTRACTING_FOCUS]), circles ([CENTER]), or comes in along one direction and
 * leaves along another ([SADDLE]).
 */
enum class FlowCriticalType { SOURCE, SINK, SADDLE, REPELLING_FOCUS, ATTRACTING_FOCUS, CENTER }

/**
 * A zero of the flow, at ([x], [y]) in pixels. [jacobian] is the flow's derivative there, row-major
 * (`du/dx, du/dy, dv/dx, dv/dy`); [divergence] and [curl] are its trace and rotation, and [strength]
 * `sqrt(|det J|)`, how fast the flow moves away from the point (in units of the field per pixel).
 */
data class FlowCriticalPoint(
    val id: Int,
    val x: Double,
    val y: Double,
    val type: FlowCriticalType,
    val jacobian: DoubleArray,
) {
    val divergence get() = jacobian[0] + jacobian[3]
    val curl get() = jacobian[2] - jacobian[1]
    val strength get() = sqrt(abs(jacobian[0] * jacobian[3] - jacobian[1] * jacobian[2]))

    override fun equals(other: Any?) = other is FlowCriticalPoint && other.id == id && other.x == x && other.y == y
    override fun hashCode() = id
}

/**
 * A separatrix: the streamline that leaves a saddle along one of its four eigen-directions. An
 * [unstable] one flows out of the saddle (forward in time); a stable one flows into it (traced
 * backward). [points] are pixel positions starting at the saddle; [end] is the critical point it
 * reached, or null if it left the frame, ran out of length, or stopped at a point weaker than
 * `minStrength`.
 */
class Separatrix(val saddle: Int, val unstable: Boolean, val points: DoubleArray, val end: Int?) {
    val size get() = points.size / 2

    fun toFLine(): FLine = FLine().also { f ->
        for (i in 0 until size) if (i == 0) f.moveTo(points[0], points[1]) else f.lineTo(points[2 * i], points[2 * i + 1])
        f.properties["color"] = if (unstable) VectorFieldTopology.UNSTABLE else VectorFieldTopology.STABLE
        f.properties["thickness"] = 1.5
    }
}

/**
 * The topology of a 2D vector field: its critical points, classified, and the separatrices joining
 * its saddles to the rest. The separatrices divide the frame into regions inside which all
 * streamlines start at the same place and end at the same place.
 */
class VectorFieldTopology internal constructor(
    val width: Int,
    val height: Int,
    val criticalPoints: List<FlowCriticalPoint>,
    val separatrices: List<Separatrix>,
    private val field: Field,
) {
    /** Critical points of the given types. */
    fun of(vararg types: FlowCriticalType) = criticalPoints.filter { it.type in types }

    /** The separatrices as FLines, unstable in [UNSTABLE] colour, stable in [STABLE]. */
    fun toFLines(): List<FLine> = separatrices.map { it.toFLine() }

    /**
     * The critical points as one pointed FLine, each node coloured by type: sources and repelling
     * foci red, sinks and attracting foci blue, saddles white, centres yellow.
     */
    fun criticalPointsFLine(size: Double = 6.0): FLine = FLine().also { f ->
        criticalPoints.forEach { c -> f.moveTo(c.x, c.y); f.nodes.last().properties["color"] = colourOf(c.type) }
        f.properties["pointed"] = true; f.properties["stroked"] = false; f.properties["pointSize"] = size
    }

    /**
     * The streamline through ([x], [y]), traced [length] pixels forward and backward (RK4, steps of
     * [step] pixels, stopping at the frame's edge or where the flow is slower than [minSpeed]).
     * Streamlines follow the flow's direction whatever its speed, so a [minSpeed] keeps them out of
     * places where the flow is only noise.
     */
    fun streamline(x: Double, y: Double, length: Double = 200.0, step: Double = 0.5, minSpeed: Double = 0.0): FLine {
        val back = field.trace(x, y, -1, length, step, emptyList(), -1, minSpeed).first
        val fwd = field.trace(x, y, 1, length, step, emptyList(), -1, minSpeed).first
        return FLine().also { f ->
            val n = back.size / 2
            for (i in n - 1 downTo 0) if (i == n - 1) f.moveTo(back[2 * i], back[2 * i + 1]) else f.lineTo(back[2 * i], back[2 * i + 1])
            for (i in 1 until fwd.size / 2) f.lineTo(fwd[2 * i], fwd[2 * i + 1])
        }
    }

    /**
     * Streamlines seeded on a grid every [spacing] pixels, each traced [length] pixels both ways: a
     * quick picture of the flow to draw the topology over. Seeds where the flow is slower than
     * [minSpeed] are skipped, and lines stop there.
     */
    fun streamlines(spacing: Double = 12.0, length: Double = 40.0, color: Vec4 = Vec4(0.6f, 0.6f, 0.6f, 0.5f), minSpeed: Double = 0.0): List<FLine> {
        val probe = DoubleArray(2)
        val out = ArrayList<FLine>()
        var y = spacing / 2
        while (y < height - 1) {
            var x = spacing / 2
            while (x < width - 1) {
                if (minSpeed > 0 && (!field.at(x, y, probe) || hypot(probe[0], probe[1]) < minSpeed)) { x += spacing; continue }
                out.add(streamline(x, y, length, minSpeed = minSpeed).also { it.properties["color"] = color; it.properties["thickness"] = 1.0 })
                x += spacing
            }
            y += spacing
        }
        return out
    }

    /**
     * The critical points as tracking [Feature]s, for [afield.geometry.topology.tracking.FeatureTracker]:
     * sinks and attracting foci as `MINIMUM`, sources and repelling foci as `MAXIMUM`, saddles as
     * `SADDLE` (centres are left out). `value` is the divergence and `persistence` the [strength].
     */
    fun features(minStrength: Double = 0.0): List<Feature> = criticalPoints.mapNotNull { c ->
        val t = when (c.type) {
            FlowCriticalType.SINK, FlowCriticalType.ATTRACTING_FOCUS -> CriticalType.MINIMUM
            FlowCriticalType.SOURCE, FlowCriticalType.REPELLING_FOCUS -> CriticalType.MAXIMUM
            FlowCriticalType.SADDLE -> CriticalType.SADDLE
            FlowCriticalType.CENTER -> null
        } ?: return@mapNotNull null
        if (c.strength < minStrength) null else Feature(t, c.x, c.y, c.divergence, c.strength, c.id)
    }

    /**
     * The topology as a [GeometryNetwork] in pixel space: critical points as nodes (channels `type`,
     * the [FlowCriticalType] ordinal, and `strength`), separatrices as curves from their saddle to the
     * point they reach (attribute `unstable`, 1 or 0). A separatrix that leaves the frame ends at a
     * node of type -1 at its last point.
     */
    fun network(): GeometryNetwork {
        val ids = ArrayList<Int>(); val pos = ArrayList<Double>(); val type = ArrayList<Double>(); val strength = ArrayList<Double>()
        criticalPoints.forEach { ids.add(it.id); pos.add(it.x); pos.add(it.y); type.add(it.type.ordinal.toDouble()); strength.add(it.strength) }
        var next = (criticalPoints.maxOfOrNull { it.id } ?: -1) + 1
        val curves = separatrices.mapIndexed { i, s ->
            val end = s.end ?: next++.also { id ->
                ids.add(id); pos.add(s.points[s.points.size - 2]); pos.add(s.points[s.points.size - 1]); type.add(-1.0); strength.add(0.0)
            }
            val (a, b) = if (s.unstable) s.saddle to end else end to s.saddle
            val pts = if (s.unstable) s.points else DoubleArray(s.points.size).also { r -> for (k in 0 until s.size) { r[2 * k] = s.points[2 * (s.size - 1 - k)]; r[2 * k + 1] = s.points[2 * (s.size - 1 - k) + 1] } }
            GeometryCurve(i, 2, pts, a, b, attributes = mapOf("unstable" to if (s.unstable) 1.0 else 0.0))
        }
        return GeometryNetwork(2,
            GeometryNodes(2, ids.toIntArray(), pos.toDoubleArray(), mapOf("type" to Channel.scalar(type.toDoubleArray()), "strength" to Channel.scalar(strength.toDoubleArray()))),
            curves, mapOf("source" to "vectorFieldTopology"))
    }

    override fun toString() = "VectorFieldTopology(${width}x$height, " +
        criticalPoints.groupingBy { it.type }.eachCount().entries.joinToString { "${it.value} ${it.key.name.lowercase()}" } +
        ", ${separatrices.size} separatrices)"

    internal class Field(val w: Int, val h: Int, val u: FloatArray, val v: FloatArray) {
        /** Bilinear sample, or null outside the frame. */
        fun at(x: Double, y: Double, out: DoubleArray): Boolean {
            if (x < 0 || y < 0 || x > w - 1 || y > h - 1) return false
            val x0 = floor(x).toInt().coerceAtMost(w - 2).coerceAtLeast(0); val y0 = floor(y).toInt().coerceAtMost(h - 2).coerceAtLeast(0)
            val fx = x - x0; val fy = y - y0
            val i = y0 * w + x0
            if (w == 1 || h == 1) { out[0] = u[i].toDouble(); out[1] = v[i].toDouble(); return true }
            out[0] = (u[i] * (1 - fx) + u[i + 1] * fx) * (1 - fy) + (u[i + w] * (1 - fx) + u[i + w + 1] * fx) * fy
            out[1] = (v[i] * (1 - fx) + v[i + 1] * fx) * (1 - fy) + (v[i + w] * (1 - fx) + v[i + w + 1] * fx) * fy
            return true
        }

        /**
         * Traces with unit-speed RK4 (the direction of the flow, [step] pixels at a time) in direction
         * [sign], up to [length] pixels. Stops on leaving the frame, where the flow vanishes, or within
         * [captureRadius] (3 px) of a critical point other than [from]; returns the points and the index
         * of the critical point reached, if any. Where the flow is slower than [minSpeed] the trace
         * stops too; with [armed] false that check waits until the trace has first been faster than
         * [minSpeed], so a separatrix can leave the slow neighbourhood of its own saddle.
         */
        fun trace(x0: Double, y0: Double, sign: Int, length: Double, step: Double, targets: List<FlowCriticalPoint>, from: Int, minSpeed: Double = 0.0, armed: Boolean = true): Pair<DoubleArray, Int?> {
            val pts = ArrayList<Double>(); pts.add(x0); pts.add(y0)
            var x = x0; var y = y0; var travelled = 0.0
            val k = DoubleArray(2)
            var fast = armed
            fun dir(px: Double, py: Double, out: DoubleArray): Boolean {
                if (!at(px, py, k)) return false
                val m = hypot(k[0], k[1]); if (m < 1e-12) return false
                if (m >= minSpeed) fast = true else if (fast) return false
                out[0] = sign * k[0] / m; out[1] = sign * k[1] / m; return true
            }
            val k1 = DoubleArray(2); val k2 = DoubleArray(2); val k3 = DoubleArray(2); val k4 = DoubleArray(2)
            while (travelled < length) {
                if (!dir(x, y, k1) || !dir(x + k1[0] * step / 2, y + k1[1] * step / 2, k2) ||
                    !dir(x + k2[0] * step / 2, y + k2[1] * step / 2, k3) || !dir(x + k3[0] * step, y + k3[1] * step, k4)) break
                x += step / 6 * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0]); y += step / 6 * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1])
                travelled += step
                pts.add(x); pts.add(y)
                if (travelled > 2 * CAPTURE) for (c in targets) if (c.id != from && hypot(c.x - x, c.y - y) < CAPTURE) {
                    pts.add(c.x); pts.add(c.y); return pts.toDoubleArray() to c.id
                }
            }
            return pts.toDoubleArray() to null
        }
    }

    companion object {
        internal const val CAPTURE = 1.5
        val UNSTABLE = Vec4(1f, 0.45f, 0.25f, 1f)
        val STABLE = Vec4(0.3f, 0.6f, 1f, 1f)

        fun colourOf(type: FlowCriticalType): Vec4 = when (type) {
            FlowCriticalType.SOURCE, FlowCriticalType.REPELLING_FOCUS -> Vec4(1f, 0.35f, 0.25f, 1f)
            FlowCriticalType.SINK, FlowCriticalType.ATTRACTING_FOCUS -> Vec4(0.3f, 0.55f, 1f, 1f)
            FlowCriticalType.SADDLE -> Vec4(1f, 1f, 1f, 1f)
            FlowCriticalType.CENTER -> Vec4(1f, 0.85f, 0.2f, 1f)
        }

        /**
         * The topology of the flow with x components [u] and y components [v] (one-channel images of
         * the same size, or pass both in one image to the other overload).
         *
         * ```
         * val topo = VectorFieldTopology.compute(u, v)
         * display("flow", lines(topo.streamlines() + topo.toFLines() + topo.criticalPointsFLine()))
         * ```
         *
         * The flow is linear on each triangle of the grid (each pixel square cut along its
         * (x+1, y+1) diagonal, as in Field's Morse–Smale complexes), so each triangle holds at most
         * one zero, found exactly, and its Jacobian is that triangle's. Points weaker than
         * [minStrength] are dropped; a focus whose rotation is less than [centerTolerance] of its
         * divergence counts as a source or sink, and one whose divergence is under [centerTolerance]
         * of its rotation as a centre. Separatrices are traced up to [maxLength] pixels, and stop
         * where the flow, having left the saddle, slows below [minSpeed] (pixels per frame; 0 traces
         * the direction whatever the speed).
         */
        fun compute(
            u: Img,
            v: Img,
            minStrength: Double = 0.0,
            centerTolerance: Double = 0.02,
            maxLength: Double = 2000.0,
            step: Double = 0.5,
            minSpeed: Double = 0.0,
        ): VectorFieldTopology {
            require(u.width == v.width && u.height == v.height) { "u is ${u.width}x${u.height} but v is ${v.width}x${v.height}" }
            val w = u.width; val h = u.height
            val uu = FloatArray(w * h) { u.data[it * u.channels] }; val vv = FloatArray(w * h) { v.data[it * v.channels] }
            require(uu.none { it.isNaN() } && vv.none { it.isNaN() }) { "the field has NaN values" }
            val field = Field(w, h, uu, vv)
            val all = ArrayList<FlowCriticalPoint>()
            for (y in 0 until h - 1) for (x in 0 until w - 1) {
                val a = y * w + x
                // the two triangles of this square: (a, a+1, a+w+1) and (a, a+w+1, a+w)
                for (tri in 0..1) {
                    val p1 = if (tri == 0) a + 1 else a + w + 1
                    val p2 = if (tri == 0) a + w + 1 else a + w
                    zeroIn(field, a, p1, p2, w)?.let { (px, py, j) ->
                        // a zero on a shared edge or vertex would be found twice
                        if (all.none { abs(it.x - px) < 1e-9 && abs(it.y - py) < 1e-9 })
                            all.add(FlowCriticalPoint(2 * a + tri, px, py, classify(j, centerTolerance), j))
                    }
                }
            }
            val cps = all.filter { it.strength >= minStrength }
            val kept = cps.map { it.id }.toSet()
            val seps = ArrayList<Separatrix>()
            for (s in cps) if (s.type == FlowCriticalType.SADDLE) {
                val j = s.jacobian
                for ((lambda, ev) in eigen(j)) for (sgn in intArrayOf(1, -1)) {
                    val unstable = lambda > 0
                    val sx = s.x + sgn * ev[0] * CAPTURE * 0.8; val sy = s.y + sgn * ev[1] * CAPTURE * 0.8
                    // traced against every zero, so it stops cleanly at a weak one too (and then ends nowhere)
                    val (pts, end) = field.trace(sx, sy, if (unstable) 1 else -1, maxLength, step, all, s.id, minSpeed, armed = false)
                    seps.add(Separatrix(s.id, unstable, doubleArrayOf(s.x, s.y) + pts, end?.takeIf { it in kept }))
                }
            }
            return VectorFieldTopology(w, h, cps, seps, field)
        }

        /** The topology of a flow given as one image with u in channel 0 and v in channel 1 (a third channel is ignored). */
        fun compute(flow: Img, minStrength: Double = 0.0, centerTolerance: Double = 0.02, maxLength: Double = 2000.0, step: Double = 0.5, minSpeed: Double = 0.0): VectorFieldTopology {
            require(flow.channels >= 3) { "a flow image holds u and v in channels 0 and 1 of an RGB image, this has ${flow.channels} channel(s)" }
            val n = flow.width * flow.height
            return compute(Img(flow.width, flow.height, 1, FloatArray(n) { flow.data[it * flow.channels] }),
                Img(flow.width, flow.height, 1, FloatArray(n) { flow.data[it * flow.channels + 1] }), minStrength, centerTolerance, maxLength, step, minSpeed)
        }

        /**
         * The flow `(u, v)` = rotated or plain gradient of a scalar field: [rotate] false gives the
         * gradient, which flows uphill (sinks at maxima, sources at minima, saddles at saddles), true gives the gradient
         * turned 90° (flow along the contours: centres at extrema). The result holds u and v in channels 0
         * and 1 of an RGB image, channel 2 zero.
         */
        fun gradientOf(field: Img, rotate: Boolean = false): Img {
            val w = field.width; val h = field.height
            val out = FloatArray(w * h * 3)
            fun f(x: Int, y: Int) = field.data[(y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)) * field.channels]
            for (y in 0 until h) for (x in 0 until w) {
                val gx = (f(x + 1, y) - f(x - 1, y)) / 2; val gy = (f(x, y + 1) - f(x, y - 1)) / 2
                out[3 * (y * w + x)] = if (rotate) -gy else gx
                out[3 * (y * w + x) + 1] = if (rotate) gx else gy
            }
            return Img(w, h, 3, out)
        }

        private fun zeroIn(f: Field, i0: Int, i1: Int, i2: Int, w: Int): Triple<Double, Double, DoubleArray>? {
            val x0 = (i0 % w).toDouble(); val y0 = (i0 / w).toDouble()
            val x1 = (i1 % w).toDouble(); val y1 = (i1 / w).toDouble()
            val x2 = (i2 % w).toDouble(); val y2 = (i2 / w).toDouble()
            val u0 = f.u[i0].toDouble(); val u1 = f.u[i1].toDouble(); val u2 = f.u[i2].toDouble()
            val v0 = f.v[i0].toDouble(); val v1 = f.v[i1].toDouble(); val v2 = f.v[i2].toDouble()
            // solve u0 + a(u1-u0) + b(u2-u0) = 0 and the same for v, for barycentric a, b
            val a11 = u1 - u0; val a12 = u2 - u0; val a21 = v1 - v0; val a22 = v2 - v0
            val det = a11 * a22 - a12 * a21
            if (abs(det) < 1e-18) return null
            val a = (-u0 * a22 + v0 * a12) / det
            val b = (-v0 * a11 + u0 * a21) / det
            val eps = -1e-12
            if (a < eps || b < eps || a + b > 1 - eps) return null
            val px = x0 + a * (x1 - x0) + b * (x2 - x0); val py = y0 + a * (y1 - y0) + b * (y2 - y0)
            // the triangle's Jacobian: d(u,v)/d(a,b) times d(a,b)/d(x,y)
            val e11 = x1 - x0; val e12 = x2 - x0; val e21 = y1 - y0; val e22 = y2 - y0
            val ed = e11 * e22 - e12 * e21
            val i11 = e22 / ed; val i12 = -e12 / ed; val i21 = -e21 / ed; val i22 = e11 / ed
            val j = doubleArrayOf(a11 * i11 + a12 * i21, a11 * i12 + a12 * i22, a21 * i11 + a22 * i21, a21 * i12 + a22 * i22)
            return Triple(px, py, j)
        }

        internal fun classify(j: DoubleArray, centerTolerance: Double): FlowCriticalType {
            val tr = j[0] + j[3]; val det = j[0] * j[3] - j[1] * j[2]
            if (det < 0) return FlowCriticalType.SADDLE
            val disc = tr * tr - 4 * det
            if (disc >= 0) return if (tr > 0) FlowCriticalType.SOURCE else FlowCriticalType.SINK
            val rot = sqrt(-disc) / 2
            return when {
                abs(tr) / 2 < centerTolerance * rot -> FlowCriticalType.CENTER
                rot < centerTolerance * abs(tr) / 2 -> if (tr > 0) FlowCriticalType.SOURCE else FlowCriticalType.SINK
                tr > 0 -> FlowCriticalType.REPELLING_FOCUS
                else -> FlowCriticalType.ATTRACTING_FOCUS
            }
        }

        /** Real eigenvalues and unit eigenvectors of a saddle's Jacobian. */
        private fun eigen(j: DoubleArray): List<Pair<Double, DoubleArray>> {
            val tr = j[0] + j[3]; val det = j[0] * j[3] - j[1] * j[2]
            val s = sqrt((tr * tr / 4 - det).coerceAtLeast(0.0))
            return listOf(tr / 2 + s, tr / 2 - s).map { l ->
                // (J - l) e = 0: take the longer of the two rows' null vectors
                val r1 = doubleArrayOf(j[1], l - j[0]); val r2 = doubleArrayOf(l - j[3], j[2])
                val e = if (hypot(r1[0], r1[1]) > hypot(r2[0], r2[1])) r1 else r2
                val m = hypot(e[0], e[1]).takeIf { it > 1e-18 } ?: 1.0
                l to doubleArrayOf(e[0] / m, e[1] / m)
            }
        }
    }
}
