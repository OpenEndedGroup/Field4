package afield.petri

import afield.four.synth.PatArc
import afield.four.synth.PatQ
import afield.four.synth.pat
import afield.stdlib.color
import afield.stdlib.pointed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live runtime and the drawing — the two halves that are deliberately *not* pure.
 *
 * `PetriRun` is a function of time and `PetriLiveTest` is about everything that is not: injecting a
 * token by hand, forcing a transition off the clock, and the picture that has to keep up with both.
 * The thing worth pinning down is the seam. A live net is built out of the same engine as a run, so
 * left alone it must play *exactly* what the run plays; interfered with, it must diverge in
 * precisely the way asked for and no other.
 */
class PetriLiveTest {

    private fun pingPong() = petri {
        grid = cyc(1, 4)
        place("a", tokens = 1)
        t("there", dur = cyc(1, 4), emit = "bd") { from("a"); to("b") }
        t("back", dur = cyc(1, 4), emit = "sd") { from("b"); to("a") }
    }

    // ── left alone, a live net is the run ─────────────────────────────────────────────────

    @Test
    fun `an untouched live net fires exactly what the deterministic run fires`() {
        val net = pingPong()
        val live = net.live()
        val heard = mutableListOf<String>()
        live.onFiring { firing -> firing.emit?.let { heard += it.toString() } }
        live.advanceTo(cyc(2))

        val fromRun = net.run().stepsUntil(cyc(2) + cyc(1, 64))
            .flatMap { it.firings }
            .mapNotNull { it.emit?.toString() }
        assertEquals(fromRun, heard, "the live path and the pure path are the same engine, so they must agree")
        assertTrue(heard.size >= 8)
    }

    @Test
    fun `advancing is idempotent - asking twice does not fire twice`() {
        val live = pingPong().live()
        val first = live.advanceTo(cyc(1)).size
        val second = live.advanceTo(cyc(1)).size
        assertTrue(first > 0)
        assertEquals(0, second, "the net has already been advanced to there")
    }

    // ── interference ──────────────────────────────────────────────────────────────────────

    @Test
    fun `injecting a token changes the marking and what becomes enabled`() {
        val live = pingPong().live()
        assertEquals(listOf("there"), live.net.enabled(live.marking), "only one token, so only one option")
        live.inject("b")
        assertEquals(setOf("there", "back"), live.net.enabled(live.marking).toSet())
        assertEquals(1, live.marking.count("b"))
    }

    @Test
    fun `an injected token can carry a pattern, which is how a section is dropped in by hand`() {
        val live = petri { place("verse") }.live()
        live.inject("verse", pat("bd*4"))
        assertTrue(live.marking["verse"].single().colour is afield.four.synth.PatPattern<*>)
    }

    @Test
    fun `removing takes the oldest token, and removing from an empty place is not an error`() {
        val live = petri { place("pool", tokens = 0) }.live()
        live.inject("pool", "first")
        live.inject("pool", "second")
        live.remove("pool")
        assertEquals(listOf<Any?>("second"), live.marking.colours("pool"))
        live.remove("pool")
        live.remove("pool")
        assertEquals(0, live.marking.count("pool"))
    }

    @Test
    fun `injecting into a place that does not exist is refused loudly`() {
        val live = pingPong().live()
        val thrown = runCatching { live.inject("nowhere") }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue("nowhere" in thrown.message.orEmpty())
    }

    @Test
    fun `forcing fires a transition off the clock and returns the firing`() {
        val live = pingPong().live()
        val firing = live.force("there")
        assertNotNull(firing)
        assertEquals("bd", firing.emit)
        assertEquals(0, live.marking.count("a"))
        assertEquals(1, live.marking.count("b"))
    }

    @Test
    fun `forcing a transition that is not enabled does nothing at all`() {
        val live = pingPong().live()
        assertNull(live.force("back"), "there is no token in 'b'")
        assertEquals(1, live.marking.count("a"), "and the marking is untouched")
        assertNull(live.force("no-such-transition"))
    }

    @Test
    fun `forcing still respects the net's logic - an inhibitor arc cannot be overridden`() {
        val live = petri {
            place("armed", tokens = 1)
            place("src", tokens = 1)
            t("blocked") { inhibit("armed"); from("src"); to("dst") }
        }.live()
        assertNull(live.force("blocked"), "the timing is yours; the logic is still the net's")
        live.clear("armed")
        assertNotNull(live.force("blocked"))
    }

    @Test
    fun `listeners hear forced firings as well as clocked ones`() {
        val live = pingPong().live()
        val heard = mutableListOf<String>()
        live.onFiring { heard += it.transition }
        live.force("there")
        live.advanceTo(cyc(1, 2))
        assertTrue(heard.size >= 2)
        assertEquals("there", heard.first())
    }

    @Test
    fun `unsubscribing stops the listener`() {
        val live = pingPong().live()
        var count = 0
        val stop = live.onFiring { count++ }
        live.force("there")
        stop()
        live.force("back")
        assertEquals(1, count)
    }

    @Test
    fun `interference redirects the future rather than replaying the past`() {
        val live = pingPong().live()
        live.advanceTo(cyc(1, 4))
        val before = live.marking
        // Drop a second token in: from here the net has two things circulating, not one.
        live.inject("a")
        assertEquals(before.count("b"), live.marking.count("b"), "the past is not rewritten")
        val fired = live.advanceTo(cyc(1, 2)).map { it.transition }.toSet()
        assertEquals(setOf("there", "back"), fired, "and now both can go at once")
    }

    @Test
    fun `reset returns a live net to its initial marking at time zero`() {
        val live = pingPong().live()
        live.advanceTo(cyc(4))
        live.reset()
        assertEquals(PatQ.ZERO, live.time)
        assertEquals(1, live.marking.count("a"))
    }

    // ── the metronome ─────────────────────────────────────────────────────────────────────

    @Test
    fun `petriTicks lays exact events on its grid and nowhere else`() {
        val ticks = petriTicks(cyc(1, 8))
        val onsets = ticks.events(PatArc(PatQ.ZERO, PatQ.ONE)).filter { it.hasOnset }.map { it.arc.start }
        assertEquals((0 until 8).map { cyc(it, 8) }, onsets)
    }

    @Test
    fun `petriTicks keeps its grid on a later cycle and across a partial query`() {
        val ticks = petriTicks(cyc(1, 4))
        val later = ticks.events(PatArc(PatQ.of(9), PatQ.of(10))).filter { it.hasOnset }.map { it.arc.start }
        assertEquals((0 until 4).map { PatQ.of(9) + cyc(it, 4) }, later)

        val halves = ticks.events(PatArc(PatQ.ZERO, cyc(1, 2))).filter { it.hasOnset } +
            ticks.events(PatArc(cyc(1, 2), PatQ.ONE)).filter { it.hasOnset }
        assertEquals(4, halves.size, "chopping the query must not create or lose a tick")
    }

    @Test
    fun `a tick pattern is pure, like everything the scheduler is allowed to see`() {
        val ticks = petriTicks(cyc(1, 16))
        val arc = PatArc(PatQ.of(3), PatQ.of(4))
        assertEquals(ticks.events(arc), ticks.events(arc))
    }

    // ── the drawing ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a ring is laid out as a ring, without being asked`() {
        val ring = petriRing("k", 3, 8, emit = "bd")
        val drawing = ring.frame()
        val radii = drawing.positions.values.map { kotlin.math.hypot(it.x.toDouble(), it.y.toDouble()) }
        assertEquals(16, drawing.positions.size, "eight places and eight transitions")
        // Positions are float32, so "on a circle" is to within a rounding error, not exactly.
        assertTrue(
            radii.all { kotlin.math.abs(it - radii.first()) < 1e-3 },
            "a cycle should be drawn on a circle, and these radii are $radii",
        )
    }

    @Test
    fun `a net that is not a cycle gets a force layout with everything in a distinct place`() {
        val net = petri {
            place("hub", tokens = 1)
            t("a") { from("hub"); to("x") }
            t("b") { from("hub"); to("y") }
            t("c") { from("x"); to("hub") }
        }
        val positions = net.frame().positions
        assertEquals(net.places.size + net.transitions.size, positions.size)
        val pairs = positions.values.toList()
        for (i in pairs.indices) for (j in i + 1 until pairs.size) {
            val distance = kotlin.math.hypot(
                (pairs[i].x - pairs[j].x).toDouble(),
                (pairs[i].y - pairs[j].y).toDouble(),
            )
            assertTrue(distance > 1.0, "two nodes landed on top of each other")
        }
    }

    @Test
    fun `the layout is deterministic, because a picture that moves every time is not a picture`() {
        val net = petri {
            place("hub", tokens = 1)
            t("a") { from("hub"); to("x") }
            t("b") { from("x"); to("y") }
            t("c") { from("y"); to("hub") }
        }
        assertEquals(net.frame().positions, net.frame().positions)
    }

    @Test
    fun `a frame knows what is enabled and what is sounding`() {
        val run = pingPong().run()
        val frame = run.frameAt(PatQ.ZERO)
        assertEquals(setOf("there"), frame.enabled)
        assertEquals(setOf("there"), frame.firing, "the transition firing at zero is sounding at zero")

        val later = run.frameAt(cyc(1, 4))
        assertEquals(setOf("back"), later.enabled, "the token has moved on")
    }

    @Test
    fun `drawing a net produces lines, and drawing an empty net produces none`() {
        assertTrue(pingPong().frame().toFLines().isNotEmpty())
        assertTrue(petri { }.frame().toFLines().isEmpty())
    }

    @Test
    fun `a firing transition is drawn differently from a quiet one`() {
        val net = pingPong()
        fun palette(drawing: PetriDrawing) =
            drawing.toFLines(labels = false).map { line ->
                val c = line.color
                listOf(c.x, c.y, c.z, c.w).joinToString(",") { "%.3f".format(it) }
            }.toSet()

        val quiet = palette(PetriDrawing(net, net.initial))
        val hot = palette(PetriDrawing(net, net.initial, enabled = setOf("there"), firing = setOf("there")))
        assertTrue(
            (hot - quiet).isNotEmpty(),
            "a firing transition must bring a colour the quiet drawing never uses",
        )
    }

    @Test
    fun `the SVG export is real SVG with something in it`() {
        val svg = petriRing("k", 3, 8, emit = "bd").frame().toSvg(600.0, 400.0)
        assertTrue(svg.startsWith("<svg") || "<svg" in svg, svg.take(120))
        assertTrue(svg.length > 500, "an eight-place ring should not render to almost nothing")
    }

    @Test
    fun `the summary names the places and admits when nothing is enabled`() {
        val stuck = petri { place("a"); t("never") { from("a"); to("b") } }
        val html = stuck.frame().summaryHtml()
        assertTrue("a" in html && "never" in html)
        assertTrue("stuck" in html, "a net that cannot move should say so: $html")
    }

    @Test
    fun `the ascii timeline has a row per step and a column per place`() {
        val timeline = pingPong().run().asciiTimeline(4)
        val rows = timeline.trim().lines()
        assertEquals(5, rows.size, "a header and four steps")
        assertTrue(rows[0].contains("a") && rows[0].contains("b"))
        assertTrue(rows[1].contains("there"))
    }

    @Test
    fun `a live frame reflects an injection immediately`() {
        val live = pingPong().live()
        assertFalse("back" in live.frame().enabled)
        live.inject("b")
        assertTrue("back" in live.frame().enabled)
    }

    @Test
    fun `a place holding a pattern draws a ring where a count draws a dot`() {
        fun dots(net: PetriNet) =
            net.frame().toFLines(labels = false).count { line -> line.pointed }

        // Same number of lines either way, so counting lines proves nothing — the difference is
        // that a count is a point sprite and a section is a drawn ring.
        assertEquals(1, dots(petri { place("p", tokens = 1) }), "a black token is a dot")
        assertEquals(0, dots(petri { place("p", pat("bd sd")) }), "a pattern token is a ring, not a dot")
    }
}
