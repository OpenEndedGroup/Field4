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

    private fun writeDocument(workspace: File, name: String, boxes: String) {
        val doc = File(workspace, name).apply { mkdirs() }
        File(doc, SavingContext.METADATA_EDN).writeText(
            "{:version 2\n :boxes [$boxes]}",
            Charsets.UTF_8
        )
    }

    @Test
    fun welcomeDirReportsBoxCountsAndTags() {
        val workspace = Files.createTempDirectory("field-welcome-dir").toFile()
        try {
            writeDocument(
                workspace, "alpha",
                """{:id "root" :properties {:name "Root" :metadata "tags:\n  - demo"}}
                   {:id "b1" :properties {:name "Box One"}}"""
            )
            writeDocument(
                workspace, "beta",
                """{:id "b1" :properties {:name "Only Box"}}"""
            )

            val docs = welcomeDir(workspace.absolutePath, emptyList())

            assertEquals(setOf("alpha", "beta"), docs.map { it.filename }.toSet())
            val alpha = docs.single { it.filename == "alpha" }
            val beta = docs.single { it.filename == "beta" }
            assertEquals(2, alpha.numBoxes)
            assertEquals(1, beta.numBoxes)
            assertTrue(alpha.tags.contains("demo"), "expected 'demo' tag, got ${alpha.tags}")
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun streamingCandidatesMatchWelcomeDir() {
        val workspace = Files.createTempDirectory("field-welcome-stream").toFile()
        try {
            writeDocument(workspace, "one", """{:id "b" :properties {:name "B"}}""")
            writeDocument(
                workspace, "two",
                """{:id "root" :properties {:name "R" :metadata "tags:\n  - t2"}}
                   {:id "b" :properties {:name "B"}}"""
            )

            // The bulk path and the streaming path (candidates + per-doc summarize) must produce
            // identical entries in identical order, since the client renders the streamed rows.
            val bulk = welcomeDir(workspace.absolutePath, emptyList())
            val streamed = welcomeDirCandidates(workspace.absolutePath, emptyList())
                .mapNotNull { summarizeWelcomeCandidate(it) }

            assertEquals(bulk.map { it.filename }, streamed.map { it.filename })
            assertEquals(bulk.map { it.numBoxes }, streamed.map { it.numBoxes })
            assertEquals(bulk.map { it.tags }, streamed.map { it.tags })

            // A second call (warm summary cache) must still return the same data.
            val cached = welcomeDir(workspace.absolutePath, emptyList())
            assertEquals(bulk.map { it.filename to it.numBoxes }, cached.map { it.filename to it.numBoxes })
        } finally {
            workspace.deleteRecursively()
        }
    }
}
