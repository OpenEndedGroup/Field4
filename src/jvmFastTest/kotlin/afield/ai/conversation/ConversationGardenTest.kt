package afield.ai.conversation

import afield.document.graph.Box
import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.graph.needsResync
import afield.document.persistence.SavingContext
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class ConversationGardenTest {
    private fun box() = Box("garden-test")
    private fun source(box: Box): GardenNode {
        AiBoxConversation.write(box, "<<you>>\nMake a sketch.\n<<ai>>\nFirst idea.\n\nSecond idea.")
        return AiConversationGarden.initialize(box).nodes.single()
    }
    private fun anchor(node: GardenNode, index: Int, mode: String = "through") =
        node.passages[index].let { GardenAnchor(node.id, it.id, it.revision, mode = mode) }

    @Test fun wireRepliesCarryTheDefaultsTheJavaScriptViewNeeds() {
        val reply = GardenReply("box", ConversationGarden(nodes = listOf(GardenNode("node", passages = listOf(GardenPassage("p", "r"))))))
        val encoded = Json.parseToJsonElement(Json.encodeToString(reply)).jsonObject
        val node = encoded.getValue("garden").jsonObject.getValue("nodes").jsonArray.single().jsonObject
        assertEquals("queued", node.getValue("status").jsonPrimitive.content)
        assertEquals(0, node.getValue("tags").jsonArray.size)
        assertEquals(0, node.getValue("edits").jsonArray.size)
        assertEquals("ai", node.getValue("passages").jsonArray.single().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(0, Json.parseToJsonElement(Json.encodeToString(GardenReply("box", ConversationGarden()))).jsonObject
            .getValue("garden").jsonObject.getValue("nodes").jsonArray.size)
    }

    @Test fun aParagraphReplyOmitsLaterTextAndSiblingBranches() {
        val b = box(); val root = source(b)
        val (_, first) = AiConversationGarden.enqueue(b, "Explore #shape", anchor(root, 1), "code")
        AiConversationGarden.update(b, first, "<<ai>>\nSibling answer.")
        val (_, second) = AiConversationGarden.enqueue(b, "Explore #colour", anchor(root, 1), "code")
        val context = AiConversationGarden.context(b, second)
        assertContains(context, "Make a sketch.")
        assertContains(context, "First idea.")
        assertFalse(context.contains("Second idea."))
        assertFalse(context.contains("Sibling answer."))
        assertFalse(context.contains("Explore #shape"))
        assertEquals(listOf("colour"), AiConversationGarden.read(b).nodes.last().tags)
    }

    @Test fun wholeResponseAndNestedRepliesPreserveTheirActualContext() {
        val b = box(); val root = source(b)
        val (_, first) = AiConversationGarden.enqueue(b, "Both ideas", anchor(root, 1, "whole"), "code")
        AiConversationGarden.update(b, first, "<<ai>>\nCombined idea.\n\nLater continuation.")
        val response = AiConversationGarden.read(b).nodes.last()
        val (_, second) = AiConversationGarden.enqueue(b, "Expand", anchor(response, 0), "code")
        val context = AiConversationGarden.context(b, second)
        assertContains(context, "Second idea.")
        assertContains(context, "Both ideas")
        assertContains(context, "Combined idea.")
        assertFalse(context.contains("Later continuation."))
    }

    @Test fun aGrowingStreamingParagraphCanBePinnedAfterTheNextChunkArrives() {
        val b = box()
        val (_, run) = AiConversationGarden.enqueue(b, "Start", null, "code")
        AiConversationGarden.update(b, run, "<<ai>>\nAn unfinished")
        val seen = AiConversationGarden.read(b).nodes.single()
        val a = anchor(seen, 0)
        AiConversationGarden.update(b, run, "<<ai>>\nAn unfinished sentence.\n\nThe future.")
        val (_, reply) = AiConversationGarden.enqueue(b, "About that", a, "code", "An unfinished")
        assertContains(AiConversationGarden.context(b, reply), "An unfinished")
        assertFalse(AiConversationGarden.context(b, reply).contains("sentence"))
        AiConversationGarden.update(b, run, "<<ai>>\nEntirely rewritten.")
        assertContains(AiConversationGarden.context(b, reply), "An unfinished")
        assertFalse(AiConversationGarden.context(b, reply).contains("rewritten"))
        assertFailsWith<IllegalArgumentException> { AiConversationGarden.enqueue(b, "Stale", a, "code", "An unfinished") }
    }

    @Test fun forgedOrUnknownAnchorsNeverBecomeSilentRoots() {
        val b = box(); val root = source(b)
        assertFailsWith<IllegalStateException> { AiConversationGarden.enqueue(b, "reply", anchor(root, 1).copy(node = "missing"), "code") }
        assertFailsWith<IllegalArgumentException> { AiConversationGarden.enqueue(b, "reply", anchor(root, 1).copy(revision = "forged"), "code", "First idea.") }
        assertEquals(1, AiConversationGarden.read(b).nodes.size)
    }

    @Test fun fencedCodeWithBlankLinesRemainsOneAddressablePassage() {
        assertEquals(listOf("Opening.", "```kotlin\nval x = 1\n\nprintln(x)\n```", "Closing."),
            AiConversationGarden.paragraphs("Opening.\n\n```kotlin\nval x = 1\n\nprintln(x)\n```\n\nClosing."))
    }

    @Test fun interruptedGardenTurnsRecoverWithoutChangingTheLinearChat() {
        val b = box(); source(b)
        val linear = AiBoxConversation.read(b)
        val (_, run) = AiConversationGarden.enqueue(b, "Start", null, "code")
        AiConversationGarden.status(b, run, "running")
        AiBoxConversation.running(b, true)
        AiConversationGarden.update(b, run, "<<ai>>\nPartial answer.")
        AiBoxConversation.recoverInterrupted(b)
        assertEquals(linear, AiBoxConversation.read(b))
        val recovered = AiConversationGarden.read(b).nodes.last()
        assertEquals("failed", recovered.status)
        assertContains(recovered.error, "Interrupted")
        assertEquals(false, b.getPropertyRaw(AiBoxConversation.RUNNING_PROPERTY))
        assertContains(AiConversationGarden.read(b).texts.getValue(recovered.passages.single().revision), "Partial answer")
    }

    @Test fun realEditsKeepHistoryEvenWhenCurrentCodeChanges() {
        val b = box(); val target = Box("target")
        val (_, run) = AiConversationGarden.enqueue(b, "Change the code", null, "code")
        AiConversationGarden.edit(b, run, target, "code", "val n = 1", "val n = 2")
        target.setPropertyRaw("code", "val n = 3", doResync = false)
        val garden = AiConversationGarden.read(b); val edit = garden.nodes.single().edits.single()
        assertEquals("val n = 1", garden.texts[edit.before])
        assertEquals("val n = 2", garden.texts[edit.after])
        assertEquals("val n = 3", target.getPropertyRaw("code"))
    }

    @Test fun gardenAndFrozenAnchorsSurviveSidecarsAndPortableDocuments() {
        val directory = Files.createTempDirectory("conversation-garden").toFile()
        val root = Box("root")
        val session = SessionInformation(directory.path, documentName = "garden", root = root)
        root.inside = session; thisSessionInfo.set(session)
        try {
            val b = Box("garden").also { it.inside = session; session.boxes[it.id] = it }
            val imported = source(b)
            val (_, reply) = AiConversationGarden.enqueue(b, "Keep #this", anchor(imported, 1), "code")
            val expected = AiConversationGarden.read(b)
            assertTrue(session.hasPendingSidecarSave(b.id, AiConversationGarden.PROPERTY))
            val saving = SavingContext()
            saving.toTransfer(setOf(b), directory.path)
            assertTrue(directory.resolve("garden_ai4_garden_.json").isFile)
            val portable = saving.exportEdnPackage(setOf(b), directory.path)
            directory.resolve("garden_ai4_garden_.json").delete()
            session.boxes.clear()
            val loaded = saving.loadFromEdnText(portable, directory.path, preferInlineFiles = true, initIfCreated = false).single()
            assertEquals(expected, AiConversationGarden.read(loaded))
            assertContains(AiConversationGarden.context(loaded, reply), "First idea.")
            assertFalse(AiConversationGarden.context(loaded, reply).contains("Second idea."))
        } finally {
            thisSessionInfo.remove()
            needsResync.removeAll { it.first.isInsideInitialized() && it.first.inside === session }
            directory.deleteRecursively()
        }
    }
}
