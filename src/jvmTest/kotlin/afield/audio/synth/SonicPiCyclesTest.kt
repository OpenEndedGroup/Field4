package afield.audio.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** With `cycles` left out, the emitted loop is as long as the pattern takes to repeat. */
class SonicPiCyclesTest {
    @Test
    fun thePeriodIsFoundFromTheEvents() {
        assertEquals(1, patternPeriod(pat("bd sd")))
        assertEquals(2, patternPeriod(pat("<bd sd>")))
        assertEquals(3, patternPeriod(pat("<bd sd cp> hh")))
        assertNull(patternPeriod(pat("[bd|sd|cp]")), "a choice drawn per cycle does not repeat")
    }

    @Test
    fun omittedCyclesCoverThePeriodAndAShortLoopSaysSo() {
        val program = sonicPiProgram("drums" to pat("<bd sd cp> hh"))
        assertEquals(3, program.cycles)
        assertTrue("SHORTER THAN THE PATTERN" !in program.source)

        val short = sonicPiProgram("drums" to pat("<bd sd cp> hh"), cycles = 1)
        assertEquals(1, short.cycles)
        assertTrue("drums: repeats every 3 cycles" in short.source, short.source)
    }
}
