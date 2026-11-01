package afield.app.server

import afield.app.protocol.Directory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * `request-templates` replies from off the websocket event loop, and the palette waits for that reply.
 * A listing that throws (an unreadable workspace document, say) must still answer, with an empty list.
 */
class TemplateListingReplyTest {
    @Test
    fun aListingThatThrowsRepliesWithAnEmptyList() {
        val reply = templateListingOrEmpty { throw IllegalStateException("unreadable workspace") }
        assertEquals("list", reply.command)
        assertEquals(emptyList(), reply.value)
        assertNull(reply.metadata)
    }

    @Test
    fun aListingThatSucceedsIsTheReply() {
        val listing = Directory(command = "list", value = listOf("a", "b"))
        assertSame(listing, templateListingOrEmpty { listing })
    }

    private fun temp(): File = Files.createTempDirectory("template-listing").toFile().also { it.deleteOnExit() }

    private fun document(dir: File, name: String): File = File(dir, name).apply {
        mkdirs()
        File(this, "metadata.edn").writeText("{\"version\" 2 \"boxes\" []}")
    }

    /** A workspace, a bundled folder, the checkout's `src/personal/templates` and `~/.afield/templates`, all temporary. */
    private class Layout(val home: File, val app: File, val checkout: File, val personal: File) {
        val roots get() = templateRoots(home.path, app.path, checkout.path, personal.path)
    }

    private fun layout() = Layout(temp(), temp(), temp(), temp())

    private fun Directory.sources(): Map<String, String> =
        (metadata as JsonArray).associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["source"]!!.jsonPrimitive.content }

    @Test
    fun theDocumentBeingEditedIsNotOffered() {
        val l = layout()
        document(l.home, "other")
        val mine = document(l.home, "mine")
        assertEquals(listOf("other"), localTemplateEntries(l.roots, mine.path).map { it.name })
        assertEquals(setOf("other", "mine"), localTemplateEntries(l.roots, null).map { it.name }.toSet())
    }

    @Test
    fun personalTemplatesAreOffered() {
        val l = layout()
        document(l.personal, "my-sketch")
        document(l.checkout, "checkout-sketch")
        val listing = listTemplatesWithMetadata(roots = l.roots, remote = emptyList())
        assertEquals(listOf("checkout-sketch", "my-sketch"), listing.value)
        assertEquals("personal-template", listing.sources()["my-sketch"])
        assertEquals("personal-template", listing.sources()["checkout-sketch"])
        assertEquals(File(l.personal, "my-sketch").path, localTemplatePath("my-sketch", l.roots))
    }

    @Test
    fun oneOrderDecidesLabelListingAndInsert() {
        val l = layout()
        val saved = File(l.home, "templates").apply { mkdirs() }.let { File(it, "greeter.edn").apply { writeText("{}") } }
        document(File(l.app, "templates"), "greeter")
        document(l.personal, "greeter")
        document(l.home, "greeter")
        val listing = listTemplatesWithMetadata(roots = l.roots, remote = listOf("greeter"))
        assertEquals("workspace-template", listing.sources()["greeter"], "the label comes from the first root")
        assertEquals(saved.path, localTemplatePath("greeter", l.roots), "the insert reads the same root")
    }

    @Test
    fun duplicateNamesAreListedOnceAndShadowedOnesAreSuffixed() {
        val l = layout()
        document(File(l.app, "templates"), "first-steps")
        document(l.personal, "first-steps")
        document(l.home, "first-steps")
        val listing = listTemplatesWithMetadata(roots = l.roots, remote = listOf("first-steps", "far-away"))
        assertEquals(listOf("first-steps", "first-steps-personal", "first-steps-workspace", "far-away"), listing.value)
        assertEquals(listing.value.size, listing.value.toSet().size, "no name twice")
        assertEquals("bundled-template", listing.sources()["first-steps"])
        assertEquals("personal-template", listing.sources()["first-steps-personal"])
        assertEquals("remote-template", listing.sources()["far-away"])
        assertEquals(File(l.personal, "first-steps").path, localTemplatePath("first-steps-personal", l.roots))
    }

    @Test
    fun aFolderReachedTwiceIsReadOnce() {
        val l = layout()
        val shared = File(l.home, "templates")
        document(shared, "greeter")
        val roots = templateRoots(l.home.path, l.app.path, l.checkout.path, shared.path)
        assertEquals(listOf("greeter"), localTemplateEntries(roots).map { it.name })
    }
}
