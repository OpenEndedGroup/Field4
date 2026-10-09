package afield

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WorkspaceLastDocumentTest {
    private fun withWorkspace(test: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("field-last-document").toFile()
        try {
            test(File(root, "a").apply { mkdirs() }, File(root, "b").apply { mkdirs() }, File(root, "preferences"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun document(workspace: File, name: String, metadata: String = "metadata.edn"): File =
        File(workspace, name).apply { mkdirs(); File(this, metadata).writeText("{}") }

    @Test
    fun switchingWorkspacesAndRestartingRestoresEachDocumentIndependently() = withWorkspace { a, b, state ->
        document(a, "alpha")
        document(b, "beta", "metadata.json")
        WorkspaceLastDocument(a, state).remember("alpha")
        assertNull(WorkspaceLastDocument(b, state).read())
        assertFalse(File(b, "alpha").exists())
        WorkspaceLastDocument(b, state).remember("beta")
        assertEquals("alpha", WorkspaceLastDocument(a, state).read())
        assertEquals("beta", WorkspaceLastDocument(b, state).read())
    }

    @Test
    fun sameNamedDocumentsDoNotTransferHistoryToAnotherWorkspace() = withWorkspace { a, b, state ->
        document(a, "shared")
        document(b, "shared")
        WorkspaceLastDocument(a, state).remember("shared")
        assertNull(WorkspaceLastDocument(b, state).read())
    }

    @Test
    fun deletedDocumentsAndEmptyDirectoriesAreNotReopenedOrCreated() = withWorkspace { a, _, state ->
        val doc = document(a, "removed")
        val history = WorkspaceLastDocument(a, state)
        history.remember("removed")
        doc.deleteRecursively()
        assertNull(history.read())
        assertFalse(doc.exists())
        doc.mkdirs()
        assertNull(history.read())
    }

    @Test
    fun canonicalWorkspaceAliasesShareHistory() = withWorkspace { a, _, state ->
        document(a, "alpha")
        val alias = File(a.parentFile, "alias")
        Files.createSymbolicLink(alias.toPath(), a.toPath())
        WorkspaceLastDocument(alias, state).remember("alpha")
        assertEquals("alpha", WorkspaceLastDocument(File(a, "."), state).read())
    }

    @Test
    fun routesAndExternalPathsDoNotReplaceTheLastLocalDocument() = withWorkspace { a, _, state ->
        document(a, "alpha")
        val history = WorkspaceLastDocument(a, state)
        history.remember("alpha")
        listOf("/welcome", "welcome.html", "https://example.com/doc", "../elsewhere", "", ".", "..").forEach {
            history.remember(it)
            assertEquals("alpha", history.read(), it)
        }
    }

    @Test
    fun switchingWorkspacesDropsThePreviousExplicitDocument() {
        assertEquals(
            listOf("-cp", "classes", "afield.Server", "--workspace", "/new", "--read-only"),
            argsWithWorkspace(listOf("-cp", "classes", "afield.Server", "--workspace", "/old", "--document", "alpha", "--read-only"), "/new")
        )
        assertEquals(listOf("--workspace", "/new"), argsWithWorkspace(listOf("--document=alpha", "--workspace=/old"), "/new"))
        assertEquals(listOf("--workspace", "/new"), argsWithWorkspace(listOf("--document", "alpha"), "/new"))
    }
}
