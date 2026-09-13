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

    /**
     * Every mutator refuses in a read-only session, with a writable control first.
     *
     * The control matters for the same reason it does in `ReadOnlyWriteSurfaceTest`: "no file
     * appeared" passes just as happily against a writer that has stopped working. `patch` and
     * `append` are asserted separately from `save` even though they delegate to it — their own
     * guard is what stops them reading and recomputing contents before the refusal.
     */
    @Test
    fun everyGuideMutatorRefusesInAReadOnlySession() {
        val root = createTempDirectory("field-guides-readonly").toFile()
        try {
            val files = DocumentGuideFiles(root)
            files.create("docs/HANDOFF.md", "# Handoff\n\nfirst\n", false)
            val disk = root.resolve("docs/HANDOFF.md")
            assertEquals("# Handoff\n\nfirst\n", disk.readText())
            val patched = files.patch("docs/HANDOFF.md", files.read("docs/HANDOFF.md").revision, "first", "second", false)
            assertEquals("# Handoff\n\nsecond\n", patched.contents)
            files.append("docs/HANDOFF.md", patched.revision, "third\n", false)
            assertEquals("# Handoff\n\nsecond\nthird\n", disk.readText())

            val current = files.read("docs/HANDOFF.md")
            assertFailsWith<IllegalStateException> { files.create("docs/NEW.md", "# New", true) }
            assertFailsWith<IllegalStateException> { files.save(current.path, current.revision, "no", true) }
            assertFailsWith<IllegalStateException> { files.patch(current.path, current.revision, "second", "no", true) }
            assertFailsWith<IllegalStateException> { files.append(current.path, current.revision, "no", true) }
            assertEquals(current.contents, disk.readText())
            // No half-written temporary left behind by a refused write, either.
            assertEquals(listOf("HANDOFF.md"), root.resolve("docs").listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun addingANoteNeverClobbersAndRespectsReadOnly() {
        val root = createTempDirectory("field-guides-create").toFile()
        try {
            val files = DocumentGuideFiles(root)
            val created = files.create("Note.md", "# Note\n\n", false)
            assertEquals("# Note\n\n", root.resolve("Note.md").readText())
            assertEquals(created.revision, files.read("Note.md").revision)
            // The name is taken: an existing note is offered, never overwritten.
            assertFailsWith<GuideConflict> { files.create("Note.md", "# Something else\n", false) }
            assertEquals("# Note\n\n", root.resolve("Note.md").readText())
            files.create("guides/Deeper.md", "# Deeper\n", false)
            assertEquals(listOf("guides/Deeper.md", "Note.md"), files.list().map { it.path })
            assertFailsWith<IllegalStateException> { files.create("ReadOnly.md", "# No\n", true) }
            assertFailsWith<IllegalArgumentException> { files.create("elsewhere/Note.md", "# No\n", false) }
            assertFailsWith<IllegalArgumentException> { files.create("notes.txt", "no", false) }
            assertFalse(root.resolve("ReadOnly.md").exists())
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
