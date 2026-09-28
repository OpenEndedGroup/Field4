package afield.pd

import afield.Box
import afield.four.synth.PdLaunch
import afield.four.synth.pdControls
import afield.four.synth.pdSend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The box-facing calls `public/docs/pure_data.md` teaches, run rather than only compiled.
 *
 * Their live paths need a document and a playing Pd box, which a jvmTest does not have; what is
 * pinned here is what they do without one — the refusals a reader would meet first, and the case
 * where there is nothing to make.
 */
class PdBoxApiTest {

    @Test fun pdSendRefusesABoxThatIsNotPdAndSaysSo() {
        val box = Box("pd-api-test-kotlin-box")
        val e = assertFailsWith<IllegalStateException> { box.pdSend("freq", 330) }
        assertTrue(e.message!!.contains("is not a Pd box"), e.message)
    }

    @Test fun pdSendRefusesAPdBoxThatIsNotPlaying() {
        val box = Box("pd-api-test-pd-box")
        box.ke = PdExecution(box)
        val e = assertFailsWith<IllegalStateException> { box.pdSend("freq", 330) }
        assertTrue(e.message!!.contains("is not playing"), e.message)
    }

    @Test fun pdControlsMakesNothingForAPatchWithNoGuiObjects() {
        if (!PdLibrary.isAvailable) return println("SKIPPED: ${PdLibrary.describe()}")
        val pd = PdInstance()
        val patch = pd.openPatchText("#X obj 20 20 osc~ 220;\n#X obj 20 60 dac~;\n#X connect 0 0 1 0;")
        PdLaunch(pd, patch, 1f, null).use { launch ->
            assertEquals(0, Box("pd-api-test-controls").pdControls(launch))
        }
    }
}
