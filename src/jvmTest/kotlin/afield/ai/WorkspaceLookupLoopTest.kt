package afield.ai

import java.nio.file.Files
import kotlin.test.*

class WorkspaceLookupLoopTest {
    @Test fun exactExtensionReturnsGetterAndDocumentationWithoutUnrelatedHits() {
        val root = Files.createTempDirectory("symbol-lookup")
        try {
            Files.writeString(root.resolve("Files.kt"), "val String.file: File\n    get() = FileManager.current().file(this)\n\n/** next */\nval String.path: Path\n")
            val definitions = listOf(
                KotlinDefinitionRecord("String.file", "val", "Files.kt", 1, null, "hash", "Resolve a document file."),
                KotlinDefinitionRecord("String.path", "val", "Files.kt", 5, null, "hash"),
                KotlinDefinitionRecord("Unrelated.file", "val", "missing.kt", 1, null, "hash"))
            val index = KotlinSourceIndexDb(root = root.toString(), apiKeyHash = null, updatedAtEpochMs = 0,
                files = emptyList(), definitions = definitions)
            val match = searchWorkspaceSymbols(index, "String.file", 6).matches.single()
            assertEquals("String.file", match.symbol)
            assertContains(match.definitionBody.orEmpty(), "get() = FileManager.current().file(this)")
            assertFalse(match.definitionBody.orEmpty().contains("next"))
            assertEquals("Resolve a document file.", match.kdoc)
            assertTrue(match.relatedGuides.isEmpty() && match.relatedExamples.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun relatedContextExcludesNestedCheckoutsAndIrrelevantGuides() {
        val root = Files.createTempDirectory("related-context")
        try {
            fun file(path: String) { val p = root.resolve(path); Files.createDirectories(p.parent); Files.writeString(p, "fixture") }
            file("dev_docs/FILE_MANAGER.md")
            file("dev_docs/stdlib/TYPOGRAPHY.md")
            file("src/jvmMain/kotlin/afield/examples/stdlib/FileManagerSmokeTest.kt")
            file(".claude/worktrees/old/dev_docs/FILE_MANAGER.md")
            file("build/dev_docs/FILE_MANAGER.md")
            file("public/nested/.git")
            file("public/nested/dev_docs/FILE_MANAGER.md")
            val catalog = RelatedContextCatalog(root)
            val hints = catalog.forPath("src/jvmMain/kotlin/afield/stdlib/FileManager.kt")
            assertEquals(listOf("dev_docs/FILE_MANAGER.md"), hints.guides)
            assertEquals(listOf("src/jvmMain/kotlin/afield/examples/stdlib/FileManagerSmokeTest.kt"), hints.examples)
            assertSame(hints, catalog.forPath("src/jvmMain/kotlin/afield/stdlib/FileManager.kt"))
        } finally { root.toFile().deleteRecursively() }
    }
}
