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

    @Test
    fun panelsAreNeverEvictedAndANewerPanelReplacesItsOwnBufferedContent() {
        val pressure = OutputBackPressure(maxPending = 1, bufferSize = 2)
        assertTrue(pressure.tryAcquire() >= 0) // the one slot is taken; everything else buffers
        for (c in "abcdefgh") pressure.bufferMessage("panel $c", coalesceKey = "box/$c")
        pressure.bufferMessage("line 1"); pressure.bufferMessage("line 2"); pressure.bufferMessage("line 3")
        pressure.bufferMessage("panel c again", coalesceKey = "box/c")
        val out = mutableListOf<String>()
        repeat(20) { pressure.release(); out += pressure.drainBuffer() }
        kotlin.test.assertEquals(
            listOf("panel a", "panel b", "panel d", "panel e", "panel f", "panel g", "panel h", "line 2", "line 3", "panel c again"),
            out
        )
        kotlin.test.assertEquals(1, pressure.takeSkipped()) // only "line 1" was dropped
    }

    @Test
    fun aReplacedPanelDoesNotJumpAheadOfAFrameBufferedAfterIt() {
        val pressure = OutputBackPressure(maxPending = 1, bufferSize = 4)
        assertTrue(pressure.tryAcquire() >= 0)
        pressure.bufferMessage("panel old", coalesceKey = "box/main")
        pressure.bufferMessage("clear")
        pressure.bufferMessage("panel new", coalesceKey = "box/main")
        val out = mutableListOf<String>()
        repeat(5) { pressure.release(); out += pressure.drainBuffer() }
        // "panel new" was sent after "clear"; it must arrive after it, or the clear wipes it.
        kotlin.test.assertEquals(listOf("clear", "panel new"), out)
    }

    @Test
    fun anUnpressuredFrameWaitsBehindBufferedOutputWithoutTakingASlot() {
        val pressure = OutputBackPressure(maxPending = 1, bufferSize = 4)
        assertFalse(pressure.enqueueBehind("early clear")) // nothing buffered: the caller sends it now
        assertTrue(pressure.tryAcquire() >= 0)
        pressure.bufferMessage("panel", coalesceKey = "box/main")
        assertTrue(pressure.enqueueBehind("clear"))
        pressure.bufferMessage("panel 2", coalesceKey = "box/other")
        pressure.release()
        // "clear" was sent after "panel", so it leaves after it, and it does not use up the slot "panel" took.
        kotlin.test.assertEquals(listOf("panel", "clear"), pressure.drainBuffer())
        pressure.release()
        kotlin.test.assertEquals(listOf("panel 2"), pressure.drainBuffer())
    }
}
