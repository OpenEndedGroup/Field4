package afield.graphics.interaction

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.graphics.render.circle
import afield.math.linalg.Vec2
import afield.math.linalg.x
import afield.runtime.execution.end
import afield.runtime.execution.start
import afield.graphics.vulkan.util.MouseEvent
import afield.geometry.path.FLine
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircleAndMouseUvTest {
    @Test
    fun aCircleIsFourCubicsWhoseMidpointsSitOnTheRadius() {
        val c = FLine().circle(2, 3, 5)
        val cubics = c.nodes.filterIsInstance<FLine.CubicTo>()
        assertEquals(4, cubics.size)
        cubics.forEach { assertEquals(5.0, hypot(it.to.x - 2.0, it.to.y - 3.0), 1e-4) }
        // Each quarter's midpoint, from the Bézier formula at t = 0.5, is within 0.03% of the radius.
        var start = c.nodes.first().to
        cubics.forEach { n ->
            val (c1, c2, end) = Triple(n.c1, n.c2, n.to)
            val mx = (start.x + 3 * c1.x + 3 * c2.x + end.x) / 8.0
            val my = (start.y + 3 * c1.y + 3 * c2.y + end.y) / 8.0
            assertTrue(abs(hypot(mx - 2.0, my - 3.0) - 5.0) < 5.0 * 3e-4, "midpoint off the circle")
            start = end
        }
    }

    @Test
    fun aCircleWithSegmentsIsAPolygonOnTheRadius() {
        val c = FLine().circle(0, 0, 1, segments = 12)
        val lines = c.nodes.filterIsInstance<FLine.LineTo>()
        assertTrue(lines.size >= 11)
        lines.forEach { assertEquals(1.0, hypot(it.to.x.toDouble(), it.to.y.toDouble()), 1e-5) }
    }

    @Test
    fun uvIsThePositionAsAFractionOfTheWindow() {
        val ev = MouseEvent(Vec2(200f, 50f), type = MouseEvent.Type.DRAG, windowSize = Vec2(800f, 400f))
        assertEquals(0.25f, ev.uv.x, 1e-6f)
        assertEquals(0.125f, ev.uv.y, 1e-6f)
    }
}
