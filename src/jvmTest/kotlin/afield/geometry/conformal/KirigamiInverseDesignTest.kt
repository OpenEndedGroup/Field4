package afield.geometry.conformal

import afield.examples.chain.KirigamiSolidChain
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KirigamiInverseDesignTest {
    @Test
    fun stencilCounts() {
        val p = KirigamiInverseDesign(n = 8)
        assertEquals(112, p.cuts.size)
        assertEquals(64, p.tiles.size)
        assertEquals(32, p.boundary.size)
    }

    @Test
    fun circleSolvesAndClosesToASquareThatBridgesIntoOneSolid() {
        val design = KirigamiInverseDesign(n = 8).solve()
        assertTrue(design.residual < 1e-8, "residual ${design.residual}")
        // Closed: the two sides of every cut coincide.
        val c = design.closed
        val worst = design.problem.cuts.maxOf { hypot(c[2 * it.endA] - c[2 * it.endB], c[2 * it.endA + 1] - c[2 * it.endB + 1]) }
        assertTrue(worst < 1e-6, "cut sides apart by $worst when closed")
        val (shut, open) = KirigamiSolidChain.measureDesign(design, gap = 0.01)
        for (m in listOf(shut, open)) {
            assertEquals(49, m.genus)
            assertEquals(1, m.parts)
            assertTrue(m.watertight)
        }
    }
}
