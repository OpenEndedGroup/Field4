package afield.audio.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Determinism of sampling, locking and improvisation in `PatMuse.kt`, all under fixed seeds. */
class PatMuseTest {

    private fun PatPattern<String>.onsets(cycles: Int): List<Triple<PatQ, String, Map<String, String>>> =
        events(PatArc(PatQ.ZERO, PatQ.of(cycles))).filter { it.hasOnset }
            .map { Triple(it.extent.start, it.value, it.annotations) }
            .sortedWith(compareBy({ it.first }, { it.second.toInt() }))

    @Test
    fun weightedSamplingFollowsTheWeights() {
        val w = PatWeights(listOf("a", "b", "c"), 1.0, 0.0, 3.0)
        val counts = IntArray(3)
        for (i in 0 until 4000) counts[w.sample(i / 4000.0)]++
        assertEquals(0, counts[1], "a zero weight is never chosen")
        assertEquals(1000, counts[0])
        assertEquals(3000, counts[2])
        assertEquals(0, PatWeights(listOf("x", "y"), 0.0, 0.0).sample(0.7), "all-zero table picks the first entry")
    }

    @Test
    fun sameSeedSameStreamDifferentSeedDifferentStream() {
        val a = PatMuseChannel(seed = 5).pattern().onsets(8)
        val b = PatMuseChannel(seed = 5).pattern().onsets(8)
        val c = PatMuseChannel(seed = 6).pattern().onsets(8)
        assertTrue(a.size > 8)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun streamIsRandomAccessAndConsistentAcrossQueries() {
        val ch = PatMuseChannel(seed = 9)
        val whole = ch.pattern().onsets(6).filter { it.first >= PatQ.of(3) }
        val late = PatMuseChannel(seed = 9).pattern().events(PatArc(PatQ.of(3), PatQ.of(6)))
            .filter { it.hasOnset }.map { Triple(it.extent.start, it.value, it.annotations) }
            .sortedWith(compareBy({ it.first }, { it.second.toInt() }))
        assertEquals(whole, late, "asking for cycles 3-6 alone gives the same events as asking for 0-6")
    }

    @Test
    fun weightsShapeTheStream() {
        val ch = PatMuseChannel(seed = 2, root = 60)
        ch.degree.set(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0) // only the fifth
        ch.chord.set(1.0, 0.0, 0.0, 0.0)
        ch.rhythm.set(0.0, 0.0, 0.0, 1.0, 0.0, 0.0) // only 4-step events
        ch.rest = 0.0
        val ev = ch.pattern().onsets(4)
        assertEquals(8, ev.size)
        assertTrue(ev.all { it.second == "67" }, "degree 4 of C major from 60 is G (67)")
        ch.chord.set(0.0, 0.0, 1.0, 0.0)
        val triads = ch.pattern().onsets(1)
        assertEquals(listOf("67", "71", "74"), triads.take(3).map { it.second }, "a triad on the fifth is G B D")
    }

    @Test
    fun lockFreezesTheLastNEventsAndLoopsThem() {
        val ch = PatMuseChannel(seed = 11)
        val stream = ch.eventsUntil(64)
        val loop = ch.lock(5, atCycle = 4)
        val before = stream.filter { it.step < 32 }.takeLast(5)
        assertEquals(before.map { it.degrees to it.length }, loop.events.map { it.degrees to it.length })
        // The loop does not care what the weights do afterwards.
        val firstPass = loop.pattern().onsets(8)
        ch.degree.set(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(firstPass, loop.pattern().onsets(8))
        // It repeats every passSteps.
        val pass = PatQ.of(loop.passSteps.toLong(), 8)
        val p = loop.pattern()
        val a = p.events(PatArc(PatQ.ZERO, pass)).filter { it.hasOnset }.map { it.value }
        val b = p.events(PatArc(pass, pass + pass)).filter { it.hasOnset }.map { it.value }
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun windowAndDirection() {
        val ch = PatMuseChannel(seed = 4).apply { rest = 0.0 }
        val loop = ch.lock(6, atCycle = 4)
        loop.start = 1; loop.end = 4
        assertEquals(listOf(1, 2, 3), loop.order())
        loop.direction = PatLoopDirection.Reverse
        assertEquals(listOf(3, 2, 1), loop.order())
        loop.direction = PatLoopDirection.PingPong
        assertEquals(listOf(1, 2, 3, 2), loop.order())
        assertEquals(listOf(1, 2, 3, 2).sumOf { loop.events[it].length }, loop.passSteps)
    }

    @Test
    fun editingALoopEventChangesThePattern() {
        val ch = PatMuseChannel(seed = 4, root = 60).apply { rest = 0.0; chord.set(1.0, 0.0, 0.0, 0.0) }
        val loop = ch.lock(4, atCycle = 2)
        loop.set(0, degree = 7)
        assertEquals("72", loop.pattern().onsets(1).first().second)
    }

    @Test
    fun improvisationIsDeterministicPerPassAndVariesBetweenPasses() {
        fun build(seed: Long) = PatMuseChannel(seed = 3).apply {
            rest = 0.0
            chord.set(1.0, 0.0, 0.0, 0.0)
            rhythm.set(0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
        }.lock(8, atCycle = 4).apply { improvise = 0.6; this.seed = seed }
        val a = build(42).pattern().onsets(16)
        val b = build(42).pattern().onsets(16)
        assertEquals(a, b, "same loop, same seed, same improvisation")
        assertNotEquals(a, build(43).pattern().onsets(16))
        val loop = build(42)
        val pass = PatQ.of(loop.passSteps.toLong(), 8)
        val p = loop.pattern()
        val passes = (0 until 4).map { k -> p.events(PatArc(pass * PatQ.of(k), pass * PatQ.of(k + 1))).filter { it.hasOnset }.map { it.value to it.annotations } }
        assertTrue(passes.toSet().size > 1, "passes differ from each other")
        val ornaments = a.mapNotNull { it.third["ornament"] }.toSet()
        assertTrue(ornaments.isNotEmpty(), "improvising at 0.6 over 16 cycles adds ornaments, got none")
        // Without improvisation every pass is identical and nothing is marked.
        val plain = build(42).apply { improvise = 0.0 }.pattern().onsets(16)
        assertTrue(plain.none { "ornament" in it.third || "improvised" in it.third })
    }

    @Test
    fun aLoopPatternIsASnapshot() {
        val loop = PatMuseChannel(seed = 3).apply { rest = 0.0 }.lock(8, atCycle = 4)
        val plain = loop.pattern()
        val before = plain.onsets(8)
        loop.improvise = 1.0
        loop.direction = PatLoopDirection.Reverse
        assertEquals(before, plain.onsets(8), "changing the loop leaves an earlier pattern alone")
        assertNotEquals(before, loop.pattern().onsets(8))
    }

    @Test
    fun ornamentsHaveTheirShape() {
        val ch = PatMuseChannel(seed = 1, root = 60).apply {
            rest = 0.0
            chord.set(1.0, 0.0, 0.0, 0.0)
            degree.set(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0) // always E
            rhythm.set(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
        val loop = ch.lock(2, atCycle = 2).apply { improvise = 1.0 }
        for ((orn, expected) in listOf(
            PatOrnament.Mordent to listOf("64", "65", "64"),
            PatOrnament.Turn to listOf("65", "64", "62", "64"),
            PatOrnament.Trill to listOf("65", "64", "65", "64", "65", "64", "65", "64"),
        )) {
            loop.ornaments = setOf(orn)
            // Find a pass-0 event that took the ornament (probability 0.7 each).
            var found = false
            for (seed in 0L until 20L) {
                loop.seed = seed
                val first = loop.pattern().onsets(1).filter { it.first < PatQ.of(1, 2) }
                if (first.all { it.third["ornament"] == orn.name.lowercase() }) {
                    assertEquals(expected, first.map { it.second }, "$orn")
                    found = true
                    break
                }
            }
            assertTrue(found, "no seed produced a $orn")
        }
    }

    @Test
    fun museStacksTwoHandsAndPlaysOnThePiano() {
        val muse = PatMuse(seed = 7)
        val both = muse.pattern().onsets(4)
        assertTrue(both.any { it.third["channel"] == "left" } && both.any { it.third["channel"] == "right" })
        muse.lock(8, atCycle = 4)
        assertTrue(muse.leftLoop != null && muse.rightLoop != null)
        muse.release()
        assertEquals(both, muse.pattern().onsets(4))
    }
}
