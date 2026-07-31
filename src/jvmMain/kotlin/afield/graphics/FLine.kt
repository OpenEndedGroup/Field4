package afield.graphics

import afield.HasProperties
import afield.Rect
import afield.stdlib.*
import field.graphics.FLinesAndJavaShapes
import field.graphics.FLinesAndJavaShapes.Cursor
import kotlinx.serialization.Serializable
import afield.stdlib.linalg.Vector3d
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.minus
import afield.stdlib.linalg.plus
import afield.stdlib.linalg.times
import java.lang.Math
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

var _flineSequenceNumber = 0L

/**
 * Translate all lines in the list by [offset].
 */
operator fun List<FLine>.plus(offset: Vec3) = this.map { it + offset }

/**
 * Scale all lines in the list component-wise by [scale].
 */
operator fun List<FLine>.times(scale: Vec3) = this.map { it * scale }

operator fun List<FLine>.plus(offset: Vec2) = this.map { it + offset }

/**
 * Scale all lines in the list component-wise by [scale].
 */
operator fun List<FLine>.times(scale: Vec2) = this.map { it * scale }

/**
 * Rotate all lines in the list by [rotation].
 */
operator fun List<FLine>.times(rotation: Quat) = this.map { it * rotation }

/**
 * Uniformly scale all lines in the list by [scale].
 */
operator fun List<FLine>.times(scale: Number) = this.map { it * scale }

/**
 * Translate all lines in each map entry by [offset].
 */
operator fun Map<String, List<FLine>>.plus(offset: Vec3) = this.entries.associate { it.key to it.value.plus(offset) }

/**
 * Translate all lines in an [FLineMap] by [offset].
 */
operator fun FLineMap.plus(offset: Vec3) = this.entries.associate { it.key to it.value.plus(offset) }

/**
 * Scale all lines in each map entry component-wise by [offset].
 */
operator fun Map<String, List<FLine>>.times(offset: Vec3) = this.entries.associate { it.key to it.value.times(offset) }

/**
 * Scale all lines in an [FLineMap] component-wise by [offset].
 */
operator fun FLineMap.times(offset: Vec3) = this.entries.associate { it.key to it.value.times(offset) }

/**
 * Uniformly scale all lines in each map entry by [scale].
 */
operator fun Map<String, List<FLine>>.times(scale: Number) = this.entries.associate { it.key to it.value.times(scale) }

/**
 * Uniformly scale all lines in an [FLineMap] by [scale].
 */
operator fun FLineMap.times(scale: Number) = this.entries.associate { it.key to it.value.times(scale) }

/**
 * Rotate all lines in each map entry by [rotation].
 */
operator fun Map<String, List<FLine>>.times(rotation: Quat) =
    this.entries.associate { it.key to it.value.times(rotation) }

/**
 * Rotate all lines in an [FLineMap] by [rotation].
 */
operator fun FLineMap.times(rotation: Quat) = this.entries.associate { it.key to it.value.times(rotation) }

internal const val FLINE_PLANE2D_KEY = "_fline_plane2d"

internal data class Plane2D(
    val origin: Vec3,
    val xAxis: Vec3,
    val yAxis: Vec3,
    val normal: Vec3
) {
    fun point(x: Number, y: Number, z: Number = 0): Vec3 {
        val xf = x.toFloat()
        val yf = y.toFloat()
        val zf = z.toFloat()
        return Vec3(origin)
            .add(Vec3(xAxis).mul(xf))
            .add(Vec3(yAxis).mul(yf))
            .add(Vec3(normal).mul(zf))
    }
}

internal fun FLine.plane2D(): Plane2D? = properties[FLINE_PLANE2D_KEY] as? Plane2D

internal fun FLine.planePoint(x: Number, y: Number, z: Number = 0): Vec3 {
    val plane = plane2D() ?: return vec(x, y, z)
    return plane.point(x, y, z)
}

/**
 * Mutable path composed of move/line/cubic/close instructions.
 *
 * Use [moveTo], [lineTo], [cubicTo], and [close] to build geometry. Builders return
 * [FLine] for chaining; use [here] to access the most recently added [Node] when you
 * need per-instruction properties (for example, `color` or `pointSize`).
 */
@Serializable(with = FLineSerializer::class)
class FLine : HasProperties {

    var nodes = arrayListOf<Node>()
    val properties = mutableMapOf<String, Any?>()

    var rev = ++_flineSequenceNumber
    val uid = ++_flineSequenceNumber

    /**
     * Base class for drawing instructions in an [FLine].
     *
     * Each node stores its control points in [storage] and has its own property map.
     */
    sealed class Node(val n: Int) : HasProperties {
        val storage = mutableListOf<Vec3>()
        val properties = mutableMapOf<String, Any?>()

        /**
         * Replace the node's control points.
         *
         * @throws IllegalArgumentException if the number of points does not match this node type.
         */
        fun set(vararg data: Vec3): Node {
            storage.clear()
            storage.addAll(data)
            if (storage.size != n) throw IllegalArgumentException(" expecting $n elements, got ${storage.size}")
            return this
        }

        abstract fun create(): Node

        /**
         * Copy this node, optionally transforming each stored point.
         */
        fun copy(transform: (Vec3) -> Vec3 = { it }): Node {
            val next = create()
            storage.forEach { next.storage.add(transform(it)) }
            next.properties.putAll(properties)
            return next
        }

        /**
         * The point this instruction ends at.
         *
         * **Throws on a [Close]**, which is `Node(0)` and stores no point of its own — its end point
         * is the start of the subpath it closes, which a lone node cannot know. Reach for
         * [FLine.endPointOf] / [FLine.currentPoint] (which resolve it) or [toOrNull] (which admits
         * it) whenever the node might be a `Close` — that is, whenever the line might be closed.
         */
        var to: Vec3
            get() = storage.getOrNull(0) ?: throw IndexOutOfBoundsException(
                "$this stores no point of its own, so `Node.to` is undefined for it. A Close's end " +
                    "point is the start of the subpath it closes: use FLine.endPointOf(index), " +
                    "FLine.currentPoint, or Node.toOrNull instead."
            )
            set(v) {
                storage[0] = v
            }

        /**
         * [to], or `null` for a [Close]. For "where does the pen end up", which is what callers
         * usually mean, see [FLine.endPointOf].
         */
        val toOrNull: Vec3? get() = storage.getOrNull(0)

        override fun setPropertyRaw(name: String, value: Any, doResync: Boolean) {
            properties.put(name, value)
        }

        override fun getPropertyRaw(name: String, default: Any?): Any? = properties.get(name)

        protected fun pointsSummary(): String =
            if (storage.isEmpty()) "" else storage.joinToString(", ", prefix = "(", postfix = ")") { prettyVec3(it) }

    }

    /**
     * The most recently added node.
     */
    val here: Node get() = nodes.last()

    /**
     * Where the instruction at [index] leaves the pen.
     *
     * The same thing as `nodes[index].to` for every node type except [Close], which stores no point
     * and whose end point is the start of the subpath it closes. Use this instead of `.to` wherever
     * the node might be a `Close`, i.e. wherever the line might be closed — `.to` throws there.
     *
     * [index] is clamped into range.
     */
    fun endPointOf(index: Int): Vec3 {
        if (nodes.isEmpty()) return Vec3()
        val at = index.coerceIn(0, nodes.size - 1)
        nodes[at].toOrNull?.let { return it }
        for (i in at downTo 0) {
            val node = nodes[i]
            if (node is MoveTo) return Vec3(node.to)
        }
        return Vec3(nodes[0].toOrNull ?: Vec3())
    }

    /**
     * Where the pen sits after the last instruction — the point a `lineTo`/`cubicTo` would start
     * from — or `null` for a line with no instructions at all.
     *
     * This is what `nodes.last().to` is reaching for, and unlike `nodes.last().to` it survives a
     * line that ends in a [Close].
     */
    val currentPoint: Vec3? get() = if (nodes.isEmpty()) null else endPointOf(nodes.size - 1)

    /**
     * Move instruction (starts a new subpath).
     */
    class MoveTo : Node(1) {
        override fun create(): Node = MoveTo()
        override fun toString(): String = "MoveTo${pointsSummary()}"
    }

    /**
     * Line instruction (straight segment).
     */
    class LineTo : Node(1) {
        override fun create(): Node = LineTo()
        override fun toString(): String = "LineTo${pointsSummary()}"
    }

    /**
     * Cubic Bezier instruction (endpoint plus two control points).
     */
    class CubicTo : Node(3) {
        override fun create(): Node = CubicTo()
        var c1: Vec3
            get() = storage[1]
            set(v) {
                storage[1] = v
            }
        var c2: Vec3
            get() = storage[2]
            set(v) {
                storage[2] = v
            }

        override fun toString(): String = "CubicTo${pointsSummary()}"
    }

    /**
     * Close instruction (connects back to the most recent move).
     */
    class Close : Node(0) {
        override fun create(): Node = Close()
        override fun toString(): String = "Close"
    }

    /**
     * When set, the next [lineTo] or [cubicTo] becomes a [moveTo].
     */
    var breakNext = false

    /**
     * Convenience to set [breakNext] for the next instruction.
     */
    fun breakNext() {
        breakNext = true
    }

    /**
     * Move the current point without drawing, starting a new subpath.
     */
    fun moveTo(x: Number, y: Number, z: Number = 0): FLine {
        nodes.add(MoveTo().set(planePoint(x, y, z)))
        rev++
        return this
    }

    /**
     * Move the current point without drawing, starting a new subpath.
     */
    fun moveTo(v: Vec3): FLine {
        nodes.add(MoveTo().set(Vec3(v)))
        rev++
        return this
    }

    /**
     * 2D overload of [moveTo].
     */
    fun moveTo(v: Vec2): FLine {
        return moveTo(v.x, v.y, 0.0)
    }

    /**
     * 3D vector overload of [moveTo].
     */
    fun moveTo(v: Vector3d): FLine {
        return moveTo(Vec3(v.x.toFloat(), v.y.toFloat(), v.z.toFloat()))
    }

    /**
     * Add a line segment to the given position.
     *
     * If [breakNext] is set, this becomes a [moveTo] instead.
     */
    fun lineTo(x: Number, y: Number, z: Number = 0): FLine {
        if (breakNext) {
            breakNext = false
            return moveTo(x, y, z)
        }
        rev++
        nodes.add(LineTo().set(planePoint(x, y, z)))
        return this
    }

    /**
     * Add a line segment to the given position.
     *
     * If [breakNext] is set, this becomes a [moveTo] instead.
     */
    fun lineTo(v: Vec3): FLine {
        if (breakNext) {
            breakNext = false
            return moveTo(v)
        }
        rev++
        nodes.add(LineTo().set(Vec3(v)))
        return this
    }

    /**
     * 3D vector overload of [lineTo].
     */
    fun lineTo(v: Vector3d): FLine {
        return lineTo(Vec3(v.x.toFloat(), v.y.toFloat(), v.z.toFloat()))
    }

    /**
     * 2D overload of [lineTo].
     */
    fun lineTo(v: Vec2): FLine {
        return lineTo(v.x, v.y, 0)
    }

    /**
     * Add a cubic Bezier segment.
     *
     * @param x0,y0,z0 First control point.
     * @param x1,y1,z1 Second control point.
     * @param x,y,z End point.
     */
    fun cubicTo(
        x0: Number,
        y0: Number,
        z0: Number = 0,
        x1: Number,
        y1: Number,
        z1: Number = 0,
        x: Number,
        y: Number,
        z: Number = 0
    ): FLine {
        if (breakNext) {
            breakNext = false
            return moveTo(x, y, z)
        }
        rev++
        val end = planePoint(x, y, z)
        val c1 = planePoint(x0, y0, z0)
        val c2 = planePoint(x1, y1, z1)
        nodes.add(
            CubicTo().set(
                end,
                c1,
                c2,
            )
        )
        return this
    }


    /**
     * Return a new line thickened to the given stroke width.
     *
     * This uses Java2D shape conversion, so it is a 2D operation.
     */
    fun byThickening(_thickness: Float) = FLinesAndJavaShapes.javaShapeToFLine(
        FLinesAndJavaShapes.flineToJavaShape(
            copy {
                thickness = _thickness.toDouble()
            })
    )

    /**
     * Return a filtered copy of this line.
     *
     * The predicate is called with the previous point (or null at the start) and the node.
     * Returning false removes the node; if a node is removed in the middle of a segment, it
     * is replaced with a [MoveTo] to preserve subpaths.
     */
    fun byFiltering(predicate: (Vec3?, Node) -> Boolean): FLine {
        fun removeInstruction(at: Vec3?, node: Node, inside: FLine) {
            val i = inside.nodes.indexOf(node)
            if (at == null) {
                inside.nodes.removeAt(i)
            } else inside.nodes[i] = MoveTo().set(node.to)
        }
        return copy {
            val segments = ArrayList(this.nodes)
            var at: Vec3? = null
            segments.forEachIndexed { i, node ->
                if (!predicate(at, node)) {
                    removeInstruction(at, node, this)
                }
                at = node.to
            }
        }
    }

//    @Doc("this is some documentation, in *markdown* format _really_")
//    fun frob(x: Number, y: Number, z: Number = 0): Node {
//        return MoveTo()
//    }

    /**
     * 2D overload of [cubicTo] (z defaults to 0).
     */
    fun cubicTo(
        x0: Number,
        y0: Number,
        x1: Number,
        y1: Number,
        x: Number,
        y: Number,
    ): FLine {
        return cubicTo(x0, y0, 0.0, x1, y1, 0.0, x, y, 0.0)
    }

    /**
     * Add a cubic Bezier segment using vector control points.
     *
     * If [breakNext] is set, this becomes a [moveTo] instead.
     */
    fun cubicTo(v0: Vec3, v1: Vec3, v: Vec3): FLine {
        if (breakNext) {
            breakNext = false
            return moveTo(v)
        }
        rev++
        nodes.add(
            CubicTo().set(
                Vec3(v),
                Vec3(v0),
                Vec3(v1),
            )
        )
        return this
    }

    /**
     * Close the current subpath.
     */
    fun close(): FLine {
        rev++
        nodes.add(Close())
        return this
    }

    /**
     * Copy this line while transforming every stored point.
     */
    fun transform(by: (Vec3) -> Vec3): FLine {
        val r = FLine()
        nodes.forEach {
            r.nodes.add(it.copy(by))
        }
        r.properties.putAll(properties)
        return r
    }

    private fun anchor3(a: ListAnchor): Vec3 = when (a) {
        ListAnchor.Start -> nodes.firstOrNull()?.to?.let(::Vec3) ?: Vec3()
        ListAnchor.End -> nodes.lastOrNull()?.to?.let(::Vec3) ?: Vec3()
        ListAnchor.Midpoint -> midPoint()
        ListAnchor.Center -> center()
    }

    private fun anchor3(a: FractionAlongAnchor): Vec3 {
        if (nodes.isEmpty()) return Vec3()
        val c = cursor()
        return c.setD(c.lengthD() * a.t.toFloat().coerceIn(0f, 1f)).position()
    }

    private fun anchor2(a: ListAnchor): Vec2 {
        val p = anchor3(a)
        return Vec2(p.x, p.y)
    }

    private fun anchor2(a: FractionAlongAnchor): Vec2 {
        val p = anchor3(a)
        return Vec2(p.x, p.y)
    }

    /**
     * Rotate in the XY plane around [center], preserving Z.
     */
    fun rot(angle: Number, center: Vec2): FLine {
        val c = cos(angle.toFloat())
        val s = sin(angle.toFloat())
        return transform {
            val x = it.x - center.x
            val y = it.y - center.y
            Vec3(center.x + x * c - y * s, center.y + x * s + y * c, it.z)
        }
    }

    /**
     * Rotate around the Z axis and the line center by default.
     */
    fun rot(angle: Number): FLine = rot(angle, vec(0f, 0f, 1f), center())

    fun rot(angle: Number, center: Vec3): FLine = rot(angle, vec(0f, 0f, 1f), center)
    fun rot(angle: Number, center: ListAnchor): FLine = rot(angle, anchor3(center))
    fun rot(angle: Number, center: FractionAlongAnchor): FLine = rot(angle, anchor3(center))

    /**
     * Rotate in 3D around [axis] and [center].
     */
    fun rot(angle: Number, axis: Vec3, center: Vec3 = center()): FLine {
        val q = quat(angle, axis)
        return transform { q.transform(Vec3(it).sub(center)).add(center) }
    }

    fun rot(angle: Number, axis: Vec3, center: ListAnchor): FLine = rot(angle, axis, anchor3(center))
    fun rot(angle: Number, axis: Vec3, center: FractionAlongAnchor): FLine = rot(angle, axis, anchor3(center))

    /**
     * Uniform scale around the line center by default.
     */
    fun scale(scale: Number): FLine = scale(scale, center())

    fun scale(scale: Number, center: Vec2): FLine = scale(Vec2(scale.toFloat(), scale.toFloat()), center)
    fun scale(scale: Number, center: Vec3): FLine = transform { Vec3(center).add(Vec3(it).sub(center).mul(scale.toFloat())) }
    fun scale(scale: Number, center: ListAnchor): FLine = scale(scale, anchor3(center))
    fun scale(scale: Number, center: FractionAlongAnchor): FLine = scale(scale, anchor3(center))

    /**
     * XY component scale around [center], preserving Z.
     */
    fun scale(scale: Vec2, center: Vec2 = anchor2(ListAnchor.Center)): FLine {
        return transform {
            Vec3(
                center.x + (it.x - center.x) * scale.x,
                center.y + (it.y - center.y) * scale.y,
                it.z
            )
        }
    }

    fun scale(scale: Vec2, center: ListAnchor): FLine = scale(scale, anchor2(center))
    fun scale(scale: Vec2, center: FractionAlongAnchor): FLine = scale(scale, anchor2(center))

    /**
     * XYZ component scale around [center].
     */
    fun scale(scale: Vec3, center: Vec3 = center()): FLine {
        return transform {
            Vec3(
                center.x + (it.x - center.x) * scale.x,
                center.y + (it.y - center.y) * scale.y,
                center.z + (it.z - center.z) * scale.z
            )
        }
    }

    fun scale(scale: Vec3, center: ListAnchor): FLine = scale(scale, anchor3(center))
    fun scale(scale: Vec3, center: FractionAlongAnchor): FLine = scale(scale, anchor3(center))

    /**
     * Translate this line by [n].
     */
    operator fun plus(n: Vec3): FLine {
        return transform { it + n }
    }

    /**
     * Translate this line by `-n`.
     */
    operator fun minus(n: Vec3): FLine {
        return transform { it - n }
    }

    /**
     * Scale this line component-wise by [n].
     */
    operator fun times(n: Vec3): FLine {
        return transform { it * n }
    }

    /**
     * Rotate this line by [n].
     */
    operator fun times(n: Quat): FLine {
        return transform { it * n }
    }

    /**
     * Uniformly scale this line by [n].
     */
    operator fun times(n: Number): FLine {
        return transform { it * n }
    }


    /**
     * 2D translation overload of [plus].
     */
    operator fun plus(n: Vec2) = this + n.v3

    /**
     * 2D translation overload of [minus].
     */
    operator fun minus(n: Vec2) = this - n.v3

    /**
     * 2D scale overload of [times].
     */
    operator fun times(n: Vec2) = this * n.v3


    /**
     * Append a copy of [other] to this line.
     */
    fun copyTo(other: Node) {
        rev++
        nodes.add(other.copy())
    }

    /**
     * Deep copy of this line and its properties.
     */
    fun copy() = this.transform { Vec3(it) }

    fun center(): Vector3f {
        val (min, max) = this.fast3DBounds()
        return min.lerp(max, 0.5f)
    }

    fun midPoint(): Vector3f {
        val c = cursor()
        return c.setD(c.lengthD() / 2).position()
    }

    fun closeMidPoint() = cursor(nodes.mapNotNull { it.storage.getOrNull(0) }.center()).position()

    /**
     * Copy this line and apply a mutating block to the clone.
     */
    fun copy(block: FLine.() -> Unit) = this.copy().apply(block)

    /**
     * Create a cursor for querying length, tangents, normals, and positions.
     *
     * @param tol Flatness tolerance used by the cursor.
     */
    fun cursor(tol: Float = 0.01f): FLinesAndJavaShapes.Cursor = FLinesAndJavaShapes.Cursor(this, tol = tol)

    /**
     * Create a cursor positioned at the closest point to [closestTo].
     */
    fun cursor(closestTo: Vec3): Cursor {
        val c = this.cursor()
        val t = FLinesAndJavaShapes.closestT(this, closestTo)
        c.setT(t)
        return c
    }

    /**
     * Sample evenly along arc length.
     *
     * @param steps Number of sample points (includes both ends).
     */
    fun sampleByNumber(steps: Int): List<Vec3> {
        val c = cursor()
        val r = ArrayList<Vec3>()
        for (i in 0 until steps) {
            c.setD(c.lengthD() * i / (steps - 1f))
            r.add(c.position())
        }
        return r
    }

    /**
     * Remove the last [n] nodes from this line.
     */
    fun drop(n: Int = 1): FLine {
        if (nodes.size == 0) return this
        nodes.subList(Math.max(0, nodes.size - n), nodes.size).clear()
        return this
    }

    /**
     * Sample points by stepping along the curve in fixed increments.
     *
     * The start point is included; the end point is only included when the length is
     * an exact multiple of [distance].
     */
    fun sampleByDistance(distance: Float): List<Vec3> {
        val c = cursor()
        val r = ArrayList<Vec3>()
        val tol = min(0.1f, 0.1f * distance)
        while (c.d <= c.lengthD()) {
            r.add(c.position())
            val was = c.d
            c.setD(c.d + distance)
            val now = c.d
            if (now - was < abs(distance - tol) && abs(now - c.lengthD()) < tol) break
        }
        return r
    }

    override fun setPropertyRaw(name: String, value: Any, doResync: Boolean) {
        rev++
        properties.put(name, value)
    }

    override fun getPropertyRaw(name: String, default: Any?): Any? = properties.get(name)

    override fun toString(): String {
        val bbox = runCatching { fast2DBounds() }.getOrNull()
        return prettySummary(
            "FLine",
            "nodes" to nodes.size,
            "pieces" to pieces().size,
            "bounds" to bbox?.let {
                "[${prettyNumber(it.x)} , ${prettyNumber(it.y)} , ${prettyNumber(it.w)} x ${
                    prettyNumber(
                        it.h
                    )
                }]"
            },
            "properties" to properties.keys.take(4)
        )
    }

    /**
     * Split this line into subpaths at [MoveTo] boundaries.
     */
    fun pieces(): List<FLine> {
        val ff = mutableListOf<FLine>()

        var d = FLine()
        for (i in this.nodes.indices) {
            if (this.nodes[i] is MoveTo) {
                d = FLine()
                ff.add(d)
            }

            d.nodes.add(this.nodes[i].copy())
        }
        return ff
    }

    /**
     * Fast axis-aligned 2D bounds based on node control points.
     */
    fun fast2DBounds(): Rect {
        var r: Rect? = null
        nodes.forEach {
            if (r == null) r = Rect(it.to.x.d, it.to.y.d, 0.0, 0.0)
            else {
                it.storage.forEach { X ->
                    val mix = Math.min(r!!.x.d, X.x.d)
                    val miy = Math.min(r!!.y.d, X.y.d)
                    val mxx = Math.max(r!!.x.d + r!!.w, X.x.d)
                    val mxy = Math.max(r!!.y.d + r!!.h, X.y.d)
                    r = Rect(mix, miy, mxx - mix, mxy - miy)
                }
            }
        }
        return r!!
    }

    fun fast3DBounds(): Pair<Vec3, Vec3> {
        var q = Vec3(
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY
        ) to Vec3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        nodes.forEach {
            it.storage.forEach { X ->
                q.first.min(X)
                q.second.max(X)
            }
        }
        return q
    }

    /**
     * Build commands from a compact format string and parameter list.
     *
     * Format uses `m`, `l`, `c`, `z` for move/line/cubic/close. Parentheses group, and a
     * trailing `+` repeats the preceding command/group greedily.
     */
    fun data(format: String, parameters: List<Any>): FLine {
        val points = parametersToPoints(parameters) // -> MutableList<Vec3>
        val ast = Parser(format).parse()
        val exec = Executor(this, points)
        exec.exec(ast)

        // If you'd prefer to silently ignore leftovers, delete this check.
        if (exec.remainingPoints() != 0) {
            throw IllegalArgumentException(
                "Unused parameters: ${exec.remainingPoints()} point(s) left after applying format '$format'. " + "Consider adding a trailing '+' group if you intend to consume everything."
            )
        }
        return this
    }

    /**
     * Variadic form of [data]. Lets you write `f.data("m l l z", 0,0, 1,0, 1,1)` instead of
     * building a `List<Any>` by hand. Parameters may be a mix of [Number] runs, [Vec2] and [Vec3];
     * see [data] for the format grammar (`m` move, `l` line, `c` cubic, `s` smooth, `z` close,
     * `( )` groups, trailing `+` greedy repeat).
     */
    fun data(format: String, vararg parameters: Any): FLine = data(format, parameters.asList())


    /**
     * Walk the path into line segments. Callback receives:
     *  - position: current point
     *  - instr: drawing-instruction index for this point. A negative value indicates the start of a subpath
     *           (equivalent to the old break flag); otherwise it's interpolated between nodes (e.g. along cubics).
     */
    fun uniform_linearize(samplesPerCubic: Int = 100, a: (Vec3, Float) -> Unit) {
        val storage = vec(0, 0, 0)
        val at = vec(0, 0, 0)
        var ok = false
        var breakNext = true;
        var lastOpen = vec(0, 0, 0)
        var lastOpenOk = false
        var instrIdx = -1
        nodes.forEach {
            instrIdx++
            when (it) {
                is MoveTo -> {
                    storage.set(it.to)
                    ok = true;
                    breakNext = true;
                    lastOpen.set(it.to)
                    lastOpenOk = true;
                }

                is LineTo -> {
                    if (!ok) {
                        storage.set(it.to)
                        breakNext = true
                        ok = true;
                        lastOpen.set(it.to)
                        lastOpenOk = true;
                    } else {
                        if (breakNext) {
                            a(storage, -(instrIdx - 1 + 1).toFloat()) // ensure strictly negative (no -0)
                        }
                        storage.set(it.to)
                        a(storage, instrIdx.toFloat())
                        ok = true
                        breakNext = false
                    }
                }

                is CubicTo -> {
                    if (!ok) {
                        storage.set(it.to)
                        breakNext = true
                        ok = true;
                        lastOpen.set(it.to)
                        lastOpenOk = true;
                    } else {
                        if (breakNext) {
                            a(storage, -(instrIdx + 1 - 1).toFloat()) // ensure strictly negative (no -0)
                            breakNext = false
                        }
                        at.set(storage)
                        uniform_linearize_cubic(
                            dest = a,
                            a = at,
                            c1 = it.c1,
                            c2 = it.c2,
                            b = it.to,
                            samplesPerCubic = samplesPerCubic,
                            storage = storage,
                            fromInstr = (instrIdx - 1).d,
                            toInstr = instrIdx.d
                        )
                    }
                }

                is Close -> {
                    if (lastOpenOk && ok) {
                        a(lastOpen, instrIdx.toFloat())
                        storage.set(lastOpen)
                    }
                }
            }
        }
    }

    /**
     * Sample a cubic Bezier segment and feed points to [dest].
     *
     * The instruction value is interpolated between [fromInstr] and [toInstr].
     */
    fun uniform_linearize_cubic(
        dest: (Vec3, Float) -> Unit,
        a: Vec3,
        c1: Vec3,
        c2: Vec3,
        b: Vec3,
        samplesPerCubic: Int = 100,
        storage: Vec3,
        fromInstr: Double,
        toInstr: Double
    ) {
        samplesPerCubic {
            val alpha = (it + 1) / samplesPerCubic.d

            val oma = 1 - alpha
            val oma2 = oma * oma
            val oma3 = oma2 * oma
            val alpha2 = alpha * alpha
            val alpha3 = alpha2 * alpha

            storage.x = ((a.x * oma3) + (3 * c1.x * alpha * oma2) + (3 * c2.x * alpha2 * oma) + (b.x * alpha3)).f
            storage.y = ((a.y * oma3) + (3 * c1.y * alpha * oma2) + (3 * c2.y * alpha2 * oma) + (b.y * alpha3)).f
            storage.z = ((a.z * oma3) + (3 * c1.z * alpha * oma2) + (3 * c2.z * alpha2 * oma) + (b.z * alpha3)).f
            val instr = ((1 - alpha) * fromInstr) + (alpha * toInstr)
            dest(storage, instr.f)
        }
    }

    @JvmName("invoke_1")
    fun invoke(f: FLine.() -> FLine): FLine {
        return f.invoke(this)
    }

    @JvmName("invoke_2")
    fun invoke(f: FLine.() -> Unit): FLine {
        f.invoke(this)
        return this;
    }

    /**
     * Tessellate filled subpaths into triangle indices.
     *
     * @param meshAcceptor Called with a packed vertex array (x,y,z) and index list.
     */
    fun tessellate(samplesPerCubic: Int = 100, meshAcceptor: (FloatArray, List<Int>) -> Unit) {
        var current = mutableListOf<Vec3>()
        val rings = mutableListOf<List<Vec3>>()
        val eps = 1e-5f

        fun same2D(a: Vec3, b: Vec3): Boolean {
            return abs(a.x - b.x) <= eps && abs(a.y - b.y) <= eps
        }

        fun sanitize(points: MutableList<Vec3>): List<Vec3> {
            if (points.isEmpty()) return emptyList()
            val out = ArrayList<Vec3>(points.size)
            points.forEach { p ->
                if (out.isEmpty() || !same2D(out[out.lastIndex], p)) {
                    out.add(Vec3(p))
                }
            }
            if (out.size >= 2 && same2D(out[0], out[out.lastIndex])) {
                out.removeAt(out.lastIndex)
            }
            return out
        }

        fun flush() {
            val cleaned = sanitize(current)
            if (cleaned.size >= 3) {
                rings.add(cleaned)
            }
            current = mutableListOf()
        }

        uniform_linearize(samplesPerCubic) { at, instr ->
            if (instr < 0) {
                flush()
            }
            current.add(Vec3(at))
        }
        flush()

        if (rings.isEmpty()) return

        data class BBox(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)
        data class RingInfo(val points: List<Vec3>, val area: Float, val bbox: BBox)

        fun bboxOf(points: List<Vec3>): BBox {
            var minX = points[0].x
            var minY = points[0].y
            var maxX = points[0].x
            var maxY = points[0].y
            for (i in 1 until points.size) {
                val p = points[i]
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x)
                maxY = max(maxY, p.y)
            }
            return BBox(minX, minY, maxX, maxY)
        }

        fun area2D(points: List<Vec3>): Float {
            var sum = 0f
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                sum += (a.x * b.y) - (b.x * a.y)
            }
            return 0.5f * sum
        }

        fun bboxContains(b: BBox, p: Vec3): Boolean {
            return p.x >= b.minX && p.x <= b.maxX && p.y >= b.minY && p.y <= b.maxY
        }

        fun pointInPolygon(points: List<Vec3>, p: Vec3): Boolean {
            var inside = false
            var j = points.size - 1
            for (i in points.indices) {
                val xi = points[i].x
                val yi = points[i].y
                val xj = points[j].x
                val yj = points[j].y
                val intersect = ((yi > p.y) != (yj > p.y)) &&
                        (p.x < (xj - xi) * (p.y - yi) / ((yj - yi) + 1e-12f) + xi)
                if (intersect) inside = !inside
                j = i
            }
            return inside
        }

        val infos = rings.map { RingInfo(it, area2D(it), bboxOf(it)) }
        val parent = IntArray(infos.size) { -1 }

        for (i in infos.indices) {
            val test = infos[i].points[0]
            var best = -1
            var bestArea = Float.POSITIVE_INFINITY
            for (j in infos.indices) {
                if (i == j) continue
                if (!bboxContains(infos[j].bbox, test)) continue
                if (!pointInPolygon(infos[j].points, test)) continue
                val area = abs(infos[j].area)
                if (area < bestArea) {
                    bestArea = area
                    best = j
                }
            }
            parent[i] = best
        }

        val depth = IntArray(infos.size)
        for (i in infos.indices) {
            var d = 0
            var p = parent[i]
            while (p != -1) {
                d++
                p = parent[p]
            }
            depth[i] = d
        }

        val groups = mutableListOf<List<Int>>()
        for (i in infos.indices) {
            if (depth[i] % 2 == 0) {
                val group = mutableListOf<Int>()
                group.add(i)
                for (j in infos.indices) {
                    if (parent[j] == i && depth[j] % 2 == 1) {
                        group.add(j)
                    }
                }
                groups.add(group)
            }
        }

        for (group in groups) {
            val verts = ArrayList<Float>()
            val holes = ArrayList<Int>()
            var vertCount = 0

            for ((idx, ringIndex) in group.withIndex()) {
                if (idx > 0) holes.add(vertCount)
                val pts = infos[ringIndex].points
                for (p in pts) {
                    verts.add(p.x)
                    verts.add(p.y)
                    verts.add(p.z)
                }
                vertCount += pts.size
            }

            if (vertCount < 3) continue
            val holeIndices = if (holes.isEmpty()) null else holes.toIntArray()
            val da = verts.toFloatArray()
            val c = FloatEarcut.earcut(da, holeIndices, 3)
            if (c.isNotEmpty()) {
                meshAcceptor(da, c)
            }
        }
    }

    operator fun plusAssign(other: FLine) {
        other.nodes.forEach {
            this.copyTo(it)
        }
    }

    /**
     * Append a point as a [lineTo] (or a [moveTo] if this line is empty): `f += vec(1, 2)`.
     */
    operator fun plusAssign(p: Vec3) {
        if (nodes.isEmpty()) moveTo(p) else lineTo(p)
    }

    /**
     * 2D overload of [plusAssign].
     */
    operator fun plusAssign(p: Vec2) {
        if (nodes.isEmpty()) moveTo(p) else lineTo(p)
    }

    // --- builder-scoped pen operators -------------------------------------------
    // These are members, so they are only in scope when an FLine is the receiver —
    // i.e. inside `fline { }`, `copy { }`, or `invoke { }`. `-v` draws (lineTo), `+v`
    // lifts the pen and starts a new subpath (moveTo). A leading `-` on an empty line
    // auto-promotes to a moveTo, so `-` alone suffices for a single open path.

    /** Draw a segment to [this] point (the dash *looks* like a segment). Starts the path if empty. */
    operator fun Vec3.unaryMinus(): FLine = if (nodes.isEmpty()) moveTo(this) else lineTo(this)

    /** 2D overload of the draw operator. */
    operator fun Vec2.unaryMinus(): FLine = if (nodes.isEmpty()) moveTo(this) else lineTo(this)

    /** Lift the pen and start a new subpath at [this] point. */
    operator fun Vec3.unaryPlus(): FLine = moveTo(this)

    /** 2D overload of the pen-up operator. */
    operator fun Vec2.unaryPlus(): FLine = moveTo(this)

    /**
     * One Vec4 color per drawing instruction. Resolution order:
     *  node.properties["color"] if Vec4 → previous color → fline.properties["color"] if Vec4 → default vec4(0.8,0.8,0.8,0.5)
     */
    fun colorsPer(): FloatArray {
        val result = FloatArray(nodes.size * 4)
        var current = (properties["color"] as? Vec4) ?: vec(0.8, 0.8, 0.8, 0.5)
        nodes.forEachIndexed { index, n ->
            (n.properties["color"] as? Vec4)?.let { current = it }
            result[index * 4 + 0] = current.x
            result[index * 4 + 1] = current.y
            result[index * 4 + 2] = current.z
            result[index * 4 + 3] = current.w
        }
        return result
    }

    /**
     * One point size (float) per drawing instruction. Resolution order:
     *  node.properties["pointSize"] if Number → previous size → fline.properties["pointSize"] if Number → default 1.0f
     */
    fun pointSizePer(): FloatArray {
        val result = FloatArray(nodes.size)
        var current = (properties["pointSize"] as? Number)?.toFloat() ?: 1.0f
        nodes.forEachIndexed { idx, n ->
            (n.properties["pointSize"] as? Number)?.toFloat()?.let { current = it }
            result[idx] = current
        }
        return result
    }


    /**
     * Remove all of the contents of this line
     */
    fun clear() {
        this.nodes.clear()
        rev++
    }

    companion object {
        /**
         * Collection-literal constructor. The first point becomes a [moveTo], the rest [lineTo] —
         * a polyline. Kotlin resolves a `[...]` literal in a typed context to this `operator fun of`
         * (only one `of` overload may be `vararg`); it also works as an ordinary `FLine.of(...)` call.
         *
         * Elements may be any mix of [Number] runs, [Vec2] and [Vec3], packed into points by the
         * same rules as [data]'s parameter handling:
         * ```
         * val tri:    FLine = [vec(0, 0), vec(1, 0), vec(0.5, 1)]
         * val square: FLine = [0, 0, 1, 0, 1, 1, 0, 1]
         * ```
         */
        operator fun of(vararg params: Any): FLine = FLine().apply {
            parametersToPoints(params.asList()).forEachIndexed { i, p -> if (i == 0) moveTo(p) else lineTo(p) }
        }
    }

}

private fun prettyVec3(v: Vec3): String = "(${prettyNumber(v.x)}, ${prettyNumber(v.y)}, ${prettyNumber(v.z)})"


private fun FLine.parametersToPoints(params: List<Any>): MutableList<Vec3> {
    val out = mutableListOf<Vec3>()
    var i = 0
    while (i < params.size) {
        when (val p = params[i]) {
            is Vec3 -> {
                out += p; i++
            }

            is Vec2 -> {
                out += Vec3(p.x, p.y, 0f); i++
            }

            is Number -> {
                // Collect a contiguous run of numbers and split into points.
                val nums = mutableListOf<Float>()
                var j = i
                while (j < params.size && params[j] is Number) {
                    nums += (params[j] as Number).toFloat()
                    j++
                }
                var k = 0
                while (k < nums.size) {
                    val left = nums.size - k
                    when {
                        // Prefer (x,y,z) triples when the run length is odd to avoid a single leftover.
                        left >= 3 && left % 2 == 1 -> {
                            out += Vec3(nums[k], nums[k + 1], nums[k + 2]); k += 3
                        }

                        left >= 2 -> {
                            out += Vec3(nums[k], nums[k + 1], 0f); k += 2
                        }

                        else -> {
                            throw IllegalArgumentException(
                                "Found a run of scalar numbers with a single leftover value (${nums[k]}). " + "Wrap as Vec2/Vec3 or add a trailing 0 for z."
                            )
                        }
                    }
                }
                i = j
            }

            else -> {
                throw IllegalArgumentException(
                    "Unsupported parameter type at index $i: ${p::class.qualifiedName}. " + "Expected Number, Vec2, or Vec3."
                )
            }
        }
    }
    return out
}

// --- tiny parser / AST -------------------------------------------------------

private sealed interface Node
private data class Cmd(val kind: Char) : Node // 'm','l','c','z'
private data class Seq(val items: List<Node>) : Node
private data class Rep(val body: Node) : Node // postfix '+'

private class Parser(private val s: String) {
    private var i = 0
    fun parse(): Node {
        val n = parseSeq()
        skip()
        if (i != s.length) error("Unexpected trailing input at ${here()}")
        return n
    }

    private fun parseSeq(stop: Char? = null): Node {
        val items = mutableListOf<Node>()
        while (true) {
            skip()
            if (i >= s.length) break
            if (stop != null && s[i] == stop) {
                i++; break
            }
            val atom = when (s[i]) {
                'm', 'l', 'c', 'z', 's' -> {
                    i++; Cmd(s[i - 1])
                }

                '(' -> {
                    i++; parseSeq(')')
                }

                else -> error("Unexpected '${s[i]}' at ${here()}; expected m,l,c,s, z or '('")
            }
            skip()
            val node = if (i < s.length && s[i] == '+') {
                i++; Rep(atom)
            } else atom
            items += node
        }
        return when (items.size) {
            0 -> Seq(emptyList())
            1 -> items[0]
            else -> Seq(items)
        }
    }

    private fun skip() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun here(): String = "pos $i in \"$s\""
}

// --- executor with greedy-but-safe '+' handling ------------------------------

private class Executor(private val f: FLine, private val pts: MutableList<Vec3>) {
    private var pos = 0

    fun remainingPoints(): Int = pts.size - pos

    fun exec(n: Node) {
        when (n) {
            is Cmd -> execCmd(n.kind)
            is Seq -> execSeq(n)
            is Rep -> execRep(n, suffixMin = 0) // top-level '+' has no suffix
        }
    }

    private fun execSeq(seq: Seq) {
        val items = seq.items
        // Precompute minimal suffix points needed from each position.
        val suffixMin = IntArray(items.size + 1)
        suffixMin[items.size] = 0
        for (i in items.size - 1 downTo 0) {
            suffixMin[i] = minPoints(items[i]) + suffixMin[i + 1]
        }
        for (i in items.indices) {
            when (val it = items[i]) {
                is Rep -> execRep(it, suffixMin = suffixMin[i + 1])
                else -> exec(it)
            }
        }
    }

    private fun execRep(rep: Rep, suffixMin: Int) {
        val minBody = minPoints(rep.body)
        if (minBody == 0) return // avoid infinite loop on e.g. 'z+'
        while (remainingPoints() - suffixMin >= minBody) {
            val before = pos
            exec(rep.body)
            if (pos == before) break // paranoia guard
        }
    }

    private fun execCmd(k: Char) {
        when (k) {
            'm' -> {
                ensure(1, "moveTo")
                val a = take()
                f.moveTo(a)          // uses your Vec3 overload
            }

            'l' -> {
                ensure(1, "lineTo")
                val a = take()
                f.lineTo(a)          // uses your Vec3 overload
            }

            's' -> {
                ensure(1, "smoothTo")
                val a = take()
                f.smoothTo(a)          // uses your Vec3 overload
            }

            'c' -> {
                ensure(3, "cubicTo")
                val a = take();
                val b = take();
                val c = take()
                f.cubicTo(a, b, c)   // uses your Vec3 overload
            }

            'z' -> f.close()
            else -> error("Unknown command: $k")
        }
    }

    private fun minPoints(n: Node): Int = when (n) {
        is Cmd -> when (n.kind) {
            'm' -> 1; 'l' -> 1; 's' -> 1; 'c' -> 3; 'z' -> 0; else -> 0
        }

        is Seq -> n.items.sumOf { minPoints(it) }
        is Rep -> 0 // zero-or-more
    }

    private fun take(): Vec3 {
        if (pos >= pts.size) throw IllegalArgumentException("Not enough points for the requested command(s).")
        return pts[pos++]
    }

    private fun ensure(n: Int, what: String) {
        if (remainingPoints() < n) {
            throw IllegalArgumentException("Need $n point(s) for $what, but only ${remainingPoints()} left.")
        }
    }
}
