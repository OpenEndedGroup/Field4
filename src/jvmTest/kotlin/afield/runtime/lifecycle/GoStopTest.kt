package afield.runtime.lifecycle

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus

import afield.document.graph.Box
import afield.document.graph.box
import afield.runtime.execution.KotlinExecution
import afield.runtime.execution.go
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
