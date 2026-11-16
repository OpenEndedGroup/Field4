package afield.ai.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Disk-only tests: no server, compiler, retrieval index or external process. */
class DirectoryProxySearchTest {
    private class Fixture : AutoCloseable {
        val root: Path = Files.createTempDirectory("directory-proxy-search")
        val document: Path = Files.createDirectories(root.resolve("workspace/document"))
        val source: Path = Files.createDirectories(root.resolve("app/src"))
        val proxy: Path = Files.createDirectories(root.resolve("external-project"))
        var resolutions = 0
        val roots = WorkspaceSearchRoots(document, document.parent, source, proxyRoot = { token ->
            resolutions++
            when (token) {
                "project-id", "My project" -> WorkspaceSearchProxyRoot("project-id", proxy)
                "ambiguous" -> throw IllegalArgumentException("Ambiguous box name")
                else -> null
            }
        })
        fun file(base: Path, relative: String, text: String = "needle"): Path = base.resolve(relative).also {
            Files.createDirectories(it.parent)
            Files.writeString(it, text)
        }
        fun request() = WorkspaceSearchRequest(WorkspaceSearchScope.FILE_PROXY, "needle", proxyBox = "project-id")
        fun search(request: WorkspaceSearchRequest = request(), limits: WorkspaceSearchLimits = WorkspaceSearchLimits()) =
            WorkspaceSearch(roots, limits, nanoTime = { 0L }).search(request)
        override fun close() { root.toFile().deleteRecursively() }
    }

    @Test fun explicitProxyOnlyAndCanonicalReadableReferences() = Fixture().use { f ->
        f.file(f.document, "saved.kt")
        f.file(f.source, "source.kt")
        f.file(f.proxy, "sub/file.kt", "first\nNEEDLE needle\n")
        val result = f.search(f.request().copy(proxyBox = "My project", ignoreCase = true))
        assertTrue(result.success, result.toString())
        assertTrue(result.complete, result.toString())
        val match = result.matches.single()
        assertEquals("sub/file.kt", match.path)
        assertEquals("proxy:project-id/sub/file.kt", match.attachmentRef)
        assertEquals(2, match.line)
        assertEquals(1, match.column)
        assertEquals(f.proxy.toRealPath().toString(), result.root)
    }

    @Test fun missingInvalidAndAmbiguousBoxesNeverFallBack() = Fixture().use { f ->
        f.file(f.document, "saved.kt")
        f.file(f.proxy, "proxy.kt")
        for (token in listOf("", "unknown", "ambiguous", f.proxy.toString(), "../external-project")) {
            val result = f.search(f.request().copy(proxyBox = token))
            assertFalse(result.success, token)
            assertTrue(result.matches.isEmpty(), token)
            assertEquals(0L, result.bytesRead)
        }
        for (scope in WorkspaceSearchScope.entries.filter { it != WorkspaceSearchScope.FILE_PROXY }) {
            assertFalse(f.search(f.request().copy(scope = scope)).success, scope.name)
        }
    }

    @Test fun prefixAndExtensionFiltersCannotWidenSearch() = Fixture().use { f ->
        f.file(f.proxy, "src/file.kt")
        f.file(f.proxy, "src/file.md")
        f.file(f.proxy, "src-other/file.kt")
        val result = f.search(f.request().copy(pathPrefix = "src", extensions = listOf(".kt")))
        assertTrue(result.complete, result.toString())
        assertEquals(listOf("src/file.kt"), result.matches.map { it.path })
        assertEquals(1, f.search(f.request().copy(pathPrefix = "src/file.kt")).matches.size)
        for (prefix in listOf("../", "/tmp", "C:/", "src/../src-other", "missing", "src\\file.kt")) {
            val invalid = f.search(f.request().copy(pathPrefix = prefix))
            assertFalse(invalid.success, prefix)
            assertTrue(invalid.matches.isEmpty(), prefix)
        }
    }

    @Test fun sharedPolicyHonorsNestedIgnoresHiddenAndGeneratedDirectories() = Fixture().use { f ->
        f.file(f.proxy, ".gitignore", "*.secret\nignored/\n")
        f.file(f.proxy, "src/.gitignore", "local.kt\n")
        for (path in listOf("a.secret", "ignored/a.kt", "src/local.kt", ".hidden/a.kt", "build/a.kt", "target/a.kt", "node_modules/a.kt"))
            f.file(f.proxy, path)
        f.file(f.proxy, "src/visible.kt")
        val result = f.search()
        assertTrue(result.success)
        assertTrue(result.complete, result.toString())
        assertEquals(listOf("src/visible.kt"), result.matches.map { it.path })
        assertTrue(result.skipped.excluded > 0)
        assertFalse(f.search(f.request().copy(pathPrefix = "a.secret")).success)
        assertTrue(f.search(f.request().copy(pathPrefix = "ignored")).matches.isEmpty())
    }

    @Test fun symlinksCannotEscapeThroughFilesOrPrefixes() = Fixture().use { f ->
        val outside = f.file(f.document, "outside.kt")
        try { Files.createSymbolicLink(f.proxy.resolve("link.kt"), outside) }
        catch (_: UnsupportedOperationException) { return@use }
        catch (_: java.nio.file.FileSystemException) { return@use }
        Files.createSymbolicLink(f.proxy.resolve("linked-dir"), f.document)
        assertTrue(f.search().matches.isEmpty())
        for (prefix in listOf("link.kt", "linked-dir", "linked-dir/outside.kt"))
            assertFalse(f.search(f.request().copy(pathPrefix = prefix)).success, prefix)
    }

    @Test fun aggregateBudgetsNeverResetBetweenProxyFiles() = Fixture().use { f ->
        repeat(8) { f.file(f.proxy, "$it.kt", "needle\n".repeat(6)) }
        val matches = f.search(f.request().copy(maxMatches = 7))
        assertEquals(7, matches.matches.size)
        assertEquals(WorkspaceSearchStop.MATCH_LIMIT, matches.stopReason)
        assertFalse(matches.complete)
        val bytes = f.search(limits = WorkspaceSearchLimits(inputBytes = 90))
        assertEquals(90L, bytes.bytesRead)
        assertEquals(WorkspaceSearchStop.INPUT_LIMIT, bytes.stopReason)
        assertFalse(bytes.complete)
        val entries = f.search(limits = WorkspaceSearchLimits(entries = 2))
        assertEquals(2, entries.filesSearched)
        assertEquals(WorkspaceSearchStop.ENTRY_LIMIT, entries.stopReason)
        assertFalse(entries.complete)
    }

    @Test fun incompleteSharedEnumerationIsNeverReportedComplete() = Fixture().use { f ->
        // Unsupported escaped ignore rules fail closed in the shared enumerator, without 2,000 files.
        f.file(f.proxy, ".gitignore", "escaped\\ pattern\n")
        f.file(f.proxy, "candidate.kt")
        val result = f.search()
        assertTrue(result.success, result.toString())
        assertFalse(result.complete)
        assertEquals(WorkspaceSearchStop.ENUMERATION_LIMIT, result.stopReason)
        assertTrue(result.matches.isEmpty())
    }

    @Test fun standardScopeDoesNotResolveProxyOrChangeItsPolicy() = Fixture().use { f ->
        f.file(f.document, "ordinary.kt")
        f.file(f.document, "backups/old.kt")
        f.file(f.proxy, "external.kt")
        val result = f.search(WorkspaceSearchRequest(WorkspaceSearchScope.DOCUMENT_FILES, "needle"))
        assertTrue(result.complete)
        assertEquals(listOf("ordinary.kt"), result.matches.map { it.path })
        assertNull(result.matches.single().attachmentRef)
        assertEquals(0, f.resolutions)
    }
}
