package afield

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputBackPressureTest {
    @Test
    fun drainedMeansNoPendingBufferedOrSkippedOutput() {
        val pressure = OutputBackPressure(maxPending = 1, bufferSize = 1)
        assertTrue(pressure.isDrained())

        assertTrue(pressure.tryAcquire() >= 0)
        assertFalse(pressure.isDrained())

        pressure.bufferMessage("newest")
        pressure.release()
        assertFalse(pressure.isDrained())

        pressure.drainBuffer()
        pressure.release()
        assertTrue(pressure.isDrained())
    }
}
