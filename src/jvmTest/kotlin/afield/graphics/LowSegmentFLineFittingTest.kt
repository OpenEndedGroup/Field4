package afield.graphics

import afield.stdlib.Vec3
import afield.stdlib.vec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LowSegmentFLineFittingTest {
    @Test
    fun keepsAlmostStraightPointsAsOneLine() {
        val points = (0..20).map { i ->
            val x = i.toFloat() * 0.5f
            vec(x, if (i % 2 == 0) 0.03f else -0.03f, 0f)
        }

        val result = points.fitLowSegmentFLineResult()

        assertEquals(1, result.segmentCount)
        assertIs<LowSegmentFLineFitPrimitive.Line>(result.primitives.single())
        assertEquals(2, result.line.nodes.size)
        assertIs<FLine.LineTo>(result.line.nodes[1])
    }

    @Test
    fun fitsUnevenlyParameterizedQuarterCircleAsOneArc() {
        val points = listOf(0.0, 0.01, 0.04, 0.16, 0.36, 0.64, 0.81, 1.0).map { u ->
            val theta = u * PI * 0.5
            Vec3((10.0 * cos(theta)).toFloat(), (10.0 * sin(theta)).toFloat(), 3f)
        }

        val result = points.fitLowSegmentFLineResult()

        assertEquals(1, result.segmentCount)
        val arc = assertIs<LowSegmentFLineFitPrimitive.Arc>(result.primitives.single())
        assertTrue(result.rmsError < 1e-3, "rms was ${result.rmsError}")
        assertTrue(arc.radius in 9.99..10.01, "radius was ${arc.radius}")
        assertIs<FLine.CubicTo>(result.line.nodes[1])
    }

    @Test
    fun splitsAClearCornerIntoTwoLines() {
        val points = buildList {
            for (i in 0..10) add(vec(i, 0, 0))
            for (i in 1..10) add(vec(10, i, 0))
        }

        val result = points.fitLowSegmentFLineResult()

        assertEquals(2, result.segmentCount)
        assertTrue(result.primitives.all { it is LowSegmentFLineFitPrimitive.Line })
        assertTrue(result.rmsError < 1e-6, "rms was ${result.rmsError}")
        assertEquals(3, result.line.nodes.size)
    }

    @Test
    fun canUseThreeSegmentsWhenEachSplitMatters() {
        val points = buildList {
            for (i in 0..8) add(vec(i, 0, 0))
            for (i in 1..8) add(vec(8, i, 0))
            for (i in 1..8) add(vec(8 + i, 8, 0))
        }

        val result = points.fitLowSegmentFLineResult()

        assertEquals(3, result.segmentCount)
        assertTrue(result.primitives.all { it is LowSegmentFLineFitPrimitive.Line })
        assertTrue(result.rmsError < 1e-6, "rms was ${result.rmsError}")
    }
}
