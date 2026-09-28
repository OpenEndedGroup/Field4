package afield.pd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The patch text read as a graph — no libpd involved, so these always run. */
class PdPatchGraphTest {

    private val patch = """
        #N canvas 0 0 450 300 12;
        #X obj 20 20 r freq;
        #X obj 20 60 osc~ 220;
        #N canvas 0 0 200 200 inner 0;
        #X obj 10 10 inlet~;
        #X obj 10 50 outlet~;
        #X connect 0 0 1 0;
        #X restore 20 100 pd inner;
        #X msg 120 20 set \$1 3;
        #X obj 20 140 dac~;
        #X obj 120 60 s out;
        #X connect 0 0 1 0;
        #X connect 1 0 2 0;
        #X connect 2 0 4 0;
        #X connect 2 0 4 1;
        #X connect 3 0 5 0;
    """.trimIndent()

    @Test fun objectsAreIndexedAsPdIndexesThem() {
        val g = PdPatchGraph.parse(patch)
        assertEquals(listOf("[r freq]", "[osc~ 220]", "[pd inner]", "[set $1 3(", "[dac~]", "[s out]"), g.objects.map { it.label })
        assertEquals("pd", g.objects[2].kind, "a subpatch is one object of its parent")
        assertEquals(5, g.connections.size, "the subpatch's own connection is not the parent's")
        assertEquals(setOf("out"), g.sendNames)
        assertEquals(setOf("freq"), g.receiveNames)
    }

    @Test fun aLineNoteSaysWhatAnObjectIsWiredTo() {
        val g = PdPatchGraph.parse(patch)
        val osc = g.describeLine(2)!!
        assertTrue(osc.startsWith("[osc~ 220] is object 1; fed by [r freq] (outlet 0 → inlet 0); feeds [pd inner]"), osc)
        val send = g.describeLine(10) { name -> if (name == "out") PdInstance.Message("out", "float", listOf(42f)) else null }!!
        assertTrue(send.endsWith("Last on 'out': 42.0."), send)
        assertEquals("[pd inner] outlet 0 → [dac~] inlet 1.", g.describeLine(14))
        assertNull(g.describeLine(0), "the canvas header says nothing")
    }

    @Test fun theDrawingHasABoxPerObjectAndALinePerConnection() {
        val g = PdPatchGraph.parse(patch)
        val lines = g.toFLines(labels = false)
        val tabs = g.objects.filter { it.kind != "text" }.sumOf { g.ports(it.index).let { (i, o) -> i + o } }
        assertEquals(g.objects.size + g.connections.size + tabs, lines.size)
        assertEquals(2 to 1, g.ports(g.objects.first { it.className == "osc~" }.index))
        assertEquals(2 to 0, g.ports(g.objects.first { it.className == "dac~" }.index))
        assertEquals(1 to 1, g.ports(g.objects.first { it.kind == "pd" }.index), "a subpatch shows what is wired")
    }

    @Test fun messageLinesReadAsAPdMessageBoxWouldSendThem() {
        assertEquals(Triple("freq", "float", listOf<Any>(440f)), parsePdMessageLine("freq 440"))
        assertEquals(Triple("go", "bang", emptyList<Any>()), parsePdMessageLine("; go;"))
        assertEquals(Triple("go", "bang", emptyList<Any>()), parsePdMessageLine("go"))
        assertEquals(Triple("v", "set", listOf<Any>(3f, "two")), parsePdMessageLine("v set 3 two"))
        assertEquals(Triple("xy", "list", listOf<Any>(1f, 2f)), parsePdMessageLine("xy 1 2"))
        assertNull(parsePdMessageLine("   "))
    }

    @Test fun aMessageLineReachesTheReceiver() {
        if (!PdLibrary.isAvailable) return println("SKIPPED: ${PdLibrary.describe()}")
        PdInstance().use { pd ->
            pd.openPatchText("#X obj 20 20 r v;\n#X obj 20 60 s back;\n#X connect 0 0 1 0;")
            val got = ArrayList<PdInstance.Message>()
            pd.receive("back") { got += it }
            for (line in listOf("v 7", "v set 3 two", "v")) {
                val (r, sel, args) = parsePdMessageLine(line)!!
                sendMessage(pd, r, sel, args)
            }
            pd.flush()
            assertEquals(listOf("float", "set", "bang"), got.map { it.selector })
            assertEquals(listOf<Any>(3f, "two"), got[1].args)
        }
    }

    @Test fun messagesAndCommentsInABoxAreKeptAwayFromPd() {
        val box = "// a comment\n#X obj 20 20 r freq;\nfreq 440\n#X text 20 60 a comment that\nwraps onto a second line;\ngo"
        assertEquals("\n#X obj 20 20 r freq;\n\n#X text 20 60 a comment that\nwraps onto a second line;\n", PdPatchGraph.statementsOnly(box))
    }

    @Test fun aWireIntoARightInletEndsAtTheRightEdge() {
        // [lop~ 1200]'s right inlet is its last port, so, as Pd draws it, the tab sits flush with the
        // box's right edge and a wire into inlet 1 ends there rather than a few pixels from the left.
        val g = PdPatchGraph.parse("#X obj 0 0 r cutoff;\n#X obj 0 40 lop~ 1200;\n#X connect 0 0 1 1;")
        val wire = g.toFLines(labels = false).first()
        val width = "[lop~ 1200]".length * 7.0 + 8.0
        assertEquals(width - 3.5, wire.nodes.last().to.x.toDouble(), 1e-6)
        assertEquals(40.0, wire.nodes.last().to.y.toDouble(), 1e-6)
        assertEquals(3.5, wire.nodes.first().to.x.toDouble(), 1e-6, "[r cutoff]'s only outlet is at its left")
    }

    @Test fun aGuiObjectIsLabelledByItsReceiveNameNotItsArguments() {
        val g = PdPatchGraph.parse(
            "#X obj 20 20 hsl 128 15 40 80 0 0 empty pitch Pitch -2 -8 0 10 #fcfcfc #000000 #000000 0 1;\n" +
                "#X obj 20 60 tgl 15 0 empty gate Gate 17 7 0 10 #fcfcfc #000000 #000000 0 1;\n" +
                "#X obj 20 100 bng 15 250 50 0 empty empty empty 17 7 0 10 #fcfcfc #000000 #000000;")
        assertEquals(listOf("[hsl pitch]", "[tgl gate]", "[bng]"), g.objects.map { it.label })
        assertEquals(1 to 1, g.ports(0))
    }
}
