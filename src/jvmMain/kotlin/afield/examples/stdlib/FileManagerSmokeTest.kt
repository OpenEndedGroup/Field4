package afield.examples.stdlib

import afield.Box
import afield.HistoryManager
import afield.SessionInformation
import afield.SavingContext
import afield.stdlib.FILE_MANAGER_ACCESS_HISTORY_PROPERTY
import afield.stdlib.FileManager
import afield.stdlib.absolutePath
import afield.stdlib.abslutePath
import afield.stdlib.file
import afield.stdlib.path
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: stdlib.file-manager.smoke
runner: jvm-main
entrypoint: afield.examples.stdlib.FileManagerSmokeTest
tags: [smoke, stdlib, files, persistence]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "FileManager smoke tests passed"
*/
object FileManagerSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "FileManager smoke tests failed" }
    }

    fun runTests(): Boolean {
        return try {
            testDocumentDefaultAndExplicitPaths()
            testProjectLookupAndGlob()
            testNamedDirectoryLookup()
            testSearchListLookup()
            testAccessHistoryPersistsOnRoot()
            testAccessHistorySurfacesAsSyntheticHistoryItem()
            println("FileManager smoke tests passed")
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    private fun testDocumentDefaultAndExplicitPaths() {
        val fixture = fixture("file-manager-document")
        Box.withBoxContextNow(fixture.box) {
            val implicit = "foo.json".file
            val expected = File(fixture.documentDir, "files/foo.json")
            require(sameFilePath(implicit, expected)) {
                "Expected implicit relative file in document files/, got ${implicit.absolutePath}"
            }
            require(implicit.parentFile.isDirectory) { "Expected document files/ directory to be created" }

            val explicit = "%document/foo.json"
            require(sameFilePath(explicit.file, implicit)) { "Expected %document/foo.json to match implicit current directory" }
            require(sameFilePath(explicit.path.toFile(), implicit)) { "Expected .path to return a Path" }
            require(explicit.absolutePath == implicit.absolutePath) { "Expected .absolutePath to return absolute string" }
            @Suppress("DEPRECATION") // the point of the assertion is that the deprecated typo alias still works
            require(explicit.abslutePath == implicit.absolutePath) { "Expected .abslutePath typo alias to work" }
        }
    }

    private fun testProjectLookupAndGlob() {
        val fixture = fixture("file-manager-project")
        val projectFiles = File(fixture.workspaceDir, "_projects/projectA/files")
        val nested = File(projectFiles, "deep/dir/bar.json").also {
            it.parentFile.mkdirs()
            it.writeText("""{"ok":true}""")
        }

        Box.withBoxContextNow(fixture.box) {
            val implicit = "deep/dir/bar.json".file
            require(sameFilePath(implicit, nested)) {
                "Expected implicit lookup to find project file, got ${implicit.absolutePath}, expected ${nested.absolutePath}"
            }

            val glob = "%project/**/bar.json"
            val globFile = glob.file
            require(sameFilePath(globFile, nested)) {
                "Expected project glob to find nested project file, got ${globFile.absolutePath}, expected ${nested.absolutePath}"
            }
        }
    }

    private fun testNamedDirectoryLookup() {
        val fixture = fixture("file-manager-named")
        val namedRoot = Files.createTempDirectory("file-manager-named-root").toFile()
        val namedFile = File(namedRoot, "a/b/bar.json").also {
            it.parentFile.mkdirs()
            it.writeText("""{"named":true}""")
        }
        FileManager.add("fmtest_${fixture.documentDir.name}", namedRoot)

        Box.withBoxContextNow(fixture.box) {
            val direct = "%fmtest_${fixture.documentDir.name}/a/b/bar.json"
            val glob = "%fmtest_${fixture.documentDir.name}/**/bar.json"
            require(sameFilePath(direct.file, namedFile)) { "Expected direct named-root lookup" }
            require(sameFilePath(glob.file, namedFile)) { "Expected named-root glob lookup" }
        }
    }

    private fun testSearchListLookup() {
        val fixture = fixture("file-manager-search")
        val name = "fmsearch_${fixture.documentDir.name}"
        val searchRoot = Files.createTempDirectory("file-manager-search-root").toFile()
        val onlyThere = File(searchRoot, "kit/only-there.json").also {
            it.parentFile.mkdirs()
            it.writeText("""{"search":true}""")
        }
        File(searchRoot, "shadowed.json").writeText("""{"from":"search"}""")
        val documentCopy = File(fixture.documentDir, "files/shadowed.json").also {
            it.parentFile.mkdirs()
            it.writeText("""{"from":"document"}""")
        }

        try {
            Box.withBoxContextNow(fixture.box) {
                FileManager.add(name, searchRoot)
                require(!sameFilePath("kit/only-there.json".file, onlyThere)) {
                    "A root added without search = true must not answer a plain name"
                }

                FileManager.add(name, searchRoot, search = true)
                require(FileManager.searchNames.contains(name)) { "Expected $name on FileManager.searchNames" }
                require(sameFilePath("kit/only-there.json".file, onlyThere)) {
                    "Expected a plain name to fall through to the search list"
                }
                val history = fixture.box.root.getPropertyRaw(FILE_MANAGER_ACCESS_HISTORY_PROPERTY) as List<*>
                val source = (history.last() as Map<*, *>)["source"]
                require(source == "search:$name") { "Expected source search:$name, got $source" }

                require(sameFilePath("**/only-there.json".file, onlyThere)) { "Expected a glob to search the search list" }
                require(sameFilePath("shadowed.json".file, documentCopy)) {
                    "Expected the document's files/ to win over a search directory"
                }
                val missing = "not-anywhere.json".file
                require(sameFilePath(missing, File(fixture.documentDir, "files/not-anywhere.json"))) {
                    "Expected a missing name to resolve into the document's files/, got ${missing.absolutePath}"
                }

                FileManager[name] = searchRoot
                require(!FileManager.searchNames.contains(name)) { "Re-registering with set should leave the search list" }
                require(!sameFilePath("kit/only-there.json".file, onlyThere)) {
                    "Expected the cached search resolution to be dropped once the root left the search list"
                }
            }
        } finally {
            FileManager.remove(name)
        }
        require(!FileManager.searchNames.contains(name)) { "Expected remove to clear the search list entry" }
    }

    private fun testAccessHistoryPersistsOnRoot() {
        val fixture = fixture("file-manager-history")
        val savedContext = SavingContext()

        Box.withBoxContextNow(fixture.box) {
            "history.json".file
            val history = fixture.box.root.getPropertyRaw(FILE_MANAGER_ACCESS_HISTORY_PROPERTY) as? List<*>
                ?: error("Expected root.$FILE_MANAGER_ACCESS_HISTORY_PROPERTY")
            require(history.size == 1) { "Expected one access entry, got ${history.size}" }

            val entry = history.single() as? Map<*, *> ?: error("Expected access entry map")
            require(entry["request"] == "history.json") { "Wrong request in access entry: $entry" }
            require(entry["access"] == "file") { "Wrong access kind in access entry: $entry" }
            require(entry["source"] == "document") { "Wrong source in access entry: $entry" }
            require(entry["box"] == fixture.box.id) { "Wrong box id in access entry: $entry" }

            val pending = fixture.box.inside.hasPendingSidecarSave(fixture.box.root.id, FILE_MANAGER_ACCESS_HISTORY_PROPERTY)
            require(pending) { "Expected FileManager access history to mark document pending for save" }
        }

        val edn = savedContext.exportEdnPackage(setOf(fixture.box.root, fixture.box), fixture.documentDir.absolutePath)
        val rootTransfer = savedContext.readEdnTransfersFromString(edn).first { it.id == "root" }
        val persisted = rootTransfer.properties[FILE_MANAGER_ACCESS_HISTORY_PROPERTY] as? List<*>
            ?: error("Expected exported root.$FILE_MANAGER_ACCESS_HISTORY_PROPERTY")
        require(persisted.size == 1) { "Expected exported access history to contain one entry" }
    }

    private fun testAccessHistorySurfacesAsSyntheticHistoryItem() {
        val fixture = fixture("file-manager-history-panel")
        val savingContext = SavingContext()
        val boxes = setOf(fixture.box.root, fixture.box)
        val backups = File(fixture.documentDir, "backups").also { it.mkdirs() }

        fixture.box.root.setPropertyRaw(FILE_MANAGER_ACCESS_HISTORY_PROPERTY, emptyList<Map<String, Any?>>())
        File(backups, "metadata_backup_00000.edn").writeText(
            savingContext.exportEdnPackage(boxes, fixture.documentDir.absolutePath)
        )

        fixture.box.root.setPropertyRaw(
            FILE_MANAGER_ACCESS_HISTORY_PROPERTY,
            listOf(
                linkedMapOf<String, Any?>(
                    "index" to 1L,
                    "time" to 1234L,
                    "access" to "file",
                    "request" to "history.json",
                    "resolvedPath" to File(fixture.documentDir, "files/history.json").absolutePath,
                    "source" to "document",
                    "exists" to false,
                    "box" to fixture.box.id,
                    "boxName" to "boxA"
                )
            )
        )
        File(fixture.documentDir, SavingContext.METADATA_EDN).writeText(
            savingContext.exportEdnPackage(boxes, fixture.documentDir.absolutePath)
        )

        val diff = HistoryManager.diffVersionAgainstPrevious(fixture.documentDir.absolutePath, 1)
            ?: error("Expected history diff")
        val synthetic = diff.boxes.singleOrNull { it.uid == "root" }
            ?: error("Expected synthetic root history item in diff: ${diff.boxes}")
        require(synthetic.name == "Document file access") { "Wrong synthetic history item name: ${synthetic.name}" }
        require(synthetic.currentFrame == null && synthetic.targetFrame == null) { "Synthetic item should not expose frames" }
        require(synthetic.currentChildren.isEmpty() && synthetic.targetChildren.isEmpty()) {
            "Synthetic item should not expose children"
        }
        require(synthetic.currentParents.isEmpty() && synthetic.targetParents.isEmpty()) {
            "Synthetic item should not expose parents"
        }

        val property = synthetic.properties.singleOrNull { it.property == FILE_MANAGER_ACCESS_HISTORY_PROPERTY }
            ?: error("Expected $FILE_MANAGER_ACCESS_HISTORY_PROPERTY change on synthetic item")
        require(property.kind == "text") { "Expected access history to open as text, got ${property.kind}" }
        require(property.targetText.contains("history.json")) {
            "Expected formatted target text to mention requested file: ${property.targetText}"
        }
        require(property.lineStats.added >= 1) { "Expected added line stats for access history" }
    }

    private fun sameFilePath(a: File, b: File): Boolean = a.canonicalFile == b.canonicalFile

    private data class Fixture(
        val workspaceDir: File,
        val documentDir: File,
        val box: Box
    )

    private fun fixture(prefix: String): Fixture {
        val workspace = Files.createTempDirectory(prefix).toFile()
        val document = File(workspace, "docA").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")

        val projectDir = File(workspace, "_projects/projectA").also { it.mkdirs() }
        File(projectDir, "contents.edn").writeText("{:documents [\"docA\"]}\n")

        val root = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = root
        )
        root.inside = session
        session.boxes[root.id] = root

        val box = Box("boxA")
        box.inside = session
        session.boxes[box.id] = box

        return Fixture(workspace, document, box)
    }
}
