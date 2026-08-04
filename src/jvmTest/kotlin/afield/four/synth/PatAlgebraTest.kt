package afield.four.synth

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exactness tests for the parameter-application algebra in `PatAlgebra.kt`.
 *
 * The interesting claims here are *cardinality* claims — how many events survive when two
 * patterns meet — and they are asserted against exact rational arcs. Two tests deliberately
 * compute what the **pre-tonight** surface produced for the same musical intent, so the
 * improvement is measured rather than described.
 */
class PatAlgebraTest {

    private fun <A> PatPattern<A>.cycle(index: Long = 0L): List<PatEvent<A>> =
        events(PatArc(PatQ.of(index), PatQ.of(index + 1))).sortedWith(compareBy({ it.arc.start }, { it.arc.end }))

    private fun arcsOf(events: List<PatEvent<*>>): List<Pair<PatQ, PatQ>> = events.map { it.arc.start to it.arc.end }

    private val twos get() = pat("0 1").nums()
    private val threes get() = pat("10 20 30").nums()

    // ── structure choice ──────────────────────────────────────────────────────────────────

    @Test
    fun `structure from both intersects the two grids exactly`() {
        val events = twos.`|+|`(threes).cycle()
        assertEquals(
            listOf(
                PatQ.of(0, 1) to PatQ.of(1, 3),
                PatQ.of(1, 3) to PatQ.of(1, 2),
                PatQ.of(1, 2) to PatQ.of(2, 3),
                PatQ.of(2, 3) to PatQ.of(1, 1)
            ),
            arcsOf(events),
            "|+| must produce the intersection grid of a half-cycle and a third-cycle pattern"
        )
        assertEquals(listOf(10.0, 20.0, 21.0, 31.0), events.map { it.value })
    }

    @Test
    fun `structure from the left keeps exactly the left's events`() {
        val events = twos.`|+`(threes).cycle()
        assertEquals(listOf(PatQ.of(0, 1) to PatQ.of(1, 2), PatQ.of(1, 2) to PatQ.of(1, 1)), arcsOf(events))
        // Right values latched at each left onset: at 0 that is 10, at 1/2 it is 20.
        assertEquals(listOf(10.0, 21.0), events.map { it.value })
    }

    @Test
    fun `structure from the right keeps exactly the right's events`() {
        val events = twos.`+|`(threes).cycle()
        assertEquals(
            listOf(
                PatQ.of(0, 3) to PatQ.of(1, 3),
                PatQ.of(1, 3) to PatQ.of(2, 3),
                PatQ.of(2, 3) to PatQ.of(1, 1)
            ),
            arcsOf(events)
        )
        // Left values latched at each right onset: 0 at 0, 0 at 1/3, 1 at 2/3.
        assertEquals(listOf(10.0, 20.0, 31.0), events.map { it.value })
    }

    @Test
    fun `every arithmetic operator agrees with its structure choice`() {
        assertEquals(4, twos.`|-|`(threes).cycle().size)
        assertEquals(2, twos.`|-`(threes).cycle().size)
        assertEquals(3, twos.`-|`(threes).cycle().size)
        assertEquals(4, twos.`|*|`(threes).cycle().size)
        assertEquals(2, twos.`|*`(threes).cycle().size)
        assertEquals(3, twos.`*|`(threes).cycle().size)
        assertEquals(4, twos.`|over|`(threes).cycle().size)
        assertEquals(2, twos.`|over`(threes).cycle().size)
        assertEquals(3, twos.`over|`(threes).cycle().size)
        assertEquals(4, twos.`|%|`(threes).cycle().size)
        // and the prose aliases are the same functions.
        assertEquals(twos.`|+|`(threes).cycle().map { it.value }, (twos addBoth threes).cycle().map { it.value })
        assertEquals(twos.`|*`(threes).cycle().map { it.value }, (twos mulLeft threes).cycle().map { it.value })
        assertEquals(twos.`over|`(threes).cycle().map { it.value }, (twos divRight threes).cycle().map { it.value })
    }

    @Test
    fun `dividing by zero yields zero rather than an exception or an infinity`() {
        val values = pat("1 2").nums().`|over`(pure(0.0)).cycle().map { it.value }
        assertEquals(listOf(0.0, 0.0), values)
    }

    // ── algebraic laws ────────────────────────────────────────────────────────────────────

    @Test
    fun `a constant is the identity of the additive family`() {
        val original = pat("3 5 8").nums().cycle()
        for (combined in listOf(
            pat("3 5 8").nums().`|+`(constant(0.0)),
            pat("3 5 8").nums().`|+|`(constant(0.0)),
            pat("3 5 8").nums().`|*`(constant(1.0))
        )) {
            val got = combined.cycle()
            assertEquals(arcsOf(original), arcsOf(got))
            assertEquals(original.map { it.value }, got.map { it.value })
        }
    }

    @Test
    fun `structure from both is symmetric in its arcs`() {
        assertEquals(arcsOf(twos.`|+|`(threes).cycle()), arcsOf(threes.`|+|`(twos).cycle()))
        assertEquals(
            twos.`|+|`(threes).cycle().map { it.value },
            threes.`|+|`(twos).cycle().map { it.value },
            "addition commutes, so the values must match too"
        )
    }

    @Test
    fun `keeping your own values against a total pattern is the identity`() {
        val original = pat("a b c").cycle()
        val kept = pat("a b c").`|L`(sine()).cycle()
        assertEquals(arcsOf(original), arcsOf(kept))
        assertEquals(original.map { it.value }, kept.map { it.value })
    }

    @Test
    fun `taking values keeps the left's rhythm and the right's values`() {
        val events = pat("bd sd").`#`(pat("x y z w")).cycle()
        assertEquals(listOf(PatQ.of(0, 1) to PatQ.of(1, 2), PatQ.of(1, 2) to PatQ.of(1, 1)), arcsOf(events))
        assertEquals(listOf("x", "z"), events.map { it.value })
        assertEquals(events.map { it.value }, (pat("bd sd") valuesFrom pat("x y z w")).cycle().map { it.value })
    }

    @Test
    fun `resampling onto someone else's grid keeps your values and their rhythm`() {
        val events = (pat("a b") rhythmFrom run(4)).cycle()
        assertEquals((0 until 4).map { PatQ.of(it, 4) to PatQ.of(it + 1, 4) }, arcsOf(events))
        assertEquals(listOf("a", "a", "b", "b"), events.map { it.value })
    }

    // ── named parameters ──────────────────────────────────────────────────────────────────

    @Test
    fun `a continuous parameter is read at each note's midpoint`() {
        val events = (pat("a b c d") with gain(sine())).cycle()
        assertEquals(4, events.size)
        val expected = listOf(1, 3, 5, 7).map { 0.5 + 0.5 * sin(2.0 * PI * (it / 8.0)) }
        // Parameters round-trip through the annotations map as canonical nine-decimal text, so
        // the tolerance here is that quantisation and nothing else.
        expected.zip(events).forEach { (e, event) ->
            assertEquals(e, event.num("gain"), 1e-8, "gain on ${event.value}")
        }
    }

    @Test
    fun `a discrete parameter is latched at each note's onset`() {
        val events = (pat("a b") with gain("0.1 0.2 0.3 0.4")).cycle()
        assertEquals(listOf(0.1, 0.3), events.map { it.num("gain") })
    }

    @Test
    fun `parameters stack, survive later transforms, and reach the scheduled boundary`() {
        val pattern = pat("a b c d") with gain(saw()) with pan("0 1") with speed(2)
        val events = pattern.rev().fast(2).cycle()
        assertTrue(events.all { it.annotations.containsKey("gain") }, "gain lost in transit")
        assertTrue(events.all { it.annotations.containsKey("pan") }, "pan lost in transit")
        assertTrue(events.all { it.num("speed") == 2.0 }, "speed lost in transit")
        val scheduled = pattern.peek(4, cps = 1.0)
        assertEquals(4, scheduled.size)
        assertEquals(2.0, scheduled.first().num("speed"))
        assertEquals(2, scheduled.first().int("speed"))
        assertTrue(scheduled.map { it.num("gain") }.zipWithNext().all { (a, b) -> b > a }, "saw did not rise")
    }

    @Test
    fun `without removes a parameter and leaves the rest`() {
        val events = (pat("a b") with gain(0.5) with pan(1)).without("gain").cycle()
        assertTrue(events.none { it.annotations.containsKey("gain") })
        assertTrue(events.all { it.num("pan") == 1.0 })
    }

    @Test
    fun `mini-notation's sample-index suffix arrives as an n parameter`() {
        val events = pat("bd:3 sd 808:12").cycle()
        assertEquals(listOf("bd:3", "sd", "808:12"), events.map { it.value })
        assertEquals(listOf("3", null, "12"), events.map { it.annotations["n"] })
        assertEquals(listOf("bd", "sd", "808"), pat("bd:3 sd 808:12").dropSampleIndex().cycle().map { it.value })
        // A parenthesised payload is NOT a sample index — PatFunctions atoms must not be disturbed.
        assertEquals(null, patSampleIndexSuffix("g:(0,0.12)"))
        assertEquals(null, patSampleIndexSuffix("808:x"))
    }

    // ── the counterfactual: what the pre-tonight surface produced ──────────────────────────

    @Test
    fun `appLeft preserves note count where appBoth multiplies it`() {
        val kicks = pat("bd*4")
        val sweep = saw().segment(16)

        // Structure from both: the sixteen-step sweep shatters four kicks into sixteen fragments.
        val both = kicks.appBoth(sweep) { atom, _ -> atom }.cycle()
        // Structure from the left: exactly four kicks, each carrying one sample of the sweep.
        val left = (kicks with cutoff(sweep.range(200, 4000))).cycle()

        assertEquals(4, kicks.cycle().size)
        assertEquals(16, both.size, "appBoth is expected to fragment — that is what it is for")
        assertEquals(4, left.size, "appLeft must never change the note count")
        assertEquals(
            arcsOf(kicks.cycle()), arcsOf(left),
            "and the surviving events must be exactly the left's events, arc for arc"
        )
        // Each kick got its own cutoff, rising across the cycle.
        val cutoffs = left.map { it.num("cutoff") }
        assertTrue(cutoffs.zipWithNext().all { (a, b) -> b > a }, "cutoff did not sweep: $cutoffs")
        assertTrue(cutoffs.first() > 200.0 && cutoffs.last() < 4000.0, "cutoff escaped its range: $cutoffs")
    }

    @Test
    fun `the old zip surface could only attach values someone had typed by hand`() {
        // `zip` is what TidalPatterns.kt already offered: attach a *string* pattern under a key.
        // It is structure-from-left, so the note count was already right — the gaps were that
        // (a) there was no continuous source to read from, and (b) no arithmetic on the way in.
        val kicks = pat("bd*4")
        val oldWay = kicks.zip(pat("200 1000 2000 4000"), "cutoff").cycle()
        assertEquals(4, oldWay.size)
        // Every value the old surface can produce is a literal from the source text.
        val typed = setOf("200", "1000", "2000", "4000")
        assertTrue(oldWay.all { it.annotations["cutoff"] in typed }, "zip invented a value it was not given")

        // The new surface reads a *function*, so the four values are not among the typed literals
        // and change shape when you change the curve rather than when you retype the numbers.
        val newWay = (kicks with cutoff(saw().rangex(200, 4000))).cycle()
        assertEquals(4, newWay.size)
        assertTrue(
            newWay.none { it.annotations["cutoff"] in typed },
            "the continuous sweep happened to land on the hand-typed staircase; pick a better example"
        )

        // And the structure choices the old surface simply had no spelling for: `zip` always
        // returns the left's event count, so a 2 x 3 intersection grid was unreachable.
        assertEquals(2, pat("0 1").zip(pat("10 20 30"), "v").cycle().size)
        assertEquals(4, twos.`|+|`(threes).cycle().size)
        assertEquals(3, twos.`+|`(threes).cycle().size)
    }
}
