package afield.csound

import afield.four.synth.Param
import afield.four.synth.SineOsc
import afield.four.synth.SynthContext
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every direction data takes between Field and Csound, measured: audio in and out through a-rate
 * channels, k- and S-rate channels, tables, `outvalue`/`invalue` callbacks, MIDI.
 *
 * Skipped without a built libcsound (`python3 tools/build_csound_native.py`).
 */
class CsoundDataPlaneTest {

    private fun available(): Boolean {
        if (!CsoundLibrary.isAvailable) {
            println("SKIPPED Csound data-plane tests: ${CsoundLibrary.describe()}")
            return false
        }
        return true
    }

    /** Pull [frames] of [signal] through a SynthContext in engine-sized blocks, as the audio thread does. */
    private fun pull(signal: afield.four.synth.Signal, frames: Int, block: Int = 256): FloatArray {
        val ctx = SynthContext(48_000f, block)
        val out = FloatArray(frames)
        val buf = FloatArray(block)
        var at = 0
        while (at < frames) {
            val n = minOf(block, frames - at)
            ctx.advanceCycle()
            signal.render(buf, n, ctx)
            System.arraycopy(buf, 0, out, at, n)
            at += n
        }
        return out
    }

    private fun rms(x: FloatArray, from: Int = 0) = sqrt((from until x.size).sumOf { x[it].toDouble() * x[it] } / (x.size - from))

    @Test fun aFieldSineGoesThroughACsoundFilterAndComesBackAttenuated() {
        if (!available()) return
        val orc = """
            instr Filter
              ain   chnget "in"
              alow  butterlp ain, 500
              chnset alow, "low"
              chnset ain, "thru"
            endin
        """
        CsoundInstance(orc, "i \"Filter\" 0 z").use { cs ->
            val sine = SineOsc(Param(1000f))
            val node = cs.node(audioIn = mapOf("in" to sine))
            val low = node.audioOut("low")
            val thru = node.audioOut("thru")
            val ctx = SynthContext(48_000f, 250)
            val lowOut = FloatArray(48_000)
            val thruOut = FloatArray(48_000)
            val inCopy = FloatArray(48_000)
            val reference = SineOsc(Param(1000f))
            val b = FloatArray(250)
            var at = 0
            while (at < 48_000) {
                ctx.advanceCycle()
                low.render(b, 250, ctx); System.arraycopy(b, 0, lowOut, at, 250)
                thru.render(b, 250, ctx); System.arraycopy(b, 0, thruOut, at, 250)
                reference.render(b, 250, ctx); System.arraycopy(b, 0, inCopy, at, 250)
                at += 250
            }
            // A second-order Butterworth lowpass at 500 Hz passes 1000 Hz at 1/sqrt(1 + (1000/500)^4).
            val expected = (1.0 / sqrt(1.0 + 16.0)) / sqrt(2.0)
            val measured = rms(lowOut, 4800)
            println("butterlp 500 Hz on a 1000 Hz unit sine: rms $measured, expected $expected")
            assertEquals(expected, measured, expected * 0.05)
            // The straight-through channel is the input delayed by exactly one control period.
            val k = cs.ksmps
            for (i in k until 48_000) assertEquals(inCopy[i - k], thruOut[i], 1e-6f, "frame $i")
            for (i in 0 until k) assertEquals(0f, thruOut[i])
        }
    }

    @Test fun renderSignalsOfflineAdvancesSharedGraphsTogether() {
        if (!available()) return
        CsoundInstance("instr Thru\n ain chnget \"in\"\n chnset ain * 0.5, \"half\"\nendin", "i \"Thru\" 0 z").use { cs ->
            val node = cs.node(audioIn = mapOf("in" to SineOsc(Param(500f))))
            val (dry, half) = afield.four.synth.renderSignalsOffline(0.1, SineOsc(Param(500f)), node.audioOut("half"))
            for (i in cs.ksmps until dry.size) assertEquals(dry[i - cs.ksmps] * 0.5f, half[i], 1e-6f)
        }
    }

    @Test fun outsComeBackAsFieldSignals() {
        if (!available()) return
        val orc = """
            instr Tone
              asig poscil 0.3, 600
              outs asig, -asig
            endin
        """
        CsoundInstance(orc, "i \"Tone\" 0 z").use { cs ->
            val node = cs.node()
            val l = pull(node.outL, 4800)
            val r = pull(node.outR, 4800)
            assertEquals(0.3 / sqrt(2.0), rms(l), 0.005)
            for (i in l.indices) assertEquals(-l[i], r[i], 1e-6f)
        }
    }

    @Test fun aTableRoundTripsAndTheOrchestraReadsWhatKotlinWrote() {
        if (!available()) return
        val orc = """
            giWave ftgen 1, 0, 1024, 10, 1
            instr Probe
              kv table 3, 1
              chnset kv, "probe"
            endin
        """
        CsoundInstance(orc, "i \"Probe\" 0 z").use { cs ->
            val sine = cs.table(1)!!
            assertEquals(1024, sine.size)
            assertEquals(1024, cs.tableLength(1))
            assertEquals(1.0, sine[256], 1e-9)
            val ramp = DoubleArray(1024) { it / 1024.0 }
            cs.writeTable(1, ramp)
            assertTrue(ramp.contentEquals(cs.table(1)!!))
            cs.renderOffline(0.01)
            assertEquals(3.0 / 1024.0, cs.channel("probe"), 1e-12)
        }
    }

    @Test fun controlAndStringChannelsGoBothWays() {
        if (!available()) return
        val orc = """
            instr Echo
              kx    chnget "x"
              chnset kx * 2, "twice"
              Swho  chnget "who"
              Sout  strcatk Swho, "!"
              chnset Sout, "reply"
            endin
        """
        CsoundInstance(orc, "i \"Echo\" 0 z").use { cs ->
            cs.setChannel("x", 21)
            cs.setStringChannel("who", "Barry")
            cs.renderOffline(0.01)
            assertEquals(42.0, cs.channel("twice"), 1e-12)
            assertEquals("Barry!", cs.stringChannel("reply"))
        }
    }

    @Test fun outvalueCallsBackAndInvalueReadsWhatKotlinSet() {
        if (!available()) return
        val orc = """
            instr Report
              kgain invalue "gain"
              kn    init 0
              kn    += 1
              outvalue "count", kn
              outvalue "gainSeen", kgain
              outvalue "word", "hello"
            endin
        """
        val numbers = ArrayList<Double>()
        val words = ArrayList<String>()
        val gains = ArrayList<Double>()
        CsoundInstance(orc, "i \"Report\" 0 z").use { cs ->
            cs.onOutvalue("count") { numbers += it as Double }
            cs.onOutvalue("word") { words += it as String }
            cs.onOutvalue("gainSeen") { gains += it as Double }
            cs.setInvalue("gain", 0.5)
            cs.renderOffline(0.1, chunk = 480)   // 10 polls, 150 control cycles
            assertTrue(numbers.size in 2..10, "one callback per poll that saw a change: $numbers")
            assertTrue(numbers.zipWithNext().all { (a, b) -> b > a }, "the count only goes up: $numbers")
            assertEquals(150.0, cs.outvalue("count") as Double, 2.0)
            assertEquals(listOf("hello"), words, "a string is delivered when it changes, not every k-cycle")
            assertEquals(listOf(0.5), gains)
        }
    }

    @Test fun midiNotesReachTheOrchestra() {
        if (!available()) return
        val orc = """
            massign 0, "Synth"
            instr Synth
              inote notnum
              ivel  veloc 0, 127
              chnset inote, "note"
              chnset ivel, "vel"
            endin
        """
        CsoundInstance(orc, "f 0 z").use { cs ->
            cs.renderOffline(0.01)
            cs.noteOn(0, 64, 99)
            cs.renderOffline(0.02)
            assertEquals(64.0, cs.channel("note"))
            assertEquals(99.0, cs.channel("vel"))
        }
    }

    @Test fun compileNowRedefinesAnInstrumentAndReportsItsErrorsOnTheirLines() {
        if (!available()) return
        CsoundInstance("instr 1\n a1 poscil 0.2, 440\n outs a1, a1\nendin", "i 1 0 z").use { cs ->
            val ok = cs.compileNow("instr 1\n a1 poscil 0.2, 880\n outs a1, a1\nendin")
            assertTrue(ok.ok, "${ok.printed}")
            val (l, _) = cs.renderOffline(1.0)
            val ups = (1 until l.size).count { l[it - 1] < 0f && l[it] >= 0f }
            assertTrue(abs(ups - 880) <= 2, "the redefined instr 1 plays 880 Hz: $ups")
            val bad = cs.compileNow("instr 2\n a1 poscilx 0.2, 440\nendin")
            assertTrue(!bad.ok)
            assertTrue(bad.problems.any { it.line == 2 }, "${bad.problems}")
            val again = cs.compileNow("instr 3\n a1 poscil 0.2, 440\n\n a2 poscily 0.2, 440\nendin")
            assertTrue(again.problems.any { it.line == 4 }, "a second live compile counts from the same place: ${again.problems}")
        }
    }

    @Test fun chnKHintsAreReadFromCsoundItself() {
        if (!available()) return
        CsoundInstance("chn_k \"cutoff\", 1, 3, 1200, 200, 6000\ninstr 1\nendin", "f 0 z").use { cs ->
            assertEquals(CsoundInstance.ControlHints(3, 1200.0, 200.0, 6000.0), cs.hints("cutoff"))
            assertEquals(null, cs.hints("nothing"))
        }
    }
}
