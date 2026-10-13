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
import afield.ai.conversation.DocumentChatStore
import java.nio.file.Files
import kotlin.test.*

/** The document chat is saved beside a document that writes to disk, and nowhere for one that does not. */
class DocumentChatStoreTest {
    private fun session(ephemeral: Boolean): Pair<SessionInformation, java.io.File> {
        val directory = Files.createTempDirectory("document-chat").toFile()
        val root = Box("root")
        val info = SessionInformation(directory.path, documentName = "doc-chat", root = root, ephemeral = ephemeral)
        root.inside = info
        return info to directory
    }

    @Test fun turnsAppendInTheBoxChatMarkupAndSeedAThread() {
        val (info, directory) = session(ephemeral = false)
        try {
            DocumentChatStore.appendTurn(info, "What does Table show?", "Squares of 1 to 5.")
            DocumentChatStore.appendTurn(info, "And Total?", "55.")
            val saved = DocumentChatStore.read(info)
            assertEquals("<<you>>\nWhat does Table show?\n<<ai>>\nSquares of 1 to 5.\n<<you>>\nAnd Total?\n<<ai>>\n55.\n", saved)
            assertTrue(directory.resolve(DocumentChatStore.FILE).isFile)
            assertTrue(AiBoxConversation.resumeContext(saved).contains("Squares of 1 to 5."))
        } finally { directory.deleteRecursively() }
    }

    @Test fun aDocumentThatDoesNotWriteToDiskKeepsNothing() {
        val (info, directory) = session(ephemeral = true)
        try {
            DocumentChatStore.appendTurn(info, "hello", "hi")
            assertEquals("", DocumentChatStore.read(info))
            assertFalse(directory.resolve(DocumentChatStore.FILE).exists())
            assertEquals("", DocumentChatStore.read(null))
        } finally { directory.deleteRecursively() }
    }
}
