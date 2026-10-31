package afield.document.persistence

import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Inserting a template that is a whole document (the palette's "Workspace file" entries, and bundled
 * template folders saved with their root) must not write that document's root over the receiving one.
 * Through [SavingContext.cloneInto] the inserted root moved the receiving root's frame, replaced its
 * children and added a nameless copy of the template's metaroot.
 */
class CloneTemplateIntoTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        thisSessionInfo.remove()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun session(): SessionInformation {
        val dir = Files.createTempDirectory("clone-template-into").toFile().also { dirs += it }
        val document = File(dir, "receiving").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{\"version\" 2 \"boxes\" []}\n")
        val root = Box("root")
        val s = SessionInformation(
            filename = document.absolutePath, documentName = "receiving",
            boxes = ConcurrentHashMap(), root = root, ephemeral = true,
        )
        root.inside = s
        s.loading = false
        s.boxes[root.id] = root
        thisSessionInfo.set(s)
        return s
    }

    private fun wholeDocumentTemplate(): File {
        val dir = Files.createTempDirectory("whole-document-template").toFile().also { dirs += it }
        File(dir, SavingContext.METADATA_EDN).writeText(
            """
            {"version" 2 "boxes" [
              {"id" "a" "properties" {"name" "A" "parents" #{#afield/box "root"} "frame" #afield/rect [10.0 20.0 100.0 100.0]}}
              {"id" "metaroot" "properties" {"name" "" "children" #{#afield/box "root"}}}
              {"id" "root" "properties" {"name" "root" "parents" #{#afield/box "metaroot"} "children" #{#afield/box "a"}
                                          "frame" #afield/rect [500.0 500.0 0.0 0.0] "drawing" "the template's drawing"}}
            ]}
            """.trimIndent()
        )
        return dir
    }

    @Test
    fun aWholeDocumentInsertsOnlyItsBoxes() {
        val s = session()
        val existing = Box("existing").also { it.inside = s; s.boxes[it.id] = it; it.parents = mutableSetOf(s.root) }
        s.root.children = mutableSetOf(existing)
        val rootFrame = s.root.frame

        val inserted = SavingContext().cloneTemplateInto(wholeDocumentTemplate().path)

        assertEquals(listOf("a"), inserted.map { it.properties["source_id"] }, "only the template's own box comes in")
        val a = inserted.single()
        assertTrue(s.root in a.parents, "it hangs off the receiving root")
        assertTrue(a in s.root.children && existing in s.root.children, "beside what was there: ${s.root.children}")
        assertEquals(rootFrame, s.root.frame, "the receiving root does not move")
        assertNull(s.root.properties["drawing"], "nor take the template's root properties")
        assertTrue(s.boxes.values.none { it.properties["source_id"] == "metaroot" }, "and no copy of its metaroot appears")
    }

    @Test
    fun aSavedSelectionStillInserts() {
        session()
        val dir = Files.createTempDirectory("saved-selection").toFile().also { dirs += it }
        val edn = File(dir, "greeter.edn").apply {
            writeText("{\"version\" 2 \"boxes\" [{\"id\" \"g\" \"properties\" {\"name\" \"Greeter\" \"parents\" #{#afield/box \"root\"}}}]}")
        }
        val inserted = SavingContext().cloneTemplateInto(edn.path)
        assertEquals(listOf("g"), inserted.map { it.properties["source_id"] })
        assertTrue(inserted.single().id != "g", "it gets a fresh uid")
    }
}
