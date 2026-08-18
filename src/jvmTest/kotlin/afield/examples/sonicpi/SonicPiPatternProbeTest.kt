package afield.examples.sonicpi

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SonicPiPatternProbeTest {
    @Test
    fun aRequiredMachineCannotReportAnUnmeasuredProbeAsGreen() {
        SonicPiPatternProbe.refuseRequiredSkip(required = false, reason = "not installed")

        val failure = assertFailsWith<IllegalStateException> {
            SonicPiPatternProbe.refuseRequiredSkip(required = true, reason = "not installed")
        }
        assertTrue("FIELD_SONIC_PI_REQUIRED=1" in failure.message.orEmpty())
        assertTrue("not installed" in failure.message.orEmpty())
    }
}
