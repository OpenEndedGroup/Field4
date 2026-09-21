package afield.four.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The claims of `Marf.kt`, asserted rather than described.
 *
 * The thing under test is a *separation*: content, timing and addressing are three patterns, and
 * the system is only worth having if changing one of them provably leaves the others alone. So the
 * tests are mostly of the form "two readers, one bank" or "same reader, one field swapped", and the
 * stage boundaries are compared as exact [PatQ] rather than as doubles — a stage clock that
 * accumulates `Double` durations passes a tolerance test and still walks off the bar by a
 * sixteenth over a long take.
 */
class MarfTest {

    private fun spansOf(reader: MarfReader<Double>, count: Int): List<Pair<PatQ, PatQ>> {
        val out = mutableListOf<Pair<PatQ, PatQ>>()
        var t = PatQ.ZERO
        repeat(count) {
            val s = reader.stageAt(t)
            out += s.start to s.end
            t = s.end
        }
        return out
    }

    // ── stage duration is first class ─────────────────────────────────────────────────────

    @Test
    fun `patterned durations lay out unequal stages, exactly`() {
        // The design note's diagram: |--0--|--------.5--------|-.2-|----1----|
        val reader = marf(
            value = pat("<0 .5 .2 1>").nums(),
            duration = pat("<1 4 .25 2>").nums(),
            fields = MarfFieldClock.STAGES
        )
        assertEquals(
            listOf(
                PatQ.of(0) to PatQ.of(1),
                PatQ.of(1) to PatQ.of(5),
                PatQ.of(5) to PatQ.of(21, 4),
                PatQ.of(21, 4) to PatQ.of(29, 4)
            ),
            spansOf(reader, 4),
            "STAGES mode must read the k-th duration for the k-th stage"
        )
        assertEquals(
            listOf(0.0, 0.5, 0.2, 1.0),
            (0..3).map { reader.stageAt(spansOf(reader, 4)[it].first).target }
        )
    }

    @Test
    fun `in CYCLES mode durations that sum to a cycle lock the reader to the bar`() {
        // Read as control voltages, a duration pattern whose steps are their own lengths is
        // self-consistent: each stage lands exactly where the next duration is written.
        val reader = marf(value = constant(0.0), duration = pat(".25 .5 .125 .125").nums())
        assertEquals(
            listOf(
                PatQ.of(0) to PatQ.of(1, 4),
                PatQ.of(1, 4) to PatQ.of(3, 4),
                PatQ.of(3, 4) to PatQ.of(7, 8),
                PatQ.of(7, 8) to PatQ.of(1),
                PatQ.of(1) to PatQ.of(5, 4)
            ),
            spansOf(reader, 5)
        )
    }

    @Test
    fun `the stage clock does not drift over thousands of stages`() {
        val reader = marf(value = run(4), duration = constant(0.25))
        val s = reader.stageAt(PatQ.of(1000))
        assertEquals(PatQ.of(1000), s.start, "stage boundaries must stay exactly on the bar")
        assertEquals(4000L, s.index)
    }

    @Test
    fun `a zero duration cannot stall the walk`() {
        val reader = marf(value = constant(0.5), duration = constant(0.0))
        assertEquals(MARF_MIN_DURATION, reader.stageAt(PatQ.ZERO).duration)
        assertTrue(reader.stageAt(PatQ.of(1)).index > 0L)
    }

    @Test
    fun `a walk far beyond the durations fails loudly instead of hanging`() {
        val reader = marf(value = constant(0.5), duration = constant(0.0), name = "tiny")
        val failure = assertFailsWith<IllegalStateException> { reader.at(100_000.0) }
        assertTrue("tiny" in failure.message!!, "the error must name the reader: ${failure.message}")
    }

    // ── curves are first class ────────────────────────────────────────────────────────────

    @Test
    fun `hold steps and linear slews`() {
        val held = marf(value = pat("<0 1>").nums(), curve = marfCurvePattern(MarfCurve.Hold), fields = MarfFieldClock.STAGES)
        assertEquals(0.0, held.value(0.0), 1e-9)
        assertEquals(0.0, held.value(0.5), 1e-9, "a hold stage is a step, flat for its whole length")
        assertEquals(1.0, held.value(1.5), 1e-9)

        val slewed = marf(value = pat("<0 1>").nums(), curve = marfCurvePattern(MarfCurve.Linear), fields = MarfFieldClock.STAGES)
        assertEquals(0.5, slewed.value(1.5), 1e-9, "stage 1 ramps from 0 to 1; halfway is 0.5")
    }

    @Test
    fun `a whole number of osc cycles returns the output to where the stage started`() {
        val shape = MarfCurve.Osc(cycles = 3.0)
        assertEquals(0.0, marfCurveShape(shape, 0.0), 1e-12)
        assertEquals(0.0, marfCurveShape(shape, 1.0), 1e-9)
        assertEquals(1.0, marfCurveShape(shape, 1.0 / 6.0), 1e-9, "first peak a sixth of the way in")
        // Half a cycle is the S-curve case: departs from `from`, arrives at `to`.
        assertEquals(1.0, marfCurveShape(MarfCurve.Osc(0.5), 1.0), 1e-9)
    }

    @Test
    fun `exp and step shapes hit their ends`() {
        for (curve in listOf(MarfCurve.Linear, MarfCurve.Smooth, MarfCurve.Exp(3.0), MarfCurve.Step(4))) {
            assertEquals(0.0, marfCurveShape(curve, 0.0), 1e-9, "$curve must start at 0")
            assertEquals(1.0, marfCurveShape(curve, 1.0), 1e-9, "$curve must arrive at 1")
        }
        assertTrue(marfCurveShape(MarfCurve.Exp(3.0), 0.5) < 0.5, "exp:3 starts slowly")
        assertTrue(marfCurveShape(MarfCurve.Exp(1.0 / 3.0), 0.5) > 0.5, "log starts quickly")
        assertEquals(1.0 / 3.0, marfCurveShape(MarfCurve.Step(4), 0.4), 1e-9, "second of four treads")
    }

    @Test
    fun `the output is continuous across a stage boundary even when the curves differ`() {
        val reader = marf(
            value = pat("<0 1 .25 .8>").nums(),
            curve = curves("<linear exp smooth osc:2>"),
            fields = MarfFieldClock.STAGES
        )
        for (boundary in 1..3) {
            val before = reader.value(boundary - 1e-6)
            val after = reader.value(boundary.toDouble())
            assertEquals(before, after, 1e-4, "a jump at the boundary into stage $boundary")
        }
    }

    @Test
    fun `curves rejects a misspelling where it was typed`() {
        assertFailsWith<IllegalArgumentException> { curves("<linear exp hodl>") }
        assertEquals(MarfCurve.Exp(1.5), marfCurve("exp:1.5"))
        assertEquals(MarfCurve.Step(6), marfCurve("step:6"))
    }

    // ── content, addressing and time are independent ──────────────────────────────────────

    private val bank = marfBank(
        rampTo(0.0, label = "a"),
        rampTo(0.3, label = "b"),
        holdAt(0.2, label = "c"),
        oscTo(0.9, cycles = 3.0, label = "d"),
        rampTo(0.8, label = "e")
    )

    @Test
    fun `two readers over one bank follow different trajectories`() {
        val a = bank.read(pat("<0 1 2 1 3 4>").nums(), name = "a")
        val b = bank.read(pat("<0 2 4 1 3>").nums().slow(3), name = "b")

        assertEquals(listOf(0.0, 1.0, 2.0, 1.0, 3.0, 4.0, 0.0), (0..6).map { a.at(it + 0.5).address })
        // slow(3) holds each address for three cycles, and the stages here are one cycle long.
        assertEquals(listOf(0.0, 0.0, 0.0, 2.0, 2.0, 2.0, 4.0), (0..6).map { b.at(it + 0.5).address })

        // Same material, and yet at the same instant they are reading different things.
        assertNotEquals(a.at(3.5).label, b.at(3.5).label)
        assertEquals("d", a.at(4.5).label)
        assertEquals("e", b.at(6.5).label)
    }

    @Test
    fun `transforming only the address leaves the bank and the sibling reader untouched`() {
        val quarters = constant(0.25)
        val plain = bank.read(pat("0 1 2 3").nums(), duration = quarters, name = "plain")
        val flipped = bank.read(pat("0 1 2 3").nums().every(4) { it.rev() }, duration = quarters, name = "flipped")

        // Four stages per cycle, so the address pattern's four steps land one per stage.
        val plainAddresses = (0..7).map { plain.at(it * 0.25 + 0.05).address }
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 0.0, 1.0, 2.0, 3.0), plainAddresses)

        val flippedAddresses = (0..7).map { flipped.at(it * 0.25 + 0.05).address }
        assertEquals(
            listOf(3.0, 2.0, 1.0, 0.0, 0.0, 1.0, 2.0, 3.0),
            flippedAddresses,
            "every(4) { rev() } must reverse cycle 0 and leave cycles 1..3 alone"
        )

        // The bank did not move, and neither did the other reader.
        assertEquals(listOf("a", "b", "c", "d", "e"), bank.stages.map { it.label })
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), (0..3).map { plain.at(it * 0.25 + 0.05).address })
    }

    @Test
    fun `a thinned address pattern holds rather than snapping to zero`() {
        // E(5,8) pulses on steps 0,2,3,5,6, so steps 1, 4 and 7 are rests. A rest must hold the
        // address the previous stage had — the whole point of `struct` on an address is that it
        // says how long an address is held for, and a silent fall back to slot 0 would make
        // `degradeBy` and `euclid` unusable here.
        val reader = bank.read(struct(euclid(5, 8), pat("0 4 2 3").nums()), duration = constant(0.125))
        val addresses = (0..7).map { reader.at(it * 0.125 + 0.02).address }
        assertEquals(listOf(0.0, 0.0, 4.0, 4.0, 4.0, 2.0, 3.0, 3.0), addresses)
        assertEquals(
            listOf(0.0, 4.0, 4.0, 2.0, 3.0),
            struct(euclid(5, 8), pat("0 4 2 3").nums()).events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value },
            "five pulses, and the held steps above are the three that are not there"
        )
    }

    @Test
    fun `bank addresses wrap so an address pattern need not know the bank length`() {
        val reader = bank.read(pat("<5 6 -1>").nums())
        assertEquals("a", reader.at(0.5).label, "5 wraps to 0 in a five-stage bank")
        assertEquals("b", reader.at(1.5).label)
        assertEquals("e", reader.at(2.5).label, "-1 wraps to the last stage")
    }

    @Test
    fun `a duration pattern overrides the bank's own stage lengths`() {
        val own = bank.read(pat("<3>").nums())
        assertEquals(PatQ.ONE, own.stageAt(PatQ.ZERO).duration, "stage d was built with the default length")

        val overridden = bank.read(pat("<3>").nums(), duration = pat("<2>").nums())
        assertEquals(PatQ.of(2), overridden.stageAt(PatQ.ZERO).duration)

        val hurried = bank.read(pat("<3>").nums(), speed = constant(4.0))
        assertEquals(PatQ.of(1, 4), hurried.stageAt(PatQ.ZERO).duration, "speed divides whichever duration is in force")
    }

    @Test
    fun `a rest in the value or duration pattern holds that field too`() {
        val reader = marf(
            value = pat("0 ~ .8 ~").nums(),
            duration = pat(".25 ~ ~ ~").nums()
        )
        val stages = reader.stagesIn(PatArc(PatQ.ZERO, PatQ.ONE))
        assertEquals(4, stages.size, "the duration is held at .25 across the three rests")
        assertEquals(listOf(0.0, 0.0, 0.8, 0.8), stages.map { it.target })
        assertTrue(stages.all { it.duration == PatQ.of(1, 4) })
    }

    // ── continuous address ────────────────────────────────────────────────────────────────

    @Test
    fun `a fractional address blends the two stages it lies between`() {
        val two = marfBank(holdAt(0.0, duration = 1.0), holdAt(1.0, duration = 3.0))

        val mid = two.read(constant(0.5))
        assertEquals(0.5, mid.at(0.5).value, 1e-9, "midway between a 0 stage and a 1 stage")
        assertEquals(PatQ.of(2), mid.stageAt(PatQ.ZERO).duration, "and midway between a 1 and a 3 in time")

        val quarter = two.read(constant(0.25))
        assertEquals(0.25, quarter.at(0.5).value, 1e-9)
        assertEquals(
            PatQ.of(3, 2),
            quarter.stageAt(PatQ.ZERO).duration,
            "a quarter of the way from a 1-cycle stage to a 3-cycle one"
        )
    }

    @Test
    fun `an integer address reads exactly one stage, with no blend`() {
        val two = marfBank(holdAt(0.0), holdAt(1.0))
        assertEquals(1, two.componentsAt(1.0).size)
        assertEquals(1, two.componentsAt(0.0).size)
        assertEquals(2, two.componentsAt(0.5).size)
        assertEquals(1.0, two.componentsAt(0.5).sumOf { it.weight }, 1e-12, "weights must be a partition")
    }

    @Test
    fun `a continuous address glides through the bank instead of stepping`() {
        val ramp = marfBank(holdAt(0.0), holdAt(1.0), holdAt(2.0), holdAt(3.0))
        val glide = ramp.read(saw().range(0.0, 3.0), duration = constant(0.125))
        val values = (0..7).map { glide.at(it * 0.125 + 0.0625).value }
        assertTrue(
            values.zipWithNext().all { (a, b) -> b > a },
            "a rising address over a rising bank must rise stage by stage, not jump: $values"
        )
        assertTrue(values.any { it % 1.0 > 1e-6 }, "and it must land between slots: $values")
    }

    // ── the two ways out ──────────────────────────────────────────────────────────────────

    @Test
    fun `signal is an ordinary pattern and composes with the existing algebra`() {
        val reader = marf(value = pat("<0 1>").nums(), fields = MarfFieldClock.STAGES)
        val scaled = reader.signal().range(100.0, 200.0)
        assertEquals(150.0, scaled.events(PatArc(PatQ.of(3, 2), PatQ.of(3, 2) + PatQ.of(1, 1000))).first().value, 0.5)

        // Discretising it gives real onsets back, which is what `segment` is for.
        val stepped = reader.signal().segment(4)
        assertEquals(4, stepped.events(PatArc(PatQ.ZERO, PatQ.ONE)).size)

        // And it can be the right operand of the parameter algebra.
        val voiced = pat("bd*4") with gain(reader.signal().range(0.2, 1.0))
        assertEquals(4, voiced.events(PatArc(PatQ.ZERO, PatQ.ONE)).size)
        assertTrue(voiced.events(PatArc(PatQ.ZERO, PatQ.ONE)).all { it.annotations.containsKey("gain") })
    }

    @Test
    fun `triggers are one event per stage, spanning that stage`() {
        val reader = marf(value = pat("<0 .5 1>").nums(), duration = constant(0.25))
        val events = reader.triggers().events(PatArc(PatQ.ZERO, PatQ.ONE))
        assertEquals(4, events.size)
        assertEquals(
            listOf(PatQ.of(0), PatQ.of(1, 4), PatQ.of(1, 2), PatQ.of(3, 4)),
            events.map { it.arc.start }
        )
        assertTrue(events.all { it.whole?.dur == PatQ.of(1, 4) })

        // A stage clipped by the query keeps its real length in `whole`, so `respond` strikes once.
        val half = reader.triggers().events(PatArc(PatQ.of(1, 8), PatQ.of(5, 8)))
        assertEquals(3, half.size)
        assertEquals(PatQ.of(1, 8), half.first().arc.start)
        assertEquals(PatQ.ZERO, half.first().extent.start)
        assertTrue(!half.first().hasOnset, "the clipped first fragment is not an onset")
    }

    @Test
    fun `a trigger pattern thins the pulses without touching the continuous output`() {
        val all = marf(value = pat("0 .3 .6 .9").nums(), duration = constant(0.25))
        val thinned = marf(
            value = pat("0 .3 .6 .9").nums(),
            duration = constant(0.25),
            trigger = bools("1 0 0 1")
        )
        val cycle = PatArc(PatQ.ZERO, PatQ.ONE)
        assertEquals(4, all.triggers().events(cycle).size)
        assertEquals(2, thinned.triggers().events(cycle).size)
        for (t in listOf(0.1, 0.4, 0.6, 0.95)) {
            assertEquals(all.value(t), thinned.value(t), 1e-12, "the continuous output must be unchanged at $t")
        }
    }

    // ── caching is an optimisation, not a semantics ───────────────────────────────────────

    @Test
    fun `sampling the same instant twice, and backwards, gives the same answer`() {
        val reader = bank.read(pat("<0 1 2 1 3 4>").nums(), duration = pat("<1 .5 2 .25>").nums())
        val forwards = (0..40).map { reader.at(it * 0.25).value }
        val backwards = (40 downTo 0).map { reader.at(it * 0.25).value }.reversed()
        assertEquals(forwards, backwards, "the timeline must be a function of the origin, not of query order")
        reader.reset()
        assertEquals(forwards, (0..40).map { reader.at(it * 0.25).value }, "and must survive a reset")
    }

    @Test
    fun `stagesIn returns every stage overlapping an arc, in order`() {
        val reader = marf(value = constant(1.0), duration = constant(0.25))
        val stages = reader.stagesIn(PatArc(PatQ.of(1, 8), PatQ.of(9, 8)))
        assertEquals(5, stages.size)
        assertEquals(PatQ.ZERO, stages.first().start)
        assertEquals(PatQ.of(5, 4), stages.last().end)
        assertEquals(stages.map { it.index }, (0L..4L).toList())
    }

    // ── discrete to continuous, with no reader at all ─────────────────────────────────────

    private fun PatPattern<Double>.sampledAt(t: Double): Double {
        val q = PatQ.fromDouble(t)
        return events(PatArc(q, q + PatQ.of(1, 1_000_000))).first().value
    }

    @Test
    fun `hold turns an event stream into a staircase defined everywhere`() {
        val held = pat("0 .5 1 .25").nums().hold()
        assertEquals(0.0, held.sampledAt(0.1), 1e-6)
        assertEquals(0.5, held.sampledAt(0.3), 1e-6)
        assertEquals(1.0, held.sampledAt(0.6), 1e-6)
        assertEquals(0.25, held.sampledAt(0.9), 1e-6)
        assertEquals(0.5, held.sampledAt(1.3), 1e-6, "and it keeps working in later cycles")
        // A held pattern is analog: it has no onsets of its own until you discretise it.
        assertTrue(held.events(PatArc(PatQ.ZERO, PatQ.ONE)).all { it.whole == null })
    }

    @Test
    fun `ramp interpolates between successive onsets and interp shapes that`() {
        val ramped = pat("0 1").nums().ramp()
        assertEquals(0.0, ramped.sampledAt(0.0), 1e-5)
        assertEquals(0.5, ramped.sampledAt(0.25), 1e-5, "halfway from the onset at 0 to the one at 1/2")
        assertEquals(1.0, ramped.sampledAt(0.5), 1e-5)

        val smooth = pat("0 1").nums().interp(MarfCurve.Smooth)
        assertEquals(0.5, smooth.sampledAt(0.25), 1e-5, "smoothstep is symmetric, so the midpoint agrees")
        assertTrue(smooth.sampledAt(0.125) < ramped.sampledAt(0.125), "but it leaves more slowly")

        // interp(Hold) is not hold(): a segment named by both its ends arrives at the *next*
        // value at once, so it is the staircase a step early. Both are wanted; neither is a bug.
        val source = pat("0 .5 1 .25").nums()
        assertEquals(0.5, source.hold().sampledAt(0.3), 1e-6)
        assertEquals(1.0, source.interp(MarfCurve.Hold).sampledAt(0.3), 1e-6)
        assertEquals(
            source.hold().sampledAt(0.3),
            source.interp(MarfCurve.Hold).sampledAt(0.05),
            1e-6,
            "and the early staircase lags the ordinary one by exactly one step"
        )
    }

    // ── the field clocks, and what they refuse ────────────────────────────────────────────

    /**
     * The ten address spellings one person actually tried, and what each of them used to mean.
     *
     * Not invented. `dev_docs/agents/demos/marf_demo/operations.yaml` records thirty-eight
     * edit-and-run cycles on one box between 03:38:58 and 03:42:10 UTC on 2026-09-21, about five
     * seconds apart, working through these. Eight of the ten produced a sequence byte-identical to
     * something strictly simpler than what was typed, and nothing said so.
     */
    private val theNightsAttempts = listOf(
        "<0 3>", "<0 3> 1", "1 <0 3>", "<0 3>*2", "<0 3>!2",
        "<0 3>, 1", "<0 3> 1*8", "<0 3> 1 2", "<0 3> <0 4>", "3 <0 3>"
    )

    private fun addressesUnder(spec: String, clock: MarfFieldClock, count: Int): List<Double> {
        val reader = marfValues(0, 1, 2, 3, 4).read(pat(spec).nums(), fields = clock, name = "probe")
        return reader.stagesIn(PatArc(PatQ.ZERO, PatQ.of(count.toLong()))).take(count).map { it.address }
    }

    @Test
    fun `STAGES refuses a field whose sub-cycle steps it cannot see, and names the field`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            marfValues(0, 1, 2, 3, 4).read(pat("<0 3> 1").nums(), fields = MarfFieldClock.STAGES, name = "addr")
        }
        val message = thrown.message ?: ""
        assertTrue("address" in message, "the refusal must name the field it is about: $message")
        assertTrue("'addr'" in message, "and the reader, so a four-reader box says which: $message")
        assertTrue("2 steps inside cycle 0" in message, "and what it saw: $message")
        assertTrue("MarfFieldClock.STEPS" in message, "and the remedy: $message")

        // Every non-address field is checked too, and each says its own name.
        val builds = listOf<Pair<String, () -> Unit>>(
            "value" to { marf(value = pat("0 1").nums(), fields = MarfFieldClock.STAGES); Unit },
            "duration" to {
                marf(value = pat("<0 1>").nums(), duration = pat("1 2").nums(), fields = MarfFieldClock.STAGES)
                Unit
            },
            "curve" to {
                marf(value = pat("<0 1>").nums(), curve = curves("linear exp"), fields = MarfFieldClock.STAGES)
                Unit
            },
            "trigger" to {
                marf(value = pat("<0 1>").nums(), trigger = bools("1 0"), fields = MarfFieldClock.STAGES)
                Unit
            },
            "speed" to {
                marf(value = pat("<0 1>").nums(), speed = pat("1 2").nums(), fields = MarfFieldClock.STAGES)
                Unit
            }
        )
        for ((field, build) in builds) {
            val failure = assertFailsWith<IllegalArgumentException>("the $field field was accepted") { build() }
            assertTrue(
                "the $field pattern" in (failure.message ?: ""),
                "the $field refusal named the wrong field: ${failure.message}"
            )
        }
    }

    @Test
    fun `a stack is refused in both sequence readings, because no clock can read one`() {
        // "<0 3>, 1" has exactly ONE onset per cycle, so a step count does not catch it. Two events
        // share that instant, patSampleOver takes the first, and the second layer is unreachable.
        assertEquals(
            1,
            marfFieldSteps(pat("<0 3>, 1").nums(), 0L).size,
            "this test is only meaningful while the stack is a single-step pattern"
        )
        for (clock in listOf(MarfFieldClock.STAGES, MarfFieldClock.STEPS)) {
            val thrown = assertFailsWith<IllegalArgumentException>("$clock accepted a stacked field") {
                marfValues(0, 1, 2, 3, 4).read(pat("<0 3>, 1").nums(), fields = clock, name = "stacked")
            }
            val message = thrown.message ?: ""
            assertTrue("stacks 2 layers" in message, "$clock: $message")
            assertTrue("address" in message, "$clock: $message")
            assertTrue(
                "MarfFieldClock.STEPS" !in message,
                "$clock must not suggest a mode that would not help either: $message"
            )
        }
    }

    @Test
    fun `the refusal does not fire on the legitimate spellings, and never fires in CYCLES`() {
        // The population: every field spelling this repository's own STAGES call sites use, plus
        // the defaults every reader gets whether it asks for them or not.
        val legitimate = listOf(
            "<0 3>", "<0 1 2 1 3 4>", "<0 2 4 1 3>", "<0 .5 .2 1>", "<1 4 .25 2>", "<1 0>", "<0 4>"
        )
        for (spec in legitimate) {
            marfValues(0, 1, 2, 3, 4).read(pat(spec).nums(), fields = MarfFieldClock.STAGES, name = "ok")
        }
        // slow(n) leaves cycles with no onset of their own; those are held stages, not a violation.
        marfValues(0, 1, 2, 3, 4)
            .read(pat("<0 2 4 1 3>").nums().slow(3), fields = MarfFieldClock.STAGES, name = "slow")
        // A signal has no onsets at all. It is one analog event per query, so it is one step.
        marfValues(0, 1, 2, 3, 4)
            .read(saw().slow(8).range(0.0, 4.0), fields = MarfFieldClock.STAGES, name = "glide")
        // And the full default field set, which is what `marf(value = …)` alone hands over.
        marf(value = pat("<0 1>").nums(), fields = MarfFieldClock.STAGES)
        marf(value = pat("<0 1>").nums(), fields = MarfFieldClock.STEPS)

        // CYCLES is exempt by design: a field is a control voltage there, and a four-step address
        // across a bar is the reading that mode exists for.
        for (spec in listOf("0 1 2 3", "<0 3> 1", "<0 3>, 1")) {
            marfValues(0, 1, 2, 3, 4).read(pat(spec).nums(), fields = MarfFieldClock.CYCLES, name = "cv")
        }
    }

    @Test
    fun `STEPS agrees with STAGES exactly wherever STAGES was already right`() {
        // The claim is equality, not closeness, over the population of patterns that have at most
        // one onset per cycle — which is every STAGES spelling in the tree.
        val agreeing = listOf("<0 3>", "<0 2 4 1 3>", "<0 1 2 1 3 4>", "<0 4>")
        for (spec in agreeing) {
            assertEquals(
                addressesUnder(spec, MarfFieldClock.STAGES, 12),
                addressesUnder(spec, MarfFieldClock.STEPS, 12),
                "STEPS must be a refinement of STAGES, not a rival, and \"$spec\" disagreed"
            )
        }

        // The load-bearing one: slow(3) must still hold each entry for three stages. That is what
        // the "at least one step per cycle" rule buys, and the gallery's reader B depends on it.
        val shared = marfValues(0, 1, 2, 3, 4)
        val held = { clock: MarfFieldClock ->
            shared.read(pat("<0 2 4 1 3>").nums().slow(3), fields = clock, name = "b")
                .stagesIn(PatArc(PatQ.ZERO, PatQ.of(9))).take(9).map { it.address }
        }
        assertEquals(
            listOf(0.0, 0.0, 0.0, 2.0, 2.0, 2.0, 4.0, 4.0, 4.0),
            held(MarfFieldClock.STAGES),
            "the published behaviour of slow(3) on an address"
        )
        assertEquals(held(MarfFieldClock.STAGES), held(MarfFieldClock.STEPS))
    }

    @Test
    fun `STEPS reads the sequence that was written, for every spelling of that night`() {
        // Under STAGES, eight of these ten were byte-identical to something simpler. Under STEPS
        // each is its own sequence, and each is the one the spelling looks like.
        val expected = mapOf(
            "<0 3>" to listOf(0.0, 3.0, 0.0, 3.0, 0.0, 3.0, 0.0, 3.0),
            "<0 3> 1" to listOf(0.0, 1.0, 3.0, 1.0, 0.0, 1.0, 3.0, 1.0),
            "1 <0 3>" to listOf(1.0, 0.0, 1.0, 3.0, 1.0, 0.0, 1.0, 3.0),
            "<0 3>*2" to listOf(0.0, 3.0, 0.0, 3.0, 0.0, 3.0, 0.0, 3.0),
            "<0 3>!2" to listOf(0.0, 0.0, 3.0, 3.0, 0.0, 0.0, 3.0, 3.0),
            "<0 3> 1 2" to listOf(0.0, 1.0, 2.0, 3.0, 1.0, 2.0, 0.0, 1.0),
            "<0 3> <0 4>" to listOf(0.0, 0.0, 3.0, 4.0, 0.0, 0.0, 3.0, 4.0)
        )
        for ((spec, want) in expected) {
            assertEquals(want, addressesUnder(spec, MarfFieldClock.STEPS, 8), "STEPS of \"$spec\"")
        }

        // Nine of the ten are readable by one clock or the other. The tenth is the stack, and it is
        // refused by both — which is the honest answer, not an omission.
        val readable = theNightsAttempts.count { spec ->
            runCatching { addressesUnder(spec, MarfFieldClock.STEPS, 4) }.isSuccess
        }
        assertEquals(9, readable, "exactly one of the ten attempts should be beyond every clock")

        // The count that carries the argument. Under STAGES the nine non-stack spellings give three
        // distinct sequences between them — six of the nine are a duplicate of a simpler one's
        // reading. Under STEPS they give seven.
        val underStages = theNightsAttempts
            .mapNotNull { spec -> runCatching { addressesUnder(spec, MarfFieldClock.CYCLES, 8) }.getOrNull() }
        assertEquals(10, underStages.size, "CYCLES reads all ten, which is why it stays the default")
        val distinctUnderSteps = theNightsAttempts
            .mapNotNull { spec -> runCatching { addressesUnder(spec, MarfFieldClock.STEPS, 8) }.getOrNull() }
            .distinct()
        assertTrue(
            distinctUnderSteps.size >= 6,
            "STEPS should tell most of these nine spellings apart; it told ${distinctUnderSteps.size}"
        )
    }

    @Test
    fun `each field walks its own step grid, so two fields of different lengths stay independent`() {
        // An address with two steps a cycle and a duration with one. Each advances exactly one
        // entry per stage, in its own alphabet — which is the property that makes STEPS a clock
        // rather than a re-spelling of the address.
        val reader = marfValues(0, 1, 2, 3, 4).read(
            pat("<0 3> 1").nums(),
            duration = pat("<1 2>").nums(),
            fields = MarfFieldClock.STEPS,
            name = "mixed"
        )
        val stages = reader.stagesIn(PatArc(PatQ.ZERO, PatQ.of(24))).take(8)
        assertEquals(
            listOf(0.0, 1.0, 3.0, 1.0, 0.0, 1.0, 3.0, 1.0),
            stages.map { it.address },
            "the address advances one step per stage"
        )
        assertEquals(
            listOf(PatQ.ONE, PatQ.of(2), PatQ.ONE, PatQ.of(2), PatQ.ONE, PatQ.of(2), PatQ.ONE, PatQ.of(2)),
            stages.map { it.duration },
            "and the duration advances one step per stage, on its own grid, exactly"
        )
        // Exact spans, in PatQ. 1 + 2 + 1 + 2 = 6 cycles for four stages, with no Double anywhere.
        assertEquals(PatQ.ZERO, stages[0].start)
        assertEquals(PatQ.of(6), stages[4].start)
        assertEquals(PatQ.of(12), stages[7].end)
    }

    @Test
    fun `the step grid is what the refusal and the clock both read, and it is exact`() {
        assertEquals(listOf(PatQ.of(2)), marfFieldSteps(pat("<0 3>").nums(), 2L))
        assertEquals(
            listOf(PatQ.of(2), PatQ.of(5, 2)),
            marfFieldSteps(pat("<0 3> 1").nums(), 2L),
            "the second step of cycle 2 is exactly 5/2, not 2.5"
        )
        assertEquals(
            listOf(PatQ.of(1)),
            marfFieldSteps(pat("<0 2 4 1 3>").nums().slow(3), 1L),
            "a cycle a slow field has no onset in is still one step, and that is what holds it"
        )
        assertEquals(
            listOf(PatQ.of(3)),
            marfFieldSteps(constant(0.25), 3L),
            "a signal has no onsets, so it is one step per cycle and reads as it always did"
        )
    }

    @Test
    fun `a STEPS reader gives the same answer however it is asked, forwards or backwards`() {
        // The step cursor is the only stateful thing in a reader. A query that lands before the
        // retained timeline rewinds it to zero, so the same instant asked twice — in either order —
        // must give the same stage, exactly.
        val reader = marfValues(0, 1, 2, 3, 4)
            .read(pat("<0 3> 1 2").nums(), fields = MarfFieldClock.STEPS, name = "cursor")
        val forwards = (0..23).map { reader.stageAt(PatQ.of(it.toLong())).address }
        val backwards = (23 downTo 0).map { reader.stageAt(PatQ.of(it.toLong())).address }.reversed()
        assertEquals(forwards, backwards, "the step cursor must not depend on the order of queries")
        reader.reset()
        assertEquals(forwards, (0..23).map { reader.stageAt(PatQ.of(it.toLong())).address })
    }

    @Test
    fun `every reader knows which clock it was built with, and AUTO is resolved before it is asked`() {
        // AUTO is the default and never survives construction: a reader reports the reading it got.
        // A stepped address is a sequence, so it gets STEPS — this is the case that used to answer
        // CYCLES and read "<0 3> 1" as "<0 3>" without saying anything.
        assertEquals(MarfFieldClock.STEPS, marfValues(0, 1).read(pat("0 1").nums()).fieldClock)

        // A signal has no discrete event to step through, so it stays a voltage. Walking it would
        // sample once per cycle, and a cycle-periodic signal sampled once per cycle is a constant.
        assertEquals(
            MarfFieldClock.CYCLES,
            marfValues(0, 1).read(saw().range(0.0, 1.0)).fieldClock
        )

        // AUTO is decided from the LEADING field, so a stepped address with a continuous duration is
        // a sequence: one reader carries one clock.
        assertEquals(
            MarfFieldClock.STEPS,
            marfValues(0, 1).read(pat("0 1").nums(), duration = saw().range(0.5, 1.0)).fieldClock
        )

        // And an explicit clock is always carried through untouched.
        assertEquals(
            MarfFieldClock.CYCLES,
            marfValues(0, 1).read(pat("0 1").nums(), fields = MarfFieldClock.CYCLES).fieldClock
        )
        assertEquals(
            MarfFieldClock.STAGES,
            marfValues(0, 1).read(pat("<0 1>").nums(), fields = MarfFieldClock.STAGES).fieldClock
        )
        assertEquals(
            MarfFieldClock.STEPS,
            marf(value = pat("<0 1> 2").nums(), fields = MarfFieldClock.STEPS).fieldClock
        )
    }
}
