package afield.ai.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WorkspaceSearchTest {
    private class Fixture : AutoCloseable {
        val root: Path = Files.createTempDirectory("field-scoped-search")
        val document: Path = Files.createDirectories(root.resolve("workspace/document"))
        val source: Path = Files.createDirectories(root.resolve("app/src"))
        val roots = WorkspaceSearchRoots(document, document.parent, source)
        fun file(path: String, text: String): Path = root.resolve(path).also {
            Files.createDirectories(it.parent); Files.writeString(it, text)
        }
        fun search(request: WorkspaceSearchRequest = request(), limits: WorkspaceSearchLimits = WorkspaceSearchLimits()) =
            WorkspaceSearch(roots, limits, nanoTime = { 0L }).search(request)
        override fun close() { root.toFile().deleteRecursively() }
    }

    companion object {
        private fun request(scope: WorkspaceSearchScope = WorkspaceSearchScope.DOCUMENT_FILES, query: String = "needle") =
            WorkspaceSearchRequest(scope, query)
    }

    @Test fun eachScopeSelectsExactlyItsCorpus() = Fixture().use { f ->
        f.file("workspace/document/box_code_.kt", "needle saved")
        f.file("workspace/document/backups/snapshot.edn", "needle history")
        f.file("workspace/other/code.kt", "needle sibling")
        f.file("app/src/jvmMain/code.kt", "needle source")
        f.file("app/src/commonMain/shared.kt", "needle shared")
        f.file("app/src/readme.md", "needle non-Kotlin")
        fun paths(scope: WorkspaceSearchScope) = f.search(request(scope)).also { assertTrue(it.complete, it.toString()) }.matches.map { it.path }.toSet()
        assertEquals(setOf("box_code_.kt"), paths(WorkspaceSearchScope.DOCUMENT_FILES))
        assertEquals(setOf("snapshot.edn"), paths(WorkspaceSearchScope.DOCUMENT_HISTORY))
        assertEquals(setOf("document/box_code_.kt", "other/code.kt"), paths(WorkspaceSearchScope.WORKSPACE_FILES))
        assertEquals(setOf("jvmMain/code.kt", "commonMain/shared.kt"), paths(WorkspaceSearchScope.FIELD_KOTLIN_SOURCE))
        assertFalse(f.search(request(WorkspaceSearchScope.FIELD_KOTLIN_SOURCE).copy(extensions = listOf("md"))).success)
    }

    /** The two calls a local model made on 2026-10-22 hunting for the FLine docs, each a wasted round. */
    @Test
    fun commonSpellingsAreAcceptedAndMissesNameTheRoot() = Fixture().use { f ->
        f.file("workspace/document/notes.md", "needle")
        f.file("app/src/jvmMain/code.kt", "needle")
        assertEquals(listOf("notes.md"), f.search(request().copy(extensions = listOf(".md"))).matches.map { it.path })
        assertEquals(listOf("jvmMain/code.kt"),
            f.search(request(WorkspaceSearchScope.FIELD_KOTLIN_SOURCE).copy(pathPrefix = "src/jvmMain")).matches.map { it.path })
        // 2026-10-28: pathPrefix "src" (the root's own name) cost a local model a round.
        assertEquals(listOf("jvmMain/code.kt"),
            f.search(request(WorkspaceSearchScope.FIELD_KOTLIN_SOURCE).copy(pathPrefix = "src")).matches.map { it.path })
        val docs = f.search(request(WorkspaceSearchScope.FIELD_KOTLIN_SOURCE).copy(extensions = listOf(".md")))
        assertFalse(docs.success)
        assertTrue("FIELD_DOCS" in docs.message, docs.message)
        val missing = f.search(request(WorkspaceSearchScope.FIELD_KOTLIN_SOURCE).copy(pathPrefix = "public/docs"))
        assertFalse(missing.success)
        assertTrue("src/" in missing.message && "documentation" in missing.message, missing.message)
    }

    /** 2026-10-28: a local model hunted "Byrne" through every scope while euclid_elements.md sat unindexed in public/docs. */
    @Test
    fun fieldDocsSearchesSavedDocsPagesLiterally() = Fixture().use { f ->
        f.file("app/public/docs/euclid_elements.md", "drawn the way Oliver Byrne printed them")
        f.file("app/public/docs/sub/page.md", "Byrne again")
        f.file("app/public/docs/hooks.py", "Byrne in code")
        val all = f.search(request(WorkspaceSearchScope.FIELD_DOCS, "Byrne"))
        assertTrue(all.complete, all.toString())
        assertEquals(setOf("euclid_elements.md", "sub/page.md"), all.matches.map { it.path }.toSet())
        for (prefix in listOf("sub", "docs/sub", "public/docs/sub"))
            assertEquals(listOf("sub/page.md"), f.search(request(WorkspaceSearchScope.FIELD_DOCS, "Byrne").copy(pathPrefix = prefix)).matches.map { it.path })
        assertFalse(f.search(request(WorkspaceSearchScope.FIELD_DOCS, "Byrne").copy(extensions = listOf("py"))).success)
    }

    @Test fun literalWhitespaceAndLineCoordinatesArePreserved() = Fixture().use { f ->
        f.file("workspace/document/code.kt", " needle \r\nNEEDLE needle\r😀needle\n.*\n  ")
        assertEquals(" needle ", f.search(request(query = " needle ")).matches.single().excerpt)
        assertEquals(3, f.search(request().copy(ignoreCase = true)).matches.size)
        val rows = f.search().matches
        assertEquals(listOf(1, 2, 3), rows.map { it.line })
        assertEquals(listOf(2, 8, 3), rows.map { it.column })
        assertEquals(4, f.search(request(query = ".*")).matches.single().line)
        assertEquals(5, f.search(request(query = "  ")).matches.single().line)
    }

    @Test fun prefixExtensionsAndNestedExclusionsCannotBroadenScope() = Fixture().use { f ->
        f.file("workspace/document/a/b/file.KT", "needle")
        f.file("workspace/document/a/bc/file.kt", "needle")
        f.file("workspace/document/a/b/file.md", "needle")
        for (dir in listOf("build", "node_modules", ".git", ".hidden", "backups", "history_screenshots"))
            f.file("workspace/document/a/b/$dir/file.kt", "needle")
        val result = f.search(request().copy(pathPrefix = "a/b", extensions = listOf("KT")))
        assertTrue(result.complete)
        assertEquals(listOf("a/b/file.KT"), result.matches.map { it.path })
        assertEquals(1, f.search(request().copy(pathPrefix = "a/b/file.KT")).matches.size)
        assertFalse(f.search(request().copy(pathPrefix = "a/b/build")).success)
    }

    @Test fun invalidRequestsAndMissingRootsFailWithoutFallback() = Fixture().use { f ->
        f.file("workspace/document/code.kt", "needle")
        for (prefix in listOf("../", "/tmp", "C:/", "\\\\server\\share", "a/../b", "a\u0000b"))
            assertFalse(f.search(request().copy(pathPrefix = prefix)).success, prefix)
        for (query in listOf("", "x\ny", "x\ry", "x".repeat(257))) assertFalse(f.search(request(query = query)).success)
        for (limit in listOf(0, 501)) assertFalse(f.search(request().copy(maxMatches = limit)).success)
        assertFalse(f.search(request().copy(extensions = listOf("*.kt"))).success)
        assertFalse(f.search(request().copy(pathPrefix = "missing")).success)
        assertFalse(f.search(request(WorkspaceSearchScope.DOCUMENT_HISTORY)).success)
        Files.delete(f.source)
        assertFalse(f.search(request(WorkspaceSearchScope.FIELD_KOTLIN_SOURCE)).success)
    }

    @Test fun symlinksAreNeverFollowedIncludingHistoryAndPrefix() = Fixture().use { f ->
        val outside = f.file("outside.txt", "needle")
        // Explicitly skip on filesystems without symlink support/permission.
        try { Files.createSymbolicLink(f.document.resolve("link.kt"), outside) }
        catch (_: UnsupportedOperationException) { return@use }
        catch (_: java.nio.file.FileSystemException) { return@use }
        Files.createSymbolicLink(f.document.resolve("loop"), f.document)
        Files.createSymbolicLink(f.document.resolve("backups"), f.root)
        val result = f.search()
        assertTrue(result.complete)
        assertTrue(result.matches.isEmpty())
        assertFalse(f.search(request().copy(pathPrefix = "loop/outside.txt")).success)
        assertFalse(f.search(request(WorkspaceSearchScope.DOCUMENT_HISTORY)).success)
    }

    @Test fun matchingLineCapIsGlobalAndConservativeAtExactLimit() = Fixture().use { f ->
        repeat(20) { f.file("workspace/document/file$it.kt", "needle needle\n") }
        val result = f.search(request().copy(maxMatches = 5))
        assertEquals(5, result.matches.size)
        assertEquals(WorkspaceSearchStop.MATCH_LIMIT, result.stopReason)
        assertFalse(result.complete)
        assertEquals(WorkspaceSearchStop.MATCH_LIMIT, f.search(request().copy(pathPrefix = "file0.kt", maxMatches = 1)).stopReason)
    }

    @Test fun oversizedAndBinaryFilesDoNotMasqueradeAsCleanNoMatches() = Fixture().use { f ->
        f.file("workspace/document/large", "x".repeat(65))
        Files.write(f.document.resolve("invalid"), byteArrayOf(0xc3.toByte(), 0x28))
        f.file("workspace/document/binary", "needle\u0000")
        val result = f.search(limits = WorkspaceSearchLimits(fileBytes = 64))
        assertEquals(1, result.skipped.oversized)
        assertEquals(2, result.skipped.nonText)
        assertTrue(result.success)
        assertFalse(result.complete)
        assertTrue(result.matches.isEmpty())
    }

    @Test fun actualReadIsBoundedEvenWhenMetadataUnderstatesSize() = Fixture().use { f ->
        f.file("workspace/document/small", "x")
        var read = 0
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(1_000_000) { 120 }) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
            override fun close() { closed = true; super.close() }
        }
        val result = WorkspaceSearch(f.roots, WorkspaceSearchLimits(fileBytes = 64), nanoTime = { 0 }, openFile = { input }).search(request())
        assertEquals(65, read)
        assertEquals(65L, result.bytesRead)
        assertEquals(1, result.skipped.oversized)
        assertTrue(closed)
    }

    @Test fun aggregateReadBudgetStopsANoMatchCorpus() = Fixture().use { f ->
        repeat(10) { f.file("workspace/document/$it", "x".repeat(40)) }
        val result = f.search(limits = WorkspaceSearchLimits(inputBytes = 100))
        assertEquals(100L, result.bytesRead)
        assertEquals(WorkspaceSearchStop.INPUT_LIMIT, result.stopReason)
        assertFalse(result.complete)
    }

    @Test fun traversalBudgetsStopWideAndDeepTrees() = Fixture().use { f ->
        repeat(20) { f.file("workspace/document/$it", "none") }
        assertEquals(WorkspaceSearchStop.ENTRY_LIMIT, f.search(limits = WorkspaceSearchLimits(entries = 3)).stopReason)
        f.file("workspace/document/a/b/c/d/e", "needle")
        assertEquals(WorkspaceSearchStop.DEPTH_LIMIT, f.search(request().copy(pathPrefix = "a"), WorkspaceSearchLimits(depth = 1)).stopReason)
    }

    @Test fun responseBudgetCountsJsonEscapingAndPaths() = Fixture().use { f ->
        f.file("workspace/document/unicode-😀-file.kt", ("\"\\\t".repeat(170) + "needle\n").repeat(500))
        val result = f.search(request().copy(maxMatches = 500), WorkspaceSearchLimits(responseBytes = 36 * 1024))
        assertEquals(WorkspaceSearchStop.RESPONSE_LIMIT, result.stopReason)
        assertTrue(result.matches.isNotEmpty())
        assertTrue(ObjectMapper().writeValueAsBytes(result).size <= 36 * 1024)
        assertTrue(ObjectMapper().registerKotlinModule().writeValueAsBytes(result).size <= 36 * 1024)
    }

    @Test fun longLineExcerptRetainsMatchAndOriginalCoordinates() = Fixture().use { f ->
        f.file("workspace/document/long.kt", "😀".repeat(1000) + "needle" + "😀".repeat(1000))
        val match = f.search().matches.single()
        assertEquals(2001, match.column)
        assertTrue(match.excerptClipped)
        assertTrue(match.excerpt.length <= 512)
        assertEquals("needle", match.excerpt.substring(match.column - match.excerptStartColumn).take(6))
        assertFalse(Character.isLowSurrogate(match.excerpt.first()))
        assertFalse(Character.isHighSurrogate(match.excerpt.last()))
    }

    @Test fun deadlineAndCancellationCloseStreamsWithoutSleeping() = Fixture().use { f ->
        f.file("workspace/document/data", "x".repeat(20_000))
        var closed = false
        var cancel = false
        val engine = WorkspaceSearch(f.roots, nanoTime = { 0 }, cancelled = { cancel }, openFile = { path ->
            object : java.io.FilterInputStream(Files.newInputStream(path)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { cancel = true }
                override fun close() { closed = true; super.close() }
            }
        })
        assertEquals(WorkspaceSearchStop.CANCELLED, engine.search(request()).stopReason)
        assertTrue(closed)
        var time = 0L
        val expired = WorkspaceSearch(f.roots, WorkspaceSearchLimits(durationNanos = 2), nanoTime = { time++ }).search(request())
        assertEquals(WorkspaceSearchStop.DEADLINE, expired.stopReason)
    }

    @Test fun cancellationDuringLongLineScanRetainsEarlierMatches() = Fixture().use { f ->
        f.file("workspace/document/data", "needle\n" + "x".repeat(20_000))
        var checks = 0
        val result = WorkspaceSearch(f.roots, nanoTime = { 0 }, cancelled = { ++checks > 100 })
            .search(request())
        assertEquals(WorkspaceSearchStop.CANCELLED, result.stopReason)
        assertEquals(1, result.matches.size)
        assertFalse(result.complete)
    }

    @Test fun threadInterruptionIsPreserved() = Fixture().use { f ->
        try {
            Thread.currentThread().interrupt()
            assertEquals(WorkspaceSearchStop.CANCELLED, WorkspaceSearch(f.roots).search(request()).stopReason)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun unreadableAndChangedFilesRetainHonestPartialStatus() = Fixture().use { f ->
        val path = f.file("workspace/document/data", "needle")
        val unreadable = WorkspaceSearch(f.roots, nanoTime = { 0 }, openFile = { throw IOException("test") }).search(request())
        assertTrue(unreadable.success)
        assertEquals(1, unreadable.skipped.unreadable)
        assertFalse(unreadable.complete)
        val top = WorkspaceSearch(f.roots, nanoTime = { 0 }, openFile = { throw IOException("test") })
            .search(request().copy(pathPrefix = "data"))
        assertFalse(top.success)
        val changed = WorkspaceSearch(f.roots, nanoTime = { 0 }, openFile = {
            Files.writeString(path, "needle changed")
            Files.newInputStream(path)
        }).search(request())
        assertEquals(1, changed.skipped.changed)
        assertTrue(changed.matches.isEmpty())
    }

    @Test fun missingNullNumericAndUnknownScopeFailJsonBinding() {
        val mapper = ObjectMapper().registerKotlinModule()
        for (json in listOf("""{"query":"needle"}""", """{"query":"needle","scope":null}""",
            """{"query":"needle","scope":0}""", """{"query":"needle","scope":"ALL"}""")) {
            assertFailsWith<com.fasterxml.jackson.databind.JsonMappingException> { mapper.readValue(json, WorkspaceSearchRequest::class.java) }
        }
        val request = mapper.readValue("""{"query":" needle ","scope":"WORKSPACE_FILES"}""", WorkspaceSearchRequest::class.java)
        assertEquals(" needle ", request.query)
        assertEquals(WorkspaceSearchScope.WORKSPACE_FILES, request.scope)
    }
}
