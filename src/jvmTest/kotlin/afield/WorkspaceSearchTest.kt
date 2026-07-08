package afield

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceSearchTest {
    @Test
    fun workspaceSearchFindsSidecarMatchesAfterPrefilter() {
        val workspace = Files.createTempDirectory("field-workspace-search").toFile()
        try {
            val doc = File(workspace, "sidecar-doc").apply { mkdirs() }
            File(doc, "box_code_.kt").writeText(
                """
                println("needleFromSidecar")
                """.trimIndent(),
                Charsets.UTF_8
            )
            File(doc, SavingContext.METADATA_EDN).writeText(
                """
                {:version 2
                 :boxes [{:id "box"
                          :properties {:name "Sidecar Box"
                                       :code #afield/file-inline {"path" "box_code_.kt" "inline" ""}
                                       :metadata "tags:\n  - sidecar"}}]}
                """.trimIndent(),
                Charsets.UTF_8
            )

            val result = searchWorkspace(
                base = workspace.absolutePath,
                rawQuery = "needleFromSidecar",
                maxDocuments = 10,
                maxMatchesPerDocument = 5,
                maxScanMillis = 3_000L
            )

            assertEquals(1, result.documents.size)
            val document = result.documents.single()
            assertEquals("sidecar-doc", document.filename)
            assertTrue(document.matches.any { it.boxName == "Sidecar Box" && it.property == "code" && it.line == 1 })
        } finally {
            workspace.deleteRecursively()
        }
    }
}
