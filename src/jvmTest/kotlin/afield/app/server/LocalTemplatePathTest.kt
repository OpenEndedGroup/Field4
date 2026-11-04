package afield.app.server

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The insert-template palette lists saved templates, bundled templates and the workspace's own
 * documents ("Workspace file"). Choosing a workspace document used to insert nothing, silently,
 * because insertion only looked in the two template folders.
 */
class LocalTemplatePathTest {
    private fun temp(): File = Files.createTempDirectory("local-template-path").toFile().also { it.deleteOnExit() }

    private fun document(dir: File, name: String) =
        File(dir, name).apply { mkdirs(); File(this, "metadata.edn").writeText("{\"version\" 2 \"boxes\" []}") }

    /** [templateRoots] with both personal folders empty, so the user's own templates never leak in. */
    private fun roots(home: File, app: File) = templateRoots(home.path, app.path, temp().path, temp().path)

    @Test
    fun aWorkspaceDocumentIsInsertable() {
        val home = temp(); val app = temp()
        val doc = document(home, "sketch")
        assertEquals(doc.path, localTemplatePath("sketch", roots(home, app)))
    }

    @Test
    fun aSavedTemplateWinsOverAWorkspaceDocumentOfTheSameName() {
        val home = temp(); val app = temp()
        document(home, "greeter")
        val saved = File(home, "templates").apply { mkdirs() }.let { File(it, "greeter.edn").apply { writeText("{}") } }
        assertEquals(saved.path, localTemplatePath("greeter", roots(home, app)))
    }

    @Test
    fun aBundledTemplateIsFound() {
        val home = temp(); val app = temp()
        val bundled = document(File(app, "templates"), "first-steps")
        assertEquals(bundled.path, localTemplatePath("first-steps", roots(home, app)))
    }

    @Test
    fun anUnknownNameIsNull() {
        assertNull(localTemplatePath("nothing", roots(temp(), temp())))
    }
}
