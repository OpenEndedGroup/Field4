package afield.ai.retrieval

import java.nio.file.Files
import java.nio.file.Path
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** No ONNX model, checkout corpus scan, or live Field server needed. */
class SemanticDirectoryProxyTest {
    private fun fixture(test: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("semantic-directory-proxy").toRealPath()
        val document = Files.createDirectories(root.resolve("document"))
        val external = Files.createDirectories(root.resolve("external"))
        Files.writeString(document.resolve("metadata.edn"), """
            {"version" 2 "boxes" [{"id" "owner" "properties" {
              "name" "Library" "file_proxy_mode" "absolute" "file_proxy_path" "$external"}}]}
        """.trimIndent())
        val key = "afield.semanticSearch.workspaceRoot"
        val before = System.getProperty(key)
        try {
            System.setProperty(key, root.toString())
            test(root, external)
        } finally {
            if (before == null) System.clearProperty(key) else System.setProperty(key, before)
            root.toFile().deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun capsules(root: Path, warnings: MutableList<String> = mutableListOf()): List<SemanticCapsule> {
        val service = SemanticRetrievalService
        val recordsMethod = service.javaClass.getDeclaredMethod("sidecarRecords", Path::class.java, List::class.java, MutableList::class.java)
            .apply { isAccessible = true }
        val records = recordsMethod.invoke(service, root, listOf(root.resolve("document")), warnings)
        return service.javaClass.getDeclaredMethod("workspaceSidecarCapsules", List::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(service, records, "workspace") as List<SemanticCapsule>
    }

    @Test fun equalContentChildrenHaveUniqueIdsAndBothAreLexicallySearchable() = fixture { root, external ->
        Files.writeString(external.resolve("first.txt"), "quartzneedle identical text")
        Files.createDirectories(external.resolve("nested"))
        Files.writeString(external.resolve("nested/second.txt"), "quartzneedle identical text")
        val all = capsules(root)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val containers = all.filter { it.scale == "container" }
        assertEquals(2, containers.size)
        assertEquals(setOf("proxy:owner/first.txt", "proxy:owner/nested/second.txt"), containers.map { it.path }.toSet())
        assertTrue(all.all { it.boxId == "owner" && it.boxName == "Library" && it.documentName == "document" })
        assertTrue(all.all { "source_path: $external" in it.metadata })
        assertTrue(all.any { "relative_path: nested/second.txt" in it.metadata })
        val convert = SemanticRetrievalService.javaClass.getDeclaredMethod("toLuceneDocument", SemanticCapsule::class.java, FloatArray::class.java, FloatArray::class.java)
            .apply { isAccessible = true }
        ByteBuffersDirectory().use { directory ->
            StandardAnalyzer().use { analyzer ->
                IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                    all.forEach { capsule ->
                        val doc = convert.invoke(SemanticRetrievalService, capsule, null, null) as org.apache.lucene.document.Document
                        writer.updateDocument(Term("id", capsule.id), doc)
                    }
                }
                DirectoryReader.open(directory).use { reader ->
                    val searcher = IndexSearcher(reader)
                    val matches = searcher.search(TermQuery(Term("all", "quartzneedle")), 20)
                    val paths = matches.scoreDocs.map { searcher.storedFields().document(it.doc).get("path") }.toSet()
                    assertEquals(containers.map { it.path }.toSet(), paths)
                }
            }
        }
    }

    @Test fun textFrameMarkdownIsIndexedWithItsBoxAndHeadings() = fixture { root, _ ->
        Files.writeString(root.resolve("document/metadata.edn"), """
            {"version" 2 "boxes" [{"id" "writer" "properties" {
              "name" "Research notes" "box_kind" "text-frame" "contents" "# Quartz observations\n\nquartzneedle is a writing sample."}}]}
        """.trimIndent())
        val all = capsules(root)
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.boxId == "writer" && it.property == "contents" && it.language == "markdown" })
        assertTrue(all.any { "quartzneedle" in it.source })
        assertTrue(all.any { it.symbol.contains("Quartz") || it.title.contains("Quartz") })
    }

    @Test fun exclusionsBinaryInvalidUtf8AndOversizedChildrenAreNotIndexed() = fixture { root, external ->
        Files.writeString(external.resolve("good.txt"), "allowedneedle")
        Files.writeString(external.resolve(".gitignore"), "ignored.txt\n")
        Files.writeString(external.resolve("ignored.txt"), "excludedneedle")
        Files.writeString(external.resolve(".hidden.txt"), "excludedneedle")
        Files.createDirectories(external.resolve("node_modules"))
        Files.writeString(external.resolve("node_modules/library.txt"), "excludedneedle")
        Files.write(external.resolve("binary.txt"), byteArrayOf(97, 0, 98))
        Files.write(external.resolve("controls.txt"), byteArrayOf(97, 1, 98))
        Files.write(external.resolve("invalid.txt"), byteArrayOf(0xc3.toByte(), 0x28))
        Files.write(external.resolve("large.txt"), ByteArray(2 * 1024 * 1024 + 1) { 97 })
        val warnings = mutableListOf<String>()
        val all = capsules(root, warnings)
        assertEquals(setOf("proxy:owner/good.txt"), all.map { it.path }.toSet())
        assertTrue(warnings.any { "skipped" in it })
        assertFalse(all.any { "excludedneedle" in it.source })
    }

    @Test fun depthLimitedScanIsReportedAsPartial() = fixture { root, external ->
        var dir = external
        repeat(33) { dir = Files.createDirectories(dir.resolve("deep")) }
        Files.writeString(dir.resolve("unreachable.txt"), "needle")
        val warnings = mutableListOf<String>()
        capsules(root, warnings)
        assertTrue(warnings.any { "PARTIAL" in it && "depth limit" in it }, warnings.toString())
    }

    @Test fun externalEditAddDeleteInvalidateAfterBoundedRefreshWithoutMetadataSave() = fixture { root, external ->
        val file = external.resolve("first.txt")
        Files.writeString(file, "original")
        val metadata = root.resolve("document/metadata.edn")
        val stamp = Files.getLastModifiedTime(metadata)
        val service = SemanticRetrievalService
        val start = System.nanoTime()
        val initial = service.currentCorpusFingerprint(start)
        Files.writeString(file, "edited and a different size")
        assertEquals(initial, service.currentCorpusFingerprint(start + 1))
        val edited = service.currentCorpusFingerprint(start + 6_000_000_000L)
        assertNotEquals(initial, edited)
        Files.writeString(external.resolve("added.txt"), "new child")
        val added = service.currentCorpusFingerprint(start + 12_000_000_000L)
        assertNotEquals(edited, added)
        Files.delete(file)
        val deleted = service.currentCorpusFingerprint(start + 18_000_000_000L)
        assertNotEquals(added, deleted)
        assertEquals(stamp, Files.getLastModifiedTime(metadata))
    }
}
