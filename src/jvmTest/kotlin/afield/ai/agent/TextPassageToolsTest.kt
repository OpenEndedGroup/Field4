package afield.ai.agent

import afield.ai.conversation.Stability
import afield.ai.conversation.focusedPassageContext
import afield.ai.tools.Ai4BackdoorTools
import afield.ai.tools.ToolFocus
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolRegistry
import afield.ai.tools.aiToolMapper
import afield.ai.tools.registerBuiltInAgentTools
import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.document.text.TEXT_RUN_ANCHOR
import afield.document.text.TextRunAnchor
import afield.document.text.textRunSources
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.*

class TextPassageToolsTest {
    @Test
    fun boxChatGetsLivePassageLinksInBothDirectionsAndCanPageOtherChildren() {
        val folder = Files.createTempDirectory("ai4-passage-chat").toFile()
        try {
            val root = Box("root")
            val info = SessionInformation(filename = folder.path, documentName = "passage chat", root = root, ephemeral = true)
            root.inside = info
            val graph = FieldGraphTools(info, InFieldAgentHarnessTest.FakeHost(emptyMap()))
            val tools = graph.textPassages
            val source = tools.create(CreateTextNoteRequest("Draft", "Opening line.\n\nAmber pin.\n\nFinal thought."))
            val amber = tools.create(CreateTextNoteRequest("Amber note", "Check the color.", source.uid, "Amber pin."))
            val final = tools.create(CreateTextNoteRequest("Ending note", "Keep this short.", source.uid, "Final thought."))
            val general = tools.create(CreateTextNoteRequest("General note", "About everything.", source.uid))
            fun context(uid: String, path: String = info.filename) = focusedPassageContext(
                ToolExecutionContext(fieldGraph = graph, focus = ToolFocus(path, info.boxes.getValue(uid), "contents"))
            )

            val child = assertNotNull(context(amber.uid))
            assertEquals(Stability.VOLATILE, child.stability)
            assertContains(child.text, source.uid)
            assertContains(child.text, amber.sourceLink)
            assertContains(child.text, "Amber pin.")
            assertFalse(child.text.contains("Final thought."))
            assertNull(context(general.uid))
            assertNull(context(amber.uid, info.filename + "-other"))

            val parent = assertNotNull(context(source.uid))
            assertContains(parent.text, amber.uid)
            assertContains(parent.text, "Check the color.")
            assertContains(parent.text, final.uid)
            assertContains(parent.text, "Final thought.")
            assertFalse(parent.text.contains(general.uid))
            val firstPage = tools.findLinks(FindTextLinksRequest(box = source.uid, direction = "backlinks", kind = "linked note", limit = 1))
            val next = assertNotNull(firstPage.nextOffset)
            val secondPage = tools.findLinks(FindTextLinksRequest(box = source.uid, direction = "backlinks", kind = "linked note", limit = 1, offset = next))
            assertEquals(setOf(amber.uid, final.uid), (firstPage.links + secondPage.links).map { it.destinationUid }.toSet())
            assertNull(secondPage.nextOffset)

            info.boxes.getValue(source.uid).setPropertyRaw("contents", "Opening line.\n\nAzure pin.\n\nFinal thought.")
            assertContains(assertNotNull(context(amber.uid)).text, "Azure pin.")
            info.boxes.getValue(source.uid).setPropertyRaw("contents", "Opening line.\n\nFinal thought.")
            val lost = assertNotNull(context(amber.uid)).text
            assertContains(lost, "\"passageLost\":true")
            assertContains(lost, "\"lastKnownExcerpt\":\"Azure pin.\"")
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun realRegistriesCreateEditDiscoverAndTranscludeLiveText() {
        val directory = Files.createTempDirectory("ai4-text-passages").toFile()
        try {
            val root = Box("root")
            val info = SessionInformation(filename = directory.path, documentName = "passage tools", root = root, ephemeral = true)
            root.inside = info
            val graph = FieldGraphTools(info, InFieldAgentHarnessTest.FakeHost(emptyMap()))
            val ctx = ToolExecutionContext(fieldGraph = graph)
            val chat = ToolRegistry().also { registerBuiltInAgentTools(it) }
            val backdoor = Ai4BackdoorTools.registry
            fun schemas(registry: ToolRegistry) = registry.describe(ctx).filter {
                it.jsonObject["name"]?.jsonPrimitive?.content in textPassageToolNames
            }
            assertEquals(5, schemas(chat).size)
            assertEquals(schemas(chat), schemas(backdoor))
            assertFalse(chat.isAvailable(ToolExecutionContext(), "CreateTextNoteRequest"))
            fun call(req: Any, registry: ToolRegistry = chat) =
                registry.executeByName(ctx, req.javaClass.simpleName, aiToolMapper.writeValueAsString(req))
            fun passage(req: Any, registry: ToolRegistry = chat) = call(req, registry) as TextPassageToolResult
            val source = passage(CreateTextNoteRequest("Source", "# Bearings\n\nAn amber pin.\n\nAnother amber pin."))
            val target = passage(CreateTextNoteRequest("Reading", "# Reading"))
            val docBox = Box("normal-box").also { box ->
                box.inside = info
                box.name = "Ordinary box"
                box.setPropertyRaw("notes", "A live compass note.")
                info.boxes[box.id] = box
            }
            assertEquals(docBox.id, passage(FindTextNotesRequest("compass")).notes.single().uid)
            assertEquals("notes", passage(FindTextNotesRequest("compass")).notes.single().property)
            assertFailsWith<IllegalArgumentException> {
                passage(CreateTextNoteRequest("Ambiguous", "", source.uid, "amber pin"))
            }
            assertTrue(info.boxes.values.none { it.name == "Ambiguous" })
            val child = passage(CreateTextNoteRequest("Margin", "A comment on the second pin.", source.uid, "amber pin", 2))
            val sourceBox = info.boxes.getValue(source.uid)
            val childBox = info.boxes.getValue(child.uid)
            assertTrue(childBox in sourceBox.children)
            val anchor = TextRunAnchor.decode(childBox.getPropertyRaw(TEXT_RUN_ANCHOR))!!
            assertEquals(sourceBox.getPropertyRaw("contents").toString().lastIndexOf("amber pin"), anchor.start)
            assertEquals(child.passageId, passage(TrackTextPassageRequest(source.uid, "amber pin", 2), backdoor).passageId)
            assertEquals(child.uid, passage(FindTextNotesRequest("second pin")).notes.single().uid)
            val patch = call(PatchBoxTextRequest(child.uid, "contents", find = "comment", replace = "observation")) as FieldGraphResult
            assertTrue(patch.success)
            assertEquals(child.uid, passage(FindTextNotesRequest("observation")).notes.single().uid)
            val inserted = passage(TranscludeTextPassageRequest(target.uid, passageId = child.passageId), backdoor)
            assertEquals(child.sourceLink, inserted.sourceLink)
            val targetBox = info.boxes.getValue(target.uid)
            assertFalse(targetBox in sourceBox.children)
            val targetText = targetBox.getPropertyRaw("contents").toString()
            assertTrue(targetText.contains(child.sourceLink) && !targetText.contains("amber pin"))
            targetBox.setPropertyRaw("contents", targetText + "\n\n`[fake](${child.sourceLink})`")
            val backwards = passage(FindTextLinksRequest(box = source.uid, direction = "backlinks", passageId = child.passageId))
            assertEquals(setOf("linked note", "transclusion"), backwards.links.map { it.kind }.toSet())
            assertEquals(2, backwards.links.size)
            val forward = passage(FindTextLinksRequest(box = target.uid, direction = "sources")).links.single()
            assertEquals(source.uid, forward.sourceUid)
            assertEquals(3, forward.destinationLine)
            assertEquals(child.sourceLink, forward.sourceLink)
            assertEquals(child.uid, passage(FindTextLinksRequest(box = child.uid, direction = "sources")).links.single().destinationUid)
            sourceBox.setPropertyRaw("contents", "# Bearings\n\nAn amber pin.\n\nAnother amber bright pin.")
            assertEquals("amber bright pin", sourceBox.textRunSources().single().excerpt)
            val own = FieldGraphTools(info, InFieldAgentHarnessTest.FakeHost(emptyMap()), replyOwned = setOf(target.uid to "contents"))
            assertFailsWith<IllegalArgumentException> { own.textPassages.transclude(TranscludeTextPassageRequest(target.uid, child.passageId)) }
            assertEquals(targetText + "\n\n`[fake](${child.sourceLink})`", targetBox.getPropertyRaw("contents"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
