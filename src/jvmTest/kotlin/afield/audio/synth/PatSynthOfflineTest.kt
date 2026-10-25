package afield.audio.synth

import kotlin.test.Test
import kotlin.test.assertEquals

/** [PatSynthLaunch.advanceOffline] and the audio engine never both advance one launch. */
class PatSynthOfflineTest {
    @Test
    fun `advanceOffline stands aside while an engine is pulling the launch`() {
        val launch = patSynth(pat("bd sd"), cps = 2.0)
        try {
            launch.advanceOffline(512)
            val afterOffline = launch.bank.frame
            assertEquals(512L, afterOffline)
            val ctx = SynthContext(launch.bank.sampleRate.toFloat(), 256)
            ctx.cycleId = 1_000_000L
            launch.render(FloatArray(256), FloatArray(256), 256, ctx)
            val afterEngine = launch.bank.frame
            launch.advanceOffline(512)
            assertEquals(afterEngine, launch.bank.frame, "advanceOffline must not advance a launch the engine is pulling")
        } finally {
            launch.close()
        }
    }
}
