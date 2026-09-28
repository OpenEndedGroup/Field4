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
        assertEquals(g.objects.size + g.connections.size, lines.size)
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
}
