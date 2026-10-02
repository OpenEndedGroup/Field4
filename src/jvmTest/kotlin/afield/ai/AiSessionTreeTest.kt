package afield.ai

import afield.*
import afield.ai.core.AgentMemoryState
import afield.ai.core.AiBoxConversation
import afield.ai.core.AiSessionTree
import afield.ai.core.ChecklistUpdateRequest
import afield.ai.core.ContextFragmentInfo
import afield.ai.core.ContextReport
import afield.ai.core.ScratchpadUpdateRequest
import java.nio.file.Files
import kotlin.test.*

/**
 * The session tree beside `ai4_chat_history`: recorded turns render back to the transcript byte for byte,
 * anything else that changed the markdown is kept as its own node, a rewind keeps the abandoned branch and
 * restores the memory of the turn rewound to, and the sidecar is saved by reference with images out of it.
 */
class AiSessionTreeTest {

    private fun turn(user: String, reply: String) = "<<you>>\n$user\n<<ai>>\n$reply"
    private fun meta(memory: AgentMemoryState? = null, instructions: String = "abc") = AiSessionTree.TurnMeta(
        kind = "general", property = "code",
        context = ContextReport("instructions", listOf(ContextFragmentInfo("core.contract", "EPHEMERAL", "STABLE", 10, 3, "deadbeef")),
            0, instructions, tools = listOf("RunBoxRequest", "NewBoxRequest")),
        model = AiChatModelIdentity("openai", "gpt-6-astra", "gpt-6-astra"),
        memory = memory?.snapshot(),
    )

    /** Do what the installer does: the transcript grows by one turn, then the tree records it. */
    private fun say(box: Box, user: String, reply: String, memory: AgentMemoryState? = null, instructions: String = "abc"): String? {
        val before = AiBoxConversation.read(box)
        val after = (if (before.isBlank()) "" else "$before\n") + turn(user, reply)
        AiBoxConversation.write(box, after)
        return AiSessionTree.recordTurn(box, before, after, meta(memory, instructions))
    }

    private fun types(box: Box) = AiSessionTree.load(box).lines.map { it["t"].toString().trim('"') }

    @Test fun recordedTurnsRenderBackToTheTranscriptExactly() {
        val box = Box("tree")
        say(box, "make primes", "Done.")
        say(box, "now squares", "Done too.")
        val session = AiSessionTree.load(box)
        assertEquals(AiBoxConversation.read(box), session.renderRaw(session.head()))
        assertEquals(listOf("header", "checkpoint", "turn", "turn", "head"), types(box), "one checkpoint while capabilities are unchanged")
        assertEquals(listOf("make primes", "now squares"), AiSessionTree.userTurns(session).map { it.text })
        val cp = session.checkpointAt(session.head())!!
        assertTrue(cp.toString().contains("RunBoxRequest") && cp.toString().contains("gpt-6-astra") && cp.toString().contains("fieldBuild"))
    }

    @Test fun aChangedCapabilityWritesANewCheckpoint() {
        val box = Box("tree-cp")
        say(box, "one", "a", instructions = "abc")
        say(box, "two", "b", instructions = "different")
        assertEquals(2, types(box).count { it == "checkpoint" })
    }

    @Test fun markdownThatGrewOrWasReplacedIsKeptAsItsOwnNode() {
        val box = Box("tree-reconcile")
        say(box, "one", "a")
        AiBoxConversation.write(box, AiBoxConversation.read(box) + "\n<<progress>>\n✓ box-exists:X — PASS\n")
        say(box, "two", "b")
        assertTrue("append" in types(box), "an agent task's verdict block, between turns")
        AiBoxConversation.write(box, "<<you>>\nedited by hand\n<<ai>>\nok")
        say(box, "three", "c")
        val session = AiSessionTree.load(box)
        assertEquals(AiBoxConversation.read(box), session.renderRaw(session.head()))
        assertEquals(2, session.tips().size, "the hand edit started a new root; the old branch is kept")
    }

    @Test fun rewindingKeepsTheAbandonedBranchAndRestoresThatTurnsMemory() {
        val box = Box("tree-rewind")
        val memory = AgentMemoryState()
        memory.updateScratchpad(ScratchpadUpdateRequest("replace", "after turn one"))
        say(box, "one", "a", memory)
        val afterOne = AiBoxConversation.read(box)
        memory.updateScratchpad(ScratchpadUpdateRequest("replace", "after turn two"))
        memory.updateChecklist(ChecklistUpdateRequest("add", text = "a step"))
        say(box, "two", "b", memory)
        say(box, "three", "c", memory)

        val rewound = AiSessionTree.rewind(box, ordinal = 1, expectedText = "two")
        assertEquals(afterOne, rewound.markdown)
        assertEquals(afterOne, AiBoxConversation.read(box))
        assertEquals("after turn one", rewound.restoredMemory!!.scratchpad)
        assertEquals(2, rewound.abandoned.size)
        assertFailsWith<IllegalStateException> { AiSessionTree.rewind(box, ordinal = 0, expectedText = "not what the panel showed") }

        // Continue on the new branch; both branches survive and the old one can be switched back to.
        say(box, "two, differently", "b2", memory)
        assertEquals(2, AiSessionTree.load(box).tips().size)
        val restoredMemory = AgentMemoryState().also { m -> AiSessionTree.switchTo(box, 0).restoredMemory?.let(m::restore) }
        assertTrue(AiBoxConversation.read(box).contains("<<you>>\nthree"))
        assertEquals("after turn two", restoredMemory.readScratchpad().text)
        assertEquals(listOf("a step"), restoredMemory.readChecklist().items.map { it.text })
        assertTrue(AiSessionTree.branches(box).contains("**current**"))
    }

    @Test fun rewindingToTheFirstTurnEmptiesTheTranscript() {
        val box = Box("tree-first")
        say(box, "one", "a")
        assertEquals("", AiSessionTree.rewind(box, 0, "one").markdown)
        say(box, "fresh start", "x")
        assertEquals(turn("fresh start", "x"), AiBoxConversation.read(box))
    }

    @Test fun aLegacyTranscriptBecomesAnImportRootAndCanBeRewoundInto() {
        val box = Box("tree-legacy")
        AiBoxConversation.write(box, turn("old one", "a") + "\n" + turn("old two", "b"))
        assertEquals("", box.getPropertyRaw(AiSessionTree.PROPERTY) as? String ?: "", "nothing is written until a turn")
        say(box, "new", "c")
        assertEquals(listOf("header", "import", "checkpoint", "turn", "head"), types(box))
        val rewound = AiSessionTree.rewind(box, ordinal = 1, expectedText = "old two")
        assertEquals(turn("old one", "a") + "\n", rewound.markdown)
    }

    @Test fun imagesLeaveTheTreeAsReferencesAndComeBack() {
        val box = Box("tree-image")
        say(box, "show it", "![plot](data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==)")
        val text = box.getPropertyRaw(AiSessionTree.PROPERTY) as String
        assertFalse(text.contains("iVBORw0KGgo"), "no base64 in the session file")
        assertTrue(text.contains("ai4img:"))
        assertEquals(AiBoxConversation.read(box), afield.ai.core.AiImageBlobs.inline(AiSessionTree.load(box).renderRaw(AiSessionTree.load(box).head()), box))
    }

    @Test fun unknownLinesSurviveAndABrokenLineIsSkipped() {
        val box = Box("tree-tolerant")
        say(box, "one", "a")
        val text = box.getPropertyRaw(AiSessionTree.PROPERTY) as String
        box.setPropertyRaw(AiSessionTree.PROPERTY, text + "{\"t\":\"future-kind\",\"x\":1}\n{this is not json\n", doResync = false)
        say(box, "two", "b")
        val after = box.getPropertyRaw(AiSessionTree.PROPERTY) as String
        assertTrue(after.contains("future-kind"))
        assertFalse(after.contains("this is not json"))
        assertEquals(AiBoxConversation.read(box), AiSessionTree.load(box).renderRaw(AiSessionTree.load(box).head()))
    }

    @Test fun theSessionIsASidecarReferencedFromMetadataWithImagesBesideIt() {
        val directory = Files.createTempDirectory("ai-session-tree").toFile()
        val root = Box("root")
        val session = SessionInformation(directory.path, documentName = "tree-test", root = root)
        root.inside = session
        thisSessionInfo.set(session)
        try {
            val box = Box("signal").also { it.inside = session; session.boxes[it.id] = it }
            say(box, "plot", "![p](data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==)")
            assertTrue(session.hasPendingSidecarSave(box.id, AiSessionTree.PROPERTY))
            val saving = SavingContext()
            saving.toTransfer(setOf(box), directory.path)
            val sidecar = directory.resolve("signal_ai4_session_.jsonl")
            assertTrue(sidecar.isFile && sidecar.readText().contains("\"t\":\"turn\""))
            assertTrue(directory.resolve("ai4_blobs").listFiles()!!.any { it.name.endsWith(".png") })
            val portable = saving.exportEdnPackage(setOf(box), directory.path)
            assertTrue(portable.contains("#afield/file \"signal_ai4_session_.jsonl\"") || portable.contains("signal_ai4_session_.jsonl"))
        } finally {
            thisSessionInfo.remove()
            needsResync.removeAll { it.first.isInsideInitialized() && it.first.inside === session }
            directory.deleteRecursively()
        }
    }

    @Test fun userTextIsTheFirstRequestInADelta() {
        assertEquals("make it\nso", AiSessionTree.userText("\n<<you>>\nmake it\nso\n<<ai>>\nok"))
        assertEquals("", AiSessionTree.userText("\n<<progress>>\nverdict"))
    }
}
