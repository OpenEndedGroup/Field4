package afield.ai

import afield.*
import afield.ai.core.AiBoxConversation
import afield.ai.core.DocumentChatStore
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
