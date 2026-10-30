package afield.app.server

import afield.app.protocol.Directory
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

    @Test
    fun theDocumentBeingEditedIsNotOffered() {
        val home = "/tmp/ws"
        assertEquals(
            listOf("other"),
            workspaceDocumentsBesides(listOf("other", "mine"), home, "/tmp/ws/mine")
        )
        assertEquals(listOf("other", "mine"), workspaceDocumentsBesides(listOf("other", "mine"), home, null))
    }
}
