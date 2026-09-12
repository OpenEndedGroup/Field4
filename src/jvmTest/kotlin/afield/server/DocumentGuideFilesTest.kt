package afield.server

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DocumentGuideFilesTest {
    @Test fun catalogueFindsGuidesWithoutGeneratedFilesOrAgentInstructions() {
        val root = createTempDirectory("field-guides").toFile()
        try {
            for (name in listOf("README.md", "Camera.md", "AGENTS.md", "document.md", "uid_notes_.md", ".secret.md"))
                root.resolve(name).writeText("# ${name.substringBefore('.')}\n\nText")
            root.resolve("docs").mkdir()
            root.resolve("docs/Nested.markdown").writeText("# Nested guide")
            root.resolve("docs/another-document").mkdir()
            root.resolve("docs/another-document/metadata.edn").writeText("{}")
            root.resolve("docs/another-document/README.md").writeText("# Another document")
            val entries = DocumentGuideFiles(root).list()
            assertEquals(listOf("README.md", "Camera.md", "docs/Nested.markdown"), entries.map { it.path })
            assertEquals("Nested guide", entries.last().title)
        } finally { root.deleteRecursively() }
    }

    @Test fun readsAndWritesStayInsideTheDocumentIncludingSymlinks() {
        val parent = createTempDirectory("field-guides-boundary").toFile()
        try {
            val root = parent.resolve("document").apply { mkdir() }
            val outside = parent.resolve("private.md").apply { writeText("private") }
            val files = DocumentGuideFiles(root)
            assertFailsWith<IllegalArgumentException> { files.read("../private.md") }
            assertFailsWith<IllegalArgumentException> { files.read(outside.absolutePath) }
            Files.createSymbolicLink(root.resolve("linked.md").toPath(), outside.toPath())
            assertFailsWith<IllegalArgumentException> { files.read("linked.md") }
            assertTrue(files.list().isEmpty())
            assertFailsWith<IllegalArgumentException> { files.save("linked.md", "", "changed", false) }
            assertEquals("private", outside.readText())
        } finally { parent.deleteRecursively() }
    }

    @Test fun savesRequireTheExactDiskRevisionAndRespectReadOnly() {
        val root = createTempDirectory("field-guides-save").toFile()
        try {
            val disk = root.resolve("README.md").apply { writeText("# First") }
            val files = DocumentGuideFiles(root)
            val first = files.read("README.md")
            val saved = files.save(first.path, first.revision, "# Second", false)
            assertEquals("# Second", disk.readText())
            assertNotEquals(first.revision, saved.revision)
            disk.writeText("# An external edit")
            assertFailsWith<GuideConflict> { files.save(first.path, saved.revision, "my stale draft", false) }
            assertEquals("# An external edit", disk.readText())
            val external = files.read(first.path)
            assertFailsWith<IllegalStateException> { files.save(first.path, external.revision, "no", true) }
            assertEquals("# An external edit", disk.readText())
            assertEquals(listOf("README.md"), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun boundsAndFileTypesAreEnforced() {
        val root = createTempDirectory("field-guides-bounds").toFile()
        try {
            val files = DocumentGuideFiles(root)
            root.resolve("metadata.edn").writeText("{}")
            root.resolve("Big.md").writeText("x".repeat(1024 * 1024 + 1))
            assertFailsWith<IllegalArgumentException> { files.read("metadata.edn") }
            assertFailsWith<IllegalArgumentException> { files.read("Big.md") }
            assertFailsWith<IllegalArgumentException> { files.read("missing.md") }
        } finally { root.deleteRecursively() }
    }
}
