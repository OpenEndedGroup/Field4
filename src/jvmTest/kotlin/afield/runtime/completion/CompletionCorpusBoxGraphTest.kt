package afield.runtime.completion

import afield.document.properties.macro_root_prop
import java.io.File
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The harvest's box graph must give `rootProp<T>(name)` a root to target.
 *
 * `document.md` lists `"root"` as a parent without a record of its own, and the graph's boxes were in no
 * session, so `Box.root` threw `lateinit property inside has not been initialized` while expanding
 * `demo_document/demo_timeline`, and that box was harvested with no preamble.
 */
class CompletionCorpusBoxGraphTest {

    private fun doc(): CompletionCorpus.DocRec {
        val boxes = linkedMapOf(
            "timeline" to CompletionCorpus.BoxRec(
                "timeline", parents = listOf("root"),
                props = mutableMapOf("code" to "rootProp<Box>(execution) = thisBox"),
            ),
        )
        return CompletionCorpus.DocRec("doc", File("."), boxes)
    }

    @Test
    fun aBoxUnderTheUnrecordedRootReachesIt() {
        val boxes = CompletionCorpus.boxGraph(doc())
        val timeline = boxes.getValue("timeline")
        val root = boxes.getValue(CompletionCorpus.ROOT_ID)
        assertSame(root, timeline.root)
        assertTrue(root in timeline.parents, "the root is a parent, so its preamble is gathered upwards")
    }

    @Test
    fun aPreambleReceiverNeedsNoImportAsATypeArgument() {
        val preamble = "var Box.execution by Inheritance<Box>() /**/\nval Once.execution by Onceing<Box>() /**/"
        assertTrue(CompletionCorpus.typesDeclaredWithoutAnImport(preamble).isEmpty())
        val unimported = "var Box.window by Inheritance<Window>() /**/"
        assertTrue("Window" in CompletionCorpus.typesDeclaredWithoutAnImport(unimported), "a real missing import is still reported")
    }

    @Test
    fun rootPropExpandsAgainstTheRoot() {
        val boxes = CompletionCorpus.boxGraph(doc())
        val insert = boxes.getValue("timeline").macro_root_prop(listOf("<Box>", "(execution)"))!!
        assertSame(boxes.getValue(CompletionCorpus.ROOT_ID), insert.target)
    }
}
