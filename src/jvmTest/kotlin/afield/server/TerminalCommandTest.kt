package afield.server

import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `/terminal.html` may start. The page picks a kind and sends text; the argv and directory
 * are built here, so the text is only ever an argument to `claude`, never a command line or a path.
 */
class TerminalCommandTest {
    private val checkout = Path.of("/work/field4")
    private val navigation = Path.of("/work/field4-worktrees/handoffs-navigation-1")
    private val sessions: (String) -> Path? = { if (it == "navigation") navigation else null }

    private fun plan(kind: String, text: String = "", batch: String = "") =
        terminalPlan(TerminalLaunch(kind, text, batch), checkout, "/bin/claude", sessions)

    @Test
    fun aPlainClaudeRunsInTheCheckoutWithTheTextAsItsFirstMessage() {
        assertEquals(TerminalPlan(listOf("/bin/claude"), checkout, "claude"), plan("claude").getOrThrow())
        assertEquals(listOf("/bin/claude", "rm -rf /; hello"), plan("claude", "rm -rf /; hello").getOrThrow().argv)
    }

    @Test
    fun aBatchRunsInItsSessionWorktreeWithTheSentence() {
        val p = plan("handoff-batch", "yes to every merge", "navigation").getOrThrow()
        assertEquals(navigation, p.directory)
        assertEquals("/bin/claude", p.argv[0])
        assertEquals(2, p.argv.size)
        assertTrue(p.argv[1].startsWith("Read HANDOFF_SESSION.md"), p.argv[1])
        assertTrue("> yes to every merge" in p.argv[1])
        assertEquals("navigation: yes to every merge", p.title)
        // No sentence is fine: the session brief alone is a complete instruction.
        assertFalse("The human's direction" in plan("handoff-batch", batch = "navigation").getOrThrow().argv[1])
    }

    @Test
    fun anythingElseIsRefused() {
        assertTrue(plan("bash", "ls").isFailure)
        assertTrue(plan("docs-pass", "everything").isFailure)
        assertTrue(plan("handoff-batch", "go", "captures").isFailure, "a batch with no open session")
        assertTrue(plan("handoff-batch", "go", "../etc").isFailure)
        assertTrue(plan("claude", "a\u001b[2Jb").isFailure)
        assertTrue(plan("claude", "x".repeat(4001)).isFailure)
    }

    @Test
    fun theSessionWorktreeIsReadFromTheFileHandoffSessionWrites() {
        val root = Files.createTempDirectory("terminal-sessions")
        val worktree = Files.createDirectories(root.resolve("wt"))
        Files.createDirectories(root.resolve(".field-backdoor/docs-pass"))
        Files.writeString(root.resolve(".field-backdoor/docs-pass/sessions.json"),
            """[{"batch":"code","worktree":"${root.resolve("gone")}"},{"batch":"navigation","worktree":"$worktree"}]""")
        assertEquals(worktree, handoffSessionWorktree(root, "navigation"))
        assertNull(handoffSessionWorktree(root, "code"), "a worktree that is gone")
        assertNull(handoffSessionWorktree(root, "site"))
        root.toFile().deleteRecursively()
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

    /**
     * pty4j's native helper loads, and a resize reaches the child. The child waits for a line
     * first: the initial size can land after a fast child has already asked (it read `0 0`
     * once), which /terminal.html never sees because it sends a resize as soon as it connects.
     */
    @Test
    fun aPtyChildSeesItsWindowSize() {
        val process = PtyProcessBuilder(arrayOf("/bin/sh", "-c", "read line; stty size"))
            .setEnvironment(terminalEnvironment(System.getenv())).setInitialColumns(80).setInitialRows(24).setConsole(false).start()
        process.winSize = WinSize(100, 30)
        process.outputStream.write("\n".toByteArray()); process.outputStream.flush()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals("30 100", output.lines().map { it.trim() }.last { it.isNotEmpty() })
    }
}
