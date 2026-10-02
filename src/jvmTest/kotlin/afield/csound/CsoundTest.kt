package afield.csound

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * libcsound through FFM, measured from the samples.
 *
 * Optional like libpd: without a built Csound every test but the first prints a SKIPPED line and
 * returns. Build it with `python3 tools/build_csound_native.py`.
 */
class CsoundTest {

    private fun available(): Boolean {
        if (!CsoundLibrary.isAvailable) {
            println("SKIPPED Csound tests: ${CsoundLibrary.describe()}")
            return false
        }
        return true
    }

    private val sine = """
        instr 1
          a1 oscili 0.25, 440
          outs a1, a1
        endin
    """

    private fun ups(x: FloatArray, from: Int = 0, to: Int = x.size) = (from + 1 until to).count { x[it - 1] < 0f && x[it] >= 0f }
    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size) = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    @Test fun missingLibraryIsReportedNotThrownAtClassLoad() {
        assertTrue(CsoundLibrary.searchedPaths.isNotEmpty())
        assertTrue(CsoundLibrary.HOW_TO_BUILD.contains("build_csound_native.py"))
    }

    @Test fun aSineRendersAtItsFrequencyAndAmplitude() {
        if (!available()) return
        CsoundInstance(sine, "i1 0 1", sampleRate = 48_000).use { cs ->
            assertTrue(cs.version >= 6180, "version ${cs.version}")
            val (left, right) = cs.renderOffline()
            assertTrue(cs.finished, "the score ended")
            assertTrue(left.size - 48_000 in 0..1024, "Csound ends a few blocks after the last event; rendered ${left.size} frames for a 1 s score")
            val n = 48_000
            val crossings = ups(left, 0, n)
            assertTrue(abs(crossings - 440) <= 1, "expected ~440 upward zero crossings, got $crossings")
            assertEquals(0.25, left.take(n).maxOf { abs(it) }.toDouble(), 0.002)
            assertEquals(0.25 / sqrt(2.0), rms(left, 0, n), 0.002)
            assertTrue(left.contentEquals(right), "outs a1, a1 is the same on both sides")
        }
    }

    @Test fun oddSizedRendersJoinUpExactly() {
        if (!available()) return
        val whole = CsoundInstance(sine, "i1 0 1").use { cs -> FloatArray(300).also { cs.render(it, FloatArray(300), 300) } }
        val pieces = CsoundInstance(sine, "i1 0 1").use { cs ->
            val out = FloatArray(300)
            var at = 0
            for (n in listOf(1, 31, 100, 168)) {
                val l = FloatArray(n)
                cs.render(l, FloatArray(n), n)
                System.arraycopy(l, 0, out, at, n)
                at += n
            }
            out
        }
        assertTrue(whole.contentEquals(pieces))
    }

    @Test fun aControlChannelSetsTheFrequencyAndChnsetReportsBack() {
        if (!available()) return
        val orc = """
            instr 1
              kf chnget "freq"
              a1 oscili 0.2, kf
              outs a1, a1
              chnset rms(a1), "level"
            endin
        """
        CsoundInstance(orc, "i1 0 2").use { cs ->
            cs.setChannel("freq", 220)
            val (a, _) = cs.renderOffline(1.0)
            cs.setChannel("freq", 660)
            val (b, _) = cs.renderOffline(1.0)
            assertTrue(abs(ups(a) - 220) <= 2, "first second at 220 Hz, got ${ups(a)}")
            assertTrue(abs(ups(b) - 660) <= 2, "second second at 660 Hz, got ${ups(b)}")
            assertEquals(0.2 / sqrt(2.0), cs.channel("level"), 0.01)
        }
    }

    @Test fun liveEventsAndInstrumentsArriveAtTheNextBlock() {
        if (!available()) return
        CsoundInstance(sine, "f0 10").use { cs ->
            val (silent, _) = cs.renderOffline(0.25)
            assertEquals(0f, silent.maxOf { abs(it) })
            cs.compile("instr 2\n a1 oscili 0.1, 330\n outs a1, a1\nendin")
            cs.event('i', 2, 0, 1)
            val (sound, _) = cs.renderOffline(1.0)
            assertTrue(abs(ups(sound) - 330) <= 2, "instr 2 compiled live plays 330 Hz, got ${ups(sound)}")
        }
    }

    @Test fun printedLinesReachTheCallback() {
        if (!available()) return
        val lines = ArrayList<String>()
        CsoundInstance("instr 1\n prints \"hello from csound\\n\"\nendin", "i1 0 0.1").use { cs ->
            cs.onMessage { lines += it }
            cs.renderOffline()
        }
        assertTrue(lines.any { it.contains("hello from csound") }, "got: $lines")
    }

    @Test fun aSyntaxErrorIsPlacedOnItsLine() {
        if (!available()) return
        val orc = "instr 1\n  a1 oscili 0.2, 440\n  a2 = a1 +* 2\n  outs a1, a1\nendin"
        val e = assertFailsWith<CsoundCompileError> { CsoundInstance(orc, "i1 0 1") }
        println(e.printed.joinToString("\n"))
        assertTrue(e.problems.any { it.line == 3 }, "problems: ${e.problems}")
    }

    @Test fun anUnknownOpcodeIsPlacedOnItsLine() {
        if (!available()) return
        val orc = "instr 1\n  a1 oscilx 0.2, 440\n  outs a1, a1\nendin"
        val e = assertFailsWith<CsoundCompileError> { CsoundInstance(orc, "i1 0 1") }
        println(e.printed.joinToString("\n"))
        assertTrue(e.problems.any { it.line == 2 }, "problems: ${e.problems}")
    }

    @Test fun aLeadingBlankLineStillCounts() {
        if (!available()) return
        // A box writes `csound("""` and starts the orchestra on the next line; Csound's line numbers
        // must be lines of the string as written.
        val e = assertFailsWith<CsoundCompileError> { CsoundInstance("\n  instr 1\n    a1 oscilx 0.2, 440\n  endin\n", "i1 0 1") }
        assertTrue(e.problems.any { it.line == 3 }, "problems: ${e.problems}")
    }

    @Test fun renderToWavWritesAStereoFile() {
        if (!available()) return
        val dir = kotlin.io.path.createTempDirectory("csound-wav").toFile()
        try {
            val f = CsoundInstance(sine, "i1 0 0.5").use { it.renderToWav(File(dir, "sine.wav")) }
            val header = f.readBytes()
            assertEquals("RIFF", String(header, 0, 4))
            assertTrue(header.size > 44 + 4 * 24_000 - 1000)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun anOpenScoreKeepsTakingEvents() {
        if (!available()) return
        CsoundInstance(sine.replace("440", "p4"), "f0 z").use { cs ->
            cs.renderOffline(3.0)
            assertTrue(!cs.finished, "f0 z holds the score open")
            cs.event('i', 1, 0, 0.5, 550)
            val (a, _) = cs.renderOffline(0.5)
            assertTrue(abs(ups(a) - 275) <= 2, "550 Hz for half a second, got ${ups(a)}")
        }
    }

    @Test fun chnKDeclarationsAreRead() {
        val chans = afield.four.synth.csoundChannels("""
            chn_k "freq", 1, 2, 220, 55, 880  ; pitch
            chn_k "level", 2
            instr 1
            endin
        """)
        assertEquals(listOf("freq", "level"), chans.map { it.name })
        assertEquals(afield.four.synth.CsoundChannel("freq", 1, 220.0, 55.0, 880.0), chans[0])
        assertTrue(chans[0].isInput && !chans[1].isInput)
    }
}
