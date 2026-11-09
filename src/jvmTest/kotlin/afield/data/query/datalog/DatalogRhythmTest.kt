package afield.data.query.datalog

import afield.audio.synth.PatArc
import afield.audio.synth.PatQ
import afield.audio.synth.PatSoundDocs
import afield.audio.synth.euclid
import afield.audio.synth.patRenderStereo
import afield.audio.synth.patSynthNotesForArc
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `DlRhythm`: Datalog rules that decide when notes play (Dogalog's idea, on Field's own engine).
 * Each test pins the exact grid a rule derives, step for step.
 */
class DatalogRhythmTest {

    private fun rhythm(src: String) = Datalog().apply { add(src) }.rhythm()

    private fun DlRhythm.stepsOf(inst: String, cycle: Long) =
        events(cycle).filter { it.instrument == inst }.map { it.step }

    @Test
    fun `euclidean kick is E(4,16) in every bar`() {
        val r = rhythm("event(kick, 36, 100, T) :- euc(T, 4, 16, 0).")
        assertEquals(listOf(0L, 4L, 8L, 12L), r.stepsOf("kick", 0))
        assertEquals(listOf(16L, 20L, 24L, 28L), r.stepsOf("kick", 1))
        assertEquals("kick   x...x...x...x...", r.grid(0))
    }

    @Test
    fun `euclid matches Pat bjorklund including rotation`() {
        val r = rhythm("event(bd, 36, 100, T) :- euc(T, 3, 8, 0). event(sd, 38, 100, T) :- euc(T, 3, 8, 2).")
        // bjorklund(3, 8) is x..x..x. ; rotated right by 2 it is x.x..x..
        assertEquals(listOf(0L, 3L, 6L, 8L, 11L, 14L), r.stepsOf("bd", 0))
        assertEquals(listOf(0L, 2L, 5L, 8L, 10L, 13L), r.stepsOf("sd", 0))
        // ...which is exactly Pat's euclid(3, 8, 2), played twice per 16-step bar.
        val pat = euclid(3, 8, 2).events(PatArc(PatQ.of(0), PatQ.of(1))).filter { it.hasOnset }
            .map { (it.arc.start.toDouble() * 8).toLong() }
        assertEquals(pat, r.stepsOf("sd", 0).filter { it < 8 })
    }

    @Test
    fun `pentatonic melody walks the scale across bars`() {
        val r = rhythm(
            "event(lead, N, 90, T) :- every(T, 4, 0), D = (T div 4) mod 5, scale(60, minor_pent, D, 0, N)."
        )
        val bar0 = r.events(0).map { it.step to it.note.toInt() }
        assertEquals(listOf(0L to 60, 4L to 63, 8L to 65, 12L to 67), bar0)
        val bar1 = r.events(1).map { it.step to it.note.toInt() }
        assertEquals(listOf(16L to 70, 20L to 60, 24L to 63, 28L to 65), bar1)
    }

    @Test
    fun `scale degrees past the top wrap an octave`() {
        val r = rhythm("event(lead, N, 90, T) :- every(T, 16, 0), scale(60, minor_pent, 5, 1, N).")
        assertEquals(listOf(84.0), r.events(0).map { it.note })
    }

    @Test
    fun `arpeggio cycles a minor chord`() {
        val r = rhythm("event(arp, N, 80, T) :- every(T, 2, 0), I = (T div 2) mod 3, chord(57, minor, I, N).")
        assertEquals(
            listOf(0L to 57, 2L to 60, 4L to 64, 6L to 57, 8L to 60, 10L to 64, 12L to 57, 14L to 60),
            r.events(0).map { it.step to it.note.toInt() },
        )
    }

    @Test
    fun `a chord is a relation so one rule strikes every note`() {
        val r = rhythm("event(pad, N, 70, T) :- every(T, 16, 0), chord(57, min7, _, N).")
        assertEquals(listOf(57, 60, 64, 67), r.events(0).map { it.note.toInt() })
    }

    @Test
    fun `within structures a song into sections`() {
        val r = rhythm(
            """
            event(kick, 36, 100, T) :- euc(T, 4, 16, 0).
            event(snare, 38, 100, T) :- within(T, 16, 48), every(T, 8, 4).
            event(hat, 42, 50, T) :- within(T, 32, 64), every(T, 2, 1), !euc(T, 4, 16, 0).
            """
        )
        assertEquals(emptyList(), r.stepsOf("snare", 0))
        assertEquals(listOf(20L, 28L), r.stepsOf("snare", 1))
        assertEquals(listOf(36L, 44L), r.stepsOf("snare", 2))
        assertEquals(emptyList(), r.stepsOf("snare", 3))
        assertEquals(emptyList(), r.stepsOf("hat", 1))
        assertEquals((0 until 8).map { 33L + 2 * it }, r.stepsOf("hat", 2))
        assertEquals(
            """
            kick   x...x...x...x...
            snare  ....x.......x...
            """.trimIndent(),
            r.grid(1),
        )
    }

    @Test
    fun `a fill reads the previous bar through the lookbehind`() {
        // A crash on the downbeat of any bar whose previous step (the end of the last bar) had a snare.
        val r = rhythm(
            """
            event(snare, 38, 100, T) :- within(T, 0, 16), every(T, 16, 15).
            event(crash, 49, 100, T) :- pos(T, 0), P = T - 1, event(snare, _, _, P).
            """
        )
        assertEquals(emptyList(), r.stepsOf("crash", 0))
        assertEquals(listOf(16L), r.stepsOf("crash", 1))
        assertEquals(emptyList(), r.stepsOf("crash", 2))
    }

    @Test
    fun `why explains a note`() {
        val r = rhythm("event(kick, 36, 100, T) :- euc(T, 4, 16, 0).")
        val proof = assertNotNull(r.why("event(kick, 36, 100, 20)"))
        val text = proof.render()
        assertTrue("euc(20, 4, 16, 0)" in text, text)
        assertEquals(null, r.why("event(kick, 36, 100, 21)"))
    }

    @Test
    fun `editing the program changes the next query without a new player`() {
        val d = Datalog().apply { add("event(kick, 36, 100, T) :- euc(T, 4, 16, 0).") }
        val r = d.rhythm()
        val pattern = r.pattern()
        assertEquals(4, pattern.events(PatArc(PatQ.of(0), PatQ.of(1))).size)
        d.add("event(hh, 42, 60, T) :- every(T, 4, 2).")
        assertEquals(8, pattern.events(PatArc(PatQ.of(1), PatQ.of(2))).size)
    }

    @Test
    fun `variable parameters are reported, not guessed`() {
        val r = rhythm("event(kick, 36, 100, T) :- k(K), euc(T, K, 16, 0). k(4).")
        assertFailsWith<DlException> { r.events(0) }
    }

    @Test
    fun `the pattern is what the scheduler strikes`() {
        val r = rhythm(
            """
            event(kick, 36, 100, T) :- euc(T, 4, 16, 0).
            event(lead, N, 90, T) :- every(T, 4, 2), D = (T div 4) mod 5, scale(60, minor_pent, D, 0, N).
            """
        )
        val notes = patSynthNotesForArc(r.pattern(), PatArc(PatQ.of(0), PatQ.of(1)), cps = 1.0, sampleRate = 48_000)
        // kick onsets at 0, 1/4, 1/2, 3/4 of a second; lead at 2/16, 6/16, 10/16, 14/16.
        val expectedFrames = (listOf(0, 4, 8, 12) + listOf(2, 6, 10, 14)).map { it * 3000L }.sorted()
        assertEquals(expectedFrames, notes.map { it.startFrame }.sorted())
        // The kick keeps its drum voice; the lead is pitched by its note (C5 = 60 -> 261.6 Hz).
        val lead0 = notes.first { it.startFrame == 6000L }
        assertTrue(abs(lead0.hz - 261.63) < 0.1, "lead hz ${lead0.hz}")
    }

    @Test
    fun `an offline render sounds on the derived steps and is silent elsewhere`() {
        val r = rhythm("event(kick, 36, 100, T) :- euc(T, 4, 16, 0).")
        val audio = patRenderStereo(r.pattern(), cycles = 2, cps = 1.0)
        val sr = PatSoundDocs.SR
        val step = sr / 16
        fun energy(stepIndex: Int): Double {
            val from = stepIndex * step
            return (from until from + step / 2).sumOf { (audio.mono[it] * audio.mono[it]).toDouble() }
        }
        val on = (0 until 32 step 4).map { energy(it) }
        // Step 2 sits halfway between kicks, where the previous kick is a tail (about a sixth of an onset).
        val off = (2 until 32 step 4).map { energy(it) }
        assertTrue(on.all { it > 0.0 }, "every derived step sounds: $on")
        assertTrue(on.min() > 4 * off.max(), "onsets $on vs between $off")
    }
}
