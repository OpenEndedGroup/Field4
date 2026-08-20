package afield

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What a meter actually costs**, measured, because "30 Hz" is a claim about a rate and a rate is a
 * thing you can be wrong about.
 *
 * The end-to-end proof that a meter reaches a browser is a screenshot
 * (`dev_docs/agents/artifacts/control_surface_laws/live_meters_linear_and_log.png`, showing `0.05461`
 * arriving on a log meter). That proof says nothing about the *rate*, and nothing at all about what
 * happens with more than one meter — which is the question worth asking, because a per-meter message is
 * not a per-gesture message:
 *
 *  - a slider update is caused by a hand, so its rate is bounded by a human.
 *  - a meter update is caused by a clock, so **N meters cost N × 30 messages a second**, unbatched,
 *    down the same websocket that carries editor commands. Ten boxes with five meters each is 1500
 *    messages a second.
 *
 * Measured, and exactly linear: 1 / 4 / 16 meters emit **30.0 / 120.0 / 480.0** messages a second — i.e.
 * **30.0/s per meter, with a spread of 0.0/s** across all three. So that worry is arithmetic rather than
 * speculation. The coalescer does work (20 964 001 `set` calls in 700 ms became **21** messages, a
 * 998 285x reduction) and an unchanged level costs nothing (50 000 repeats → 0 messages). What is *not*
 * here is batching: nothing merges two meters into one frame. This pins the multiplier so a future
 * batching change has a number to beat.
 *
 * What is measured here is the rate at the **coalescer** — [MeterHandle.set], the gate every update
 * passes through — not at the browser. That boundary is chosen deliberately: it is the one that decides
 * how many messages exist, it is testable without a browser, and the delivery leg beyond it is
 * `broadcastControllerControlUpdates`, which is the same leg a slider already uses and is covered
 * elsewhere. Where a browser *is* required — the delivered frame rate under load — this file says so
 * rather than guessing.
 */
class MeterRateTest {

    private fun boxWithMeters(count: Int): Pair<Box, List<MeterHandle>> {
        val box = Box("meter-rate-test-${counter++}")
        box.output.displayProxy = { _, _ -> }
        val handles = mutableListOf<MeterHandle>()
        box.controlGroup("levels") {
            repeat(count) { index -> handles += meter("level $index", min = 0.0, max = 1.0) }
        }
        return box to handles
    }

    /**
     * One meter, pushed as fast as a loop can push it, is coalesced to the declared rate.
     *
     * The loop is a tight one on purpose — that is the audio-callback case, where a box will call `set`
     * far more often than anything needs to see. What the assertion pins is the *cap*: over a measured
     * window the number that escaped must be no more than the window allows, plus one for the boundary.
     */
    @Test
    fun `one meter coalesces an unbounded push down to the declared rate`() {
        val (_, handles) = boxWithMeters(1)
        val meter = handles.single()

        val windowMs = 700L
        val started = System.nanoTime()
        var attempts = 0L
        while ((System.nanoTime() - started) / 1_000_000 < windowMs) {
            // A changing value every time, so nothing is skipped by the "same value" short-circuit and
            // the rate limiter is the only thing doing any work.
            meter.set(attempts / 100_000.0 % 1.0)
            attempts++
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val (sent, dropped) = meter.counts
        val allowed = (elapsedMs * meterUpdateHz / 1000) + 1

        println(
            "MeterRateTest: $attempts set() calls in ${elapsedMs}ms → $sent sent, $dropped coalesced " +
                "(cap ${meterUpdateHz}Hz allows $allowed); that is a ${attempts / maxOf(sent, 1)}x reduction"
        )
        assertTrue(attempts > 10_000, "only $attempts pushes in ${elapsedMs}ms — the loop is not tight enough to prove anything")
        assertTrue(sent <= allowed, "$sent updates escaped in ${elapsedMs}ms, but ${meterUpdateHz}Hz allows at most $allowed")
        assertTrue(sent + dropped <= attempts, "the counters claim more updates than were attempted")
        // And it is not simply refusing everything: a 700 ms window at 30 Hz should let ~21 through.
        assertTrue(sent >= 18, "only $sent updates escaped in ${elapsedMs}ms; the meter is effectively dead")
    }

    /**
     * The cost is **per meter**, and this states the multiplier rather than implying it is amortised.
     *
     * Sixteen meters pushed together produce sixteen times the messages, because each has its own
     * coalescer and there is no batching anywhere. That is the honest shape of the current design and
     * the number a batching change would have to improve.
     */
    @Test
    fun `the message cost is per meter and unbatched`() {
        val counts = listOf(1, 4, 16)
        val perMeter = mutableListOf<Double>()

        counts.forEach { n ->
            val (_, handles) = boxWithMeters(n)
            val windowMs = 500L
            val started = System.nanoTime()
            var round = 0
            while ((System.nanoTime() - started) / 1_000_000 < windowMs) {
                handles.forEachIndexed { index, handle -> handle.set((round + index) / 1000.0 % 1.0) }
                round++
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val sent = handles.sumOf { it.counts.first }
            val ratePerSecond = sent * 1000.0 / elapsedMs
            perMeter += ratePerSecond / n
            println(
                "MeterRateTest: $n meter(s) → $sent messages in ${elapsedMs}ms = " +
                    "${"%.1f".format(ratePerSecond)}/s total, ${"%.1f".format(ratePerSecond / n)}/s per meter"
            )
        }

        // Each meter reaches its own cap independently: the per-meter rate must NOT fall as meters are
        // added (that would mean accidental sharing), and must not exceed the cap either.
        perMeter.forEach { rate ->
            assertTrue(
                rate <= meterUpdateHz + 3,
                "a meter emitted ${"%.1f".format(rate)}/s, above the ${meterUpdateHz}Hz cap"
            )
            assertTrue(
                rate >= meterUpdateHz - 6,
                "a meter emitted only ${"%.1f".format(rate)}/s; meters are interfering with each other"
            )
        }
        val spread = perMeter.max() - perMeter.min()
        println(
            "MeterRateTest: per-meter rate across 1/4/16 meters spread by only ${"%.1f".format(spread)}/s — " +
                "so the cost is linear in the number of meters. 16 meters is " +
                "${"%.0f".format(perMeter.average() * 16)} messages/second down one websocket."
        )
        // Measured spread across 1 / 4 / 16 meters: 0.0/s — each reaches the cap independently.
        assertTrue(spread < 3.0, "the per-meter rate varies by $spread/s with meter count; the cost is not linear")
    }

    /** An unchanged value costs nothing at all — the cheapest case, and the common one for a quiet signal. */
    @Test
    fun `an unchanged level is not sent again`() {
        val (_, handles) = boxWithMeters(1)
        val meter = handles.single()
        assertTrue(meter.set(0.25), "the first update should go out")
        var repeats = 0
        repeat(50_000) { if (meter.set(0.25)) repeats++ }
        val (sent, dropped) = meter.counts
        println("MeterRateTest: 50 000 repeats of the same level → $repeats sent, counters say $sent sent / $dropped coalesced")
        assertEquals(0, repeats, "an unchanged level was re-sent")
        assertEquals(1L, sent, "an unchanged level moved the sent counter")
        assertEquals(0L, dropped, "an unchanged level should short-circuit before the rate limiter")
    }

    /** A non-finite level is refused outright, so a broken source cannot put a `NaN` on the wire. */
    @Test
    fun `a non-finite level never leaves`() {
        val (_, handles) = boxWithMeters(1)
        val meter = handles.single()
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { poison ->
            assertTrue(!meter.set(poison), "$poison was accepted by a meter")
        }
        assertEquals(0L to 0L, meter.counts, "a non-finite level moved a counter")
    }

    @Test
    fun `a meter carries its owning document onto sampler threads`() {
        val root = Box("meter-document-root")
        val session = SessionInformation(
            filename = "/tmp/workspaces/document-a",
            documentName = "document-a",
            root = root,
            ephemeral = true
        )
        root.inside = session
        val handles = mutableListOf<MeterHandle>()

        root.controlGroup("levels") {
            handles += meter("level")
        }

        assertEquals(session.filename, handles.single().targetDocument)
    }

    @Test
    fun `a meter on an unattached box has no broadcast route`() {
        val root = Box("unattached-meter-root")
        val handles = mutableListOf<MeterHandle>()

        root.controlGroup("levels") {
            handles += meter("level")
        }

        assertEquals(null, handles.single().targetDocument)
    }

    /**
     * A meter is not in the box's value cache, so it cannot be restored across a rerun — and neither
     * can a button press.
     *
     * This is the cache invariant from the other side: `controlGroup` stores every *other* control's
     * value in a box property, and the two new controls are absent from it by construction. A press that
     * survived a rerun would re-fire; a meter reading has nothing to preserve.
     */
    @Test
    fun `neither a meter nor a button enters the box value cache`() {
        val box = Box("meter-rate-cache-${counter++}")
        box.output.displayProxy = { _, _ -> }
        var presses = 0
        box.controlGroup("mixed") {
            slider("gain", min = 0.0, max = 1.0, initialValue = 0.4) { }
            checkbox("bypass", initialValue = true) { }
            button("gate") { presses++ }
            meter("level", min = 0.0, max = 1.0, initialValue = 0.5)
        }

        @Suppress("UNCHECKED_CAST")
        val cached = box.properties["_controlGroupValues"] as? Map<String, Map<String, Any?>>
            ?: error("controlGroup wrote no value cache at all")
        val cachedTypes = cached.values.mapNotNull { it["type"]?.toString() }.toSet()
        println("MeterRateTest: the value cache holds ${cached.size} control(s), of type(s) $cachedTypes")

        assertEquals(setOf("slider", "checkbox"), cachedTypes, "the wrong set of control types is cached")
        assertTrue("button" !in cachedTypes, "a button press is being cached; it will re-fire on the next rerun")
        assertTrue("meter" !in cachedTypes, "a meter reading is being cached; it is an output")
        assertEquals(0, presses, "declaring a button fired its callback")

        // A rerun of the same block: the slider and checkbox come back, the button still does not fire.
        box.controlGroup("mixed") {
            slider("gain", min = 0.0, max = 1.0, initialValue = 0.4) { }
            checkbox("bypass", initialValue = true) { }
            button("gate") { presses++ }
            meter("level", min = 0.0, max = 1.0, initialValue = 0.5)
        }
        assertEquals(0, presses, "a rerun re-fired the button — this is the retrigger bug the design avoids")
    }

    /**
     * A meter with a `source` is sampled by the shared daemon, and stops being sampled when the box
     * reruns without it.
     *
     * The lifecycle is the part of the meter most likely to leak, so it is asserted rather than
     * described: declare two sampled meters, confirm both are sampled and both sources are actually
     * being called, then rerun the same channel declaring only one and confirm the other is retired.
     */
    @Test
    fun `a sampled meter is retired when the box stops declaring it`() {
        val box = Box("meter-rate-sampler-${counter++}")
        box.output.displayProxy = { _, _ -> }
        val callsA = java.util.concurrent.atomic.AtomicLong()
        val callsB = java.util.concurrent.atomic.AtomicLong()

        val before = activeMeterSamplerCount()
        box.controlGroup("sampled") {
            meter("a", source = { callsA.incrementAndGet() / 1000.0 % 1.0 })
            meter("b", source = { callsB.incrementAndGet() / 1000.0 % 1.0 })
        }
        assertEquals(before + 2, activeMeterSamplerCount(), "two sampled meters did not register two samplers")

        // Long enough for several ticks of a 30 Hz sampler.
        Thread.sleep(250)
        println("MeterRateTest: in 250ms the shared sampler called source A ${callsA.get()}x and source B ${callsB.get()}x")
        assertTrue(callsA.get() >= 3, "source A was only sampled ${callsA.get()} times in 250ms")
        assertTrue(callsB.get() >= 3, "source B was only sampled ${callsB.get()} times in 250ms")

        box.controlGroup("sampled") {
            meter("a", source = { callsA.incrementAndGet() / 1000.0 % 1.0 })
        }
        assertEquals(before + 1, activeMeterSamplerCount(), "the undeclared meter's sampler was not retired")

        val afterRetire = callsB.get()
        Thread.sleep(200)
        assertEquals(afterRetire, callsB.get(), "the retired source is still being called")

        // Leave nothing running for the next test.
        box.controlGroup("sampled") { }
        assertEquals(before, activeMeterSamplerCount(), "a control group with no meters left a sampler behind")
    }

    private companion object {
        private var counter = 0

        init {
            // Keeps `abs` referenced if the assertions above are ever rewritten in absolute terms.
            check(abs(-1.0) == 1.0)
        }
    }
}
