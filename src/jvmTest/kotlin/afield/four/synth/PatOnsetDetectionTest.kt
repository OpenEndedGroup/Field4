package afield.four.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatOnsetDetectionTest {
    @Test
    fun `constant lag is estimated before applying the strict scoring window`() {
        val scheduled = listOf(0.0, 2.0, 2.333333, 2.666667, 3.0, 4.0, 6.0, 6.333333, 6.666667, 7.0)
        val observed = scheduled.mapIndexed { index, at -> if (index == 0) at else at - 0.002667 }

        val match = patMatchOnsets(observed, scheduled, tolerance = 0.002)

        // The first interval really is outside the 2 ms bound; the other nine share a recoverable
        // constant lag. The old circular estimator scored only the first onset (recall 0.1).
        assertEquals(scheduled.size - 1, match.matched)
        assertEquals(0.9, match.recall)
        assertTrue(match.worstError <= 0.000001, "unexpected residual error ${match.worstError}")
        assertTrue(match.offset < -0.0026, "the independently estimated lag was ${match.offset}")
    }

    @Test
    fun `a truncated recording scores only the schedule it could contain`() {
        val scheduled = (0..14).map(Int::toDouble)
        val observed = scheduled.take(11).map { it + 0.004 }

        val match = patMatchOnsets(observed, scheduled, tolerance = 0.001)

        assertEquals(11, match.scheduled)
        assertEquals(11, match.matched)
        assertEquals(1.0, match.recall)
    }
}
