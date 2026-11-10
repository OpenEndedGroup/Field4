package afield.ai.conversation

import afield.ai.agent.*
import afield.ai.conversation.*
import afield.ai.tools.*
import afield.animation.gesture.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.app.diagnostics.*
import afield.app.editor.*
import afield.app.notifications.*
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.synth.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.graphics.color.*
import afield.graphics.paint.*
import afield.graphics.vulkan.*
import afield.math.functions.*
import afield.media.capture.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.lifecycle.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import afield.ai.conversation.AiBoxConversation
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
            val panel = """{"present":true,"open":false,"property":"fragment","width":1080,"height":820}"""
            box.setPropertyRaw("ai4_chat_panel", panel)
            val continuation = """{"version":1,"checkpoint":"provider-owned-state"}"""
            box.setPropertyRaw("ai4_continuation", continuation, doResync = false)
            assertTrue(session.hasPendingSidecarSave(box.id, AiBoxConversation.PROPERTY))
            assertEquals("", AiBoxConversation.read(other))
            val saving = SavingContext()
            saving.toTransfer(setOf(box), directory.path)
            assertEquals(transcript, directory.resolve("signal_ai4_chat_history_.md").readText())
            val portable = saving.exportEdnPackage(setOf(box), directory.path)
            session.boxes.clear()
            val loaded = saving.loadFromEdnText(portable, directory.path, initIfCreated = false).single()
            assertEquals(transcript, AiBoxConversation.read(loaded))
            assertEquals(panel, loaded.getPropertyRaw("ai4_chat_panel"))
            assertEquals(continuation, loaded.getPropertyRaw("ai4_continuation"))
            needsResync.removeAll { it.first === loaded }
            loaded.resyncAll()
            assertFalse(loaded to "ai4_chat_history" in needsResync, "Load history once through its dedicated request")
            assertTrue(loaded to "ai4_chat_panel" in needsResync)
            // Portable documents also retain the transcript without their sidecar files.
            directory.resolve("signal_ai4_chat_history_.md").delete()
            session.boxes.clear()
            val imported = saving.loadFromEdnText(portable, directory.path, preferInlineFiles = true, initIfCreated = false).single()
            assertEquals(transcript, AiBoxConversation.read(imported))
            assertEquals(panel, imported.getPropertyRaw("ai4_chat_panel"))
            assertEquals(continuation, imported.getPropertyRaw("ai4_continuation"))
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

    @Test fun legacyChatsGainPresentationWithoutReopeningAnExplicitlyRemovedPanel() {
        val box = Box("legacy-chat")
        box.setPropertyRaw(AiBoxConversation.PROPERTY, "<<you>>\nHello\n<<ai>>\nWelcome", doResync = false)
        AiBoxConversation.ensurePanel(box)
        assertTrue(box.getPropertyRaw(AiBoxConversation.PANEL_PROPERTY).toString().contains("\"open\":true"))
        box.setPropertyRaw(AiBoxConversation.PANEL_PROPERTY, """{"present":false}""", doResync = false)
        AiBoxConversation.ensurePanel(box)
        assertEquals("""{"present":false}""", box.getPropertyRaw(AiBoxConversation.PANEL_PROPERTY))
        AiBoxConversation.write(box, AiBoxConversation.read(box) + "\n<<you>>\nContinue")
        assertEquals("""{"present":false}""", box.getPropertyRaw(AiBoxConversation.PANEL_PROPERTY))
        needsResync.removeAll { it.first === box }
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
