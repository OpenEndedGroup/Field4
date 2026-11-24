package afield.ai.agent

import afield.ai.tools.Ai4BackdoorTools
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolRegistry
import afield.ai.tools.aiToolMapper
import afield.ai.tools.registerBuiltInAgentTools
import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.document.text.TextReviewEdit
import afield.document.text.proposeTextReview
import afield.document.text.reviewTextSuggestion
import afield.document.text.textReviewSuggestions
import java.nio.file.Files
import kotlin.test.*

class TextReviewToolsTest {
    @Test
    fun reviewDecisionsAndCommentsAreVisibleToBothRegistries() {
        val directory = Files.createTempDirectory("ai4-text-review").toFile()
        try {
            val root = Box("root")
            val info = SessionInformation(filename = directory.path, documentName = "review tools", root = root, ephemeral = true)
            root.inside = info
            val box = Box("frame").also {
                it.inside = info
                it.name = "Draft"
                it.setPropertyRaw("box_kind", "text-frame")
                it.setPropertyRaw("contents", "Amber pin.\n\nBlue pin.\n\nGreen pin.")
                info.boxes[it.id] = it
            }
            val graph = FieldGraphTools(info, InFieldAgentHarnessTest.FakeHost(emptyMap()))
            val context = ToolExecutionContext(fieldGraph = graph)
            val chat = ToolRegistry().also { registerBuiltInAgentTools(it) }
            fun call(request: Any, registry: ToolRegistry = chat) =
                registry.executeByName(context, request.javaClass.simpleName,
                    aiToolMapper.writeValueAsString(request)) as TextReviewToolResult

            val changes = call(SuggestTextEditsRequest(box.id, listOf(
                TextReviewEdit("Amber", "Bright amber"),
                TextReviewEdit("Blue pin.", ""),
                TextReviewEdit("Green pin.", "Green pin, too."),
            ))).suggestions
            assertEquals("Amber pin.\n\nBlue pin.\n\nGreen pin.", box.getPropertyRaw("contents"))
            val (initial, second, third) = changes
            box.reviewTextSuggestion("comment", initial.id, "Keep this term consistent.")
            val first = call(SuggestTextEditsRequest(box.id, listOf(
                TextReviewEdit("Amber", "Bright amber", supersedes = initial.id),
            ))).suggestions.single()
            box.setPropertyRaw("contents", "Heading\n\n" + box.getPropertyRaw("contents"))
            assertEquals(9, box.textReviewSuggestions().single { it.id == first.id }.start)
            box.reviewTextSuggestion("accept", first.id, "")
            assertEquals("Heading\n\nBright amber pin.\n\nBlue pin.\n\nGreen pin.", box.getPropertyRaw("contents"))
            assertFailsWith<IllegalArgumentException> { box.reviewTextSuggestion("accept", first.id, "") }
            box.reviewTextSuggestion("reject", second.id, "")
            box.reviewTextSuggestion("reject-all", "", "")
            val reviewed = call(ListTextReviewRequest(box.id), Ai4BackdoorTools.registry).suggestions.associateBy { it.id }
            assertEquals("accepted", reviewed[first.id]?.status)
            assertEquals("rejected", reviewed[second.id]?.status)
            assertEquals("rejected", reviewed[third.id]?.status)
            assertEquals("superseded", reviewed[initial.id]?.status)
            assertEquals(initial.id, reviewed[first.id]?.supersedes)
            assertEquals(listOf("Keep this term consistent."), reviewed[initial.id]?.comments)
            assertTrue(box.getPropertyRaw("contents").toString().contains("Blue pin.\n\nGreen pin."))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun acceptAllAppliesOneRevisionAndLeavesStaleSuggestionsPending() {
        val frame = Box("bulk")
        frame.setPropertyRaw("box_kind", "text-frame")
        frame.setPropertyRaw("contents", "First.\r\n\r\nMiddle.\r\n\r\nLast.")
        val (last, middle, first) = frame.proposeTextReview(listOf(
            TextReviewEdit("Last.", ""),
            TextReviewEdit("Middle.", "Replace the middle."),
            TextReviewEdit("First.", "The new opening."),
        ))
        frame.setPropertyRaw("contents", "First.\r\n\r\nAuthor edit.\r\n\r\nLast.")

        frame.reviewTextSuggestion("accept-all", id = "", comment = "")

        assertEquals("The new opening.\r\n\r\nAuthor edit.\r\n\r\n", frame.getPropertyRaw("contents"))
        val reviewed = frame.textReviewSuggestions().associateBy { it.id }
        assertEquals("accepted", reviewed.getValue(first.id).status)
        assertEquals("accepted", reviewed.getValue(last.id).status)
        assertEquals("pending", reviewed.getValue(middle.id).status)
        assertNull(reviewed.getValue(middle.id).start)
    }

    @Test
    fun staleAndAmbiguousSuggestionsNeverOverwriteAnEdit() {
        val frame = Box("frame")
        frame.setPropertyRaw("box_kind", "text-frame")
        frame.setPropertyRaw("contents", "one pin\n\ntwo pin")
        assertFailsWith<IllegalArgumentException> {
            frame.proposeTextReview(listOf(TextReviewEdit("one", "first"), TextReviewEdit("pin", "mark")))
        }
        assertTrue(frame.textReviewSuggestions().isEmpty())
        val change = frame.proposeTextReview(listOf(TextReviewEdit("pin", "mark", occurrence = 1))).single()
        assertFailsWith<IllegalArgumentException> {
            frame.proposeTextReview(listOf(TextReviewEdit("one pin", "another object")))
        }
        frame.setPropertyRaw("contents", "one peg\n\ntwo pin")
        assertNull(frame.textReviewSuggestions().single().start)
        assertFailsWith<IllegalArgumentException> { frame.reviewTextSuggestion("accept", change.id, "") }
        assertEquals("one peg\n\ntwo pin", frame.getPropertyRaw("contents"))
    }
}
