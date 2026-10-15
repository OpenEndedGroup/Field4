package afield.ui.output

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.app.server.OutputBackPressure
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
