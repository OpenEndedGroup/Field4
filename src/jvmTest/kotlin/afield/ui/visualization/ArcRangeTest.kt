package afield.ui.visualization

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ArcRange]: `in` follows the arc a polar range slider describes, wrapped or not. */
class ArcRangeTest {
    @Test
    fun `a wrapped band contains the values on both sides of the top`() {
        val band = ArcRange(1700.0, 400.0)
        assertFalse(band.isEmpty())
        assertTrue(100.0 in band)
        assertTrue(1900.0 in band)
        assertTrue(400.0 in band && 1700.0 in band)
        assertFalse(1000.0 in band)
    }

    @Test
    fun `an unwrapped band is an ordinary range`() {
        val band = ArcRange(200.0, 800.0)
        assertTrue(500.0 in band)
        assertFalse(100.0 in band)
        assertTrue((200.0..800.0).arcContains(500.0))
    }
}
