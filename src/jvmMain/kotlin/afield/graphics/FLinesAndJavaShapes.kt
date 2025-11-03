package field.graphics

import afield.Rect
import afield.graphics.FLine
import afield.graphics.PathFlattener
import afield.stdlib.*
import org.apache.commons.math4.legacy.optim.MaxEval
import org.apache.commons.math4.legacy.optim.nonlinear.scalar.GoalType
import org.apache.commons.math4.legacy.optim.univariate.BrentOptimizer
import org.apache.commons.math4.legacy.optim.univariate.SearchInterval
import org.apache.commons.math4.legacy.optim.univariate.UnivariateObjectiveFunction
import org.apache.commons.math4.legacy.optim.univariate.UnivariatePointValuePair
import org.joml.*
import java.awt.BasicStroke
import java.awt.Shape
import java.awt.geom.*
import java.lang.Math
import java.util.*
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import kotlin.math.*

/**
 * Utility classes for manipulating and traversing FLines.
 *
 *
 * In addition to the tools that exploit the ability to round trip the geometry (but not the attributes) of 2d FLines to java.awt.geom classes (and thus gain quite a bit of 2d computational geometry
 * for no cost), the star here is the Cursor class which solves some key problems in traversing FLines by equal units of distance (rather than node by node). *
 */
class FLinesAndJavaShapes {
    /**
     * returns the c1, c2 control points for a cubic segement to go through p0@0, p1@u, p2@v and p3@ t=1.0
     */
    fun fromFourPoints(
        p0: Vec3, u: Float, p1: Vec3, v: Float, p2: Vec3, p3: Vec3
    ): Pair<Vec3, Vec3>? {
        var a = 0.0f
        var b = 0.0f
        var c = 0.0f
        var d = 0.0f
        var det = 0.0f
        val q1: Vec3 = Vec3()
        val q2: Vec3 = Vec3()

        if ((u <= 0.0f) || (u >= 1.0f) || (v <= 0.0f) || (v >= 1.0f) || (u >= v)) return null

        a = (3 * (1 - u) * (1 - u) * u)
        b = (3 * (1 - u) * u * u)
        c = (3 * (1 - v) * (1 - v) * v)
        d = (3 * (1 - v) * v * v)
        det = a * d - b * c
        if (det == 0f) return null

        val r: Pair<Vec3, Vec3> = Pair(Vec3(), Vec3())

        q1.x = p1.x - ((1 - u) * (1 - u) * (1 - u) * p0.x + u * u * u * p3.x)
        q1.y = p1.y - ((1 - u) * (1 - u) * (1 - u) * p0.y + u * u * u * p3.y)
        q1.z = p1.z - ((1 - u) * (1 - u) * (1 - u) * p0.z + u * u * u * p3.z)

        q2.x = p2.x - ((1 - v) * (1 - v) * (1 - v) * p0.x + v * v * v * p3.x)
        q2.y = p2.y - ((1 - v) * (1 - v) * (1 - v) * p0.y + v * v * v * p3.y)
        q2.z = p2.z - ((1 - v) * (1 - v) * (1 - v) * p0.z + v * v * v * p3.z)

        r.first.x = d * q1.x - b * q2.x
        r.first.y = d * q1.y - b * q2.y
        r.first.z = d * q1.z - b * q2.z
        r.first.x /= det
        r.first.y /= det
        r.first.z /= det

        r.second.x = (-c) * q1.x + a * q2.x
        r.second.y = (-c) * q1.y + a * q2.y
        r.second.z = (-c) * q1.z + a * q2.z
        r.second.x /= det
        r.second.y /= det
        r.second.z /= det

        return r
    }


    /**
     * segments this along .moveTo's
     */
    fun segment(f: FLine): List<FLine> {
        val r = mutableListOf<FLine>()
        var at: FLine? = null
        f.nodes.forEach {
            when (it) {
                is FLine.MoveTo -> {
                    at = FLine()
                    r.add(at!!)
                    at!!.nodes.add(it.copy())
                }

                is FLine.LineTo -> {
                    at!!.nodes.add(it.copy())

                }

                is FLine.CubicTo -> {
                    at!!.nodes.add(it.copy())

                }

                is FLine.Close -> {
                    at!!.nodes.add(it.copy())
                }
            }
        }
        return r
    }

    /**
     * A list of low curvature positions out of a FLine
     */
    fun positions(f: FLine, flatness: Float): List<Vec3> {
        val c = Cursor(f, flatness)
        val m: List<PathFlattener.Mapping> = c.pathFlattener.mappings

        return m.mapNotNull { it.start }
    }

    /**
     * A class for navigating FLines either by distance "D" or by node "T".
     */
    class Cursor(on: FLine, private val tol: Float) {
        protected val on: FLine = on

        // lazy inited
        protected var p: PathFlattener? = null

        protected var index: Int = 0
        protected var alpha: Float = 0.0f

        fun hasNext(): Boolean {
            return index < on.nodes.size - 1
        }

        /**
         * returns whether the current segment is a .lineTo
         */
        fun segmentIsLinear(): Boolean {
            return on.nodes.get(clamp(index + 1)) is FLine.LineTo
        }

        /**
         * returns whether the current segment is a .cubicTo
         */
        fun segmentIsCubic(): Boolean {
            return on.nodes.get(clamp(index + 1)) is FLine.CubicTo
        }

        /**
         * returns whether the current segment is a .moveTo
         */
        fun segmentIsMove(): Boolean {
            return on.nodes.get(clamp(index + 1)) is FLine.MoveTo
        }

        fun next(): Array<Vec3> {
            try {
                return if (segmentIsCubic()) arrayOf<Vec3>(
                    Vec3(on.nodes[clamp(index)].to),
                    Vec3((on.nodes[clamp(index + 1)] as FLine.CubicTo).c1),
                    Vec3(
                        (on.nodes[clamp(index + 1)] as FLine.CubicTo).c2
                    ),
                    Vec3((on.nodes[clamp(index + 1)] as FLine.CubicTo).to)
                )
                else if (segmentIsLinear()) arrayOf<Vec3>(
                    Vec3(on.nodes[clamp(index)].to),
                    Vec3(on.nodes[clamp(index + 1)].to)
                )
                else if (segmentIsMove()) arrayOf<Vec3>(Vec3(on.nodes[clamp(index)].to))
                else throw java.lang.IllegalStateException(" segment not implemented ")
            } finally {
                index++
            }
        }


        /**
         * resets the cursor to the start of its line
         */
        fun reset() {
            index = 0
            alpha = 0.0f
        }

        /**
         * returns the position of the cursor
         */
        fun position(): Vec3 {
            return if (segmentIsMove()) {
                if (alpha < 0.5) Vec3(on.nodes.get(clamp(index)).to)
                else Vec3(on.nodes.get(clamp(index + 1)).to)
            } else if (segmentIsLinear()) {
                on.nodes.get(clamp(index)).to.lerp(on.nodes.get(clamp(index + 1)).to, alpha.toFloat(), Vec3())
            } else if (segmentIsCubic()) {
                evaluateCubicFrame(
                    on.nodes.get(clamp(index)).to,
                    (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c1,
                    (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c2,
                    (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).to,
                    alpha,
                    Vec3()
                )
            } else throw IllegalStateException(" segment not implemented ")
        }


        fun tangentForward(): Vec3? {
            if (index == on.nodes.size - 1) return null

            return if (segmentIsMove()) {
                null
            } else if (segmentIsLinear()) {
                on.nodes.get(clamp(index + 1)).to - on.nodes.get(clamp(index)).to
            } else if (segmentIsCubic()) {
                evaluateDCubicFrame(
                    on.nodes.get(clamp(index)).to,
                    (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c1,
                    (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c2,
                    (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).to,
                    alpha,
                    Vec3()
                )
            } else throw IllegalStateException(" segment not implemented ")
        }


        fun tangentBackward(): Vec3? {
            if (alpha != 0.0f) return tangentForward()
            if (index == 0) return null

            index--
            alpha = 1.0f
            try {
                return tangentForward()
            } finally {
                index++
                alpha = 0.0f
            }
        }


        fun accelerationForward(): Vec3? {
            return if (segmentIsMove()) {
                null
            } else if (segmentIsLinear()) {
                Vec3()
            } else if (segmentIsCubic()) {
                evaluateDDCubicFrame(
                    on.nodes.get(clamp(index)).to,
                    (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c1,
                    (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c2,
                    (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).to,
                    alpha,
                    Vec3()
                )
            } else throw IllegalStateException(" segment not implemented ")
        }


        fun accelerationBackward(): Vec3? {
            if (alpha != 0.0f) return accelerationForward()
            if (index == 0) return null

            index--
            alpha = 1.0f
            try {
                return accelerationForward()
            } finally {
                index++
                alpha = 0.0f
            }
        }


        fun binormalForward(): Vec3? {
            val a: Vec3? = accelerationForward()
            val t: Vec3? = tangentForward()

            if (a == null || t == null) return null

            return t.cross(a, Vec3())
        }


        fun binormalBackward(): Vec3? {
            val a: Vec3? = accelerationBackward()
            val t: Vec3? = tangentBackward()

            if (a == null || t == null) return null

            return t.cross(a, Vec3())
        }

        /**
         * returns the 'binormal' of the line, that is a vector that is perpendicular to both the tangent at this point and the acceleration. This is never interesting in the 2d case.
         */
        fun binormal(): Vec3? {
            val a: Vec3? = acceleration()
            val t: Vec3? = tangent()

            if (a == null || t == null) return null

            return t.cross(a, Vec3())
        }


        fun normalForward(): Vec3? {
            val a: Vec3? = binormalForward()
            val t: Vec3? = tangentForward()

            if (a == null || t == null) return null

            return t.cross(a, Vec3())
        }


        fun normalBackward(): Vec3? {
            val a: Vec3? = binormalBackward()
            val t: Vec3? = tangentBackward()

            if (a == null || t == null) return null

            return t.cross(a, Vec3())
        }

        /**
         * returns the normal at this point in the line. Note that this normal is parallel and in the direction of the acceleration. This means that, in the 2d case, it changes 'side' of
         * the line as the line changes curvature (the normal is always on the side that the line is curving towards). For a simpler 2d definition of normal, use `normal2()`
         */
        fun normal(): Vec3? {
            val a: Vec3? = binormal()
            val t: Vec3? = tangent()

            if (a == null || t == null) return null

            val n = t.cross(a, Vec3())
            if (n.length() == 0.0f) return n
            n.normalize().mul(t.length())
            return n
        }


        /**
         * returns the normal at this point in the line. This is always perpendicular to the line at the current point.
         */
        fun normal2(): Vec2? {
            val t: Vec3? = tangent()
            val t2: Vec2? = if (t == null) null else Vec2(t.y, -t.x)

            if (t2 == null) System.err.println(("NORMAL2 failed :" + on.nodes).toString() + " / " + this.alpha)

            return t2
        }

        /**
         * this is a vector along the normal of the right length. i.e, you can draw this circle and it will be tangent at this point. This doesn't exist at if the current point is on a
         * straight line.
         */
        fun radiusOfCurvature(): Vec3? {
            if (segmentIsCubic()) {
                val p0: Vec3 = on.nodes.get(clamp(index)).to
                val p1: Vec3 = (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c1
                val p2: Vec3 = (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c2
                val p3: Vec3 = (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).to

                val c = CubicSegment3(p0, p1, p2, p3)
                return Vec3(normal()).normalize().mul(c.R(alpha).toFloat())
            } else return null
        }


        fun currentSegment(): CubicSegment3? {
            if (segmentIsCubic()) {
                val p0: Vec3 = on.nodes.get(clamp(index)).to
                val p1: Vec3 = (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c1
                val p2: Vec3 = (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c2
                val p3: Vec3 = (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).to

                val c = CubicSegment3(p0, p1, p2, p3)
                return c
            } else return null
        }

        /**
         * returns the 'acceleration' vector at this point in the line. This is the direction that the 'velocity' of the line is changing.
         */
        fun acceleration(): Vec3? {
            if (alpha != 0.0f) return accelerationForward()
            var t1: Vec3? = accelerationBackward()
            var t2: Vec3? = accelerationForward()

            if (t1 != null && t1.isNaN()) t1 = null
            if (t2 != null && t2.isNaN()) t2 = null

            if (t1 == null && t2 == null) return null

            val t: Vec3 = Vec3(if (t1 == null) t2 else t1).add(if (t2 == null) t1 else t2).mul(0.5f)
            return if (t.isNaN()) null else t
        }

        /**
         * returns the 'tangent' vecotr at this point in the line. This is the direction that the line is heading in.
         */
        fun tangent(): Vec3? {
            if (alpha != 0.0f) return tangentForward()
            var t1: Vec3? = tangentBackward()
            var t2: Vec3? = tangentForward()

            if (t1 != null && t1.isNaN()) t1 = null
            if (t2 != null && t2.isNaN()) t2 = null

            if (t1 == null && t2 == null) return null

            val t: Vec3 = Vec3(if (t1 == null) t2 else t1).add(if (t2 == null) t1 else t2).mul(0.5f)
            return if (t.isNaN()) null else t
        }

        /**
         * returns a list of 't' positions where dx, dy, or dz is zero. These points only exist inside cubic segments.
         */
        fun extremalInnerPoints(): List<Float> {
            val q: MutableList<Float> = ArrayList()
            for (i in 1 until on.nodes.size) {
                if (on.nodes.get(i) is FLine.CubicTo) {
                    val fi = i
                    val c: CubicSegment3 = CubicSegment3(on, i)
                    val z1 = c.findDXZeroRoots()
                    val z2 = c.findDYZeroRoots()
                    val z3 = c.findDZZeroRoots()
                    z1.forEach(Consumer { x: Float -> q.add(fi + x - 1) })
                    z2.forEach(Consumer { x: Float -> q.add(fi + x - 1) })
                    z3.forEach(Consumer { x: Float -> q.add(fi + x - 1) })
                }
            }
            Collections.sort(q)
            if (q.size == 0) return q

            val o = q[0]
            var i = 1
            while (i < q.size) {
                if (abs(q[i] - o) < 1e-3) {
                    q.removeAt(i)
                    i--
                }
                i++
            }
            return q
        }

        /**
         * returns a list of 't's where tangentForward doesn't equal tangentBackward
         */
        fun tangentBreaks(): List<Float> {
            return extremalOuterPoints(Predicate<Pair<Vec3, Vec3>> { p: Pair<Vec3, Vec3> ->
                p.first.distance(
                    p.second
                ) > 1e-3
            })
        }

        /**
         * returns a list of 't's where tangent's change dx, dy or dz
         */
        fun extremalOuterPoints(): List<Float> {
            return this.extremalOuterPoints { p: Pair<Vec3, Vec3> ->
                Math.signum(p.first.x) !== Math.signum(
                    p.second.x
                ) || Math.signum(p.first.y) !== Math.signum(p.second.y) || Math.signum(
                    p.first.z
                ) !== Math.signum(p.second.z)
            }
        }

        /**
         * returns a list of 't's that are points of inflection (this only happens inside cubic segments)
         */
        fun inflectionPointsXY(): List<Float> {
            val q = ArrayList<Float>()
            for (i in 1 until on.nodes.size) {
                val fi = i
                val nn: FLine.Node = on.nodes.get(i)
                if (nn is FLine.CubicTo) {
                    val c: CubicSegment3 = CubicSegment3(on, i)
                    val d = c.inflectionPointsXY()

                    d.forEach(Consumer { x: Float? -> q.add(x!! + fi - 1) })
                }
            }
            return q
        }


        protected fun extremalOuterPoints(p: Predicate<Pair<Vec3, Vec3>>): List<Float> {
            val was = t
            val q: MutableList<Float> = ArrayList()
            try {
                for (i in 0 until lengthT()) {
                    setT(i.toFloat())
                    val b: Vec3? = tangentBackward()
                    val f: Vec3? = tangentForward()
                    if (b == null && f != null) q.add(i + 0.0f)
                    else if (b != null && f == null) q.add(i + 0.0f)
                    else if (b == null && f == null) {
                    } else {
                        if (p.test(b!! to f!!)) q.add(i + 0.0f)
                    }
                }
            } finally {
                setT(was)
            }
            return q
        }

        /**
         * returns two new FLines: one on each side of the current cursor. For example `myLine.cursor().setD(20.3).split().first` is a line that is the first 20.3 units of `myLine`.
         */
        fun split(): Pair<FLine?, FLine?> {
            if (alpha == 0.0f && index == 0) return Pair(null, on.copy())
            if (alpha == 1.0f && index == on.nodes.size - 2) return Pair(on.copy(), null)
            if (alpha == 0.0f && index == on.nodes.size - 1) return Pair(on.copy(), null)

            val left: FLine = FLine()
            val right: FLine = FLine()

            for (i in 0 until index + 1) {
                left.copyTo(on.nodes.get(i))
            }

            if (segmentIsMove()) {
                for (i in index + 1 until on.nodes.size) {
                    right.copyTo(on.nodes.get(i))
                }
            } else if (segmentIsLinear()) {
                left.lineTo(position())
                right.moveTo(position())
                for (i in index + 1 until on.nodes.size) {
                    right.copyTo(on.nodes.get(i))
                }
            } else if (segmentIsCubic()) {
                val a: Vec3 = Vec3(on.nodes.get(clamp(index)).to)
                val c1: Vec3 = Vec3((on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c1)
                val c2: Vec3 = Vec3((on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c2)
                val b: Vec3 = Vec3((on.nodes.get(clamp(index + 1)) as FLine.CubicTo).to)

                val c12: Vec3 = Vec3()
                val m: Vec3 = Vec3()
                val c21: Vec3 = Vec3()
                val t: Vec3 = Vec3()

                splitCubicFrame(a, c1, c2, b, alpha, c12, m, c21, t)

                left.cubicTo(c1, c12, m)
                right.moveTo(m)
                right.cubicTo(c21, c2, b)
                for (i in index + 2 until on.nodes.size) {
                    right.copyTo(on.nodes.get(i))
                }
            }

            return Pair(left, right)
        }


        /**
         * returns a list of new FLines that are this line split at an (ordered) list of points. For example `myLine.splitD([3.2,4,8])` returns a list of four FLines all pieces of `myLine`,
         * the first line runs from 0 to 3.2, the second from 3.2 to 4, the third from 4 to 8 and the fourth from 8 to the end of `myLine`.
         */
        fun splitD(d: List<Float>): List<FLine> {
            val was = t
            try {
                var right: FLine? = on
                var soFar = 0.0f

                var cursor = this
                val f: MutableList<FLine> = ArrayList<FLine>()
                for (i in d.indices) {
                    val at = d[i] - soFar
                    cursor.setD(at)
                    val s: Pair<FLine?, FLine?> = cursor.split()

                    f.add(s.first!!)
                    soFar = at
                    right = s.second
                    cursor = Cursor(right!!, 0.1f)
                }
                f.add(right!!)
                return f
            } finally {
                setT(was)
            }
        }

        /**
         * splits on a list of T positions
         */
        fun splitT(t: List<Float>): List<FLine> {
            val was = this.t
            try {
                val d: MutableList<Float> = ArrayList(t.size)
                for (tt in t) {
                    setT(tt)
                    d.add(this.d)
                }
                return splitD(d)
            } finally {
                setT(was)
            }
        }

        /**
         * _modifies_ this line, to include a new node here. Note this will increment lengthT and possibly t, but this cursor will still be in the same actual .position() This returns the
         * added
         */
        fun segment(): FLine.Node? {
            if (alpha == 0.0f) return null

            if (segmentIsMove()) {
                return null
            } else if (segmentIsLinear()) {
                val s = on.lineTo(position())
                on.nodes.add(index + 1, s)
                alpha = 0.0f
                index++
                return on.nodes.get(index + 1)
            } else if (segmentIsCubic()) {
                val a: Vec3 = Vec3(on.nodes.get(clamp(index)).to)
                val c1: Vec3 = Vec3((on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c1)
                val c2: Vec3 = Vec3((on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c2)
                val b: Vec3 = Vec3((on.nodes.get(clamp(index + 1)) as FLine.CubicTo).to)

                val c12: Vec3 = Vec3()
                val m: Vec3 = Vec3()
                val c21: Vec3 = Vec3()
                val t: Vec3 = Vec3()

                splitCubicFrame(a, c1, c2, b, alpha, c12, m, c21, t)

                (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c1.set(c1)
                (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).c2.set(c12)
                (on.nodes.get(clamp(index + 1)) as FLine.CubicTo).to.set(m)

                on.nodes.add(index + 2, on.cubicTo(c21, c2, b))

                p = null
                alpha = 0.0f
                index++
                return on.nodes.get(index + 1)
            }

            return null
        }

        /**
         * the overal length in 'node' aka 't' units. A line with 4 drawing instructions has a lengthT of 4.
         */
        fun lengthT(): Int {
            return on.nodes.size - 1
        }

        /**
         * length in actual units.
         */
        fun lengthD(): Float {
            return pathFlattener.length()
        }

        val d: Float
            /**
             * get current position as distance along line.
             */
            get() = pathFlattener.dotToLength(index + alpha)

        /**
         * set the current position as a distance along this line.
         */
        fun setD(d: Float): Cursor {
            val q: Float = pathFlattener.lengthToDot(d)
            index = q.toInt()
            alpha = (q - index).toFloat()
            return this
        }

        val t: Float
            /**
             * Get current position in node.t format (so 0 is the start of the line, 1 is the first node 1.5 is roughly (but certainly not exactly) half-way between the first and second node, and
             * so on. The key trouble here is that while evalLinear(x,y, alpha) parameterizes linear segments evently, evalCubic(x, c1, c2, y, alpha) certainly doesn't and alpha=0.5 doesn't mean
             * half-way along in terms of distance. If you can about even distribution use the much slower but accurate setD and getD.
             */
            get() = index + alpha

        /**
         * set the current position as a distance along this line. Returns position in node.t format
         */
        fun setT(q: Float): Cursor {
            index = q.toInt()
            alpha = (q - index)
            return this
        }

        val pathFlattener: PathFlattener
            get() {
                if (p == null) return PathFlattener(this.on, tol).also { p = it }
                return p as PathFlattener
            }


        private fun clamp(index: Int): Int {
            if (index >= on.nodes.size) return on.nodes.size - 1
            if (index < 0) return 0
            return index
        }
    }


    class CubicSegment3 {
        var a: Vec3
        var b: Vec3
        var c: Vec3
        var d: Vec3

        constructor(a: Vec3, b: Vec3, c: Vec3, d: Vec3) {
            this.a = Vec3(a)
            this.b = Vec3(b)
            this.c = Vec3(c)
            this.d = Vec3(d)
        }

        constructor(a: Vec3, b: Vec3, c: Vec3, d: Vec3, dx: Float) {
            this.a = Vec3(a)
            this.b = Vec3(b)
            this.c = Vec3(c)
            this.d = Vec3(d)
            this.a.x += dx.toFloat()
            this.b.x += dx.toFloat()
            this.c.x += dx.toFloat()
            this.d.x += dx.toFloat()
        }

        constructor(a: Vec3, b: Vec3, c: Vec3, d: Vec3, dx: Float, dy: Float) {
            this.a = Vec3(a)
            this.b = Vec3(b)
            this.c = Vec3(c)
            this.d = Vec3(d)
            this.a.x += dx.toFloat()
            this.b.x += dx.toFloat()
            this.c.x += dx.toFloat()
            this.d.x += dx.toFloat()
            this.a.y += dy.toFloat()
            this.b.y += dy.toFloat()
            this.c.y += dy.toFloat()
            this.d.y += dy.toFloat()
        }

        constructor(a: Vec3, d: Vec3) {
            this.a = Vec3(a)
            this.b = Vec3(a.x + (d.x - a.x) / 3, a.y + (d.y - a.y) / 3, a.z + (d.z - a.z) / 3)
            this.c = Vec3(a.x + 2 * (d.x - a.x) / 3, a.y + 2 * (d.y - a.y) / 3, a.z + 2 * (d.z - a.z) / 3)
            this.d = Vec3(d)
        }

        constructor(f: FLine, index: Int) {
            this.a = Vec3(f.nodes.get(index - 1).to)
            this.b = Vec3((f.nodes.get(index) as FLine.CubicTo).c1)
            this.c = Vec3((f.nodes.get(index) as FLine.CubicTo).c2)
            this.d = Vec3((f.nodes.get(index) as FLine.CubicTo).to)
        }

        fun intersect(x1: Vec3, x2: Vec3): List<Vec3> {
            val c2 = rotateToX(x1, x2)
            return c2.findYZeroRoots().filter { x ->
                Math.abs(
                    evaluateCubicFrame(c2.a.z, c2.b.z, c2.c.z, c2.d.z, x.toFloat()).x
                ) < 1e-5f
            }.map { x ->
                evaluateCubicFrame(
                    a, b, c, d, x, Vec3()
                )
            }.toList()

            // filter out things passed the end of x1->x2?
        }

        /**
         * rotates so that x1->x2 is along the x axis
         */
        fun rotateToX(x1: Vec3, x2: Vec3): CubicSegment3 {
            var x1: Vec3 = x1
            var x2: Vec3 = x2
            val dir: Vec3 = x2 - x1
            val q = Quat().rotateTo(vec(1.0, 0.0, 0.0f), dir)

            x1 = q.transform(Vec3(x1))
            x2 = q.transform(Vec3(x2))

            val a2: Vec3 = q.transform(a) - x1
            val b2: Vec3 = q.transform(b) - x1
            val c2: Vec3 = q.transform(c) - x1
            val d2: Vec3 = q.transform(d) - x1
            return CubicSegment3(a2, b2, c2, d2)
        }

        fun boundingBox(): Cuboid {
            val y = findDYZeroRoots()
            val x = findDXZeroRoots()
            val z = findDZZeroRoots()

            val min: Vec3 = Vec3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            val max: Vec3 = Vec3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)

            run {
                val s: Vec3 = evaluateCubicFrame(a, b, c, d, 0.0f, Vec3())
                min.min(s)
                max.max(s)
            }
            run {
                val s: Vec3 = evaluateCubicFrame(a, b, c, d, 1.0f, Vec3())
                min.min(s)
                max.max(s)
            }
            for (yy in y) {
                val s: Vec3 = evaluateCubicFrame(a, b, c, d, yy, Vec3())
                min.min(s)
                max.max(s)
            }
            for (yy in x) {
                val s: Vec3 = evaluateCubicFrame(a, b, c, d, yy, Vec3())
                min.min(s)
                max.max(s)
            }
            for (yy in z) {
                val s: Vec3 = evaluateCubicFrame(a, b, c, d, yy, Vec3())
                min.min(s)
                max.max(s)
            }

            return Cuboid(min.x, min.y, min.z, max.x - min.x, max.y - min.y, max.z - min.z)
        }

        fun split(alpha: Float): Pair<CubicSegment3, CubicSegment3> {
            val c12: Vec3 = Vec3()
            val c21: Vec3 = Vec3()
            val m: Vec3 = Vec3()

            val s1 = CubicSegment3(a, b, c, d)
            val s2 = CubicSegment3(a, b, c, d)

            splitCubicFrame(s1.a, s1.b, s2.c, s2.d, alpha.toFloat(), c12, m, c21, Vec3())

            s1.c = c12
            s1.d = m
            s2.a = Vec3(m)
            s2.b = c21

            return Pair(s1, s2)
        }

        override fun toString(): String {
            return "c[$a,$b,$c,$d]"
        }

        fun intersection(with: CubicSegment3, sz: Float): List<Pair<CubicSegment3, CubicSegment3>> {
            val a: MutableList<CubicSegment3> = ArrayList()
            a.add(this)
            val b: MutableList<CubicSegment3> = ArrayList()
            b.add(with)

            val out: MutableList<Pair<CubicSegment3, CubicSegment3>> = ArrayList()

            var gen = 0
            while (a.size > 0 && b.size > 0 && gen < 20) {
                val cache: MutableMap<CubicSegment3, Cuboid> = LinkedHashMap<CubicSegment3, Cuboid>()

                val na: MutableSet<CubicSegment3> = LinkedHashSet()
                val nb: MutableSet<CubicSegment3> = LinkedHashSet()

                o@ for (aa in a) {
                    val baa: Cuboid = aa.boundingBox()
                    val ib = b.iterator()
                    while (ib.hasNext()) {
                        val nib = ib.next()

                        val bnn: Cuboid = cache.computeIfAbsent(
                            nib,
                            Function<CubicSegment3, Cuboid> { x: CubicSegment3? -> nib.boundingBox() })


                        if (baa.intersects(bnn)) {

                            if (Math.max(baa.w, baa.h) < sz && Math.max(bnn.w, bnn.h) < sz) {
                                ib.remove()
                                out.add(Pair(aa, nib))
                            } else {
                                na.add(aa)
                                nb.add(nib)
                            }
                            //continue o;
                        }
                    }

                }

                a.clear()
                b.clear()

                for (c in na) {
                    val r: Cuboid = cache.computeIfAbsent(
                        c,
                        Function<CubicSegment3, Cuboid> { x: CubicSegment3? -> c.boundingBox() })
                    if (Math.max(r.w, r.y) < sz) a.add(c)
                    else {
                        val lr = c.split(0.5f)
                        a.add(lr.first)
                        a.add(lr.second)
                    }
                }

                for (c in nb) {
                    val r: Cuboid = cache.computeIfAbsent(
                        c,
                        Function<CubicSegment3, Cuboid> { x: CubicSegment3? -> c.boundingBox() })
                    if (Math.max(r.w, r.y) < sz) b.add(c)
                    else {
                        val lr = c.split(0.5f)
                        b.add(lr.first)
                        b.add(lr.second)
                    }
                }

                gen++
            }


            return out
        }


        fun findYZeroRoots(): List<Float> {
            val subdiv = 20

            val dd: MutableList<Float> = ArrayList()

            for (i in 0 until subdiv) {
                val alphaStart = ((i - 0.75f) / subdiv).toFloat()
                val alphaEnd = ((i + 0.75f) / subdiv).toFloat()
                val yStart: Float = evaluateCubicFrame(a.y, b.y, c.y, d.y, alphaStart).x
                val yEnd: Float = evaluateCubicFrame(a.y, b.y, c.y, d.y, alphaEnd).x
                if (sign(yStart) != sign(yEnd)) {
                    val t = findYZeroRoot_bracketed(alphaStart, alphaEnd)
                    if (t != null) dd.add(t)
                }
            }

            for (i in dd.indices) {
                var j = i + 1
                while (j < dd.size) {
                    if (abs(dd[i] - dd[j]) < 1e-6) {
                        dd.removeAt(j)
                        j--
                    }
                    j++
                }
            }

            // filter dd for repeated roots
            return dd
        }

        private fun findYZeroRoot_bracketed(yStart: Float, yEnd: Float): Float? {
            var t = (yStart + yEnd) / 2

            for (i in 0..9) {
                val D: Vec2 = evaluateCubicFrame(a.y, b.y, c.y, d.y, t)
                val dt: Float = D.x / D.y
                t = t - dt
                if (abs(dt) < 1e-5) {
                    return t
                }
            }

            if (t >= yStart && t <= yEnd && evaluateCubicFrame(
                    a.y, b.y, c.y, d.y, t
                ).x < 1e-6
            ) {
                return t
            }

            return null
        }

        fun findXZeroRoots(): List<Float> {
            val subdiv = 20

            val dd: MutableList<Float> = ArrayList()

            for (i in 0 until subdiv) {
                val alphaStart = ((i - 0.75f) / subdiv).toFloat()
                val alphaEnd = ((i + 0.75f) / subdiv).toFloat()
                val yStart: Float = evaluateCubicFrame(a.x, b.x, c.x, d.x, alphaStart).x
                val yEnd: Float = evaluateCubicFrame(a.x, b.x, c.x, d.x, alphaEnd).x
                if (sign(yStart) != sign(yEnd)) {
                    val t = findXZeroRoot_bracketed(alphaStart, alphaEnd)
                    if (t != null) dd.add(t)
                }
            }

            for (i in dd.indices) {
                var j = i + 1
                while (j < dd.size) {
                    if (abs(dd[i] - dd[j]) < 1e-6) {
                        dd.removeAt(j)
                        j--
                    }
                    j++
                }
            }

            return dd
        }

        private fun findXZeroRoot_bracketed(yStart: Float, yEnd: Float): Float? {
            var t = (yStart + yEnd) / 2

            for (i in 0..9) {
                val D: Vec2 = evaluateCubicFrame(a.x, b.x, c.x, d.x, t)
                val dt: Float = D.x / D.y
                t = t - dt
                if (abs(dt) < 1e-5) {
                    return t
                }
            }

            if (t >= yStart && t <= yEnd && evaluateCubicFrame(
                    a.x, b.x, c.x, d.x, t
                ).x < 1e-6
            ) {
                return t
            }

            return null
        }

        fun findDYZeroRoots(): List<Float> {
            val subdiv = 20

            val dd: MutableList<Float> = ArrayList()

            for (i in 0 until subdiv) {
                val alphaStart = ((i - 0.75f) / subdiv).toFloat()
                val alphaEnd = ((i + 0.75f) / subdiv).toFloat()
                val yStart: Float = evaluateCubicFrame(a.y, b.y, c.y, d.y, alphaStart).y
                val yEnd: Float = evaluateCubicFrame(a.y, b.y, c.y, d.y, alphaEnd).y
                if (sign(yStart) != sign(yEnd)) {
                    val t = findDYZeroRoot_bracketed(alphaStart, alphaEnd)
                    if (t != null) dd.add(t)
                }
            }

            for (i in dd.indices) {
                var j = i + 1
                while (j < dd.size) {
                    if (abs(dd[i] - dd[j]) < 1e-6) {
                        dd.removeAt(j)
                        j--
                    }
                    j++
                }
            }

            // filter dd for repeated roots
            return dd
        }

        private fun findDYZeroRoot_bracketed(yStart: Float, yEnd: Float): Float? {
            var t = (yStart + yEnd) / 2

            for (i in 0..9) {
                val D: Vec2 = evaluateDCubicFrame(a.y, b.y, c.y, d.y, t)
                val dt: Float = D.x / D.y
                t = t - dt
                if (abs(dt) < 1e-5) {
                    return t
                }
            }

            if (t >= yStart && t <= yEnd && evaluateDCubicFrame(
                    a.y, b.y, c.y, d.y, t
                ).x < 1e-6
            ) {
                return t
            }

            return null
        }

        fun findDXZeroRoots(): List<Float> {
            val subdiv = 20

            val dd: MutableList<Float> = ArrayList()

            for (i in 0 until subdiv) {
                val alphaStart = ((i - 0.75f) / subdiv).toFloat()
                val alphaEnd = ((i + 0.75f) / subdiv).toFloat()
                val yStart: Float = evaluateCubicFrame(a.x, b.x, c.x, d.x, alphaStart).y
                val yEnd: Float = evaluateCubicFrame(a.x, b.x, c.x, d.x, alphaEnd).y
                if (sign(yStart) != sign(yEnd)) {
                    val t = findDXZeroRoot_bracketed(alphaStart, alphaEnd)
                    if (t != null) dd.add(t)
                }
            }

            for (i in dd.indices) {
                var j = i + 1
                while (j < dd.size) {
                    if (abs(dd[i] - dd[j]) < 1e-6) {
                        dd.removeAt(j)
                        j--
                    }
                    j++
                }
            }

            // filter dd for repeated roots
            return dd
        }

        private fun findDXZeroRoot_bracketed(yStart: Float, yEnd: Float): Float? {
            var t = (yStart + yEnd) / 2

            for (i in 0..9) {
                val D: Vec2 = evaluateDCubicFrame(a.x, b.x, c.x, d.x, t)
                val dt: Float = D.x / D.y
                t = t - dt
                if (abs(dt) < 1e-5) {
                    return t
                }
            }

            if (t >= yStart && t <= yEnd && evaluateDCubicFrame(
                    a.x, b.x, c.x, d.x, t
                ).x < 1e-6
            ) {
                return t
            }

            return null
        }


        fun findDZZeroRoots(): List<Float> {
            val subdiv = 20

            val dd: MutableList<Float> = ArrayList()

            for (i in 0 until subdiv) {
                val alphaStart = ((i - 0.75f) / subdiv).toFloat()
                val alphaEnd = ((i + 0.75f) / subdiv).toFloat()
                val yStart: Float = evaluateCubicFrame(a.z, b.z, c.z, d.z, alphaStart).y
                val yEnd: Float = evaluateCubicFrame(a.z, b.z, c.z, d.z, alphaEnd).y
                if (sign(yStart) != sign(yEnd)) {
                    val t = findDZZeroRoot_bracketed(alphaStart, alphaEnd)
                    if (t != null) dd.add(t)
                }
            }

            for (i in dd.indices) {
                var j = i + 1
                while (j < dd.size) {
                    if (abs(dd[i] - dd[j]) < 1e-6) {
                        dd.removeAt(j)
                        j--
                    }
                    j++
                }
            }

            // filter dd for repeated roots
            return dd
        }

        private fun findDZZeroRoot_bracketed(yStart: Float, yEnd: Float): Float? {
            var t = (yStart + yEnd) / 2

            for (i in 0..9) {
                val D: Vec2 = evaluateDCubicFrame(a.z, b.z, c.z, d.z, t)
                val dt: Float = D.x / D.y
                t = t - dt
                if (abs(dt) < 1e-5) {
                    return t
                }
            }

            if (t >= yStart && t <= yEnd && evaluateDCubicFrame(
                    a.z, b.z, c.z, d.z, t
                ).x < 1e-6
            ) {
                return t
            }

            return null
        }

        fun osculating(alpha: Float): Vec3 {
            return D(alpha).cross(DD(alpha), Vec3())
        }

        fun D(alpha: Float): Vec3 {
            val a: Vec3 = this.b - this.a
            val b: Vec3 = this.c + this.b * -2.0 + this.a
            val c: Vec3 = this.d + this.c * -3.0 + this.b * 3.0 + this.a * -1.0

            val D: Vec3 = a * 3.0 + b * 6.0 * alpha + c * 3.0 * alpha * alpha

            return D
        }


        fun DD(alpha: Float): Vec3 {
            val a: Vec3 = this.b - this.a
            val b: Vec3 = this.c + this.b * -2.0 + this.a
            val c: Vec3 = this.d + this.c * -3.0 + this.b * 3.0 + this.a * -1.0

            val DD: Vec3 = b * 6.0 + c * 6.0 * alpha

            return DD
        }

        fun R(alpha: Float): Float {
            val D: Vec3 = D(alpha)
            val DD: Vec3 = DD(alpha)

            return (D.x * D.x + D.y * D.y + D.z * D.z).pow((3 / 2f).toFloat()) / D.cross(DD, Vec3())
                .length()
        }

        fun inflectionPointsXY(): List<Float> {
            val ax: Float = -a.x + 3 * b.x - 3 * c.x + d.x
            val bx: Float = 3 * a.x - 6 * b.x + 3 * c.x
            val cx: Float = -3 * a.x + 3 * b.x
            val dx: Float = a.x

            val ay: Float = -a.y + 3 * b.y - 3 * c.y + d.y
            val by: Float = 3 * a.y - 6 * b.y + 3 * c.y
            val cy: Float = -3 * a.y + 3 * b.y
            val dy: Float = a.y

            val az: Float = -a.z + 3 * b.z - 3 * c.z + d.z
            val bz: Float = 3 * a.z - 6 * b.z + 3 * c.z
            val cz: Float = -3 * a.z + 3 * b.z
            val dz: Float = a.z

            val cuspxy = -(ay * cx - ax * cy) / (2 * (ay * bx - ax * by))
            val disxy = cuspxy * cuspxy - (by * cx - bx * cy) / (3 * (ay * bx - ax * by))


            if (disxy < 0) {
                return emptyList()
            } else if (disxy == 0.0f) {
                return listOf(cuspxy)
            } else {
                val d0 = cuspxy + sqrt(disxy)
                val d1 = cuspxy - sqrt(disxy)

                val dd: MutableList<Float> = ArrayList()
                dd.add(d0)
                dd.add(d1)
                return dd
            }
        }

        fun inflectionPointsXZ(): List<Float> {
            val ax: Float = -a.x + 3 * b.x - 3 * c.x + d.x
            val bx: Float = 3 * a.x - 6 * b.x + 3 * c.x
            val cx: Float = -3 * a.x + 3 * b.x
            val dx: Float = a.x

            //			Float ay = -a.y+3*b.y-3*c.y+d.y;
//			Float by = 3*a.y-6*b.y+3*c.y;
//			Float cy = -3*a.y+3*b.y;
//			Float dy = a.y;
            val ay: Float = -a.z + 3 * b.z - 3 * c.z + d.z
            val by: Float = 3 * a.z - 6 * b.z + 3 * c.z
            val cy: Float = -3 * a.z + 3 * b.z
            val dy: Float = a.z

            val cuspxy = -(ay * cx - ax * cy) / (2 * (ay * bx - ax * by))
            val disxy = cuspxy * cuspxy - (by * cx - bx * cy) / (3 * (ay * bx - ax * by))


            if (disxy < 0) {
                return emptyList()
            } else if (disxy == 0.0f) {
                return listOf(cuspxy)
            } else {
                val d0 = cuspxy + sqrt(disxy)
                val d1 = cuspxy - sqrt(disxy)

                val dd: MutableList<Float> = ArrayList()
                dd.add(d0)
                dd.add(d1)
                return dd
            }
        }

        fun inflectionPointsYZ(): List<Float> {
            //			Float ax = -a.x+3*b.x-3*c.x+d.x;
//			Float bx = 3*a.x-6*b.x+3*c.x;
//			Float cx = -3*a.x+3*b.x;
//			Float dx = a.x;

            val ay: Float = -a.y + 3 * b.y - 3 * c.y + d.y
            val by: Float = 3 * a.y - 6 * b.y + 3 * c.y
            val cy: Float = -3 * a.y + 3 * b.y
            val dy: Float = a.y

            val ax: Float = -a.z + 3 * b.z - 3 * c.z + d.z
            val bx: Float = 3 * a.z - 6 * b.z + 3 * c.z
            val cx: Float = -3 * a.z + 3 * b.z
            val dx: Float = a.z

            val cuspxy = -(ay * cx - ax * cy) / (2 * (ay * bx - ax * by))
            val disxy = cuspxy * cuspxy - (by * cx - bx * cy) / (3 * (ay * bx - ax * by))


            if (disxy < 0) {
                return emptyList()
            } else if (disxy == 0.0f) {
                return listOf(cuspxy)
            } else {
                val d0 = cuspxy + sqrt(disxy)
                val d1 = cuspxy - sqrt(disxy)

                val dd: MutableList<Float> = ArrayList()
                dd.add(d0)
                dd.add(d1)
                return dd
            }
        }

        fun closestToPoint(v: Vec3): Float {
            // right now we split this segment into two pieces to reduce the changes of getting stuck in a local minimum. We ought to split at inflection points instead

            val o: BrentOptimizer = BrentOptimizer(1e-4, 1e-4)
            val p1: UnivariatePointValuePair = o.optimize(
                UnivariateObjectiveFunction { x ->
                    evaluateCubicFrame(
                        a, b, c, d, x.toFloat(), Vec3()
                    ).distance(v).toDouble()
                }, SearchInterval(0.0, 0.5, 0.25), GoalType.MINIMIZE, MaxEval(200)
            )
            val p2: UnivariatePointValuePair = o.optimize(
                UnivariateObjectiveFunction { x ->
                    evaluateCubicFrame(
                        a, b, c, d, x.toFloat(), Vec3()
                    ).distance(v).toDouble()
                }, SearchInterval(0.5, 1.0, 0.75), GoalType.MINIMIZE, MaxEval(200)
            )

            return if (p1.getValue() < p2.getValue()) p1.getPoint().toFloat()
            else p2.getPoint().toFloat()
        }

        fun evaluate(t: Float): Vec3 {
            return evaluateCubicFrame(a, b, c, d, t, Vec3())
        }
    }

    /**
     * helper class for a 2d cubic segment, for the things that we can only reasonably do in 2d (like intersections).
     */
    inner class CubicSegment2 {
        var a: Vec2
        var b: Vec2
        var c: Vec2
        var d: Vec2

        constructor(a: Vec2, b: Vec2, c: Vec2, d: Vec2) {
            this.a = Vec2(a)
            this.b = Vec2(b)
            this.c = Vec2(c)
            this.d = Vec2(d)
        }

        constructor(a: Vec3, b: Vec3, c: Vec3, d: Vec3) {
            this.a = a.v2
            this.b = b.v2
            this.c = c.v2
            this.d = d.v2
        }

        fun intersect(x1: Vec2, x2: Vec2): List<Vec2> {
            val c2 = rotateToX(x1, x2)
            return c2.findYZeroRoots().map { x ->
                evaluateCubicFrame(
                    a, b, c, d, x, Vec2()
                )
            }.toList()
        }

        /**
         * rotates so that x1->x2 is along the x axis
         */
        fun rotateToX(x1: Vec2, x2: Vec2): CubicSegment2 {
            var x1: Vec2 = x1
            var x2: Vec2 = x2
            val dir: Vec2 = x2 - x1
            val q = Quat().rotateTo(vec(1.0, 0.0, 0.0f), dir.v3)

            x1 = (x1 * q) as Vec2
            x2 = (x2 * q) as Vec2

            val a2 = (a * q) - x1

            val b2: Vec2 = (b * q) - x1
            val c2: Vec2 = (c * q) - x1
            val d2: Vec2 = (d * q) - x1

            return CubicSegment2(a2, b2, c2, d2)
        }

        fun boundingBox(): Rect {
            val y = findDYZeroRoots()
            val x = findDXZeroRoots()

            val min: Vec2 = Vec2(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            val max: Vec2 = Vec2(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)

            run {
                val s: Vec2 = evaluateCubicFrame(a, b, c, d, 0.0f, Vec2())
                min.min(s)
                max.max(s)
            }
            run {
                val s: Vec2 = evaluateCubicFrame(a, b, c, d, 1.0f, Vec2())
                min.min(s)
                max.max(s)
            }
            for (yy in y) {
                val s: Vec2 = evaluateCubicFrame(a, b, c, d, yy, Vec2())
                min.min(s)
                max.max(s)
            }
            for (yy in x) {
                val s: Vec2 = evaluateCubicFrame(a, b, c, d, yy, Vec2())
                min.min(s)
                max.max(s)
            }

            return Rect(min.x.toDouble(), min.y.toDouble(), (max.x - min.x).toDouble(), (max.y - min.y).toDouble())
        }

        fun split(alpha: Float): Pair<CubicSegment2, CubicSegment2> {
            val c12: Vec2 = Vec2()
            val c21: Vec2 = Vec2()
            val m: Vec2 = Vec2()

            val s1: CubicSegment2 = CubicSegment2(a, b, c, d)
            val s2: CubicSegment2 = CubicSegment2(a, b, c, d)

            splitCubicFrame(s1.a, s1.b, s2.c, s2.d, alpha.toFloat(), c12, m, c21, Vec2())

            s1.c = c12
            s1.d = m
            s2.a = Vec2(m)
            s2.b = c21

            return Pair(s1, s2)
        }


        fun intersection(with: CubicSegment2, sz: Float): List<Pair<CubicSegment2, CubicSegment2>> {
            val a: MutableList<CubicSegment2> = ArrayList()
            a.add(this)
            val b: MutableList<CubicSegment2> = ArrayList()
            b.add(with)

            val out: MutableList<Pair<CubicSegment2, CubicSegment2>> = ArrayList()

            var gen = 0
            while (a.size > 0 && b.size > 0 && gen < 20) {
                val cache: MutableMap<CubicSegment2, Rect> = LinkedHashMap<CubicSegment2, Rect>()

                val na: MutableSet<CubicSegment2> = LinkedHashSet()
                val nb: MutableSet<CubicSegment2> = LinkedHashSet()

                o@ for (aa in a) {
                    val baa: Rect = aa.boundingBox()
                    val ib = b.iterator()
                    while (ib.hasNext()) {
                        val nib = ib.next()

                        val bnn: Rect = cache.computeIfAbsent(
                            nib,
                            Function<CubicSegment2, Rect> { x: CubicSegment2? -> nib.boundingBox() })

                        if (baa.intersects(bnn)) {

                            if (Math.max(baa.w, baa.h) < sz && Math.max(bnn.w, bnn.h) < sz) {
                                ib.remove()
                                out.add(Pair(aa, nib))
                            } else {
                                na.add(aa)
                                nb.add(nib)
                            }
                            //continue o;
                        }
                    }
                }

                a.clear()
                b.clear()

                for (c in na) {
                    val r: Rect =
                        cache.computeIfAbsent(c, Function<CubicSegment2, Rect> { x: CubicSegment2? -> c.boundingBox() })
                    if (Math.max(r.w, r.y) < sz) a.add(c)
                    else {
                        val lr = c.split(0.5f)
                        a.add(lr.first)
                        a.add(lr.second)
                    }
                }

                for (c in nb) {
                    val r: Rect =
                        cache.computeIfAbsent(c, Function<CubicSegment2, Rect> { x: CubicSegment2? -> c.boundingBox() })
                    if (Math.max(r.w, r.y) < sz) b.add(c)
                    else {
                        val lr = c.split(0.5f)
                        b.add(lr.first)
                        b.add(lr.second)
                    }
                }

                gen++
            }

            return out
        }


        fun findYZeroRoots(): List<Float> {
            val subdiv = 20

            val dd: MutableList<Float> = ArrayList()

            for (i in 0 until subdiv) {
                val alphaStart = ((i - 0.75f) / subdiv).toFloat()
                val alphaEnd = ((i + 0.75f) / subdiv).toFloat()
                val yStart: Float = evaluateCubicFrame(a.y, b.y, c.y, d.y, alphaStart).x
                val yEnd: Float = evaluateCubicFrame(a.y, b.y, c.y, d.y, alphaEnd).x
                if (sign(yStart) != sign(yEnd)) {
                    val t = findYZeroRoot_bracketed(alphaStart, alphaEnd)
                    if (t != null) dd.add(t)
                }
            }

            for (i in dd.indices) {
                var j = i + 1
                while (j < dd.size) {
                    if (abs(dd[i] - dd[j]) < 1e-6) {
                        dd.removeAt(j)
                        j--
                    }
                    j++
                }
            }

            // filter dd for repeated roots
            return dd
        }

        private fun findYZeroRoot_bracketed(yStart: Float, yEnd: Float): Float? {
            var t = (yStart + yEnd) / 2

            for (i in 0..9) {
                val D: Vec2 = evaluateCubicFrame(a.y, b.y, c.y, d.y, t)
                val dt: Float = D.x / D.y
                t = t - dt
                if (abs(dt) < 1e-5) {
                    return t
                }
            }

            if (t >= yStart && t <= yEnd && evaluateCubicFrame(
                    a.y, b.y, c.y, d.y, t
                ).x < 1e-6
            ) {
                return t
            }

            return null
        }

        fun findXZeroRoots(): List<Float> {
            val subdiv = 20

            val dd: MutableList<Float> = ArrayList()

            for (i in 0 until subdiv) {
                val alphaStart = ((i - 0.75f) / subdiv).toFloat()
                val alphaEnd = ((i + 0.75f) / subdiv).toFloat()
                val yStart: Float = evaluateCubicFrame(a.x, b.x, c.x, d.x, alphaStart).x
                val yEnd: Float = evaluateCubicFrame(a.x, b.x, c.x, d.x, alphaEnd).x
                if (sign(yStart) != sign(yEnd)) {
                    val t = findXZeroRoot_bracketed(alphaStart, alphaEnd)
                    if (t != null) dd.add(t)
                }
            }

            for (i in dd.indices) {
                var j = i + 1
                while (j < dd.size) {
                    if (abs(dd[i] - dd[j]) < 1e-6) {
                        dd.removeAt(j)
                        j--
                    }
                    j++
                }
            }

            // filter dd for repeated roots
            return dd
        }

        private fun findXZeroRoot_bracketed(yStart: Float, yEnd: Float): Float? {
            var t = (yStart + yEnd) / 2

            for (i in 0..9) {
                val D: Vec2 = evaluateCubicFrame(a.x, b.x, c.x, d.x, t)
                val dt: Float = D.x / D.y
                t = t - dt
                if (abs(dt) < 1e-5) {
                    return t
                }
            }

            if (t >= yStart && t <= yEnd && evaluateCubicFrame(
                    a.x, b.x, c.x, d.x, t
                ).x < 1e-6
            ) {
                return t
            }

            return null
        }

        fun findDYZeroRoots(): List<Float> {
            val subdiv = 20

            val dd: MutableList<Float> = ArrayList()

            for (i in 0 until subdiv) {
                val alphaStart = ((i - 0.75f) / subdiv).toFloat()
                val alphaEnd = ((i + 0.75f) / subdiv).toFloat()
                val yStart: Float = evaluateCubicFrame(a.y, b.y, c.y, d.y, alphaStart).y
                val yEnd: Float = evaluateCubicFrame(a.y, b.y, c.y, d.y, alphaEnd).y
                if (sign(yStart) != sign(yEnd)) {
                    val t = findDYZeroRoot_bracketed(alphaStart, alphaEnd)
                    if (t != null) dd.add(t)
                }
            }

            for (i in dd.indices) {
                var j = i + 1
                while (j < dd.size) {
                    if (abs(dd[i] - dd[j]) < 1e-6) {
                        dd.removeAt(j)
                        j--
                    }
                    j++
                }
            }

            // filter dd for repeated roots
            return dd
        }

        private fun findDYZeroRoot_bracketed(yStart: Float, yEnd: Float): Float? {
            var t = (yStart + yEnd) / 2

            for (i in 0..9) {
                val D: Vec2 = evaluateDCubicFrame(a.y, b.y, c.y, d.y, t)
                val dt: Float = D.x / D.y
                t = t - dt
                if (abs(dt) < 1e-5) {
                    return t
                }
            }

            if (t >= yStart && t <= yEnd && evaluateDCubicFrame(
                    a.y, b.y, c.y, d.y, t
                ).x < 1e-6
            ) {
                return t
            }

            return null
        }

        fun findDXZeroRoots(): List<Float> {
            val subdiv = 20

            val dd: MutableList<Float> = ArrayList()

            for (i in 0 until subdiv) {
                val alphaStart = ((i - 0.75f) / subdiv).toFloat()
                val alphaEnd = ((i + 0.75f) / subdiv).toFloat()
                val yStart: Float = evaluateCubicFrame(a.x, b.x, c.x, d.x, alphaStart).y
                val yEnd: Float = evaluateCubicFrame(a.x, b.x, c.x, d.x, alphaEnd).y
                if (sign(yStart) != sign(yEnd)) {
                    val t = findDXZeroRoot_bracketed(alphaStart, alphaEnd)
                    if (t != null) dd.add(t)
                }
            }

            for (i in dd.indices) {
                var j = i + 1
                while (j < dd.size) {
                    if (abs(dd[i] - dd[j]) < 1e-6) {
                        dd.removeAt(j)
                        j--
                    }
                    j++
                }
            }

            // filter dd for repeated roots
            return dd
        }

        private fun findDXZeroRoot_bracketed(yStart: Float, yEnd: Float): Float? {
            var t = (yStart + yEnd) / 2

            for (i in 0..9) {
                val D: Vec2 = evaluateDCubicFrame(a.x, b.x, c.x, d.x, t)
                val dt: Float = D.x / D.y
                t = t - dt
                if (abs(dt) < 1e-5) {
                    return t
                }
            }

            if (t >= yStart && t <= yEnd && evaluateDCubicFrame(
                    a.x, b.x, c.x, d.x, t
                ).x < 1e-6
            ) {
                return t
            }

            return null
        }

        fun closestToPoint(v: Vec2): Float {
            // right now we split this segment into two pieces to reduce the changes of getting stuck in a local minimum.

            val o: BrentOptimizer = BrentOptimizer(1e-4, 1e-4)
            val p1: UnivariatePointValuePair = o.optimize(
                UnivariateObjectiveFunction { x ->
                    evaluateCubicFrame(
                        a, b, c, d, x.toFloat(), Vec2()
                    ).distance(v).toDouble()
                }, SearchInterval(0.0, 0.5, 0.25), GoalType.MINIMIZE
            )
            val p2: UnivariatePointValuePair = o.optimize(
                UnivariateObjectiveFunction { x ->
                    evaluateCubicFrame(
                        a, b, c, d, x.toFloat(), Vec2()
                    ).distance(v).toDouble()
                }, SearchInterval(0.5, 1.0, 0.75), GoalType.MINIMIZE
            )

            return if (p1.getValue() < p2.getValue()) p1.getPoint().toFloat()
            else p2.getPoint().toFloat()
        }
    }

    companion object {
        private const val angle = Math.PI / 4.0
        private val a = 1.0 - cos(angle)
        private val b = tan(angle)
        private val c = sqrt(1.0 + b * b) - 1 + a
        private val cv = 4.0 / 3.0 * a * b / c
        private val acv = (1.0 - cv) / 2.0

        // For each array:
        //     4 values for each point {v0, v1, v2, v3}:
        //         point = (x + v0 * w + v1 * arcWidth,
        //                  y + v2 * h + v3 * arcHeight);
        private val ctrlpts = arrayOf(
            doubleArrayOf(0.0, 0.0, 0.0, 0.5),
            doubleArrayOf(0.0, 0.0, 1.0, -0.5),
            doubleArrayOf(0.0, 0.0, 1.0, -acv, 0.0, acv, 1.0, 0.0, 0.0, 0.5, 1.0, 0.0),
            doubleArrayOf(1.0, -0.5, 1.0, 0.0),
            doubleArrayOf(
                1.0, -acv, 1.0, 0.0, 1.0, 0.0, 1.0, -acv, 1.0, 0.0, 1.0, -0.5
            ),
            doubleArrayOf(1.0, 0.0, 0.0, 0.5),
            doubleArrayOf(1.0, 0.0, 0.0, acv, 1.0, -acv, 0.0, 0.0, 1.0, -0.5, 0.0, 0.0),
            doubleArrayOf(0.0, 0.5, 0.0, 0.0),
            doubleArrayOf(
                0.0, acv, 0.0, 0.0, 0.0, 0.0, 0.0, acv, 0.0, 0.0, 0.0, 0.5
            ),
            doubleArrayOf(),
        )

        fun flineToJavaShape(f: FLine): Shape {
            f.thicken?.let {
                return it.createStrokedShape(_flineToJavaShape(f))
            }
            f.thickness?.let {
                return BasicStroke(it.toFloat()).createStrokedShape(_flineToJavaShape(f))
            }

            return _flineToJavaShape(f)
        }

        fun flineToJavaShape_notThickened(f: FLine): Shape {
            return _flineToJavaShape(f)
        }


        private fun _flineToJavaShape(f: FLine): GeneralPath {
            val p = GeneralPath()

            for (n in f.nodes) {
                if (n is FLine.MoveTo) p.moveTo(n.to.x, n.to.y)
                else if (n is FLine.LineTo) p.lineTo(n.to.x, n.to.y)
                else if (n is FLine.CubicTo) p.curveTo(
                    n.c1.x, n.c1.y, n.c2.x, n.c2.y, n.to.x, n.to.y
                )
            }
            return p
        }


        fun flineToJavaShape(f: Collection<FLine>): Shape {
            val p = GeneralPath()


            for (ff in f) {
                p.append(flineToJavaShape(ff), false)
            }
            return p
        }

        fun evaluateCubicFrame(
            a: Vec2, c1: Vec2, c2: Vec2, b: Vec2, alpha: Float, out: Vec2
        ): Vec2 {
            val oma = 1 - alpha
            val oma2 = oma * oma
            val oma3 = oma2 * oma
            val alpha2 = alpha * alpha
            val alpha3 = alpha2 * alpha

            out.x = (a.x * oma3 + 3 * c1.x * alpha * oma2 + 3 * c2.x * alpha2 * oma + b.x * alpha3).toFloat()
            out.y = (a.y * oma3 + 3 * c1.y * alpha * oma2 + 3 * c2.y * alpha2 * oma + b.y * alpha3).toFloat()

            return out
        }



        /**
         * returns both the evaluation (x) and it's derivative (y)
         */
        fun evaluateCubicFrame(a: Float, c1: Float, c2: Float, b: Float, alpha: Float): Vec2 {
            val oma = 1 - alpha
            val oma2 = oma * oma
            var oma3 = oma2 * oma
            val alpha2 = alpha * alpha
            var alpha3 = alpha2 * alpha

            val out: Vec2 = Vec2()
            out.x = (a * oma3 + 3 * c1 * alpha * oma2 + 3 * c2 * alpha2 * oma + b * alpha3).toFloat()

            oma3 = -3 * (1 - alpha) * (1 - alpha)
            val alphaoma2 = 3 * alpha * alpha - 4 * alpha + 1
            val alpha2oma = alpha * (2 - 3 * alpha)
            alpha3 = 3 * alpha * alpha

            out.y = (a * oma3 + 3 * c1 * alphaoma2 + 3 * c2 * alpha2oma + b * alpha3).toFloat()

            return out
        }

        /**
         * returns both the derivative (x) and it's second derivative (y)
         */
        fun evaluateDCubicFrame(a: Float, c1: Float, c2: Float, b: Float, alpha: Float): Vec2 {
            val out: Vec2 = Vec2()

            var oma3 = -3 * (1 - alpha) * (1 - alpha)
            var alphaoma2 = 3 * alpha * alpha - 4 * alpha + 1
            var alpha2oma = alpha * (2 - 3 * alpha)
            var alpha3 = 3 * alpha * alpha

            out.x = (a * oma3 + 3 * c1 * alphaoma2 + 3 * c2 * alpha2oma + b * alpha3).toFloat()


            oma3 = -2 + 2 * alpha
            alphaoma2 = 6 * alpha - 4
            alpha2oma = 2 - 6 * alpha
            alpha3 = 6 * alpha

            out.y = (a * oma3 + 3 * c1 * alphaoma2 + 3 * c2 * alpha2oma + b * alpha3).toFloat()

            return out
        }

        fun evaluateCubicFrame(
            a: Vec3, c1: Vec3, c2: Vec3, b: Vec3, alpha: Float, out: Vec3
        ): Vec3 {
            val oma = 1 - alpha
            val oma2 = oma * oma
            val oma3 = oma2 * oma
            val alpha2 = alpha * alpha
            val alpha3 = alpha2 * alpha

            out.x = (a.x * oma3 + 3 * c1.x * alpha * oma2 + 3 * c2.x * alpha2 * oma + b.x * alpha3).toFloat()
            out.y = (a.y * oma3 + 3 * c1.y * alpha * oma2 + 3 * c2.y * alpha2 * oma + b.y * alpha3).toFloat()
            out.z = (a.z * oma3 + 3 * c1.z * alpha * oma2 + 3 * c2.z * alpha2 * oma + b.z * alpha3).toFloat()

            return out
        }

        fun evaluateCubicFrame(
            a: Vector4d, c1: Vector4d, c2: Vector4d, b: Vector4d, alpha: Float, out: Vector4d
        ): Vector4d {
            val oma = 1 - alpha
            val oma2 = oma * oma
            val oma3 = oma2 * oma
            val alpha2 = alpha * alpha
            val alpha3 = alpha2 * alpha

            out.x = (a.x * oma3) + (3 * c1.x * alpha * oma2) + (3 * c2.x * alpha2 * oma) + (b.x * alpha3)
            out.y = (a.y * oma3) + (3 * c1.y * alpha * oma2) + (3 * c2.y * alpha2 * oma) + (b.y * alpha3)
            out.z = (a.z * oma3) + (3 * c1.z * alpha * oma2) + (3 * c2.z * alpha2 * oma) + (b.z * alpha3)
            out.w = (a.w * oma3) + (3 * c1.w * alpha * oma2) + (3 * c2.w * alpha2 * oma) + (b.w * alpha3)

            return out
        }

        fun evaluateDCubicFrame(
            a: Vec3, c1: Vec3, c2: Vec3, b: Vec3, alpha: Float, out: Vec3
        ): Vec3 {
            val oma3 = -3 * (1 - alpha) * (1 - alpha)
            val alphaoma2 = 3 * alpha * alpha - 4 * alpha + 1
            val alpha2oma = alpha * (2 - 3 * alpha)
            val alpha3 = 3 * alpha * alpha

            out.x = (a.x * oma3 + 3 * c1.x * alphaoma2 + 3 * c2.x * alpha2oma + b.x * alpha3).toFloat()
            out.y = (a.y * oma3 + 3 * c1.y * alphaoma2 + 3 * c2.y * alpha2oma + b.y * alpha3).toFloat()
            out.z = (a.z * oma3 + 3 * c1.z * alphaoma2 + 3 * c2.z * alpha2oma + b.z * alpha3).toFloat()

            println(a.toString() + " " + c1 + " " + c2 + " " + b + " " + alpha + " == " + out)

            return out
        }

        fun evaluateDDCubicFrame(
            a: Vec3, c1: Vec3, c2: Vec3, b: Vec3, alpha: Float, out: Vec3
        ): Vec3 {
            val oma3 = 6 * (1 - alpha)
            val alphaoma2 = 6 * alpha - 4
            val alpha2oma = (2 - 6 * alpha)
            val alpha3 = 6 * alpha

            out.x = (a.x * oma3 + 3 * c1.x * alphaoma2 + 3 * c2.x * alpha2oma + b.x * alpha3).toFloat()
            out.y = (a.y * oma3 + 3 * c1.y * alphaoma2 + 3 * c2.y * alpha2oma + b.y * alpha3).toFloat()
            out.z = (a.z * oma3 + 3 * c1.z * alphaoma2 + 3 * c2.z * alpha2oma + b.z * alpha3).toFloat()

            return out
        }

        fun ptSegDistSq3(
            x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, px: Float, py: Float, pz: Float
        ): Float {
            var x2 = x2
            var y2 = y2
            var z2 = z2
            var px = px
            var py = py
            var pz = pz
            x2 -= x1
            y2 -= y1
            z2 -= z1
            px -= x1
            py -= y1
            pz -= z1
            var dotprod = px * x2 + py * y2 + pz * z2
            val projlenSq: Float
            if (dotprod <= 0.0f) {
                projlenSq = 0.0f
            } else {
                px = x2 - px
                py = y2 - py
                pz = z2 - pz
                dotprod = px * x2 + py * y2 + pz * z2
                projlenSq = if (dotprod <= 0.0f) {
                    0.0f
                } else {
                    dotprod * dotprod / (x2 * x2 + y2 * y2 + z2 * z2)
                }
            }
            var lenSq = px * px + py * py + pz * pz - projlenSq
            if (lenSq < 0) {
                lenSq = 0.0f
            }
            return lenSq
        }


        /**
         * mutates c1 and c2
         */
        fun splitCubicFrame(
            a: Vec2,
            c1: Vec2,
            c2: Vec2,
            b: Vec2,
            alpha: Float,
            c12: Vec2,
            m: Vec2,
            c21: Vec2,
            tmp: Vec2
        ) {
            c1.lerp(c2, alpha, tmp)

            evaluateCubicFrame(a, c1, c2, b, alpha, m)

            a.lerp(c1, alpha, c1)
            c1.lerp(tmp, alpha, c12)

            c2.lerp(b, alpha, c2)
            tmp.lerp(c2, alpha, c21)
        }

        /**
         * mutates c1 and c2
         */
        fun splitCubicFrame(
            a: Vec3,
            c1: Vec3,
            c2: Vec3,
            b: Vec3,
            alpha: Float,
            c12: Vec3,
            m: Vec3,
            c21: Vec3,
            tmp: Vec3
        ) {
            c1.lerp(c2, alpha, tmp)

            evaluateCubicFrame(a, c1, c2, b, alpha, m)

            a.lerp(c1, alpha, c1)
            c1.lerp(tmp, alpha, c12)

            c2.lerp(b, alpha, c2)
            tmp.lerp(c2, alpha, c21)
        }

        fun javaShapeToFLine(f: Shape): FLine {
            return javaShapeToFLine(f, AffineTransform.getTranslateInstance(0.0, 0.0))
        }

        fun javaShapeToFLineFlat(f: Shape, tol: Float, levels: Int): FLine {
            var pi = f.getPathIterator(AffineTransform.getTranslateInstance(0.0, 0.0))
            pi = FlatteningPathIterator(pi, tol.toDouble(), levels)
            val cc = FloatArray(6)
            var lastAt: Vec2? = null
            var lastMoveTo: Vec2? = null

            val `in`: FLine = FLine()
            while (!pi.isDone()) {
                val ty = pi.currentSegment(cc)
                if (ty == PathIterator.SEG_CLOSE) {
                    if (lastMoveTo != null && lastAt!!.distance(lastMoveTo) > 1e-6) `in`.lineTo(
                        lastMoveTo.x, lastMoveTo.y
                    )
                    lastAt = null
                } else if (ty == PathIterator.SEG_CUBICTO) {
                    if (lastAt == null || (Math.abs(lastAt.x - cc[4]) + Math.abs(lastAt.y - cc[5]) > 1e-15)) `in`.cubicTo(
                        cc[0], cc[1], cc[2], cc[3], cc[4], cc[5]
                    )
                    if (lastAt == null) lastAt = Vec2(cc[4].toFloat(), cc[5].toFloat())
                    else {
                        lastAt.x = cc[4].toFloat()
                        lastAt.y = cc[5].toFloat()
                    }
                } else if (ty == PathIterator.SEG_LINETO) {
                    if (lastAt == null || (Math.abs(lastAt.x - cc[0]) + Math.abs(lastAt.y - cc[1]) > 1e-15)) `in`.lineTo(
                        cc[0], cc[1]
                    )
                    if (lastAt == null) lastAt = Vec2(cc[0].toFloat(), cc[1].toFloat())
                    else {
                        lastAt.x = cc[0].toFloat()
                        lastAt.y = cc[1].toFloat()
                    }
                } else if (ty == PathIterator.SEG_MOVETO) {
                    if (lastAt == null || (Math.abs(lastAt.x - cc[0]) + Math.abs(lastAt.y - cc[1]) > 1e-15)) `in`.moveTo(
                        cc[0], cc[1]
                    )

                    lastMoveTo = Vec2(cc[0].toFloat(), cc[1].toFloat())

                    if (lastAt == null) lastAt = Vec2(cc[0].toFloat(), cc[1].toFloat())
                    else {
                        lastAt.x = cc[0].toFloat()
                        lastAt.y = cc[1].toFloat()
                    }
                } else if (ty == PathIterator.SEG_QUADTO) {
                    if (lastAt == null || (Math.abs(lastAt.x - cc[2]) + Math.abs(lastAt.y - cc[3]) > 1e-15)) `in`.cubicTo(
                        (cc[0] - lastAt!!.x) * (2 / 3f) + lastAt!!.x,
                        (cc[1] - lastAt!!.y) * (2 / 3f) + lastAt!!.y,
                        (cc[0] - cc[2]) * (2 / 3f) + cc[2],
                        (cc[1] - cc[3]) * (2 / 3f) + cc[3],
                        cc[2].toFloat(),
                        cc[3].toFloat()
                    )

                    if (lastAt == null) lastAt = Vec2(cc[2].toFloat(), cc[3].toFloat())
                    else {
                        lastAt.x = cc[2].toFloat()
                        lastAt.y = cc[3].toFloat()
                    }
                }

                pi.next()
            }

            return `in`
        }

        fun javaShapeToFLine(f: Shape, at: AffineTransform?): FLine {
            val pi = f.getPathIterator(at)
            val cc = FloatArray(6)
            var lastAt: Vec2? = null
            var lastMoveTo: Vec2? = null

            val `in`: FLine = FLine()
            while (!pi.isDone) {
                val ty = pi.currentSegment(cc)
                if (ty == PathIterator.SEG_CLOSE) {
                    if (lastMoveTo != null && lastAt!!.distance(lastMoveTo) > 1e-6) `in`.lineTo(
                        lastMoveTo.x, lastMoveTo.y
                    )
                    lastAt = null
                } else if (ty == PathIterator.SEG_CUBICTO) {
                    if (lastAt == null || (Math.abs(lastAt.x - cc[4]) + Math.abs(lastAt.y - cc[5]) > 1e-15)) `in`.cubicTo(
                        cc[0].toFloat(),
                        cc[1].toFloat(),
                        cc[2].toFloat(),
                        cc[3].toFloat(),
                        cc[4].toFloat(),
                        cc[5].toFloat()
                    )
                    if (lastAt == null) lastAt = Vec2(cc[4].toFloat(), cc[5].toFloat())
                    else {
                        lastAt.x = cc[4].toFloat()
                        lastAt.y = cc[5].toFloat()
                    }
                } else if (ty == PathIterator.SEG_LINETO) {
                    if (lastAt == null || (Math.abs(lastAt.x - cc[0]) + Math.abs(lastAt.y - cc[1]) > 1e-15)) `in`.lineTo(
                        cc[0], cc[1]
                    )
                    if (lastAt == null) lastAt = Vec2(cc[0].toFloat(), cc[1].toFloat())
                    else {
                        lastAt.x = cc[0].toFloat()
                        lastAt.y = cc[1].toFloat()
                    }
                } else if (ty == PathIterator.SEG_MOVETO) {
                    if (lastAt == null || (Math.abs(lastAt.x - cc[0]) + Math.abs(lastAt.y - cc[1]) > 1e-15)) `in`.moveTo(
                        cc[0], cc[1]
                    )

                    lastMoveTo = Vec2(cc[0].toFloat(), cc[1].toFloat())

                    if (lastAt == null) lastAt = Vec2(cc[0].toFloat(), cc[1].toFloat())
                    else {
                        lastAt.x = cc[0].toFloat()
                        lastAt.y = cc[1].toFloat()
                    }
                } else if (ty == PathIterator.SEG_QUADTO) {
                    if (lastAt == null || (Math.abs(lastAt.x - cc[2]) + Math.abs(lastAt.y - cc[3]) > 1e-15)) `in`.cubicTo(
                        (cc[0] - lastAt!!.x) * (2 / 3f) + lastAt!!.x,
                        (cc[1] - lastAt!!.y) * (2 / 3f) + lastAt!!.y,
                        (cc[0] - cc[2]) * (2 / 3f) + cc[2],
                        (cc[1] - cc[3]) * (2 / 3f) + cc[3],
                        cc[2],
                        cc[3]
                    )

                    if (lastAt == null) lastAt = Vec2(cc[2].toFloat(), cc[3].toFloat())
                    else {
                        lastAt.x = cc[2].toFloat()
                        lastAt.y = cc[3].toFloat()
                    }
                }

                pi.next()
            }

            return `in`
        }

        fun javaShapeToFLine(f: Shape, copyDepthFrom: FLine, at: AffineTransform?): FLine {
            val s1: FLine = javaShapeToFLine(f, at)
            copyDepthFrom(s1, Function<Vec2, Float> { n: Vec2 ->
                var d = Float.POSITIVE_INFINITY
                var z = 0.0
                for (n2 in copyDepthFrom.nodes) {
                    run {
                        val dd = ((n2.to.x - n.x) * (n2.to.x - n.x) + (n2.to.y - n.y) * (n2.to.y - n.y)) as Float
                        if (dd < d) {
                            d = dd.toFloat()
                            z = n2.to.z.toDouble()
                        }
                    }
                    if (n2 is FLine.CubicTo) {
                        run {
                            val dd =
                                ((n2.c1.x - n.x) * (n2.c1.x - n.x) + (n2.c1.y - n.y) * (n2.c1.y - n.y)) as Float
                            if (dd < d) {
                                d = dd.toFloat()
                                z = n2.c1.z.toDouble()
                            }
                        }
                        run {
                            val dd =
                                (((n2).c2.x - n.x) * ((n2).c2.x - n.x) + ((n2).c2.y - n.y) * ((n2).c2.y - n.y)) as Float
                            if (dd < d) {
                                d = dd.toFloat()
                                z = (n2).c2.z.toDouble()
                            }
                        }
                    }
                }
                z.toFloat()
            })
            return s1
        }

        private fun copyDepthFrom(s1: FLine, close: Function<Vec2, Float>) {
            for (n in s1.nodes) {
                var z = close.apply(n.to.v2).toFloat()
                n.to.z = z
                if (n is FLine.CubicTo) {
                    z = close.apply((n as FLine.CubicTo).c1.v2)
                    (n as FLine.CubicTo).c1.z = z
                    z = close.apply((n as FLine.CubicTo).c2.v2)
                    (n as FLine.CubicTo).c2.z = z
                }
            }
//            s1.modify()
        }

        fun insetShape(f: FLine, amount: Float): FLine {
            val s = flineToJavaShape_notThickened(f)
            val ss = BasicStroke(amount, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(s)

            val sa = Area(s)
            val ssa = Area(ss)
            sa.subtract(ssa)
            return javaShapeToFLine(sa)
        }

        fun outsetShape(f: FLine, amount: Float): Shape {
            val s = flineToJavaShape_notThickened(f)
            val ss = BasicStroke(amount, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(s)

            val sa = Area(s)
            val ssa = Area(ss)
            sa.add(ssa)
            return sa
        }

        fun samplePoints(f: FLine, distance: Float): List<Vec3> {
            val cc: Cursor = f.cursor()
            val r: ArrayList<Vec3> = ArrayList<Vec3>()
            var D = 0.0
            while (D < cc.lengthD()) {
                r.add(cc.position())
                cc.setD(distance.let { D += it.toFloat(); D }.toFloat())
            }
            return r
        }

        fun drawRoundedRectInto(into: FLine, x: Float, y: Float, w: Float, h: Float, r: Float): FLine {
            into.moveTo(
                x + ctrlpts[0][0] * w + ctrlpts[0][1] * r, y + ctrlpts[0][2] * h + ctrlpts[0][3] * r
            )
            into.lineTo(
                x + ctrlpts[1][0] * w + ctrlpts[1][1] * r, y + ctrlpts[1][2] * h + ctrlpts[1][3] * r
            )
            into.cubicTo(
                x + ctrlpts[2][0] * w + ctrlpts[2][1] * r,
                y + ctrlpts[2][2] * h + ctrlpts[2][3] * r,
                x + ctrlpts[2][4] * w + ctrlpts[2][5] * r,
                y + ctrlpts[2][6] * h + (ctrlpts[2][7] * r),
                x + ctrlpts[2][8] * w + ctrlpts[2][9] * r,
                y + ctrlpts[2][10] * h + ctrlpts[2][11] * r
            )
            into.lineTo(
                x + ctrlpts[3][0] * w + ctrlpts[3][1] * r, y + ctrlpts[3][2] * h + ctrlpts[3][3] * r
            )
            into.cubicTo(
                x + ctrlpts[4][0] * w + ctrlpts[4][1] * r,
                y + ctrlpts[4][2] * h + ctrlpts[4][3] * r,
                x + ctrlpts[4][4] * w + ctrlpts[4][5] * r,
                y + ctrlpts[4][6] * h + (ctrlpts[4][7] * r),
                x + ctrlpts[4][8] * w + ctrlpts[4][9] * r,
                y + ctrlpts[4][10] * h + ctrlpts[4][11] * r
            )
            into.lineTo(
                x + ctrlpts[5][0] * w + ctrlpts[5][1] * r, y + ctrlpts[5][2] * h + ctrlpts[5][3] * r
            )
            into.cubicTo(
                x + ctrlpts[6][0] * w + ctrlpts[6][1] * r,
                y + ctrlpts[6][2] * h + ctrlpts[6][3] * r,
                x + ctrlpts[6][4] * w + ctrlpts[6][5] * r,
                y + ctrlpts[6][6] * h + (ctrlpts[6][7] * r),
                x + ctrlpts[6][8] * w + ctrlpts[6][9] * r,
                y + ctrlpts[6][10] * h + ctrlpts[6][11] * r
            )
            into.lineTo(
                x + ctrlpts[7][0] * w + ctrlpts[7][1] * r, y + ctrlpts[7][2] * h + ctrlpts[7][3] * r
            )
            into.cubicTo(
                x + ctrlpts[8][0] * w + ctrlpts[8][1] * r,
                y + ctrlpts[8][2] * h + ctrlpts[8][3] * r,
                x + ctrlpts[8][4] * w + ctrlpts[8][5] * r,
                y + ctrlpts[8][6] * h + (ctrlpts[8][7] * r),
                x + ctrlpts[8][8] * w + ctrlpts[8][9] * r,
                y + ctrlpts[8][10] * h + ctrlpts[8][11] * r
            )
            return into
        }

        /**
         * returns the 't' (in the cursor sense) that's closest to this Vec3
         */
        fun closestT(to: FLine, point: Vec3): Float {
            var best = Float.POSITIVE_INFINITY
            var tt = 0.0
            for (i in 1 until to.nodes.size) {
                val n1: FLine.Node = to.nodes.get(i)
                val n0: FLine.Node = to.nodes.get(i - 1)
                if (n1 is FLine.MoveTo) continue

                if (n1 is FLine.LineTo) {
                    val dir: Vec3 = Vec3(n1.to).sub(n0.to)
                    if (dir.length() == 0.0f) {
                        val dd: Float = n1.to.distance(point)
                        if (dd <= best) {
                            best = dd

                            tt = (i - 1).toDouble()
                        }
                    } else {
                        val len: Float = dir.length()
                        dir.mul(1 / len)
                        var d: Float = dir.dot(Vec3(point).sub(n0.to))
                        if (d < 0) d = 0.0f
                        if (d > len) d = len
                        val at: Vec3 = n0.to + dir * d
                        val dd: Float = at.distance(point)
                        if (dd < best) {
                            best = dd
                            tt = (d / len + (i - 1)).toDouble()
                        }
                    }
                } else {
                    val c = n1 as FLine.CubicTo
                    val t = CubicSegment3(n0.to, c.c1, c.c2, c.to).closestToPoint(point)
                    val dd: Float = evaluateCubicFrame(n0.to, c.c1, c.c2, c.to, t, Vec3()).distance(point)
                    if (dd < best) {
                        best = dd
                        tt = (t + (i - 1)).toDouble()
                    }
                }
            }
            return tt.toFloat()
        }

        fun intersectTwoLineSegments(a1: Vec3, a2: Vec3, b1: Vec3, b2: Vec3): Vec3? {
            val da: Vec3 = Vec3(a2).sub(a1)
            val db: Vec3 = Vec3(b2).sub(b1)
            val dc: Vec3 = Vec3(b1).sub(a1)

            if (dc.dot(da.cross(db, Vec3())) != 0.0f) // lines are not coplanar
                return null

            val s: Float =
                dc.cross(db, Vec3()).dot(
                    da.cross(db, Vec3())
                ) / da.cross(db, Vec3()).lengthSquared()
            if (s >= 0.0 && s <= 1.0f) {
                return a1 + da * s
            }

            return null
        }


        fun intersect(a: FLine, b: FLine): List<Vec3> {
            val ret: MutableList<Vec3> = ArrayList<Vec3>()

            for (i in 0 until a.nodes.size) {
                val ni: FLine.Node = a.nodes.get(i)
                if (ni is FLine.CubicTo) {
                    val ci = CubicSegment3(a.nodes.get(i - 1).to, ni.c1, (ni).c2, ni.to)
                    for (j in 0 until b.nodes.size) {
                        val nj: FLine.Node = b.nodes.get(j)
                        if (nj is FLine.CubicTo) {
                            val cj = CubicSegment3(b.nodes.get(j - 1).to, (nj).c1, (nj).c2, nj.to)
                            val intersections = ci.intersection(cj, 1f)
                            intersections.forEach(Consumer<Pair<CubicSegment3, CubicSegment3>> { x: Pair<CubicSegment3, CubicSegment3?> ->
                                ret.add(
                                    Vec3(x.first.a).add(x.first.d).mul(0.5f)
                                )
                            })
                        } else if (nj is FLine.LineTo) {
                            val intersections = ci.intersection(CubicSegment3(b.nodes.get(j - 1).to, nj.to), 1f)
                            intersections.forEach(Consumer<Pair<CubicSegment3, CubicSegment3>> { x: Pair<CubicSegment3, CubicSegment3?> ->
                                ret.add(
                                    Vec3(x.first.a).add(x.first.d).mul(0.5f)
                                )
                            })
                        }
                    }
                } else if (ni is FLine.LineTo) {
                    for (j in 0 until b.nodes.size) {
                        val nj: FLine.Node = b.nodes.get(j)
                        if (nj is FLine.CubicTo) {
                            val cj = CubicSegment3(b.nodes.get(j - 1).to, (nj).c1, (nj).c2, nj.to)
                            val intersections = cj.intersection(CubicSegment3(a.nodes.get(i - 1).to, ni.to), 1f)
                            intersections.forEach(Consumer<Pair<CubicSegment3, CubicSegment3>> { x: Pair<CubicSegment3, CubicSegment3?> ->
                                ret.add(
                                    Vec3(x.first.a).add(x.first.d).mul(0.5f)
                                )
                            })
                        } else if (nj is FLine.LineTo) {
                            val aa: Vec3? =
                                intersectTwoLineSegments(b.nodes.get(j - 1).to, nj.to, a.nodes.get(i - 1).to, ni.to)
                            if (aa != null) ret.add(aa)
                        }
                    }
                }
            }
            return ret
        }

        fun intersectX(a: FLine, x: Float): List<Vec3> {
            val ret: MutableList<Vec3> = ArrayList<Vec3>()

            for (i in 0 until a.nodes.size) {
                val ni: FLine.Node = a.nodes.get(i)
                if (ni is FLine.CubicTo) {
                    val ci = CubicSegment3(a.nodes.get(i - 1).to, (ni).c1, (ni).c2, ni.to, -x)
                    val roots = ci.findXZeroRoots()
                    roots.forEach(Consumer<Float> { xx: Float ->
                        val vv: Vec3 = ci.evaluate(xx)
                        vv.x += x
                        ret.add(vv)
                    })
                } else if (ni is FLine.LineTo) {
                    val aa: Vec3 = a.nodes.get(i - 1).to
                    val bb: Vec3 = ni.to
                    if (Math.min(aa.x, bb.x) <= x && Math.max(aa.x, bb.x) >= x) {
                        val ii: Vec3? = intersectTwoLineSegments(
                            aa,
                            bb,
                            Vec3(x, Math.max(aa.y, bb.y) + 1, 0.0f),
                            Vec3(x, Math.min(aa.y, bb.y) - 1, 0.0f)
                        )
                        if (ii != null) ret.add(ii)
                    }
                }
            }
            return ret
        }

        fun intersectY(a: FLine, x: Float): List<Vec3> {
            val ret: MutableList<Vec3> = ArrayList<Vec3>()

            for (i in 0 until a.nodes.size) {
                val ni: FLine.Node = a.nodes.get(i)
                if (ni is FLine.CubicTo) {
                    val ci =
                        CubicSegment3(a.nodes.get(i - 1).to, (ni).c1, (ni).c2, ni.to, 0.0f, -x)
                    val roots = ci.findYZeroRoots()
                    roots.forEach(Consumer<Float> { xx: Float ->
                        val vv: Vec3 = ci.evaluate(xx)
                        vv.x += x
                        ret.add(vv)
                    })
                } else if (ni is FLine.LineTo) {
                    val aa: Vec3 = a.nodes.get(i - 1).to
                    val bb: Vec3 = ni.to
                    if (Math.min(aa.x, bb.x) <= x && Math.max(aa.x, bb.x) >= x) {
                        val ii: Vec3? = intersectTwoLineSegments(
                            aa, bb, Vec3(Math.max(aa.x, bb.x) + 1, x, 0.0f), Vec3(
                                Math.min(aa.x, bb.x) - 1, x, 0.0f
                            )
                        )
                        if (ii != null) ret.add(ii)
                    }
                }
            }
            return ret
        }
    }
}


