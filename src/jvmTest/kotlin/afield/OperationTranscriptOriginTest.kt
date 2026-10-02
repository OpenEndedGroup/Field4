package afield

import java.nio.file.Files
import kotlin.test.*

/**
 * OperationTranscript edits carry who made them, so rewinding an AI turn can undo exactly its edits: a
 * person's edit and an AI turn's edit of the same property are never merged into one entry, a turn
 * boundary stops merging into anything before it, and undo-by-origin leaves everyone else's edits alone.
 */
class OperationTranscriptOriginTest {

    private fun <T> inDocument(block: (SessionInformation, Box) -> T): T {
        val directory = Files.createTempDirectory("transcript-origin").toFile()
        val root = Box("root")
        val session = SessionInformation(directory.path, documentName = "origin-test", root = root)
        root.inside = session
        thisSessionInfo.set(session)
        try {
            val box = Box("subject").also { it.inside = session; session.boxes[it.id] = it }
            box.setPropertyRaw("code", "v0", doResync = false)
            return block(session, box)
        } finally {
            thisSessionInfo.remove()
            needsResync.removeAll { it.first.isInsideInitialized() && it.first.inside === session }
            directory.deleteRecursively()
        }
    }

    private fun edit(box: Box, to: String, origin: String) {
        val from = box.getPropertyRaw("code") as String
        box.setPropertyRaw("code", to, doResync = false)
        OperationTranscript.recordEdit(box, "code", from, to, origin)
    }

    @Test
    fun aPersonsEditAndAnAiTurnsEditOfOnePropertyStayTwoEntries() = inDocument { session, box ->
        edit(box, "v1 by hand", "")
        edit(box, "v2 by the agent", "ai4:t1")
        OperationTranscript.turnBoundary(session.filename)
        assertEquals(1, OperationTranscript.countOrigins(session.filename, setOf("ai4:t1")))
        val undone = OperationTranscript.undoOrigins(session.filename, setOf("ai4:t1"))
        assertTrue(undone.success, undone.message)
        assertEquals("v1 by hand", box.getPropertyRaw("code"), "the agent's edit is undone, the person's kept")
    }

    @Test
    fun aTurnBoundaryStopsMergingIntoTheTurnBefore() = inDocument { session, box ->
        val start = OperationTranscript.turnBoundary(session.filename)
        edit(box, "turn one", "ai4:t1")
        val mid = OperationTranscript.turnBoundary(session.filename)
        edit(box, "turn two", "ai4:t1")
        val end = OperationTranscript.turnBoundary(session.filename)
        assertEquals(1, mid - start)
        assertEquals(1, end - mid, "the second edit is its own entry even with the same origin")
    }

    @Test
    fun undoingASkippedEditDoesNotOverwriteLaterWork() = inDocument { session, box ->
        edit(box, "agent wrote this", "ai4:t2")
        OperationTranscript.turnBoundary(session.filename)
        edit(box, "a person changed it after", "")
        OperationTranscript.turnBoundary(session.filename)
        OperationTranscript.undoOrigins(session.filename, setOf("ai4:t2"))
        assertEquals("a person changed it after", box.getPropertyRaw("code"))
    }

    @Test
    fun nothingRecordedUnderAnOriginIsNothingToUndo() = inDocument { session, _ ->
        assertEquals(0, OperationTranscript.countOrigins(session.filename, setOf("ai4:none")))
        assertTrue(OperationTranscript.undoOrigins(session.filename, setOf("ai4:none")).success)
    }
}
