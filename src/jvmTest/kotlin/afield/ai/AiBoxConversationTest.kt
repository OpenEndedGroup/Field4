package afield.ai

import afield.*
import afield.ai.core.AiBoxConversation
import java.nio.file.Files
import kotlin.test.*

class AiBoxConversationTest {
    @Test fun transcriptSurvivesSidecarAndPortableDocumentRoundTrips() {
        val directory = Files.createTempDirectory("ai-box-conversation").toFile()
        val root = Box("root")
        val session = SessionInformation(directory.path, documentName = "chat-test", root = root)
        root.inside = session
        thisSessionInfo.set(session)
        try {
            val box = Box("signal").also { it.inside = session; session.boxes[it.id] = it }
            val other = Box("other").also { it.inside = session; session.boxes[it.id] = it }
            val transcript = "<<you>>\nMake three sine waves.\n<<ai>>\n" +
                AiChatModelIdentity("openai", "gpt-6-astra", "gpt-6-astra").marker() +
                "Done.\n<<progress>>\nCompiled\nAll checks passed.\n<<ai>>\n![plot](data:image/png;base64,YWJj)"
            AiBoxConversation.write(box, transcript)
            assertTrue(session.hasPendingSidecarSave(box.id, AiBoxConversation.PROPERTY))
            assertEquals("", AiBoxConversation.read(other))
            val saving = SavingContext()
            saving.toTransfer(setOf(box), directory.path)
            assertEquals(transcript, directory.resolve("signal_ai4_chat_history_.md").readText())
            val portable = saving.exportEdnPackage(setOf(box), directory.path)
            session.boxes.clear()
            val loaded = saving.loadFromEdnText(portable, directory.path, initIfCreated = false).single()
            assertEquals(transcript, AiBoxConversation.read(loaded))
            // Portable documents also retain the transcript without their sidecar files.
            directory.resolve("signal_ai4_chat_history_.md").delete()
            session.boxes.clear()
            val imported = saving.loadFromEdnText(portable, directory.path, preferInlineFiles = true, initIfCreated = false).single()
            assertEquals(transcript, AiBoxConversation.read(imported))
            AiBoxConversation.write(imported, "")
            val cleared = saving.exportEdnPackage(setOf(imported), directory.path)
            session.boxes.clear()
            assertEquals("", AiBoxConversation.read(saving.loadFromEdnText(cleared, directory.path, initIfCreated = false).single()))
        } finally {
            thisSessionInfo.remove()
            needsResync.removeAll { it.first.isInsideInitialized() && it.first.inside === session }
            directory.deleteRecursively()
        }
    }

    @Test fun interruptedRepliesRemainReadableWithoutPretendingAToolIsStillRunning() {
        val box = Box("interrupted")
        AiBoxConversation.write(box, "<<you>>\nContinue.\n<<progress-open>>\nChecking\n<<ai>>\nPartial reply")
        AiBoxConversation.running(box, true)
        AiBoxConversation.recoverInterrupted(box)
        val recovered = AiBoxConversation.read(box)
        assertContains(recovered, "Partial reply")
        assertContains(recovered, "was interrupted")
        assertFalse(recovered.contains("<<progress-open>>"))
        AiBoxConversation.recoverInterrupted(box)
        assertEquals(recovered, AiBoxConversation.read(box))
    }

    @Test fun resumedContextKeepsRecentTurnsAndDoesNotSendImageBytesAsText() {
        val old = "<<you>>\n" + "old ".repeat(1000) + "\n<<ai>>\nEarlier reply.\n"
        val recent = "<<you>>\nKeep the z channel delayed.\n<<ai>>\nDone. ![plot](data:image/png;base64,YWJj)"
        val context = AiBoxConversation.resumeContext(old + recent, limit = 200)
        assertContains(context, "Earlier conversation omitted")
        assertContains(context, "Keep the z channel delayed.")
        assertContains(context, "image retained in saved chat")
        assertFalse(context.contains("base64"))
        assertFalse(context.contains("old old"))
        assertEquals("", AiBoxConversation.resumeContext(""))
    }
}
