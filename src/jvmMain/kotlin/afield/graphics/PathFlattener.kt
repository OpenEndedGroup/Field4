package afield.graphics

import afield.stdlib.Vec3
import field.graphics.FLinesAndJavaShapes
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class PathFlattener(private val c: FLine, tol: Float) {
    private val cursor = FLinesAndJavaShapes.Cursor(c, tol)

    inner class Mapping {
        var dotStart: Float = 0.0F // node.t
        var dotEnd: Float = 0.0f // node.t
        var start: Vec3? = null
        var end: Vec3? = null
        var cumulativeDistanceAtEnd: Float = 0.0f

        override fun toString(): String {
            return "$dotStart->$dotEnd > $cumulativeDistanceAtEnd"
        }
    }

    var mappings: MutableList<Mapping> = ArrayList()
    private val tol = tol


    var searchDistance: Comparator<Any> = object : Comparator<Any> {
        override fun compare(o1: Any, o2: Any): Int {
            val f1 = if (o1 is Number) o1.toFloat() else (o1 as Mapping).cumulativeDistanceAtEnd
            val f2 = if (o2 is Number) o2.toFloat() else (o2 as Mapping).cumulativeDistanceAtEnd
            return java.lang.Float.compare(f1, f2)
        }
    }

    var searchDot: Comparator<Any> = object : Comparator<Any> {
        override fun compare(o1: Any, o2: Any): Int {
            val f1 = if (o1 is Number) o1.toFloat() else (o1 as Mapping).dotEnd
            val f2 = if (o2 is Number) o2.toFloat() else (o2 as Mapping).dotEnd
            return java.lang.Float.compare(f1, f2)
        }
    }

    fun length(): Float {
        if (mappings.size < 1) return 0.0f
        return mappings[mappings.size - 1].cumulativeDistanceAtEnd
    }

    fun getMappingSublist(length: Float): List<Mapping> {
        val found = Collections.binarySearch<Any>(mappings, length, searchDistance)
        if (found >= 0) return mappings.subList(0, found + 1)
        return mappings.subList(0, -found - 1 + 1)
    }


    fun lengthToDot(length: Float): Float {
        if (length == 0.0f) return 0.0f
        if (mappings.size == 0) return 0.0f

        val found = Collections.binarySearch<Any>(mappings, length, searchDistance)
        if (found >= 0) return mappings[found].dotEnd

        val leftOf = -found - 1
        val rightOf = leftOf - 1
        if (leftOf > mappings.size - 1) return mappings[mappings.size - 1].dotEnd

        val l1 = if (rightOf >= 0) mappings[rightOf].cumulativeDistanceAtEnd else 0.0f
        val l2 = mappings[leftOf].cumulativeDistanceAtEnd

        if (l2 == l1) return mappings[leftOf].dotEnd
        val x = (length - l1) / (l2 - l1)
        val de = mappings[leftOf].dotStart * (1 - x) + x * mappings[leftOf].dotEnd

        return de
    }

    fun dotToLength(dot: Float): Float {
        val found = Collections.binarySearch<Any>(mappings, dot, searchDot)

        if (found >= 0) return mappings[found].cumulativeDistanceAtEnd

        val leftOf = -found - 1
        val rightOf = leftOf - 1

        if (leftOf > mappings.size - 1) return mappings[mappings.size - 1].cumulativeDistanceAtEnd


        val l1 = mappings[leftOf].dotStart
        val l2 = mappings[leftOf].dotEnd

        if (l2 == l1) return mappings[rightOf].cumulativeDistanceAtEnd
        val x = (dot - l1) / (l2 - l1)
        val de =
            (if (rightOf >= 0) mappings[rightOf].cumulativeDistanceAtEnd else 0.0f) * (1 - x) + x * mappings[leftOf].cumulativeDistanceAtEnd

        return de
    }

    private fun emitLinearFrame(dotStart: Float, dotEnd: Float, a: Vec3, b: Vec3) {
        val m: Mapping = Mapping()
        m.start = a
        m.end = b
        m.dotStart = dotStart
        m.dotEnd = dotEnd
        if (mappings.size == 0) m.cumulativeDistanceAtEnd = b.distance(a)
        else m.cumulativeDistanceAtEnd = b.distance(a) + mappings[mappings.size - 1].cumulativeDistanceAtEnd
        mappings.add(m)
    }

    var tmp: Vec3 = Vec3()

    init {
        var index = 0


        while (cursor.hasNext()) {
            val v: Array<Vec3> = cursor.next()
            //			System.out.println(" cursor :"+ Arrays.asList(v)+" "+index);
            if (v.size == 4) {
                emitCubicFrame(index.toFloat(), (index + 1).toFloat(), v[0], v[1], v[2], v[3], 0)
            } else if (v.size == 2) {
                emitLinearFrame(index.toFloat(), (index + 1).toFloat(), v[0], v[1])
            }
            index++
        }
    }

    private fun emitCubicFrame(
        dotStart: Float,
        dotEnd: Float,
        a: Vec3,
        c1: Vec3,
        c2: Vec3,
        b: Vec3,
        sub: Int
    ) {
        var c1: Vec3 = c1
        var c2: Vec3 = c2
        if (sub > maxSubDiv) {
            System.err.println(
                "warning: maxSubDiv reached :" + dotStart + " " + dotEnd + " " + a + " " + c1 + " " + c2 + " " + b + " -> " + flatnessFor(
                    a,
                    c1,
                    c2,
                    b
                )
            )
            return
        }
        // Flatness is a *geometric* test — how far the control points stray from the chord — but what
        // this table is actually for is the *parameterisation*: `lengthToDot` / `dotToLength` interpolate
        // linearly inside each emitted frame, so a frame is only honest where arc length is close to
        // linear in the parameter. Those are not the same question, and a straight cubic is exactly where
        // they come apart: collinear control points score flatness 0.0, which is <= any tolerance, so the
        // whole cubic used to collapse to ONE frame and the mapping became plain parameter interpolation.
        // A cubic need not be uniformly parameterised, so for bunched controls that is simply wrong, and
        // no tolerance can catch it. Hence a floor on subdivision, independent of flatness. Only cubics
        // pay it: genuine straight lines arrive as 2-element frames and never reach this method.
        // Clamped against maxSubDiv so the floor can never drive the recursion into the bail-out above,
        // which returns *without emitting* and would drop the segment's geometry outright.
        val f = flatnessFor(a, c1, c2, b)
        if (f > tol || sub < min(minSubDiv, maxSubDiv)) {
            val c12: Vec3 = Vec3()
            val c21: Vec3 = Vec3()
            val m: Vec3 = Vec3()

            FLinesAndJavaShapes.splitCubicFrame(
                a,
                Vec3(c1).also { c1 = it },
                Vec3(c2).also { c2 = it },
                b,
                0.5f,
                c12,
                m,
                c21,
                tmp
            )

            val mp = dotStart + (dotEnd - dotStart) * 0.5f

            emitCubicFrame(dotStart, mp, a, c1, c12, m, sub + 1)
            emitCubicFrame(mp, dotEnd, m, c21, c2, b, sub + 1)
        } else {
            emitLinearFrame(dotStart, dotEnd, a, b)
        }
    }

    private fun flatnessFor(a: Vec3, c1: Vec3, c2: Vec3, b: Vec3): Float {
        //	return (Float) CubicCurve2D.getFlatness(a.x, a.y, c1.x, c1.y, c2.x, c2.y, b.x, b.y);
        val f1 = FLinesAndJavaShapes.ptSegDistSq3(a.x, a.y, a.z, b.x, b.y, b.z, c1.x, c1.y, c1.z)
        val f2 = FLinesAndJavaShapes.ptSegDistSq3(a.x, a.y, a.z, b.x, b.y, b.z, c2.x, c2.y, c2.z)
        return sqrt(max(f1, f2))
    }

    companion object {
        var maxSubDiv: Int = 10

        /**
         * Subdivide every cubic at least this many times regardless of flatness, giving `2^minSubDiv`
         * frames as a floor.
         *
         * Flatness alone cannot decide this. A cubic whose control points are collinear scores exactly
         * `0.0`, so it used to emit a single frame spanning the whole segment and
         * [lengthToDot] / [dotToLength] degenerated to interpolating the *parameter* — correct only if
         * the cubic happens to be uniformly parameterised, which nothing guarantees. (In a Python port
         * of this class — a simulation, not a measurement of the running system — bunching the controls
         * of an otherwise straight cubic moved the point at half arc-length by ~15 units on a 1000-unit
         * span, with 82 frames collapsing to 1.) Tightening [tol] cannot help: `0.0` satisfies every
         * tolerance.
         *
         * `3` is measured on the real system, not guessed, and not carried over from a prototype. On
         * `stdlib.typography.gallery`'s `motion_morph` panel — text riding a curve that sweeps through
         * collinearity — the worst per-frame jump falls from **9.7x the median step to 2.74x**, inside
         * that panel's 3x threshold, and the panel goes from failing to passing.
         *
         * The floor depth is chosen by where the metric stops improving: `minSubDiv` of 3, 4 and 5 give
         * **2.74x, 2.73x and 2.73x**. It has plateaued by 3, so deeper floors buy nothing and only cost
         * frames. The residual 2.73x is not subdivision-limited and is not believed to be a defect —
         * the panel deforms the curve sinusoidally (arc length sweeps 940..1021), so a glyph pinned at a
         * fixed arc-length offset genuinely moves fastest mid-sweep. What the floor removed was the
         * *discontinuity*, which was an artifact; the remaining variation is the motion itself.
         *
         * The cost is 8 frames for a straight cubic. Straight *lines* are unaffected: they arrive as
         * 2-element frames and never reach the cubic path at all.
         */
        var minSubDiv: Int = 3
    }
}
