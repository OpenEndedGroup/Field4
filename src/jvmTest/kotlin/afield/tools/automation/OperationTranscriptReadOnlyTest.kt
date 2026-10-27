package afield.tools.automation

import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.graph.needsResync
import afield.document.workspace.createWorkspaceProject
import afield.document.workspace.projectsDir
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Document-scoped macro writes refuse read-only sessions; explicit workspace exports still work. */
class OperationTranscriptReadOnlyTest {
    private fun inDocument(readOnly: Boolean, action: (SessionInformation, File, Box) -> Unit) {
        val workspace = Files.createTempDirectory("macro-readonly-").toFile()
        val directory = File(workspace, "document").apply { mkdirs() }
        File(directory, "metadata.edn").writeText("{}\n")
        val root = Box("root")
        val session = SessionInformation(directory.absolutePath, directory.name, root = root, readOnly = readOnly)
        root.inside = session
        val subject = Box("subject").also { it.inside = session; session.boxes[it.id] = it }
        documents[session.filename] = session
        thisSessionInfo.set(session)
        try {
            action(session, directory, subject)
        } finally {
            documents.remove(session.filename, session)
            thisSessionInfo.remove()
            needsResync.removeAll { it.first.isInsideInitialized() && it.first.inside === session }
            workspace.deleteRecursively()
        }
    }

    @Test
    fun macroWritesRefuseReadOnlyDocumentsButAllowExplicitWorkspaceExports() {
        for (readOnly in listOf(false, true)) inDocument(readOnly) { session, directory, _ ->
            val saved = OperationTranscript.writeMacro(session.filename, "example", YAML)
            assertEquals(!readOnly, saved.success, saved.message)
            assertEquals(!readOnly, File(directory, "macros/example.yaml").exists())
            val exported = OperationTranscript.writeMacro(session.filename, "exported", YAML, scope = "workspace")
            assertTrue(exported.success, exported.message)
            assertTrue(File(directory.parentFile, "macros/exported.yaml").isFile)
            createWorkspaceProject(directory.parentFile.path, "shared", listOf(directory.name))
            val projectExport = OperationTranscript.writeMacro(session.filename, "shared", YAML,
                scope = "project", projectName = "shared")
            assertTrue(projectExport.success, projectExport.message)
            assertTrue(File(projectsDir(directory.parentFile.path), "shared/macros/shared.yaml").isFile)
        }
    }

    @Test
    fun checkpointKeepsReadOnlyEditsInMemory() {
        for (readOnly in listOf(false, true)) inDocument(readOnly) { session, directory, subject ->
            OperationTranscript.recordEdit(subject, "code", "before", "after")
            assertEquals(!readOnly, OperationTranscript.checkpoint(session.filename))
            assertEquals(!readOnly, File(directory, "operations.yaml").isFile)
            assertTrue(OperationTranscript.transcriptPage(session.filename).total > 0)
        }
    }

    @Test
    fun recorderAndSelectionSavesHonorReadOnly() {
        for (readOnly in listOf(false, true)) inDocument(readOnly) { session, directory, subject ->
            assertTrue(OperationTranscript.markStart("recording").success)
            OperationTranscript.recordEdit(subject, "code", "before", "after")
            val selected = OperationTranscript.saveTranscriptSelectionAsMacro(session.filename, "selected", listOf(1))
            assertEquals(!readOnly, selected.success, selected.message)
            val recorded = OperationTranscript.markEndAndSave("recorded")
            assertEquals(!readOnly, recorded.success, recorded.message)
            assertEquals(!readOnly, File(directory, "macros/selected.yaml").isFile)
            assertEquals(!readOnly, File(directory, "macros/recorded.yaml").isFile)
            assertEquals(!readOnly, File(directory, "operations.yaml").isFile)
        }
    }

    @Test
    fun renameAndDeleteLeaveReadOnlyMacrosUntouched() {
        for (readOnly in listOf(false, true)) {
            for (delete in listOf(false, true)) inDocument(readOnly) { session, directory, _ ->
                val original = File(directory, "macros/example.yaml").apply { parentFile.mkdirs(); writeText(YAML) }
                assertTrue(OperationTranscript.readMacro(session.filename, "example").success)
                val result = if (delete) OperationTranscript.deleteMacro(session.filename, "example")
                    else OperationTranscript.renameMacro(session.filename, "example", "renamed")
                assertEquals(!readOnly, result.success, result.message)
                assertEquals(readOnly, original.exists())
                if (readOnly) {
                    assertEquals(YAML, original.readText())
                    assertEquals(listOf("example.yaml"), original.parentFile.list()!!.toList())
                } else {
                    assertTrue(if (delete) File(directory, "macros/.deleted").listFiles()!!.size == 1
                        else File(directory, "macros/renamed.yaml").isFile)
                }
            }
        }
    }

    @Test
    fun revealingAnExistingReadOnlyMacroDirectoryStillWorks() {
        for (readOnly in listOf(false, true)) inDocument(readOnly) { session, directory, _ ->
            val result = OperationTranscript.macroDirectory(session.filename)
            assertEquals(!readOnly, result.success, result.message)
            assertEquals(!readOnly, File(directory, "macros").isDirectory)
            File(directory, "macros").mkdirs()
            assertTrue(OperationTranscript.macroDirectory(session.filename).success)
            assertFalse(File(directory, "operations.yaml").exists())
        }
    }

    private companion object {
        const val YAML = "name: example\noperations: []\n"
    }
}
