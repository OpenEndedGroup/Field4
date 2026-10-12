package afield.server

import afield.Box
import afield.Message
import afield.ReactiveRuns
import afield.Runloop
import afield.StopPending
import afield._send
import afield.payload
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells a box's clients when a stop has not taken effect, so the box's status marker can say
 * "asked to stop, not responding".
 *
 * A stop is carried out on the runloop. When the runloop is blocked (a box sleeping or in a busy
 * loop, or a `propUp` pull of one, whose nested pump does not advance [Runloop.frame]), nothing
 * stops until the block ends, and the client's own running state can already say "stopped"
 * because the queued launch was cancelled. So the test is server-side: the stop is pending while
 * the runloop has not finished a tick since the stop was asked for, or while the box is still
 * inside a run. Watched from a daemon thread, which is the point: the runloop cannot report on
 * itself.
 */
internal object StopWatch {
    private const val PENDING_AFTER_MS = 600L
    private const val POLL_MS = 200L
    private const val GIVE_UP_MS = 10 * 60 * 1000L

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "afield-stop-watch").apply { isDaemon = true }
    }
    private val watching = ConcurrentHashMap<String, ScheduledFuture<*>>()

    fun watch(box: Box) {
        val askedAtFrame = Runloop.runLoop.frame
        val askedAt = System.currentTimeMillis()
        var shown = false
        watching.remove(box.id)?.cancel(false)
        lateinit var task: ScheduledFuture<*>
        task = executor.scheduleWithFixedDelay({
            val stuck = Runloop.runLoop.frame == askedAtFrame || ReactiveRuns.isRunning(box)
            val expired = System.currentTimeMillis() - askedAt > GIVE_UP_MS
            if (stuck && !expired) {
                if (!shown) {
                    shown = true
                    tell(box, true)
                }
            } else {
                if (shown) tell(box, false)
                watching.remove(box.id, task)
                task.cancel(false)
            }
        }, PENDING_AFTER_MS, POLL_MS, TimeUnit.MILLISECONDS)
        watching[box.id] = task
    }

    private fun tell(box: Box, pending: Boolean) {
        if (!box.isInsideInitialized()) return
        val m = Message("stop-pending").payload(StopPending(box.id, pending))
        box.inside.sessions.forEach { runCatching { _send(it, m) } }
    }
}
