package afield.four.synth

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exactness tests for the continuous/signal family in `PatSignals.kt`.
 *
 * Every structural claim (where events start and end, how many there are) is asserted in exact
 * rational cycle space with [PatQ] equality, never on a converted `Double`. Only the sampled
 * *values* are compared with a tolerance, because those genuinely are floating point.
 */
class PatSignalsTest {

    private fun <A> PatPattern<A>.cycle(index: Long = 0L): List<PatEvent<A>> =
        events(PatArc(PatQ.of(index), PatQ.of(index + 1))).sortedWith(compareBy({ it.arc.start }, { it.arc.end }))

    private fun arcsOf(events: List<PatEvent<*>>): List<Pair<PatQ, PatQ>> = events.map { it.arc.start to it.arc.end }

    // ── the sampling convention ────────────────────────────────────────────────────────────

    @Test
    fun `a continuous pattern answers any arc with exactly one event spanning it`() {
        val arc = PatArc(PatQ.of(3, 7), PatQ.of(5, 7))
        val events = sine().events(arc)
        assertEquals(1, events.size)
        assertEquals(PatQ.of(3, 7), events[0].arc.start)
        assertEquals(PatQ.of(5, 7), events[0].arc.end)
    }

    @Test
    fun `a signal is sampled at the exact rational midpoint of the arc`() {
        // Midpoint of 3/7 .. 5/7 is 4/7, exactly — and 4/7 is not representable in binary, so
        // this is the case that distinguishes a rational midpoint from a Double one.
        val arc = PatArc(PatQ.of(3, 7), PatQ.of(5, 7))
        val mid = patArcMidpoint(arc)
        assertEquals(PatQ.of(4, 7), mid, "the sampling instant must be exact")

        // The value is then `f` of that instant. The only inexactness in the chain is
        // `PatQ.toDouble()`, which rounds to twelve decimal places, so the sampled value agrees
        // with the mathematical one to about 1e-11 and with the pattern's own idea of 4/7 exactly.
        assertEquals(0.5 + 0.5 * sin(2.0 * PI * mid.toDouble()), sine().events(arc).single().value)
        assertEquals(0.5 + 0.5 * sin(2.0 * PI * (4.0 / 7.0)), sine().events(arc).single().value, 1e-9)
    }

    @Test
    fun `square switches exactly on the half-cycle boundary`() {
        // A rational-midpoint comparison, not a Double one: no epsilon can flip this.
        val values = square().segment(2).cycle().map { it.value }
        assertEquals(listOf(0.0, 1.0), values)
        // and the boundary itself: an arc whose midpoint is exactly 1/2 reads as the upper half.
        assertEquals(1.0, square().events(PatArc(PatQ.of(1, 4), PatQ.of(3, 4))).single().value)
    }

    // ── segment ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `segment n yields exactly n events per cycle on the exact 1 over n grid`() {
        for (n in listOf(1, 2, 3, 5, 8, 16)) {
            val events = sine().segment(n).cycle()
            assertEquals(n, events.size, "segment($n) event count")
            assertEquals(
                (0 until n).map { PatQ.of(it, n) to PatQ.of(it + 1, n) },
                arcsOf(events),
                "segment($n) arcs"
            )
        }
    }

    @Test
    fun `segment samples each cell at that cell's own centre`() {
        val values = sine().segment(4).cycle().map { it.value }
        val expected = listOf(1, 3, 5, 7).map { 0.5 + 0.5 * sin(2.0 * PI * (it / 8.0)) }
        assertEquals(expected.size, values.size)
        expected.zip(values).forEach { (e, v) -> assertEquals(e, v, 1e-12) }
    }

    @Test
    fun `segment is query-granularity independent`() {
        // The whole point of sampling the *cell* rather than the query arc: chop the query into
        // eight pieces and every value must be identical to the single-query answer.
        val pattern = sine().segment(4)
        val whole = pattern.cycle().map { it.arc.start to it.value }
        val pieces = (0 until 8).flatMap { i ->
            pattern.events(PatArc(PatQ.of(i, 8), PatQ.of(i + 1, 8)))
        }.map { it.arc.start to it.value }
        // Each cell is now seen twice (two half-cells per cell), so de-duplicate by cell index.
        val byCell = pieces.groupBy { qFloorDiv(it.first, PatQ.of(1, 4)) }
        assertEquals(4, byCell.size)
        whole.forEachIndexed { index, (start, value) ->
            val seen = byCell.getValue(index.toLong()).map { it.second }.distinct()
            assertEquals(1, seen.size, "cell $index (start $start) disagreed across query granularities: $seen")
            assertEquals(value, seen.single(), 1e-15)
        }
    }

    @Test
    fun `a segmented sine is symmetric under rev`() {
        // The midpoint convention is what makes this true; a start- or end-sampling convention
        // shifts the values by half a cell and the reversal no longer lines up.
        val forward = sine().segment(8).cycle().map { it.value }
        val backward = sine().segment(8).rev().cycle().map { it.value }
        assertEquals(forward.size, backward.size)
        forward.reversed().zip(backward).forEach { (e, v) -> assertEquals(e, v, 1e-12) }
    }

    @Test
    fun `fast then slow is the identity on a segmented signal`() {
        val original = sine().segment(4).cycle()
        val roundTrip = sine().segment(4).fast(2).slow(2).cycle()
        assertEquals(arcsOf(original), arcsOf(roundTrip))
        original.map { it.value }.zip(roundTrip.map { it.value })
            .forEach { (e, v) -> assertEquals(e, v, 1e-12) }
    }

    // ── the signal family ─────────────────────────────────────────────────────────────────

    @Test
    fun `saw and isaw are exact complements and tri is their fold`() {
        val saws = saw().segment(8).cycle().map { it.value }
        val isaws = isaw().segment(8).cycle().map { it.value }
        saws.zip(isaws).forEach { (a, b) -> assertEquals(1.0, a + b, 1e-12) }
        // saw at cell centres is (2i+1)/16.
        saws.forEachIndexed { i, v -> assertEquals((2.0 * i + 1.0) / 16.0, v, 1e-12) }
        val tris = tri().segment(8).cycle().map { it.value }
        assertEquals(tris.take(4), tris.drop(4).reversed().map { it })
    }

    @Test
    fun `run n is exactly n integer events per cycle`() {
        val events = run(5).cycle()
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0), events.map { it.value })
        assertEquals((0 until 5).map { PatQ.of(it, 5) to PatQ.of(it + 1, 5) }, arcsOf(events))
    }

    @Test
    fun `rand is deterministic, in range, and differs per cycle`() {
        val first = rand().segment(8).cycle(0).map { it.value }
        val again = rand().segment(8).cycle(0).map { it.value }
        assertEquals(first, again, "rand must be a pure function of position")
        assertTrue(first.all { it in 0.0..1.0 }, "rand out of range: $first")
        assertTrue(first.distinct().size >= 7, "rand is suspiciously flat: $first")
        val next = rand().segment(8).cycle(1).map { it.value }
        assertTrue(first != next, "rand repeated itself across cycles")
    }

    @Test
    fun `irand n only ever produces integers below n`() {
        val values = irand(6).segment(64).cycle().map { it.value }
        assertTrue(values.all { it == Math.rint(it) && it >= 0.0 && it < 6.0 }, "irand(6) escaped: $values")
        assertTrue(values.distinct().size >= 5, "irand(6) never used most of its range")
    }

    @Test
    fun `perlin is continuous where rand is not`() {
        // Adjacent samples of a smooth signal must be close; adjacent samples of a hash need not be.
        fun maxStep(values: List<Double>) = values.zipWithNext().maxOf { (a, b) -> Math.abs(b - a) }
        val smooth = maxStep(perlin().segment(64).cycle().map { it.value })
        val hashed = maxStep(rand().segment(64).cycle().map { it.value })
        assertTrue(smooth < 0.1, "perlin is not smooth: max step $smooth")
        assertTrue(hashed > 3.0 * smooth, "rand ($hashed) is not rougher than perlin ($smooth)")
    }

    // ── shaping ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `range maps the unit interval onto its endpoints`() {
        val values = saw().range(100, 900).segment(4).cycle().map { it.value }
        // saw cell centres are 1/8, 3/8, 5/8, 7/8.
        assertEquals(listOf(200.0, 400.0, 600.0, 800.0), values.map { Math.rint(it) })
    }

    @Test
    fun `rangex is geometric so a cutoff sweep is even in octaves`() {
        val values = saw().rangex(100, 1600).segment(4).cycle().map { it.value }
        val ratios = values.zipWithNext().map { (a, b) -> b / a }
        ratios.forEach { assertEquals(ratios.first(), it, 1e-9) }
    }

    @Test
    fun `quantise snaps to a step and rounded lands on integers`() {
        val quantised = saw().quantise(0.25).segment(8).cycle().map { it.value }
        quantised.forEach { assertEquals(0.0, (it * 4.0) % 1.0, 1e-12, "not on a 0.25 grid: $it") }
        val rounded = saw().range(0, 4).rounded().segment(4).cycle().map { it.value }
        assertEquals(listOf(1, 2, 3, 4), rounded)
    }

    // ── struct ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `struct takes rhythm from the boolean pattern and values from the other`() {
        val events = struct(euclid(3, 8), pat("bd")).cycle()
        // `bjorklund(3, 8)` is the tresillo x..x..x. — onsets at eighths 0, 3 and 6, gaps 3, 3, 2,
        // and the same spelling Tidal gives `bd(3,8)`.
        assertEquals(3, events.size)
        assertEquals(
            listOf(
                PatQ.of(0, 8) to PatQ.of(1, 8),
                PatQ.of(3, 8) to PatQ.of(4, 8),
                PatQ.of(6, 8) to PatQ.of(7, 8)
            ),
            arcsOf(events)
        )
        assertEquals(listOf("bd", "bd", "bd"), events.map { it.value })
    }

    @Test
    fun `struct against a continuous pattern samples each structural event's midpoint`() {
        val events = struct(euclid(3, 8), sine()).cycle()
        assertEquals(3, events.size)
        // Midpoints of the three eighth-note slots (0, 3, 6): 1/16, 7/16, 13/16.
        val expected = listOf(1, 7, 13).map { 0.5 + 0.5 * sin(2.0 * PI * (it / 16.0)) }
        expected.zip(events.map { it.value }).forEach { (e, v) -> assertEquals(e, v, 1e-12) }
    }

    @Test
    fun `bools reads a boolean mini-notation pattern`() {
        val events = struct(bools("t f t t"), run(4)).cycle()
        assertEquals(3, events.size)
        assertEquals(
            listOf(PatQ.of(0, 4) to PatQ.of(1, 4), PatQ.of(2, 4) to PatQ.of(3, 4), PatQ.of(3, 4) to PatQ.of(1, 1)),
            arcsOf(events)
        )
        // Values come from run(4) latched at each structural onset: 0, 2, 3.
        assertEquals(listOf(0.0, 2.0, 3.0), events.map { it.value })
    }

    @Test
    fun `nums drops unparseable atoms and texts round-trips`() {
        assertEquals(listOf(1.0, 3.0), pat("1 bd 3 ~").nums().cycle().map { it.value })
        assertEquals(listOf("1", "0.25"), pat("1 0.25").nums().texts().cycle().map { it.value })
    }
}
