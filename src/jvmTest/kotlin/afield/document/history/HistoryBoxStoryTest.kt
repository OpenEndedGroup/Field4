package afield.document.history

import afield.app.protocol.HistoryBoxStoryEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The history panel's **This box** list: one box's code across every saved version, with runs of
 * unchanged versions collapsed and versions without the box marked absent.
 */
class HistoryBoxStoryTest {
    private val dir: File = Files.createTempDirectory("history-box-story").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun esc(code: String) = code.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** A version holding root, an `other` box and, when [code] is non-null, `sketch` with that code. */
    private fun versionEdn(code: String?, other: String): String {
        val sketch = if (code == null) "" else
            """{"id" "sketch" "properties" {"name" "sketch" "parents" #{#afield/box "root"} "code" #afield/file-inline {"path" "sketch_code_.kt" "inline" "${esc(code)}"}}} """
        return """{"version" 2 "boxes" [$sketch{"id" "other" "properties" {"name" "other" "code" "${esc(other)}"}} {"id" "root" "properties" {"name" "root"}}]}"""
    }

    private fun writeVersions(vararg versions: String) {
        val backups = File(dir, "backups").apply { mkdirs() }
        versions.dropLast(1).forEachIndexed { i, v ->
            File(backups, "metadata_backup_%05d.edn".format(i)).writeText(v)
        }
        File(dir, "metadata.edn").writeText(versions.last())
    }

    @Test
    fun unchangedVersionsCollapseAndAbsenceIsAnEntry() {
        val a = "val radius = 40.0\nprintln(radius)"
        val b = "val radius = 64.0\nprintln(radius)"
        writeVersions(
            versionEdn(null, "x"),          // #0 sketch not made yet
            versionEdn(a, "x"),             // #1 created
            versionEdn(a, "y"),             // #2 only `other` changed
            versionEdn(b, "y"),             // #3 sketch edited
            versionEdn(b, "z"),             // #4 current, only `other` changed
        )
        HistoryManager.clearCache(dir.path)

        val story = HistoryManager.boxStory(dir.path, "sketch", "code")
        assertEquals(5, story.versionCount)
        assertEquals(
            listOf(Triple(0, 0, false), Triple(1, 2, true), Triple(3, 4, true)),
            story.entries.map { Triple(it.fromVersion, it.toVersion, it.exists) }
        )
        val edit = story.entries.last()
        assertEquals(1, edit.added)
        assertEquals(1, edit.removed)
        assertEquals("val radius = 64.0", edit.sample)
        assertEquals(2, story.entries[1].added)
        assertEquals(2, story.entries[1].lineCount)
    }

    @Test
    fun markdownAppearsInDocumentHistoryAndInTheInlineLens() {
        fun version(word: String) = """{"version" 2 "boxes" [{"id" "sketch" "properties" {
            "name" "sketch" "box_kind" "text-frame" "contents" "# Frame $word"
            "notes" #afield/file-inline {"path" "sketch_notes_.md" "inline" "# Note $word"}
        }} {"id" "root" "properties" {"name" "root"}}]}"""
        writeVersions(version("first"), version("second"))
        File(dir, "sketch_notes_.md").writeText("# Note second")
        HistoryManager.clearCache(dir.path)

        val versions = HistoryManager.listVersions(dir.path)
        val (before, after) = versions.map { HistoryManager.loadVersionDiffSummary(dir.path, it) }
        val diff = HistoryManager.computeDiffStat(before, after)
        assertEquals(2, diff.totalLinesChanged)
        for ((property, title) in listOf("contents" to "Frame", "notes" to "Note")) {
            val current = "# $title second"
            assertEquals(listOf(0, 1), HistoryManager.selectionTraceVersions(dir.path, "sketch", property, 1, 1, "", current))
            assertEquals("# $title first", HistoryManager.propertyTextAtVersion(dir.path, 0, "sketch", property))
            assertEquals(current, HistoryManager.propertyTextAtVersion(dir.path, 1, "sketch", property))
        }
    }

    /** The panel reads a missing `exists` as false; the first live drive listed every version as absent. */
    @Test
    fun defaultsAreOnTheWire() {
        val json = Json.encodeToString(HistoryBoxStoryEntry(fromVersion = 1, toVersion = 2))
        check("\"exists\":true" in json) { json }
        check("\"added\":0" in json) { json }
    }
}
