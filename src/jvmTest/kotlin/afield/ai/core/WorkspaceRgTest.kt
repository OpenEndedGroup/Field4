package afield.ai.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceRgTest {
    @Test
    fun enforcesGlobalResultLimitAcrossFiles() {
        val workspace = Files.createTempDirectory("field-workspace-rg").toFile()
        try {
            val document = File(workspace, "document").apply { mkdirs() }
            repeat(20) { index ->
                File(document, "match-$index.kt").writeText("val needle$index = \"needle\"\n", Charsets.UTF_8)
            }

            val result = runWorkspaceRg(
                documentPath = document.absolutePath,
                fieldRoot = workspace,
                workspaceRoot = workspace,
                request = WorkspaceRgRequest(query = "needle", maxMatches = 5)
            )

            assertTrue(result.success, result.message)
            assertTrue(result.truncated, "matches across files should hit the global result limit")
            assertEquals(5, result.matchLines)
            assertEquals(5, result.output.lineSequence().count())
        } finally {
            workspace.deleteRecursively()
        }
    }
}
