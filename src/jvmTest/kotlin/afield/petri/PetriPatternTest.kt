package afield.petri

import afield.four.synth.PatArc
import afield.four.synth.PatPattern
import afield.four.synth.PatQ
import afield.four.synth.euclid
import afield.four.synth.every
import afield.four.synth.fast
import afield.four.synth.pat
import afield.four.synth.range
import afield.four.synth.rev
import afield.four.synth.segment
import afield.four.synth.slow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claim the whole musical layer rests on: **a net is a pure function of time**, so a net is a
 * `PatPattern`.
 *
 * `PatPattern`'s contract is not "produces events". It is "answers any arc, in any order, the same
 * way every time" — `rev()` reads a cycle backwards, `every(4)` re-queries under a transform, and
 * the scheduler walks forward one cycle at a time while the visualizer asks about the past. A
 * stateful sequencer cannot honour that. The first three tests here are the ones that would fail
 * if the memo in `PetriRun` ever became order-sensitive, and everything downstream would then be
 * subtly, unreproducibly wrong rather than loudly broken.
 */
class PetriPatternTest {

    private val pingPong = petri {
        grid = cyc(1, 4)
        place("a", tokens = 1)
        t("there", dur = cyc(1, 4), emit = "bd") { from("a"); to("b") }
        t("back", dur = cyc(1, 4), emit = "sd") { from("b"); to("a") }
    }

    private fun <A> PatPattern<A>.onsetsIn(cycle: Long): List<Pair<PatQ, A>> =
        events(PatArc(PatQ.of(cycle), PatQ.of(cycle + 1)))
            .filter { it.hasOnset }
            .sortedBy { it.arc.start }
            .map { it.arc.start to it.value }

    // ── purity ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the same arc queried twice gives identical events`() {
        val pattern = pingPong.emits()
        val arc = PatArc(PatQ.of(2), PatQ.of(3))
        assertEquals(pattern.events(arc), pattern.events(arc))
    }

    @Test
    fun `querying out of order does not change any answer`() {
        val pattern = pingPong.emits()
        fun window(cycle: Long) = PatArc(PatQ.of(cycle), PatQ.of(cycle + 1))

        val forwards = (0L..5L).map { pattern.events(window(it)) }

        val shuffled = pingPong.emits()
        shuffled.events(window(5))
        shuffled.events(window(0))
        shuffled.events(window(3))
        val backwards = (5L downTo 0L).map { shuffled.events(window(it)) }.reversed()

        assertEquals(forwards, backwards, "a run that answers differently when asked out of order is not a pattern")
        assertTrue(forwards.all { it.isNotEmpty() })
    }

    @Test
    fun `a sub-cycle query agrees with the whole cycle it sits inside`() {
        val pattern = pingPong.emits()
        val whole = pattern.events(PatArc(PatQ.ZERO, PatQ.ONE)).filter { it.hasOnset }
        val halves = pattern.events(PatArc(PatQ.ZERO, PatQ.of(1, 2))).filter { it.hasOnset } +
            pattern.events(PatArc(PatQ.of(1, 2), PatQ.ONE)).filter { it.hasOnset }
        assertEquals(whole.map { it.arc.start to it.value }, halves.map { it.arc.start to it.value })
    }

    // ── the language applies, which is the whole point ────────────────────────────────────

    @Test
    fun `fast doubles the onsets in a cycle`() {
        val plain = pingPong.emits()
        assertEquals(4, plain.onsetsIn(0).size, "four sixteenth-of-a-cycle steps at grid 1 over 4")
        assertEquals(8, plain.fast(2).onsetsIn(0).size)
    }

    @Test
    fun `rev reads the net's cycle backwards without disturbing it`() {
        val plain = pingPong.emits()
        val forwards = plain.onsetsIn(0).map { it.second }
        val backwards = plain.rev().onsetsIn(0).map { it.second }
        assertEquals(forwards.reversed(), backwards)
        assertEquals(forwards, plain.onsetsIn(0).map { it.second }, "rev must not have consumed anything")
    }

    @Test
    fun `every applies a transform on the cycles it says and no others`() {
        val plain = pingPong.emits()
        val varied = plain.every(2) { it.fast(2) }
        assertEquals(8, varied.onsetsIn(0).size, "cycle 0 is a multiple of 2, so it is doubled")
        assertEquals(4, varied.onsetsIn(1).size, "cycle 1 is not")
    }

    // ── emits ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a silent transition is dropped rather than sounded as null`() {
        val net = petri {
            grid = cyc(1, 4)
            place("a", tokens = 1)
            t("loud", emit = "bd") { from("a"); to("b") }
            t("plumbing") { from("b"); to("a") }
        }
        val sounds = net.emits().onsetsIn(0).map { it.second }
        assertEquals(listOf("bd", "bd"), sounds, "the plumbing transition moves a token and says nothing")
    }

    @Test
    fun `only and withoutTransitions split a net into lanes`() {
        val pattern = pingPong.pattern()
        assertEquals(listOf("there", "there"), pattern.only("there").onsetsIn(0).map { it.second.transition })
        assertEquals(listOf("back", "back"), pattern.withoutTransitions("there").onsetsIn(0).map { it.second.transition })
    }

    @Test
    fun `events are annotated with the transition that produced them`() {
        val event = pingPong.emits().events(PatArc(PatQ.ZERO, PatQ.ONE)).first()
        assertEquals("there", event.annotations["petri"])
        assertEquals("bd", event.annotations["emit"])
    }

    // ── a net reproduces the language's own Euclidean rhythm ──────────────────────────────

    @Test
    fun `a Euclidean token ring plays exactly what euclid plays`() {
        for ((pulses, steps) in listOf(3 to 8, 5 to 8, 4 to 16, 7 to 12)) {
            val fromNet = petriRing("k", pulses, steps, emit = "bd").emits().onsetsIn(0).map { it.first }
            val fromLanguage = euclid(pulses, steps)
                .events(PatArc(PatQ.ZERO, PatQ.ONE))
                .filter { it.value }
                .map { it.arc.start }
                .sorted()
            assertEquals(
                fromLanguage,
                fromNet,
                "a token walking a ring of $steps places must land on the same $pulses positions as euclid($pulses, $steps)",
            )
        }
    }

    @Test
    fun `a ring keeps playing the same rhythm on later cycles`() {
        val ring = petriRing("k", 3, 8, emit = "bd").emits()
        assertEquals(ring.onsetsIn(0).map { it.first }, ring.onsetsIn(7).map { it.first - PatQ.of(7) })
    }

    @Test
    fun `rotation moves the ring's hits and keeps their count`() {
        val plain = petriRing("k", 3, 8, emit = "bd").emits().onsetsIn(0).map { it.first }
        val turned = petriRing("k", 3, 8, rotation = 2, emit = "bd").emits().onsetsIn(0).map { it.first }
        assertEquals(plain.size, turned.size)
        assertTrue(plain != turned, "rotating by two of eight must actually move something")
    }

    // ── tokens that are patterns ──────────────────────────────────────────────────────────

    @Test
    fun `unfold plays a section's own pattern for the section's own length`() {
        val song = petri {
            timing = PetriTiming.TIMED
            place("verse", pat("bd sd"))
            t("play", dur = cyc(4)) { from("verse"); to("done") }
        }
        val notes = song.pattern().unfold<String>(PetriUnfold.NATURAL)
        // A one-cycle, two-event loop held for four cycles is eight events, at their natural rate.
        val over4 = (0L..3L).flatMap { notes.onsetsIn(it) }
        assertEquals(8, over4.size)
        assertEquals(List(4) { listOf("bd", "sd") }.flatten(), over4.map { it.second })
    }

    @Test
    fun `SQUEEZE fits the whole inner pattern into the firing instead`() {
        val song = petri {
            timing = PetriTiming.TIMED
            place("verse", pat("bd sd"))
            t("play", dur = cyc(4)) { from("verse"); to("done") }
        }
        val squeezed = song.pattern().unfold<String>(PetriUnfold.SQUEEZE)
        val over4 = (0L..3L).flatMap { squeezed.onsetsIn(it) }
        assertEquals(listOf("bd", "sd"), over4.map { it.second }, "one cycle of the inner pattern, stretched over four")
        assertEquals(listOf(PatQ.ZERO, PatQ.of(2)), over4.map { it.first })
    }

    @Test
    fun `a section change swaps which pattern is sounding`() {
        val song = petriSong(
            "verse" to (pat("bd bd") to 2),
            "drop" to (pat("sd sd") to 2),
        )
        val notes = song.pattern().unfold<String>()
        assertEquals(listOf("bd", "bd"), notes.onsetsIn(0).map { it.second })
        assertEquals(listOf("bd", "bd"), notes.onsetsIn(1).map { it.second })
        assertEquals(listOf("sd", "sd"), notes.onsetsIn(2).map { it.second })
        assertEquals(listOf("bd", "bd"), notes.onsetsIn(4).map { it.second }, "and it loops")
    }

    @Test
    fun `a note may not sound past the transition that ended its section`() {
        val song = petri {
            timing = PetriTiming.TIMED
            // A two-cycle-long note inside a section that only lasts one cycle.
            place("verse", pat("bd").slow(2))
            t("play", dur = cyc(1)) { from("verse"); to("done") }
        }
        val held = song.pattern().unfold<String>().events(PatArc(PatQ.ZERO, PatQ.of(4)))
        assertTrue(held.isNotEmpty())
        assertTrue(
            held.all { (it.whole ?: it.arc).end <= PatQ.ONE },
            "the section ends at cycle 1, so nothing it started may still be sounding after that",
        )
    }

    // ── markings as control signals ───────────────────────────────────────────────────────

    @Test
    fun `a place's token count reads back as a signal`() {
        val net = petri {
            grid = cyc(1, 4)
            place("src", tokens = 3)
            t("fill") { from("src"); to("tension") }
        }
        val run = net.run()
        val tension = run.signal("tension").segment(4)
        val counts = tension.onsetsIn(0).map { it.second }
        // `segment(4)` samples each quarter at its *midpoint* (1/8, 3/8, 5/8, 7/8), and the tick at
        // the top of each quarter has already fired by then — so the first sample reads 1, not 0.
        // Three tokens run out after the third tick, which is why the last two samples agree.
        assertEquals(listOf(1.0, 2.0, 3.0, 3.0), counts, "one token accumulates per quarter-cycle tick")
    }

    @Test
    fun `an interpolated signal is monotone and stays between the counts it joins`() {
        val net = petri {
            grid = cyc(1, 2)
            place("src", tokens = 2)
            t("fill") { from("src"); to("tension") }
        }
        val run = net.run()
        val smooth = run.signal("tension", PetriInterp.SMOOTH).segment(16).onsetsIn(0).map { it.second }
        assertEquals(smooth, smooth.sorted(), "a ramp between rising counts must not wobble")
        assertTrue(smooth.first() >= 0.0 && smooth.last() <= 2.0)
        assertTrue(smooth.toSet().size > 3, "a smoothed signal should take more than the step values")
    }

    @Test
    fun `level normalises a count so the shaping operators can be trusted`() {
        val net = petri {
            grid = cyc(1, 4)
            place("src", tokens = 4)
            place("tension", capacity = 4)
            t("fill") { from("src"); to("tension") }
        }
        val run = net.run()
        // `range` maps 0..1 and extrapolates outside it, so the raw count would sail past the top.
        val raw = run.signal("tension").range(200.0, 800.0).segment(4).onsetsIn(0).map { it.second }
        assertTrue(raw.any { it > 800.0 }, "the raw count is not a 0..1 signal, and range says so")

        // `level` reads the place's declared capacity, so no second number is needed.
        val swept = run.level("tension").range(200.0, 800.0).segment(4).onsetsIn(0).map { it.second }
        assertTrue(swept.all { it in 200.0..800.0 }, "level keeps a sweep inside its interval: $swept")
        assertEquals(swept, swept.sorted(), "and a filling place is a rising sweep")
    }

    @Test
    fun `a gate is one while the place is occupied and zero when it is not`() {
        val net = petri {
            grid = cyc(1, 4)
            place("armed", tokens = 1)
            place("fuse", tokens = 2)
            t("burn", priority = 5) { from("fuse"); to("ash") }
            // Only once the fuse has burned through does the inhibitor release and the place empty.
            t("disarm", priority = 1) { inhibit("fuse"); from("armed"); to("spent") }
        }
        val gate = net.run().gate("armed").segment(4).onsetsIn(0).map { it.second }
        assertEquals(listOf(1.0, 1.0, 0.0, 0.0), gate, "occupied for two ticks, then disarmed")
    }

    // ── sharing a run ─────────────────────────────────────────────────────────────────────

    @Test
    fun `two views onto one run see the same performance`() {
        val run = pingPong.run()
        val sounds = run.pattern().emits()
        val tension = run.signal("b")
        sounds.events(PatArc(PatQ.ZERO, PatQ.of(4)))
        // The signal reads the same trajectory the pattern already forced, not a second one.
        // Sampled at 1/8: the tick at 0 has moved the token into "b", the tick at 1/4 has not yet
        // moved it back, so the place is occupied.
        assertEquals(1.0, tension.events(PatArc(PatQ.ZERO, cyc(1, 4))).single().value)
        assertEquals(0.0, tension.events(PatArc(cyc(1, 4), cyc(1, 2))).single().value, "and empty again a tick later")
    }
}
