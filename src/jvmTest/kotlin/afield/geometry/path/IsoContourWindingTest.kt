package afield.geometry.path

import afield.math.linalg.Vec2
import afield.media.image.Img
import afield.media.image.isoContourFLines
import afield.simulation.agents.flineSignedArea
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IsoContourWindingTest {
    private fun shoelace(loop: List<Vec2>): Double {
        var a = 0.0
        for (i in loop.indices) {
            val p = loop[i]; val q = loop[(i + 1) % loop.size]
            a += p.x.toDouble() * q.y - q.x.toDouble() * p.y
        }
        return a / 2
    }

    // inside (below iso) is the ring 1.5 < r < 2.5
    private val ring: ScalarField2D = { x, y -> abs(hypot(x, y) - 2.0) - 0.5 }

    @Test fun aHoleIsWoundOppositeToItsOuterLoopSoSignedAreasSumToTheRing() {
        val loops = generateIsoContours(ring, -3.0, 3.0, -3.0, 3.0, 120, 120, 0.0)
        assertEquals(2, loops.size)
        val areas = loops.map { shoelace(it) }.sortedBy { abs(it) }
        assertTrue(areas[0].sign != areas[1].sign, "outer and hole wound the same way: $areas")
        assertTrue(areas[1] > 0, "the outer boundary of an inside region runs counter-clockwise: $areas")
        val ringArea = PI * (2.5 * 2.5 - 1.5 * 1.5)
        assertEquals(ringArea, areas.sum(), 0.01 * ringArea)
    }

    @Test fun imageContoursOfAnAnnulusSumToItsArea() {
        val img = Img.generate(121, 121) { x, y -> ring((x - 60) / 20.0, (y - 60) / 20.0).toFloat() }
        val lines = img.isoContourFLines(0.0)
        assertEquals(2, lines.size)
        val ringArea = PI * (2.5 * 2.5 - 1.5 * 1.5) * 400
        assertEquals(ringArea, abs(lines.sumOf { flineSignedArea(listOf(it)) }), 0.01 * ringArea)
    }

    @Test fun manyLoopsOnAFineGridStitchWhole() {
        // 400 small discs on a 1600 x 1600 grid: every loop closes, none is broken into arcs
        val field: ScalarField2D = { x, y -> hypot(x % 4.0 - 2.0, y % 4.0 - 2.0) - 1.0 }
        val loops = generateIsoContours(field, 0.0, 80.0, 0.0, 80.0, 1600, 1600, 0.0)
        assertEquals(400, loops.size)
        loops.forEach { assertEquals(PI, shoelace(it), 0.01 * PI) }
    }
}
