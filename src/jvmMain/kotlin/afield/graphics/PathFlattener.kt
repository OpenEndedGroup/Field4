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
    private val tol = tol.toFloat()


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
        if (mappings.size == 0) m.cumulativeDistanceAtEnd = b.distance(a).toFloat()
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
        val f = flatnessFor(a, c1, c2, b)
        if (f > tol) {
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
    }
}
