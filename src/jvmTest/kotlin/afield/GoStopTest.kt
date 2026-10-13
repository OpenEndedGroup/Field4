package afield

import afield.stdlib.go
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoStopTest {
    @Test
    fun stopCancelsRunningGoBlock() {
        val box = Box("go-stop")
        var ticks = 0
        var finished = false
        box.go {
            try {
                while (true) {
                    ticks++
                    yield()
                }
            } finally {
                finished = true
            }
        }

        repeat(6) { Runloop.runLoop.main.frame() }
        assertTrue(ticks > 0)
        assertTrue(box.jobs.isNotEmpty())

        KotlinExecution().stop(box)
        repeat(6) { Runloop.runLoop.main.frame() }
        assertTrue(finished)
        val stoppedAt = ticks
        repeat(6) { Runloop.runLoop.main.frame() }
        assertEquals(stoppedAt, ticks)
        assertTrue(box.jobs.isEmpty())
    }

    @Test
    fun stopBeforeFirstFramePreventsGoBlockFromStarting() {
        val box = Box("go-before-frame")
        var started = false
        box.go { started = true }

        KotlinExecution().stop(box)
        repeat(6) { Runloop.runLoop.main.frame() }
        assertFalse(started)
        assertTrue(box.jobs.isEmpty())
    }
}
