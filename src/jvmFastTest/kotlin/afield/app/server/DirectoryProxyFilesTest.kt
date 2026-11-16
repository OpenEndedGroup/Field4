package afield.app.server

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.*

class DirectoryProxyFilesTest {
    private fun withTree(block: (File) -> Unit) {
        val root = Files.createTempDirectory("directory-proxy-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun put(root: File, path: String, text: String = "hello"): File =
        root.resolve(path).also { it.parentFile.mkdirs(); it.writeText(text) }

    private fun paths(root: File): Set<String> = DirectoryProxyFiles.scan(root).files
        .map { it.relativeTo(root.canonicalFile).invariantSeparatorsPath }.toSet()

    @Test fun nestedSourcesAndMarkdownAreLiveAndResolvable() = withTree { root ->
        put(root, "docs/nested/readme.md")
        put(root, "src/main/kotlin/App.kt")
        assertEquals(setOf("docs/nested/readme.md", "src/main/kotlin/App.kt"), paths(root))
        assertEquals(root.resolve("src/main/kotlin/App.kt").canonicalFile,
            DirectoryProxyFiles.resolve(root, "src/main/kotlin/App.kt"))
        root.resolve("docs/nested/readme.md").delete()
        put(root, "new.md")
        assertEquals(setOf("new.md", "src/main/kotlin/App.kt"), paths(root))
        assertFailsWith<IllegalArgumentException> { DirectoryProxyFiles.resolve(root, "docs/nested/readme.md") }
    }

    @Test fun generatedHiddenAndNestedIgnorePoliciesMatchExplicitReads() = withTree { root ->
        put(root, ".gitignore", "*.log\n/root-only.md\nignored/\n*.tmp\n!keep.tmp\n")
        put(root, "src/.gitignore", "/local.md\ncache/\n")
        val denied = listOf("build/a.kt", "node_modules/a.js", "target/a", "dist/a", "vendor/a",
            "generated/a.kt", ".git/config", ".secret", "src/.private/a", "src/no.log", "root-only.md",
            "ignored/deep.md", "src/local.md", "src/cache/a", "src/no.tmp")
        denied.forEach { put(root, it) }
        val allowed = setOf("src/root-only.md", "src/keep.tmp", "src/code.kt", "nested/local.md")
        allowed.forEach { put(root, it) }
        assertEquals(allowed, paths(root))
        denied.forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) { DirectoryProxyFiles.resolve(root, path) }
        }
        allowed.forEach { DirectoryProxyFiles.resolve(root, it) }
        val scan = DirectoryProxyFiles.scan(root)
        assertTrue(scan.complete)
        assertTrue(scan.skipped > 0)
    }

    @Test fun globRulesSupportDoubleStarClassesAndParentNegation() = withTree { root ->
        put(root, ".gitignore", "docs/**/draft?.md\nfile[0-9].txt\nblocked/\n!blocked/keep.md\n")
        listOf("docs/draft1.md", "docs/a/b/draft2.md", "file3.txt", "blocked/keep.md")
            .forEach { put(root, it) }
        put(root, "docs/final.md")
        assertEquals(setOf("docs/final.md"), paths(root))
    }

    @Test fun unsafePathsAndSymlinkChildrenAreRejected() = withTree { root ->
        withTree { outside ->
            put(outside, "secret.md")
            put(root, "real/file.md")
            Files.createSymbolicLink(root.resolve("linked").toPath(), outside.toPath())
            Files.createSymbolicLink(root.resolve("alias").toPath(), root.resolve("real").toPath())
            Files.createSymbolicLink(root.resolve("real/link.md").toPath(), outside.resolve("secret.md").toPath())
            assertEquals(setOf("real/file.md"), paths(root))
            listOf("../secret.md", "/etc/passwd", "C:\\secret.md", "C:/secret.md", "real/../real/file.md",
                "real//file.md", "linked/secret.md", "alias/file.md", "real/link.md").forEach { path ->
                assertFailsWith<IllegalArgumentException>(path) { DirectoryProxyFiles.resolve(root, path) }
            }
        }
    }

    @Test fun candidateSizeAndAttachmentSizeHaveSeparateBounds() = withTree { root ->
        val attachment = put(root, "attachment.pdf")
        RandomAccessFile(attachment, "rw").use { it.setLength(2L * 1024 * 1024 + 1) }
        assertTrue(paths(root).isEmpty())
        assertEquals(attachment.canonicalFile, DirectoryProxyFiles.resolve(root, "attachment.pdf"))
        RandomAccessFile(attachment, "rw").use { it.setLength(10L * 1024 * 1024 + 1) }
        assertFailsWith<IllegalArgumentException> { DirectoryProxyFiles.resolve(root, "attachment.pdf") }
    }

    @Test fun referencesAndCatalogueReportPartialLimitsAndSkips() = withTree { root ->
        put(root, "one.md"); put(root, "two.md"); put(root, ".hidden")
        assertEquals("proxy:box-123/src/main.kt", DirectoryProxyFiles.reference("box-123", "src/main.kt"))
        assertFailsWith<IllegalArgumentException> { DirectoryProxyFiles.reference("box", "../bad") }
        val limits = DirectoryProxyFiles.Limits(files = 1)
        val scan = DirectoryProxyFiles.scan(root, limits)
        assertFalse(scan.complete)
        assertEquals(1, scan.files.size)
        assertEquals("file limit (1)", scan.stopReason)
        val catalogue = DirectoryProxyFiles.catalogue(root, "box-123", limits)
        assertContains(catalogue, "PARTIAL: file limit (1)")
        assertContains(catalogue, "skipped")
        assertContains(catalogue, "proxy:box-123/")
        assertContains(catalogue, "<=2 MiB")
        assertContains(DirectoryProxyFiles.catalogue(root, "box-123"), "complete")
    }

    @Test fun entryDepthAndTimeBudgetsAreExplicit() = withTree { root ->
        put(root, "a/b/c.md")
        val entries = DirectoryProxyFiles.scan(root, DirectoryProxyFiles.Limits(entries = 0))
        assertFalse(entries.complete)
        assertEquals("entry limit (0)", entries.stopReason)
        val depth = DirectoryProxyFiles.scan(root, DirectoryProxyFiles.Limits(depth = 1))
        assertFalse(depth.complete)
        assertEquals("depth limit (1)", depth.stopReason)
        val time = DirectoryProxyFiles.scan(root, DirectoryProxyFiles.Limits(millis = 0))
        assertFalse(time.complete)
        assertEquals("time limit (0 ms)", time.stopReason)
    }

    @Test fun narrowedScanHasItsOwnBudgetAndPreservesOriginalRootPaths() = withTree { root ->
        repeat(5) { put(root, "large/other-$it.md") }
        put(root, "selected/nested/wanted.md")
        val limits = DirectoryProxyFiles.Limits(files = 1)
        assertFalse(DirectoryProxyFiles.scan(root, limits).complete)
        val narrowed = DirectoryProxyFiles.scan(root, "selected", limits)
        assertTrue(narrowed.complete)
        assertNull(narrowed.stopReason)
        assertEquals(listOf("selected/nested/wanted.md"), narrowed.files.map {
            it.relativeTo(root.canonicalFile).invariantSeparatorsPath
        })
        assertEquals("proxy:box/selected/nested/wanted.md", DirectoryProxyFiles.reference("box",
            narrowed.files.single().relativeTo(root.canonicalFile).invariantSeparatorsPath))
        assertEquals(narrowed.files, DirectoryProxyFiles.scan(root, "selected/").files)
        assertEquals(narrowed.files, DirectoryProxyFiles.scan(root, "selected/nested/wanted.md", limits).files)
        assertEquals(paths(root), DirectoryProxyFiles.scan(root, "").files.map {
            it.relativeTo(root.canonicalFile).invariantSeparatorsPath
        }.toSet())
    }

    @Test fun prefixChecksAllAncestorPoliciesBeforeTraversing() = withTree { root ->
        put(root, ".gitignore", "blocked/\n")
        val excluded = listOf("blocked", "build", ".hidden", "src/vendor")
        excluded.forEach { prefix ->
            // A bad descendant ignore file must never be opened: reject the prefix first.
            put(root, "$prefix/deep/.gitignore", "\\\\#unsupported\n")
            put(root, "$prefix/deep/a.md")
            for (request in listOf(prefix, "$prefix/deep", "$prefix/deep/a.md")) {
                val failure = assertFailsWith<IllegalArgumentException>(request) {
                    DirectoryProxyFiles.scan(root, request)
                }
                assertContains(failure.message.orEmpty(), "excluded directory proxy child")
            }
        }
        Files.createSymbolicLink(root.resolve("alias").toPath(), root.resolve("src").toPath())
        assertFailsWith<IllegalArgumentException> { DirectoryProxyFiles.scan(root, "alias") }
        assertFailsWith<IllegalArgumentException> { DirectoryProxyFiles.scan(root, "alias/vendor/deep") }
        listOf("../src", "/", "C:\\src", "src/../src").forEach { prefix ->
            assertFailsWith<IllegalArgumentException>(prefix) { DirectoryProxyFiles.scan(root, prefix) }
        }
    }

    @Test fun prefixInheritsRootAndNestedIgnoreRulesAndRemainsLive() = withTree { root ->
        put(root, ".gitignore", "*.log\n*.tmp\n")
        put(root, "src/.gitignore", "!keep.tmp\n/private/\n")
        put(root, "src/deep/.gitignore", "/local.md\n")
        put(root, "src/deep/yes.md")
        put(root, "src/deep/keep.tmp")
        put(root, "src/deep/no.log")
        put(root, "src/deep/local.md")
        put(root, "src/private/no.md")
        val narrowed = DirectoryProxyFiles.scan(root, "src/deep")
        assertTrue(narrowed.complete)
        assertEquals(setOf("yes.md", "keep.tmp"), narrowed.files.map { it.name }.toSet())
        assertEquals(DirectoryProxyFiles.resolve(root, "src/deep/keep.tmp"),
            DirectoryProxyFiles.scan(root, "src/deep/keep.tmp").files.single())
        listOf("src/deep/no.log", "src/deep/local.md", "src/private").forEach { prefix ->
            assertFailsWith<IllegalArgumentException>(prefix) { DirectoryProxyFiles.scan(root, prefix) }
        }
        put(root, "src/.gitignore", "deep/\n")
        assertFailsWith<IllegalArgumentException> { DirectoryProxyFiles.scan(root, "src/deep") }
    }

    @Test fun singleFilePrefixStillAppliesSizeAndBudgets() = withTree { root ->
        val file = put(root, "a.md")
        assertFalse(DirectoryProxyFiles.scan(root, "a.md", DirectoryProxyFiles.Limits(files = 0)).complete)
        assertFalse(DirectoryProxyFiles.scan(root, "a.md", DirectoryProxyFiles.Limits(entries = 0)).complete)
        assertFalse(DirectoryProxyFiles.scan(root, "a.md", DirectoryProxyFiles.Limits(millis = 0)).complete)
        RandomAccessFile(file, "rw").use { it.setLength(2L * 1024 * 1024 + 1) }
        val oversized = DirectoryProxyFiles.scan(root, "a.md")
        assertTrue(oversized.complete)
        assertTrue(oversized.files.isEmpty())
        assertEquals(1, oversized.skipped)
    }

    @Test fun ignoreChangesAreLiveAndUnsupportedRulesFailClosed() = withTree { root ->
        put(root, "a.md")
        assertEquals(setOf("a.md"), paths(root))
        put(root, ".gitignore", "*.md\n")
        assertTrue(paths(root).isEmpty())
        assertFailsWith<IllegalArgumentException> { DirectoryProxyFiles.resolve(root, "a.md") }
        put(root, ".gitignore", "\\#escaped\n")
        val scan = DirectoryProxyFiles.scan(root)
        assertFalse(scan.complete)
        assertTrue(scan.files.isEmpty())
        assertContains(scan.stopReason.orEmpty(), ".gitignore")
        assertFailsWith<IllegalArgumentException> { DirectoryProxyFiles.resolve(root, "a.md") }
    }
}
