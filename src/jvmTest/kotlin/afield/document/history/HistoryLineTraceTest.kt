package afield.document.history

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.document.workspace.metadata
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.output.history

import afield.tools.docs.format
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The History Lens lists the *values* a line had, read from the versions that changed it. Those
 * versions never include the one holding the line's first value, so before the fix a line saved as
 * `radius = 40.0`, then `64.0`, then `80.0` showed only `#1` and `#2` in the lens: its first value
 * could be neither seen nor rolled back to from there.
 */
class HistoryLineTraceTest {
    private val dir: File = Files.createTempDirectory("history-line-trace").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun versionEdn(code: String): String {
        val inline = code.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"version" 2 "boxes" [{"id" "sketch" "properties" {"name" "sketch" "parents" #{#afield/box "root"} "code" #afield/file-inline {"path" "sketch_code_.kt" "inline" "$inline"}}} {"id" "root" "properties" {"name" "root" "children" #{#afield/box "sketch"}}}]}"""
    }

    private fun code(radius: String, extra: String = "") = "// ring\nval radius = $radius\n${extra}println(radius)\n"

    private fun writeVersions(vararg codes: String) {
        val backups = File(dir, "backups").apply { mkdirs() }
        codes.dropLast(1).forEachIndexed { i, c ->
            File(backups, "metadata_backup_%05d.edn".format(i)).writeText(versionEdn(c))
        }
        File(dir, "sketch_code_.kt").writeText(codes.last())
        File(dir, "metadata.edn").writeText(versionEdn(codes.last()))
    }

    @Test
    fun lensIncludesTheLinesFirstValue() {
        val current = code("80.0")
        writeVersions(code("40.0"), code("64.0"), current)

        assertEquals(listOf(1, 2), HistoryManager.blameLineHistory(dir.path, "sketch", "code", 2, "val radius = 80.0", current))
        assertEquals(
            listOf(0, 1, 2),
            HistoryManager.selectionTraceVersions(dir.path, "sketch", "code", 2, 2, "", current)
        )
    }

    @Test
    fun aLineInsertedLaterHasNoEarlierValue() {
        val current = code("40.0", "val count = 24\n")
        writeVersions(code("40.0"), code("40.0", "val count = 12\n"), current)

        // `val count` was inserted in #1 (nothing else changed) and edited in #2; #0 never had it,
        // so #0 is not added.
        assertEquals(
            listOf(1, 2),
            HistoryManager.selectionTraceVersions(dir.path, "sketch", "code", 3, 3, "", current)
        )
    }
}
