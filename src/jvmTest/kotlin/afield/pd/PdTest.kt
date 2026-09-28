package afield.pd

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * libpd through FFM, measured from the samples.
 *
 * Optional like TetGen and Manifold: without `build/field-libpd` every test but the first prints a
 * SKIPPED line and returns. Build it with `python3 tools/build_libpd_native.py`.
 */
class PdTest {

    private fun available(): Boolean {
        if (!PdLibrary.isAvailable) {
            println("SKIPPED libpd tests: ${PdLibrary.describe()}")
            return false
        }
        return true
    }

    private val sine = """
        #N canvas 0 0 400 300 12;
        #X obj 20 20 osc~ 440;
        #X obj 20 60 *~ 0.25;
        #X obj 20 100 dac~;
        #X connect 0 0 1 0;
        #X connect 1 0 2 0;
        #X connect 1 0 2 1;
    """

    @Test fun missingLibraryIsReportedNotThrownAtClassLoad() {
        assertTrue(PdLibrary.fileName.startsWith("libpd-multi"))
        assertTrue(PdLibrary.searchedPaths.isNotEmpty())
        assertTrue(PdLibrary.HOW_TO_BUILD.contains("build_libpd_native.py"))
    }

    @Test fun anOscillatorRendersAtItsFrequencyAndAmplitude() {
        if (!available()) return
        PdInstance(sampleRate = 48_000).use { pd ->
            pd.openPatchText(sine, "sine")
            val (left, right) = pd.renderOffline(1.0)
            assertEquals(48_000, left.size)
            // Upward zero crossings over one second are the frequency, to within one.
            val ups = (1 until left.size).count { left[it - 1] < 0f && left[it] >= 0f }
            assertTrue(abs(ups - 440) <= 1, "expected ~440 upward zero crossings, got $ups")
            // A sine of amplitude a has peak a and RMS a/sqrt(2).
            val peak = left.maxOf { abs(it) }
            val rms = sqrt(left.sumOf { it.toDouble() * it } / left.size)
            assertEquals(0.25, peak.toDouble(), 0.002)
            assertEquals(0.25 / sqrt(2.0), rms, 0.002)
            assertTrue(left.contentEquals(right), "both dac~ inlets carry the same signal")
        }
    }

    @Test fun oddSizedRendersJoinUpExactly() {
        if (!available()) return
        val whole = PdInstance().use { pd ->
            pd.openPatchText(sine)
            FloatArray(300).also { pd.render(it, FloatArray(300), 300) }
        }
        val pieces = PdInstance().use { pd ->
            pd.openPatchText(sine)
            val out = FloatArray(300)
            var at = 0
            for (n in listOf(1, 63, 100, 136)) {
                val l = FloatArray(n)
                pd.render(l, FloatArray(n), n)
                System.arraycopy(l, 0, out, at, n)
                at += n
            }
            out
        }
        assertTrue(whole.contentEquals(pieces), "rendering 300 frames in pieces must equal one render")
    }

    @Test fun aMessageMakesTheRoundTrip() {
        if (!available()) return
        PdInstance().use { pd ->
            pd.openPatchText("""
                #X obj 20 20 r in;
                #X obj 20 60 + 1;
                #X obj 20 100 s out;
                #X connect 0 0 1 0;
                #X connect 1 0 2 0;
            """)
            val seen = ArrayList<Float>()
            pd.receive("out") { m -> m.float?.let { seen += it } }
            pd.send("in", 41)
            assertTrue(seen.isEmpty(), "sends are queued, not delivered on the caller's stack")
            pd.flush()
            assertEquals(listOf(42f), seen)
            // And through the render path, the way a playing box sees it.
            pd.send("in", 1.5)
            pd.renderOffline(0.01)
            assertEquals(listOf(42f, 2.5f), seen)
            assertEquals(2.5f, pd.lastValue("out")?.float)
        }
    }

    @Test fun symbolsListsAndPrintCome_back() {
        if (!available()) return
        PdInstance().use { pd ->
            pd.openPatchText("""
                #X obj 20 20 r words;
                #X obj 20 60 print said;
                #X obj 20 100 s echo;
                #X connect 0 0 1 0;
                #X connect 0 0 2 0;
            """)
            val printed = ArrayList<String>()
            val echoed = ArrayList<PdInstance.Message>()
            pd.onPrint { printed += it }
            pd.receive("echo") { echoed += it }
            pd.list("words", 1, "two", 3.5)
            pd.symbol("words", "hello")
            pd.flush()
            assertEquals(listOf("said: 1 two 3.5", "said: symbol hello"), printed.filter { it.startsWith("said") })
            assertEquals(listOf<Any>(1f, "two", 3.5f), echoed[0].args)
            assertEquals("list", echoed[0].selector)
            assertEquals("hello", echoed[1].symbol)
        }
    }

    @Test fun arraysWriteAndReadBack() {
        if (!available()) return
        PdInstance().use { pd ->
            pd.openPatchText("#X obj 20 20 table tab 8;")
            assertEquals(8, pd.arraySize("tab"))
            assertEquals(-1, pd.arraySize("nope"))
            pd.writeArray("tab", floatArrayOf(0.5f, -1f, 2f), offset = 2)
            val back = pd.readArray("tab")
            assertTrue(floatArrayOf(0f, 0f, 0.5f, -1f, 2f, 0f, 0f, 0f).contentEquals(back), back.joinToString())
            pd.resizeArray("tab", 4)
            assertEquals(4, pd.arraySize("tab"))
        }
    }

    @Test fun aPatchReadsAnArrayThatFieldWrote() {
        if (!available()) return
        // Field writes a wavetable; [tabread4~]-free [tabplay~] plays it back into the dac~.
        PdInstance().use { pd ->
            pd.openPatchText("""
                #X obj 20 20 table wave 64;
                #X obj 20 60 r go;
                #X obj 20 100 tabplay~ wave;
                #X obj 20 140 dac~;
                #X connect 1 0 2 0;
                #X connect 2 0 3 0;
            """)
            val ramp = FloatArray(64) { it / 64f }
            pd.writeArray("wave", ramp)
            pd.bang("go")
            val (left, _) = pd.renderOffline(128.0 / pd.sampleRate)
            // tabplay~ starts on the block after the bang arrives, sample-exact within that block.
            val start = left.indexOfFirst { it != 0f }.let { if (it < 0) 0 else it - 1 }
            for (k in 1 until 64) assertEquals(ramp[k], left[start + k], 1e-6f, "sample $k")
        }
    }

    @Test fun twoInstancesDoNotHearEachOther() {
        if (!available()) return
        val relay = "#X obj 20 20 r in;\n#X obj 20 60 s out;\n#X connect 0 0 1 0;"
        PdInstance().use { a ->
            PdInstance().use { b ->
                a.openPatchText(relay)
                b.openPatchText(relay)
                val gotA = ArrayList<Float>()
                val gotB = ArrayList<Float>()
                a.receive("out") { gotA += it.float!! }
                b.receive("out") { gotB += it.float!! }
                a.send("in", 1)
                b.send("in", 2)
                a.flush(); b.flush()
                assertEquals(listOf(1f), gotA)
                assertEquals(listOf(2f), gotB)
            }
        }
    }

    @Test fun anObjectPdCannotCreateIsPlacedOnItsLine() {
        if (!available()) return
        PdInstance().use { pd ->
            val text = "#N canvas 0 0 400 300 12;\n#X obj 20 20 osc 440;\n#X obj 20 60 dac~;\n#X connect 0 0 1 0;\n"
            val patch = pd.openPatchText(text)
            val problem = patch.problems.firstOrNull { it.message.contains("osc 440") }
            assertTrue(problem != null, "problems were ${patch.problems}; console ${pd.console}")
            assertEquals(1, problem.line, "${patch.problems}")
        }
    }

    @Test fun pdGuiSlidersBecomeFieldControlsAndStillWorkWithoutAGui() {
        val text = """
            #N canvas 0 0 400 300 12;
            #X obj 20 20 hsl 128 15 100 1000 0 0 freqOut freq Frequency -2 -8 0 10 #fcfcfc #000000 #000000 0 1;
            #X obj 20 60 tgl 15 0 empty gate Gate 17 7 0 10 #fcfcfc #000000 #000000 0 1;
            #X obj 20 100 hsl 128 15 0 1 0 0 empty empty empty -2 -8 0 10 #fcfcfc #000000 #000000 0 1;
        """
        val controls = afield.four.synth.pdGuiControls(text)
        assertEquals(2, controls.size, "the unnamed slider has nothing to send to: $controls")
        assertEquals(afield.four.synth.PdGuiControl("hsl", "freq", "Frequency", 100.0, 1000.0, 100.0), controls[0])
        assertEquals("tgl", controls[1].kind)
        assertEquals(1.0, controls[1].max)
        if (!available()) return
        PdInstance().use { pd ->
            pd.openPatchText(text)
            val out = ArrayList<Float>()
            pd.receive("freqOut") { out += it.float!! }
            pd.send("freq", 500)
            pd.flush()
            assertEquals(listOf(500f), out, "an hsl with no GUI still clips and forwards what it receives")
            // Pd's hsl forwards a received float as it is, without clipping it to the range.
            pd.send("freq", 5000)
            pd.flush()
            assertEquals(5000f, out.last())
        }
    }

    @Test fun aPatchRendersToAWavWithoutAnAudioDevice() {
        if (!available()) return
        val file = kotlin.io.path.createTempFile("field-pd-", ".wav").toFile()
        try {
            afield.four.synth.renderPdWav(file, sine, 0.5)
            val bytes = file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4))
            // 16-bit stereo: 44 header bytes + 4 bytes a frame.
            assertEquals(44 + 24_000 * 4, bytes.size)
        } finally {
            file.delete()
        }
    }
}
