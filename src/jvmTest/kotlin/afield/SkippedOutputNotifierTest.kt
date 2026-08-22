package afield

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The point of the accumulator is that a 60fps loop that skips an output every frame produces a
 * handful of toasts, not one per frame — and that the counts still add up.
 */
class SkippedOutputNotifierTest {

    private class Harness(interval: Long = 3_000L, burstReset: Long = 5_000L) {
        var now = 1_000L
        val emitted = mutableListOf<Long>()
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()

        val notifier = SkippedOutputNotifier(
            intervalMillis = interval,
            burstResetMillis = burstReset,
            clock = { now },
            schedule = { waitMillis, action -> timers.add(now + waitMillis to action) },
            emit = { total -> emitted.add(total) }
        )

        /** Advance the fake clock, running any trailing flush whose deadline has passed. */
        fun advance(millis: Long) {
            val target = now + millis
            while (true) {
                val due = timers.filter { it.first <= target }.minByOrNull { it.first } ?: break
                timers.remove(due)
                now = due.first
                due.second()
            }
            now = target
        }
    }

    @Test
    fun oneToastPerIntervalNotOnePerFrame() {
        val h = Harness()

        // 60fps of skipping for a second: the first one lands, the rest only accumulate.
        repeat(60) {
            h.notifier.note(1)
            h.advance(16)
        }

        assertEquals(listOf(1L), h.emitted)

        // Once the interval is up, one more toast carries the whole burst: 60 frames, 2 toasts.
        h.advance(2_100)
        assertEquals(listOf(1L, 60L), h.emitted)

        // And a skip arriving right after that one waits its turn rather than flickering.
        h.notifier.note(1)
        assertEquals(listOf(1L, 60L), h.emitted)
    }

    @Test
    fun trailingFlushReportsTheLastSkipsOfABurst() {
        val h = Harness()

        h.notifier.note(5)
        assertEquals(listOf(5L), h.emitted)

        // Skips that arrive inside the quiet interval and then stop still get reported once.
        h.notifier.note(7)
        assertEquals(listOf(5L), h.emitted)
        h.advance(3_100)
        assertEquals(listOf(5L, 12L), h.emitted)

        // And the trailing flush does not fire again with nothing new to say.
        h.advance(10_000)
        assertEquals(listOf(5L, 12L), h.emitted)
    }

    @Test
    fun aQuietGapStartsTheCountOver() {
        val h = Harness()

        h.notifier.note(4)
        h.advance(20_000)
        h.notifier.note(3)

        assertEquals(listOf(4L, 3L), h.emitted)
    }

    @Test
    fun anUninterruptedBurstReportsARunningTotal() {
        val h = Harness()

        // Three interval-spaced windows of skipping, no quiet gap long enough to reset.
        repeat(3) {
            repeat(30) {
                h.notifier.note(2)
                h.advance(50)
            }
            h.advance(1_600)
        }

        assertEquals(listOf(2L, 60L, 120L, 180L), h.emitted)
    }
}
