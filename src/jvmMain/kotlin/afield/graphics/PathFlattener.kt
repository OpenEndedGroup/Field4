package afield.graphics

import afield.stdlib.Vec3
import field.graphics.FLinesAndJavaShapes
import java.util.*
import kotlin.math.max
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
        // `lengthToDot` guards this case and `dotToLength` did not, so a line with no drawable
        // segment at all — a bare `moveTo`, or an empty FLine — reached `mappings[-1]` below and
        // threw IndexOutOfBoundsException out of `Cursor.d`. `sampleByDistance` reads `Cursor.d` in
        // its loop condition, so `FLine().moveTo(x, y).sampleByDistance(5f)` blew up.
        if (mappings.isEmpty()) return 0.0f

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
            // Report both terms: reporting only the flatness is actively misleading now, because the
            // curves that recurse deepest are the ones whose flatness is 0.
            System.err.println(
                "warning: maxSubDiv reached :" + dotStart + " " + dotEnd + " " + a + " " + c1 + " " + c2 + " " + b +
                    " -> flatness " + flatnessFor(a, c1, c2, b) + " skew " + parameterSkewFor(a, c1, c2, b)
            )
            // Emit the chord anyway. Returning empty-handed dropped this piece of the curve out of
            // the table entirely, which silently under-reports `length()` and shifts every distance
            // past this point — a much worse failure than a coarse mapping.
            emitLinearFrame(dotStart, dotEnd, a, b)
            return
        }
        val f = subdivisionErrorFor(a, c1, c2, b)
        if (f > tol || sub < minSubDiv) {
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

    /**
     * How badly a straight line from [a] to [b] stands in for this cubic, **for the purpose of
     * building an arc-length table**.
     *
     * This is deliberately not just [flatnessFor]. A leaf of this recursion becomes a [Mapping]
     * whose distance advances *linearly in `t`*, so a leaf is only acceptable when the cubic is
     * both geometrically straight and evenly parameterised, and [flatnessFor] can only see the
     * first of those. The classic counter-example is a cubic whose control points sit exactly on
     * the chord but bunched towards one end: its flatness is exactly `0.0`, so no tolerance can
     * ever force a subdivision, yet `t = 0.5` can be four fifths of the way along.
     *
     * The second term closes that hole: the distance between the curve's mid-*parameter* point
     * `B(0.5)` and the chord's mid-*point*. It is zero exactly when `t` is (locally) tracking
     * distance, and for the collinear example above it is 161 units on a 500 unit chord.
     *
     * Neither term subsumes the other. A symmetric S-curve has `B(0.5)` sitting precisely on the
     * chord's midpoint — the parameterisation term is 0 — while its control points are 200 units
     * off the chord. So take the larger of the two.
     */
    private fun subdivisionErrorFor(a: Vec3, c1: Vec3, c2: Vec3, b: Vec3): Float =
        max(flatnessFor(a, c1, c2, b), parameterSkewFor(a, c1, c2, b))

    private fun flatnessFor(a: Vec3, c1: Vec3, c2: Vec3, b: Vec3): Float {
        //	return (Float) CubicCurve2D.getFlatness(a.x, a.y, c1.x, c1.y, c2.x, c2.y, b.x, b.y);
        val f1 = FLinesAndJavaShapes.ptSegDistSq3(a.x, a.y, a.z, b.x, b.y, b.z, c1.x, c1.y, c1.z)
        val f2 = FLinesAndJavaShapes.ptSegDistSq3(a.x, a.y, a.z, b.x, b.y, b.z, c2.x, c2.y, c2.z)
        return sqrt(max(f1, f2))
    }

    /**
     * `|B(0.5) - (a + b) / 2|`. The Bernstein weights at `t = 0.5` are `1/8, 3/8, 3/8, 1/8`.
     */
    private fun parameterSkewFor(a: Vec3, c1: Vec3, c2: Vec3, b: Vec3): Float {
        val dx = 0.125f * (a.x + 3 * c1.x + 3 * c2.x + b.x) - 0.5f * (a.x + b.x)
        val dy = 0.125f * (a.y + 3 * c1.y + 3 * c2.y + b.y) - 0.5f * (a.y + b.y)
        val dz = 0.125f * (a.z + 3 * c1.z + 3 * c2.z + b.z) - 0.5f * (a.z + b.z)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        var maxSubDiv: Int = 10

        /**
         * Subdivide every cubic at least this many times whatever the error metric says.
         *
         * Adaptive subdivision is blind to symmetry: a cubic can be collinear *and* symmetric
         * about its chord midpoint, so that both terms of [subdivisionErrorFor] are exactly zero
         * while the parameterisation in between is still badly skewed. Two forced levels break
         * that symmetry — the halves of such a curve are not themselves symmetric — at a floor of
         * 4 leaves per cubic. Anything actually curved already produces hundreds of leaves, so
         * this only ever binds on the straight-ish segments where it is needed.
         */
        var minSubDiv: Int = 2
    }
}
