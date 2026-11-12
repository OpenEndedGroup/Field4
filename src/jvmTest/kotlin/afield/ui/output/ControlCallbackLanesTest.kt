package afield.ui.output

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ControlCallbackLanesTest {
    private fun waitUntil(ms: Long = 10_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(done(), "timed out")
    }

    @Test fun aFastDragOnASlowRedrawSkipsValuesAndEndsOnTheLast() {
        val lane = "lane-${System.nanoTime()}"
        val calls = Collections.synchronizedList(mutableListOf<Int>())
        val started = System.nanoTime()
        // 100 updates in about a second and a half, to a callback that takes 30 ms: the old path ran
        // all 100, one after another (three seconds), and finished long after the last update.
        for (v in 1..100) {
            ControlCallbackLanes.submit(lane, "amount") { Thread.sleep(30); calls += v }
            Thread.sleep(15)
        }
        val lastSent = System.nanoTime()
        waitUntil { calls.lastOrNull() == 100 }
        val lag = (System.nanoTime() - lastSent) / 1e6
        assertTrue(calls.size < 70, "superseded values are skipped: ${calls.size} calls for 100 updates")
        assertEquals(calls.sorted(), calls, "values run in the order they were set")
        assertEquals(100, calls.last(), "the last value of the drag always runs")
        assertTrue(lag < 200, "the final redraw follows the last update promptly: ${lag.toInt()} ms (whole drag ${((lastSent - started) / 1e6).toInt()} ms)")
    }

    @Test fun aBoxsCallbacksNeverOverlapEvenAcrossControls() {
        val lane = "lane-${System.nanoTime()}"
        val inside = AtomicInteger()
        var overlapped = false
        val done = AtomicInteger()
        for (v in 1..20) for (control in listOf("a", "b", "c")) ControlCallbackLanes.submit(lane, control) {
            if (inside.incrementAndGet() > 1) overlapped = true
            Thread.sleep(3)
            inside.decrementAndGet()
            done.incrementAndGet()
        }
        Thread.sleep(400)
        waitUntil { inside.get() == 0 }
        assertFalse(overlapped, "one callback of a box at a time")
        assertTrue(done.get() >= 3, "every control ran at least its last value")
    }

    @Test fun runNowWaitsForTheRunningCallbackAndSupersedesTheWaitingValue() {
        val lane = "lane-${System.nanoTime()}"
        val calls = Collections.synchronizedList(mutableListOf<String>())
        ControlCallbackLanes.submit(lane, "x") { Thread.sleep(200); calls += "drag 1" }
        Thread.sleep(50)
        ControlCallbackLanes.submit(lane, "x") { calls += "drag 2" }
        val result = ControlCallbackLanes.runNow(lane, "x") { calls += "step"; 42 }
        assertEquals(42, result)
        Thread.sleep(300)
        assertEquals(listOf("drag 1", "step"), calls, "the step waits for the running callback and replaces the waiting one")
    }

    @Test fun aKeysPressesAndReleasesAreEdgesAndNeverSkipped() {
        val lane = "lane-${System.nanoTime()}"
        val calls = Collections.synchronizedList(mutableListOf<Boolean>())
        // Forty taps faster than the callback can take them: every down and every up must arrive, in order.
        repeat(40) {
            ControlCallbackLanes.submit(lane, "key", edge = true) { Thread.sleep(5); calls += true }
            ControlCallbackLanes.submit(lane, "key", edge = true) { Thread.sleep(5); calls += false }
        }
        waitUntil { calls.size == 80 }
        assertEquals(List(40) { listOf(true, false) }.flatten(), calls.toList())
    }

    @Test fun everyValueOptsOutOfSkipping() {
        val lane = "lane-${System.nanoTime()}"
        val id = "recorded-${System.nanoTime()}"
        ControlCallbackLanes.everyValue(id, true)
        val calls = Collections.synchronizedList(mutableListOf<Int>())
        try {
            for (v in 1..50) ControlCallbackLanes.submit(lane, id) { Thread.sleep(5); calls += v }
            waitUntil { calls.size == 50 }
            assertEquals((1..50).toList(), calls.toList(), "a recorder sees the whole path")
        } finally { ControlCallbackLanes.everyValue(id, false) }
    }

    @Test fun aSliderDeclaresEveryValueThroughTheControlsDsl() {
        val box = afield.document.graph.Box("every-${System.nanoTime()}").also { it.output.displayProxy = { _, _ -> } }
        box.controlGroup("controls") {
            slider("recorded", 0.0, 1.0, everyValue = true) { }
            slider("plain", 0.0, 1.0) { }
        }
        val ids = controlGroupControlsWithKeys(box, "controls").associate { it.second.label to it.second.id }
        val calls = Collections.synchronizedList(mutableListOf<String>())
        for (v in 1..20) {
            deliverControlCallback(ids.getValue("recorded")) { Thread.sleep(3); calls += "r$v" }
            deliverControlCallback(ids.getValue("plain")) { Thread.sleep(3); calls += "p$v" }
        }
        waitUntil { "p20" in calls && calls.count { it.startsWith("r") } == 20 }
        assertEquals(20, calls.count { it.startsWith("r") }, "everyValue = true: all 20")
        assertTrue(calls.count { it.startsWith("p") } < 20, "the plain slider skips superseded values")
    }
}
