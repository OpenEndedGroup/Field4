package afield.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What `/docs-pass.html` may ask `tools/docs_notes.py` to do. The page's requests become an argv
 * for a process that commits and moves main, so anything the page would never send is refused
 * before a process starts.
 */
class DocsPassArgumentsTest {

    @Test
    fun theActionsThePageSendsBecomeTheirCommands() {
        assertEquals(listOf("pass", "create"), docsPassArguments(DocsPassAction("create")))
        assertEquals(
            listOf("pass", "revise", "--jobs", "3", "--agent", "codex", "--tag", "voice", "--page", "sound.md"),
            docsPassArguments(DocsPassAction("revise", jobs = 3, agent = "codex", tags = listOf("@Voice"), pages = listOf("sound.md"))),
        )
        assertEquals(listOf("pass", "stop", "--now"), docsPassArguments(DocsPassAction("stop-now")))
        assertEquals(listOf("pass", "rebuild", "--mode", "incremental"), docsPassArguments(DocsPassAction("rebuild", mode = "incremental")))
        assertEquals(listOf("pass", "finish", "--abandon"), docsPassArguments(DocsPassAction("finish", abandon = true)))
        assertEquals(listOf("pass", "discard", "--", "public/docs/sound.md"), docsPassArguments(DocsPassAction("discard", path = "public/docs/sound.md")))
        assertEquals(
            listOf("handoff-close", "--state", "done", "--", "2dde24f2", "merged"),
            docsPassArguments(DocsPassAction("close-handoff", id = "2dde24f2", state = "done", text = " merged ")),
        )
    }

    @Test
    fun aClosingSentenceThatLooksLikeAnOptionStaysASentence() {
        val argv = docsPassArguments(DocsPassAction("close-handoff", id = "2dde24f2", state = "done", text = "--state dropped"))!!
        assertEquals("--", argv[argv.size - 3])
        assertEquals("--state dropped", argv.last())
    }

    @Test
    fun anythingElseIsRefused() {
        val refused = listOf(
            DocsPassAction("push"),
            DocsPassAction("revise", jobs = 0),
            DocsPassAction("revise", jobs = 50),
            DocsPassAction("revise", agent = "sh"),
            DocsPassAction("revise", tags = listOf("--commit")),
            DocsPassAction("revise", pages = listOf("../../etc")),
            DocsPassAction("rebuild", mode = "always; rm -rf /"),
            DocsPassAction("discard", path = "../outside.md"),
            DocsPassAction("discard", path = "/etc/passwd"),
            DocsPassAction("discard", path = "public//docs.md"),
            DocsPassAction("discard", path = ""),
            DocsPassAction("discard", path = "-rf"),
            DocsPassAction("close-handoff", id = "--store=/tmp/x", state = "done", text = "x"),
            DocsPassAction("close-handoff", id = "2dde24f2", state = "done", text = ""),
            DocsPassAction("close-handoff", id = "2dde24f2", state = "merged", text = "x"),
            DocsPassAction("close-handoff", id = "2dde24f2", state = "done", text = "x".repeat(2001)),
        )
        refused.forEach { assertNull(docsPassArguments(it), "should refuse $it") }
    }
}
