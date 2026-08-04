package afield

import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real [UserKeybindingsManager] — generation, appending and reading — driven against a scratch
 * file via [UserKeybindingsManager.watchFile] rather than the developer's own `~/.afield`.
 *
 * [UserKeybindingsParseTest] covers the parsing *rules* against a local copy of them; this covers the
 * manager itself, which is where the file-shaped mistakes live: what the generated file says, that a
 * user's own lines survive Field topping the file up, and that Field never leaves it unreadable.
 */
class UserKeybindingsManagerTest {

    private val scratch: File = File.createTempFile("field-keybindings-test", ".yaml").also { it.delete() }

    @AfterTest
    fun restore() {
        scratch.delete()
        UserKeybindingsManager.watchFile(null)
    }

    private fun inventory() = CommandInventory(
        commands = listOf(
            CommandBinding(id = "new-box", label = "New Box", defaults = listOf("n"), scope = KeyScope.canvas, group = "Canvas"),
            CommandBinding(id = "edit-hashtag", label = "Edit hashtag", defaults = listOf("cmd+h"), group = "Canvas"),
            CommandBinding(id = "stop-all", label = "Stop Box", defaults = listOf("alt+down", "alt+shift+down"), scope = KeyScope.editor, group = "Editor"),
            CommandBinding(id = "export-ipynb", label = "Export notebook (.ipynb)", group = "Canvas"),
            CommandBinding(id = "-copy", label = "Duplicate box(es)", group = "Canvas")
        )
    )

    /** The generated file has to be valid YAML that reads back as the very defaults it was made from. */
    @Test
    fun generatesAFileThatParsesBackToItsOwnDefaults() {
        UserKeybindingsManager.watchFile(scratch)
        UserKeybindingsManager.applyInventory(inventory())

        assertTrue(scratch.exists(), "the manager did not create ${scratch.absolutePath}")
        val root = Yaml().load<Any?>(scratch.readText())
        @Suppress("UNCHECKED_CAST")
        val bindings = assertNotNull((root as? Map<Any?, Any?>)?.get("bindings") as? Map<Any?, Any?>)
        assertEquals("n", bindings["new-box"])
        assertEquals("cmd+h", bindings["edit-hashtag"])
        assertEquals(listOf("alt+down", "alt+shift+down"), bindings["stop-all"])
        // Unbound-by-default commands are listed too — that is the point of generating the file.
        assertEquals("", bindings["export-ipynb"])
        // A `-copy:` key would be a YAML trap, so it has to arrive quoted.
        assertEquals("", bindings["-copy"])
        // The scope of a canvas-only or editor-only shortcut is recorded as a comment.
        val text = scratch.readText()
        assertTrue(text.contains("#scope canvas"), "no canvas scope comment in:\n$text")
        assertTrue(text.contains("#scope editor"), "no editor scope comment in:\n$text")
    }

    /** A user's override wins, an unparseable one is reported, and the rest of the file still works. */
    @Test
    fun readsOverridesAndExplainsTheBadOnes() {
        scratch.writeText(
            """
            bindings:
              new-box: cmd+alt+b
              edit-hashtag: ""
              stop-all: cmd+k cmd+s
            """.trimIndent()
        )
        UserKeybindingsManager.watchFile(scratch)

        val resolved = UserKeybindingsManager.resolved
        assertEquals(listOf("cmd+alt+b"), resolved.bindings["new-box"])
        // "" is an explicit unbind, not "use the default".
        assertEquals(emptyList(), resolved.bindings["edit-hashtag"])
        val problem = assertNotNull(resolved.problems.singleOrNull(), "problems were ${resolved.problems}")
        assertTrue(problem.contains("cmd+k cmd+s"), problem)
        // The message has to name the actual reason: this is a two-keystroke chord, not a typo.
        assertTrue(problem.contains("two-keystroke"), problem)
    }

    /** Broken YAML keeps the last good bindings rather than unbinding the whole application. */
    @Test
    fun brokenYamlKeepsTheLastGoodBindings() {
        scratch.writeText("bindings:\n  new-box: cmd+alt+b\n")
        UserKeybindingsManager.watchFile(scratch)
        assertEquals(listOf("cmd+alt+b"), UserKeybindingsManager.resolved.bindings["new-box"])

        scratch.writeText("bindings:\n  new-box: [unclosed\n")
        UserKeybindingsManager.watchFile(scratch)
        val resolved = UserKeybindingsManager.resolved
        assertEquals(listOf("cmd+alt+b"), resolved.bindings["new-box"], "a syntax error wiped the bindings")
        assertTrue(resolved.problems.any { it.contains("not valid YAML") }, "problems were ${resolved.problems}")
    }

    /**
     * Topping the file up must not touch what the user wrote, and must not accumulate a new
     * "Commands Field added..." banner every time it happens — deleting a line to get a default back
     * makes that command "missing" again, so this path runs repeatedly over the life of one file.
     */
    @Test
    fun appendsMissingCommandsOnceAndLeavesUserLinesAlone() {
        UserKeybindingsManager.watchFile(scratch)
        UserKeybindingsManager.applyInventory(inventory())

        // The user rebinds one command and deletes another line entirely.
        val edited = scratch.readLines()
            .filterNot { it.trim().startsWith("edit-hashtag:") }
            .joinToString("\n") { if (it.trim().startsWith("new-box:")) "  new-box: cmd+alt+b" else it }
        scratch.writeText(edited + "\n")
        UserKeybindingsManager.watchFile(scratch)

        UserKeybindingsManager.applyInventory(inventory())
        val first = scratch.readText()
        assertTrue(first.contains("  new-box: cmd+alt+b"), "the user's own line was rewritten:\n$first")
        assertTrue(first.contains("edit-hashtag:"), "the deleted default was not restored:\n$first")

        // A second pass with a command deleted again must reuse the same banner.
        val again = first.lines().filterNot { it.trim().startsWith("export-ipynb:") }.joinToString("\n")
        scratch.writeText(again + "\n")
        UserKeybindingsManager.watchFile(scratch)
        UserKeybindingsManager.applyInventory(inventory())

        val text = scratch.readText()
        val banners = text.lines().count { it.startsWith("# Commands Field added") }
        assertEquals(1, banners, "expected one banner, got $banners in:\n$text")
        assertEquals(listOf("cmd+alt+b"), UserKeybindingsManager.resolved.bindings["new-box"])
    }

    /**
     * A hand-written file is allowed to be a bare mapping with no `bindings:` wrapper, and Field
     * appending indented entries to one would have produced a file that is no longer YAML at all.
     */
    @Test
    fun appendingToABareMappingKeepsItValidYaml() {
        scratch.writeText("new-box: cmd+alt+b\n")
        UserKeybindingsManager.watchFile(scratch)
        UserKeybindingsManager.applyInventory(inventory())

        val text = scratch.readText()
        val root = Yaml().load<Any?>(text)
        @Suppress("UNCHECKED_CAST")
        val map = assertNotNull(root as? Map<Any?, Any?>, "appending broke the YAML:\n$text")
        assertEquals("cmd+alt+b", map["new-box"])
        assertEquals("cmd+h", map["edit-hashtag"], "appended entry missing or nested wrongly:\n$text")
        assertFalse(text.contains("\n  edit-hashtag:"), "entries were indented into nothing:\n$text")
        assertEquals(listOf("cmd+alt+b"), UserKeybindingsManager.resolved.bindings["new-box"])
    }
}
