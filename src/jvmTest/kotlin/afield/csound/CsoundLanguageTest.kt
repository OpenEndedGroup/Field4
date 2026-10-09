package afield.csound

import afield.Box
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Csound box language without a running Csound: reading a `.csd`, what Alt-Enter does where,
 * completion, line notes — and, when libcsound is built, that the committed vocabulary is what the
 * library lists today.
 */
class CsoundLanguageTest {

    private val csd = """
        <CsoundSynthesizer>
        <CsOptions>
        -m0
        </CsOptions>
        <CsInstruments>
        chn_k "cutoff", 1, 3, 1200, 200, 6000
        giSine ftgen 0, 0, 8192, 10, 1

        instr Pluck
          iamp = ampdbfs(p5)
          asig pluck iamp, p4, p4, 0, 1
          kcut chnget "cutoff"
          outs butterlp(asig, kcut), butterlp(asig, kcut)
        endin

        instr 2
          prints "two\n"
        endin
        </CsInstruments>
        <CsScore>
        f 0 z
        i "Pluck" 0 1 220 -12
        i .       1 1 330 -12
        i 2 0 0.1
        </CsScore>
        </CsoundSynthesizer>
    """.trimIndent()

    private val lines = csd.split("\n")
    private fun lineOf(fragment: String) = lines.indexOfFirst { it.contains(fragment) }

    @Test fun aCsdIsReadIntoItsSectionsWithTheirLines() {
        val doc = CsdDocument.parse(csd)
        assertEquals(listOf("-m0"), doc.optionFlags)
        assertEquals(lineOf("chn_k"), doc.orchestra.firstLine)
        assertEquals(lineOf("f 0 z"), doc.score!!.firstLine)
        assertEquals(listOf(listOf("Pluck"), listOf("2")), doc.instruments.map { it.names })
        assertEquals(lineOf("instr Pluck"), doc.instruments[0].startLine)
        assertEquals(listOf("Pluck", "Pluck", "2"), doc.events.map { it.instrument }, "the `.` carries p1")
        assertEquals(setOf("cutoff"), doc.channelNames)
        assertEquals(CsdDocument.Part.SCORE, doc.partAt(lineOf("i 2 0")))
        assertEquals(CsdDocument.Part.OPTIONS, doc.partAt(lineOf("-m0")))
    }

    @Test fun aBareOrchestraIsTheWholeText() {
        val doc = CsdDocument.parse("instr 1\n a1 poscil 0.1, 440\n outs a1, a1\nendin")
        assertEquals(0, doc.orchestra.firstLine)
        assertEquals(null, doc.score)
        assertEquals(listOf(listOf("1")), doc.instruments.map { it.names })
    }

    @Test fun altEnterRecompilesTheEnclosingInstrumentSendsScoreLinesAndCompilesGlobals() {
        val ex = CsoundExecution(Box("csound-lang-test"))
        val inside = lineOf("asig pluck")
        val (a, textA) = ex.dispatchFor(csd, lines[inside], inside)
        assertEquals(CsoundExecution.Dispatch.Instrument(listOf("Pluck"), lineOf("instr Pluck")), a)
        assertTrue(textA.startsWith("instr Pluck") && textA.trimEnd().endsWith("endin"), textA)

        val ev = lineOf("i 2 0")
        val (b, textB) = ex.dispatchFor(csd, lines[ev], ev)
        assertEquals(CsoundExecution.Dispatch.Score("i 2 0 0.1", ev), b)
        assertEquals(lines[ev], textB)

        val g = lineOf("giSine")
        assertEquals(CsoundExecution.Dispatch.Global(g), ex.dispatchFor(csd, lines[g], g).first)

        val sel = lines.subList(lineOf("instr 2"), lineOf("instr 2") + 3).joinToString("\n")
        assertEquals(CsoundExecution.Dispatch.Instrument(listOf("2"), lineOf("instr 2")), ex.dispatchFor(csd, sel, lineOf("instr 2")).first)

        assertEquals(CsoundExecution.Dispatch.Options(lineOf("-m0")), ex.dispatchFor(csd, "-m0", lineOf("-m0")).first)
    }

    @Test fun completionOffersGeneratedOpcodesWithSignaturesChannelsAndInstruments() {
        val ex = CsoundExecution(Box("csound-lang-test-complete"))
        val at = csd.indexOf("asig pluck") + "asig posc".length
        val code = csd.substring(0, at - "posc".length) + "posc" + csd.substring(at - "posc".length + "pluck".length)
        val pos = code.indexOf("asig posc") + "asig posc".length
        val (_, rows) = ex.completions(code, pos)!!
        val poscil = rows.firstOrNull { it.text == "poscil" }
        assertNotNull(poscil, "poscil is offered: ${rows.map { it.text }}")
        assertEquals("ares poscil aamp, acps [, ifn, iphs]", poscil.tail)
        assertEquals(CsoundExecution.OPCODE_CATEGORY, poscil.category)
        assertEquals("""
            High precision oscillator.

            ```csound
            ares poscil aamp, acps [, ifn, iphs]
            ares poscil aamp, kcps [, ifn, iphs]
            ares poscil kamp, acps [, ifn, iphs]
            ares poscil kamp, kcps [, ifn, iphs]
            ires poscil kamp, kcps [, ifn, iphs]
            kres poscil kamp, kcps [, ifn, iphs]
            ```
        """.trimIndent(), poscil.documentation.substringBefore("\n\n- "))
        assertTrue(poscil.documentation.contains("- `ifn` — (optional) function table number."), poscil.documentation)
        assertTrue(poscil.documentation.endsWith("[csound.com/docs/manual/poscil.html](https://csound.com/docs/manual/poscil.html)"), poscil.documentation)
        // An opcode the manual lacks falls back to libcsound's spelled-out signature.
        val maxtab = CsoundExecution.opcodeTail(CsoundVocabulary.byName.getValue("maxtab"))
        assertEquals(CsoundVocabulary.describe("maxtab", CsoundVocabulary.byName.getValue("maxtab").signatures.first()), maxtab)

        val chan = csd.indexOf("chnget \"cutoff\"") + "chnget \"cu".length
        val (_, chans) = ex.completions(csd, chan)!!
        assertEquals(listOf("cutoff"), chans.map { it.text })

        val score = csd.indexOf("i \"Pluck\" 0 1 220") + "i \"Pl".length
        val (_, instrs) = ex.completions(csd, score)!!
        assertEquals(listOf("Pluck"), instrs.map { it.text })
    }

    @Test fun lineNotesCountEventsAndNameWhatAnEventPlays() {
        val doc = CsdDocument.parse(csd)
        val pluck = csoundLineNote(doc, lineOf("instr Pluck"), null)!!
        assertEquals("instr Pluck: 2 events in the score", pluck.caption)
        val two = csoundLineNote(doc, lineOf("instr 2"), null)!!
        assertEquals("instr 2: 1 event in the score", two.caption)
        val ev = csoundLineNote(doc, lineOf("i .       1"), null)!!
        assertEquals("plays instr Pluck, defined on line ${lineOf("instr Pluck") + 1}", ev.caption)
        val missing = csoundLineNote(CsdDocument.parse(csd.replace("i 2 0 0.1", "i 9 0 0.1")), lineOf("i 2 0"), null)!!
        assertEquals("no instrument 9 in this orchestra", missing.caption)
        val chan = csoundLineNote(doc, lineOf("chnget \"cutoff\""), null)!!
        assertTrue(chan.volatile && chan.meta.contains("not playing"), "${chan.caption} ${chan.meta}")
    }

    @Test fun aLiveChannelNoteReadsThePlayingCsound() {
        if (!CsoundLibrary.isAvailable) return println("SKIPPED: ${CsoundLibrary.describe()}")
        val doc = CsdDocument.parse(csd)
        CsoundInstance(doc.orchestra.body, doc.score!!.body).use { cs ->
            cs.setChannel("cutoff", 900)
            val note = csoundLineNote(doc, lineOf("chnget \"cutoff\""), cs)!!
            assertEquals("\"cutoff\" = 900.0", note.caption)
        }
    }

    @Test fun theCommittedVocabularyIsWhatLibcsoundListsToday() {
        val committed = CsoundVocabulary.committed
        assertTrue(committed.opcodes.size > 1000, "csound-vocabulary.json is missing or truncated: ${committed.opcodes.size}")
        if (!CsoundLibrary.isAvailable) return println("SKIPPED vocabulary drift: ${CsoundLibrary.describe()}")
        // Keyed on the library's list: names and signatures must be exactly what libcsound says today.
        val generated = CsoundVocabulary.generate()
        assertEquals(generated.csoundVersion, committed.csoundVersion)
        assertEquals(generated.opcodes.map { it.name to it.signatures }, committed.opcodes.map { it.name to it.signatures },
            "csound-vocabulary.json has drifted from libcsound; regenerate with ./gradlew generateCsoundVocabulary")
        val manual = cachedManual() ?: return println("SKIPPED manual drift: the pinned manual is not in the download cache")
        assertEquals(CsoundVocabulary.toJson(CsoundVocabulary.describe(generated, CsoundVocabulary.readManual(manual))),
            File(CsoundVocabulary.RESOURCE_PATH).readText(), "the descriptions have drifted from manual ${CsoundVocabulary.MANUAL_TAG}")
    }

    private fun cachedManual(): File? {
        val root = System.getenv("AFIELD_NATIVE_DOWNLOAD_CACHE")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: if (System.getProperty("os.name").lowercase().contains("mac")) File(System.getProperty("user.home"), "Library/Caches/Field4")
            else File(System.getenv("XDG_CACHE_HOME") ?: (System.getProperty("user.home") + "/.cache"), "field4")
        return File(root, "native-downloads/csound-manual/manual-${CsoundVocabulary.MANUAL_TAG}").takeIf { File(it, "opcodes").isDirectory }
    }

    @Test fun readingTheManualIsDeterministic() {
        val manual = cachedManual() ?: return println("SKIPPED: the pinned manual is not in the download cache")
        assertEquals(CsoundVocabulary.readManual(manual), CsoundVocabulary.readManual(manual))
    }

    @Test fun knownOpcodesCarryTheManualsDescriptionSyntaxAndArguments() {
        val v = CsoundVocabulary.byName
        val aftouch = v.getValue("aftouch")
        assertEquals("Get the current after-touch value for this channel.", aftouch.description)
        assertEquals(listOf("kaft aftouch [imin] [, imax]"), aftouch.syntax)
        assertEquals(listOf("imin", "imax"), aftouch.arguments.map { it.name })
        assertEquals("aftouch", aftouch.page)
        assertEquals("High precision oscillator.", v.getValue("poscil").description)
        assertTrue("ares poscil aamp, acps [, ifn, iphs]" in v.getValue("poscil").syntax)
        val butterlp = v.getValue("butterlp")
        assertEquals("A low-pass Butterworth filter.", butterlp.description)
        assertTrue(butterlp.arguments.any { it.name == "kfreq" })
        assertEquals("Declare a channel of the named software bus.", v.getValue("chn_k").description)
        val m = CsoundVocabulary.committed.manual!!
        assertEquals(CsoundVocabulary.MANUAL_TAG, m.tag)
        assertTrue(m.licence.startsWith("GNU Free Documentation License"))
        assertEquals(CsoundVocabulary.committed.opcodes.count { it.description == null }, m.undescribed.size)
        assertTrue(File("src/commonMain/resources/csound-manual-COPYING.txt").readText().contains("GNU Free Documentation License"))
    }
}
