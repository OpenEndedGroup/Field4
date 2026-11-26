package afield.geometry.path

import afield.math.linalg.Vec3
import java.util.Locale
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkinnedLineTest {
    /** A bump from (0, 0) to (10, 0) that rises to (5, 2). */
    private fun bump() = FLine().apply {
        moveTo(0, 0)
        lineTo(5, 2)
        lineTo(10, 0)
    }

    private fun points(line: FLine) = line.nodes.map { it.storage.last() }.map { p ->
        listOf(p.x, p.y, p.z).joinToString(",") { String.format(Locale.ROOT, "%.2f", it).replace("-0.00", "0.00") }
    }

    @Test
    fun theBindPoseGivesTheShapeBack() {
        val skin = SkinnedLine(bump(), Vec3(0f, 0f, 0f), Vec3(10f, 0f, 0f))
        assertEquals(points(bump()), points(skin.pose(Vec3(0f, 0f, 0f), Vec3(10f, 0f, 0f), 0.0)))
    }

    @Test
    fun theCurveTurnsAndStretchesItsWidthLessThanItsLength() {
        // Twice as long and turned to point up: along, the bump doubles; across, it grows by
        // 2 to the power 0.5, and the bump's rise, which pointed up, now points left.
        val skin = SkinnedLine(bump(), Vec3(0f, 0f, 0f), Vec3(10f, 0f, 0f), across = 0.5)
        assertEquals(
            listOf("0.00,0.00,0.00", "-2.83,10.00,0.00", "0.00,20.00,0.00"),
            points(skin.pose(Vec3(0f, 0f, 0f), Vec3(0f, 20f, 0f), 0.0)),
        )
        // With across = 0 the bump keeps its height.
        val narrow = SkinnedLine(bump(), Vec3(0f, 0f, 0f), Vec3(10f, 0f, 0f), across = 0.0)
        assertEquals("-2.00,10.00,0.00", points(narrow.pose(Vec3(0f, 0f, 0f), Vec3(0f, 20f, 0f), 0.0))[1])
    }

    @Test
    fun aFlatCurveTurnedRightRoundStaysFlat() {
        val skin = SkinnedLine(bump(), Vec3(0f, 0f, 0f), Vec3(10f, 0f, 0f))
        assertEquals(
            listOf("10.00,0.00,0.00", "5.00,-2.00,0.00", "0.00,0.00,0.00"),
            points(skin.pose(Vec3(10f, 0f, 0f), Vec3(0f, 0f, 0f), 0.0)),
        )
    }

    @Test
    fun theMiddleLagsWhileTheEndsFollow() {
        val flat = FLine().apply {
            moveTo(0, 0)
            lineTo(5, 0)
            lineTo(10, 0)
        }
        // No lag at the ends, a second at the middle.
        val skin = SkinnedLine(flat, Vec3(0f, 0f, 0f), Vec3(10f, 0f, 0f), lag = { s -> 4 * s * (1 - s) })
        skin.pose(Vec3(0f, 0f, 0f), Vec3(10f, 0f, 0f), 0.0)
        val raised = skin.pose(Vec3(0f, 10f, 0f), Vec3(10f, 10f, 0f), 0.1)
        val (start, middle, end) = raised.nodes.map { it.storage.last() }
        assertEquals(10f, start.y)
        assertEquals(10f, end.y)
        assertEquals(10 * (1 - exp(-0.1)), middle.y.toDouble(), 1e-4)
        var t = 0.1
        repeat(100) { t += 0.1; skin.pose(Vec3(0f, 10f, 0f), Vec3(10f, 10f, 0f), t) }
        val settled = skin.pose(Vec3(0f, 10f, 0f), Vec3(10f, 10f, 0f), t + 0.1).nodes[1].storage.last()
        assertTrue(kotlin.math.abs(settled.y - 10f) < 1e-3, "the middle catches up once the ends stop, at ${settled.y}")
    }
}
