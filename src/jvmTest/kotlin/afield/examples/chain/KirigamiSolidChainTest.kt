package afield.examples.chain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KirigamiSolidChainTest {
    @Test
    fun hingeCountAndMechanism() {
        assertEquals(2 * 8 * 7, KirigamiSolidChain.hinges(8).size)
        for (deg in listOf(0.0, 20.0, 45.0)) assertTrue(KirigamiSolidChain.hingeGap(8, Math.toRadians(deg)) < 1e-12)
    }

    @Test
    fun solidGenusMatchesTileGraph() {
        for (deg in listOf(0.0, 30.0)) {
            val m = KirigamiSolidChain.measure(n = 4, angle = Math.toRadians(deg))
            assertEquals(9, m.predictedGenus)
            assertEquals(m.predictedGenus, m.genus)
            assertEquals(1, m.parts)
            assertTrue(m.watertight)
        }
    }
}
