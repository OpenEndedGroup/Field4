package afield.server

import com.pty4j.PtyProcessBuilder
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What `/terminal.html` may start. The page picks a kind and sends text; the argv is built here,
 * so the text is only ever an argument to `claude`, never a command line.
 */
class TerminalCommandTest {
    private val checkout = Path.of("/work/field4")
    private val docsNotes = listOf("/opt/homebrew/bin/uv", "run", "--no-project", "python", "tools/docs_notes.py", "--store", "/s/notes store.json", "--repo", "/work/field4")

    @Test
    fun aPlainClaudeTakesTheTextAsItsFirstMessage() {
        assertEquals(listOf("claude"), terminalCommand(TerminalLaunch("claude"), checkout, "claude", docsNotes).getOrThrow())
        assertEquals(listOf("claude", "rm -rf /; hello"), terminalCommand(TerminalLaunch("claude", "rm -rf /; hello"), checkout, "claude", docsNotes).getOrThrow())
    }

    @Test
    fun aDocsPassCarriesTheSentenceAndTheExactPassCommand() {
        val argv = terminalCommand(TerminalLaunch("docs-pass", "yes to every voice edit"), checkout, "/bin/claude", docsNotes).getOrThrow()
        assertEquals(2, argv.size)
        assertEquals("/bin/claude", argv[0])
        assertTrue("> yes to every voice edit" in argv[1])
        assertTrue("tools/docs_notes.py --store '/s/notes store.json' --repo /work/field4 pass <subcommand>" in argv[1], argv[1])
    }

    @Test
    fun anythingElseIsRefused() {
        assertTrue(terminalCommand(TerminalLaunch("bash", "ls"), checkout, "claude", docsNotes).isFailure)
        assertTrue(terminalCommand(TerminalLaunch("docs-pass", "  "), checkout, "claude", docsNotes).isFailure)
        assertTrue(terminalCommand(TerminalLaunch("claude", "a\u001b[2Jb"), checkout, "claude", docsNotes).isFailure)
        assertTrue(terminalCommand(TerminalLaunch("claude", "x".repeat(4001)), checkout, "claude", docsNotes).isFailure)
    }

    @Test
    fun theChildEnvironmentIsARealTerminalAndNotANestedAgent() {
        val env = terminalEnvironment(mapOf("PATH" to "/usr/bin", "CLAUDECODE" to "1", "CLAUDE_CODE_ENTRYPOINT" to "cli", "CLAUDE_CODE_CHILD_SESSION" to "1", "TERM" to "dumb"))
        assertFalse("CLAUDECODE" in env)
        assertFalse("CLAUDE_CODE_ENTRYPOINT" in env)
        assertFalse("CLAUDE_CODE_CHILD_SESSION" in env)
        assertEquals("xterm-256color", env["TERM"])
        assertTrue(env["PATH"]!!.startsWith("/usr/bin:"))
        assertTrue("/opt/homebrew/bin" in env["PATH"]!!)
    }

    /** pty4j's native helper loads, and the child sees a terminal of the size asked for. */
    @Test
    fun aPtyChildSeesItsWindowSize() {
        val process = PtyProcessBuilder(arrayOf("/bin/sh", "-c", "stty size"))
            .setEnvironment(terminalEnvironment(System.getenv())).setInitialColumns(100).setInitialRows(30).setConsole(false).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals("30 100", output.trim())
    }
}
