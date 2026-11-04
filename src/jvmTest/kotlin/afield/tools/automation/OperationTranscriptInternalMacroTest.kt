package afield.tools.automation

import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.graph.needsResync
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Macros marked `internal: true` (the docs runner's capture drives, test fixtures) are left out of
 * macro lists but still run by name. The same macro without the marker is listed: the positive
 * control that the filter, not the fixture, removes it.
 */
class OperationTranscriptInternalMacroTest {
    @Test
    fun internalMacrosAreUnlistedButStillRun() {
        val workspace = Files.createTempDirectory("macro-internal-").toFile()
        val directory = File(workspace, "document").apply { mkdirs() }
        File(directory, "metadata.edn").writeText("{}\n")
        val root = Box("root")
        val session = SessionInformation(directory.absolutePath, directory.name, root = root)
        root.inside = session
        documents[session.filename] = session
        thisSessionInfo.set(session)
        try {
            val path = session.filename
            assertTrue(OperationTranscript.writeMacro(path, "ring_sketch", "name: ring_sketch\noperations:\n  - wait:\n      millis: 1\n").success)
            assertTrue(OperationTranscript.writeMacro(path, "page_ui_drive",
                """{"name":"page_ui_drive","internal":true,"operations":[{"wait":{"millis":1}}]}""").success)
            assertTrue(OperationTranscript.writeMacro(path, "fixture",
                "name: fixture\ninternal: true\noperations:\n  - wait:\n      millis: 1\n").success)
            assertEquals(listOf("ring_sketch"), OperationTranscript.listMacros(path).macros)

            // Positive control: the same two without the marker are listed.
            assertTrue(OperationTranscript.writeMacro(path, "page_ui_drive",
                """{"name":"page_ui_drive","operations":[{"wait":{"millis":1}}]}""").success)
            assertTrue(OperationTranscript.writeMacro(path, "fixture",
                "name: fixture\ninternal: false\noperations:\n  - wait:\n      millis: 1\n").success)
            assertEquals(listOf("fixture", "page_ui_drive", "ring_sketch"), OperationTranscript.listMacros(path).macros)

            // Internal macros still run by name.
            assertTrue(OperationTranscript.writeMacro(path, "fixture",
                "name: fixture\ninternal: true\noperations:\n  - wait:\n      millis: 1\n").success)
            val run = OperationTranscript.runMacro(path, "fixture")
            assertTrue(run.success, run.message)
        } finally {
            documents.remove(session.filename, session)
            thisSessionInfo.remove()
            needsResync.removeAll { it.first.isInsideInitialized() && it.first.inside === session }
            workspace.deleteRecursively()
        }
    }
}
