package afield.math.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the 2026-11-02 docs-audit bug: an oscillator placed on a timeline has [Domain.ALL] as its
 * source domain, and DIRECT mapping read it at `-inf`, so `mark-marker` and `timelinegraph-asfn-tap`
 * plotted nothing and a placed `pulseTrain` plotted a constant.
 */
class TimelineUnboundedSourceTest {
    private fun spread(fn: (Double) -> Double, from: Double, to: Double): Pair<Int, Double> {
        var finite = 0
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in 0..200) {
            val y = fn(from + (to - from) * i / 200.0)
            if (y.isFinite()) {
                finite++; lo = minOf(lo, y); hi = maxOf(hi, y)
            }
        }
        return finite to (hi - lo)
    }

    @Test
    fun placedSineFollowsTheSourceFromItsStart() = with(Functions) {
        val src = sin(2.0)
        val t = timeline {
            mark("intro", 0.5)
            moveToMark("intro", 0.2)
            src.place()
        }
        assertEquals(0.7, t.domain.min, 1e-12)
        for (x in listOf(0.7, 0.9, 1.3, 1.6)) assertEquals(src.eval(x - 0.7), t.eval(x), 1e-12)
        val (finite, range) = spread({ t.eval(it) }, 0.7, 1.7)
        assertEquals(201, finite)
        assertTrue(range > 0.5, "placed sine is flat: range $range")
    }

    @Test
    fun placedPulseTrainPulses() = with(Functions) {
        val t = timeline { moveTo(0.15); pulseTrain(0.12).place() }
        val (_, range) = spread({ t.eval(it) }, 0.15, 1.15)
        assertTrue(range > 0.5, "placed pulseTrain is flat: range $range")
    }

    @Test
    fun tappedTimelineGraphIsDelayedNotEmpty() = with(Functions) {
        val g = timelineGraph { saw(0.2).place() }
        val tap = g.tap(offset = 0.12)
        val (finite, range) = spread({ tap.eval(it) }, 0.12, 1.12)
        assertEquals(201, finite)
        assertTrue(range > 0.1, "tapped saw is flat: range $range")
        assertEquals(g.eval(0.4), tap.eval(0.52), 1e-12)
    }
}
