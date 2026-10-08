package afield.four.synth

import java.io.File
import kotlin.test.*

/** ORCA's own operators, run in the JVM, and the page's sound rendered from them. */
class OrcaHeadlessTest {
    @Test fun aDelayAndAMidiOperatorPlayEveryFourFrames() = OrcaHeadless("D4\n.:03C").use { o ->
        val notes = mutableListOf<OrcaNote>()
        o.orca.onNote { notes += it }
        o.step(16)
        assertEquals(listOf(0, 4, 8, 12), notes.map { it.frame })
        assertTrue(notes.all { it.midi == 60 && it.channel == 0 })
        assertEquals(16, o.orca.frame)
        assertEquals('D', o.orca[0, 0])
    }

    @Test fun cellsAndVariablesFromKotlinReachTheOperators() = OrcaHeadless("D1\n.:03.\n..Va.").use { o ->
        val notes = mutableListOf<Int>()
        o.orca.onNote { notes += it.midi }
        // `Va` reads variable a into the cell below it; ':' takes its note from (4, 1).
        o.orca.variable('a', 'E')
        o.step(2)
        assertEquals('E', o.orca[2, 3], "Va outputs the variable the box holds")
        o.orca[4, 1] = 'G'
        o.step(3)
        assertTrue(67 in notes, "the note set from Kotlin plays: $notes")
    }

    @Test fun javaScriptRunsBesideTheOperatorsAndCanPatchThem() = OrcaHeadless("D1\n.:03C").use { o ->
        assertEquals(64, o.evaluate("client.orca.w").toString().toInt())
        // Replace the MIDI operator's note with a fixed G, from Kotlin, through the same op a box sends.
        val notes = mutableListOf<Int>()
        o.orca.onNote { notes += it.midi }
        o.orca.js("""
            const Midi = library[':']
            library[':'] = function (orca, x, y, passive) {
              Midi.call(this, orca, x, y, passive)
              const listen = this.listen
              this.listen = (port, toValue) => port === this.ports.note ? 'G' : listen.call(this, port, toValue)
            }
        """)
        o.step(3)
        assertTrue(notes.isNotEmpty() && notes.last() == 67, "the patched operator plays G: $notes")
    }

    @Test fun everyOutputKindIsCaptured() = OrcaHeadless("D1\n.;hi\nD1\n.=a12\nD1\n.!012").use { o ->
        val udp = mutableListOf<String>(); val osc = mutableListOf<OrcaOsc>(); var cc = 0
        o.orca.onUdp { udp += it }.onOsc { osc += it }.onCC { cc++ }
        o.step(2)
        assertTrue("hi" in udp)
        assertEquals(listOf(1, 2), osc.first().args)
        assertTrue(cc > 0)
    }

    @Test fun theDuetEvolvesAndBothVoicesSound() {
        val duet = OrcaSoundDocs.render("duet")
        val byChannel = duet.notes.groupBy { it.channel }
        assertEquals(setOf(0, 1, 2), byChannel.keys, "lead's melody, lead's bass and echo all play")
        assertTrue(byChannel.getValue(1).any { it.note == "F" }, "the bass follows the tune onto F")
        assertTrue(byChannel.getValue(2).map { it.note }.toSet().size > 1, "echo plays what lead played, not its own Cs")
        assertTrue(duet.left.any { it != 0f } && duet.right.any { it != 0f })
        // Notes start and stop: the level over each ORCA frame moves, it is not one held block.
        val frameSamples = (OrcaSoundDocs.SR * 0.125).toInt()
        val levels = duet.left.toList().chunked(frameSamples).map { c -> c.sumOf { (it * it).toDouble() } / c.size }
        assertTrue(levels.min() < 0.25 * levels.max(), "levels per frame run from ${levels.min()} to ${levels.max()}")
        assertEquals(16 * OrcaSoundDocs.SR, duet.left.size)
        // Offline means repeatable: the same notes on the same frames, every render.
        assertEquals(duet.notes.map { it.frame to it.midi }, OrcaSoundDocs.render("duet").notes.map { it.frame to it.midi })
    }

    // The renderer's copies of the duet and of the page's first box must match the originals.
    @Test fun theRendererPlaysTheSameGridsAndExchangeAsTheBoxes() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "dev_docs").isDirectory }
        val duet = File(root, "dev_docs/agents/transcripts/orca_duet.yaml").readText()
        val page = File(root, "public/docs/orca.md").readText()
        val source = File(root, "src/jvmMain/kotlin/afield/four/synth/OrcaSoundDocs.kt").readText()
        fun rows(grid: String) = grid.lines().map { it.trim() }
        for (row in rows(OrcaSoundDocs.LEAD) + rows(OrcaSoundDocs.ECHO)) assertTrue(row in duet, "orca_duet.yaml has grid row $row")
        for (row in rows(OrcaSoundDocs.HELLO)) assertTrue(row in page, "orca.md has grid row $row")
        val boxCode = duet.substringAfter("code: |\n").lines().joinToString("\n") { it.removePrefix("          ") }
        assertTrue(boxCode.trim() in page, "orca.md's duet fence is the starting point's code")
        val exchange = listOf(
            "val up = mapOf('C' to 'D', 'D' to 'E', 'E' to 'G', 'G' to 'A', 'A' to 'C')",
            "if (it.channel == 0) { heard.addLast(it.note[0]); heard.removeFirst() }",
            "play(it, pan = 0.35, wave = if (it.channel == 1) PatWave.SINE else PatWave.TRIANGLE)",
            "play(it, pan = 0.7, wave = PatWave.SQUARE, gain = 0.35, cutoffHz = 2400.0)",
            "heard.forEachIndexed { i, note -> echo[5 + i, 3] = note }",
            "if (echoes >= 8) {",
            "val x = 5 + seen % 8",
            "lead[4, 7] = if (lead[4, 4] in \"GA\") 'F' else 'C'",
        )
        for (line in exchange) {
            assertTrue(line in duet, "orca_duet.yaml has `$line`")
            assertTrue(line in source, "OrcaSoundDocs has `$line`")
        }
    }
}
