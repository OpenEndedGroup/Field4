package afield.csound

import afield.Box
import afield.four.synth.CsoundLaunch
import afield.four.synth.csound
import afield.four.synth.csoundControls
import afield.four.synth.csoundOffline
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The box-facing calls `public/docs/csound.md` teaches, run rather than only compiled. Skipped
 * without a built libcsound.
 */
class CsoundBoxApiTest {

    private fun available(): Boolean {
        if (!CsoundLibrary.isAvailable) {
            println("SKIPPED: ${CsoundLibrary.describe()}")
            return false
        }
        return true
    }

    @Test fun csoundOfflineRendersAndReturnsTheSamples() {
        if (!available()) return
        val box = Box("csound-api-test-offline")
        val (left, right) = box.csoundOffline("""
            instr 1
              a1 oscili 0.25, 440
              outs a1, a1
            endin
        """, "i1 0 1")
        val ups = (1 until 48_000).count { left[it - 1] < 0f && left[it] >= 0f }
        assertTrue(abs(ups - 440) <= 1, "got $ups")
        assertTrue(left.contentEquals(right))
    }

    @Test fun csoundControlsMakesOneSliderPerInputChannelAndSetsItsDefault() {
        if (!available()) return
        val box = Box("csound-api-test-controls")
        val launch: CsoundLaunch = box.csound("""
            chn_k "pitch", 1, 2, 110, 55, 440
            chn_k "level", 2
            instr 1
              kf chnget "pitch"
              chnset kf, "level"
            endin
        """, "i1 0 z")
        launch.use {
            assertEquals(1, box.csoundControls(it))
            assertEquals(110.0, it.get("pitch"), 1e-9)
            it.csound.renderOffline(0.1)
            assertEquals(110.0, it.get("level"), 1e-9)
        }
    }

    @Test fun aCompileErrorThrowsWithItsLine() {
        if (!available()) return
        val e = assertFailsWith<CsoundCompileError> {
            Box("csound-api-test-error").csoundOffline("instr 1\n a1 oscilx 0.2, 440\nendin", "i1 0 1")
        }
        assertTrue(e.problems.any { it.line == 2 }, "${e.problems}")
    }
}
