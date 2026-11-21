package afield.document.graph

import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.persistence.SavingContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Box.parentHint] is the note a template box shows above itself until it is connected beneath
 * something. It only does that job if it reaches the canvas and survives a save, because a template
 * is a saved document: these tests hold both.
 */
class ParentHintTest {

    private val boxes = mutableListOf<Box>()
    private val tempDirs = mutableListOf<File>()

    private fun fileBackedSession(label: String): SessionInformation {
        val dir = Files.createTempDirectory("afield-parent-hint").toFile()
        tempDirs += dir
        val document = File(dir, label).also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{\"version\" 2 \"boxes\" []}\n")
        val root = Box("root")
        val s = SessionInformation(
            filename = document.absolutePath,
            documentName = label,
            boxes = ConcurrentHashMap(),
            root = root,
            ephemeral = true,
        )
        root.inside = s
        s.loading = false
        s.boxes[root.id] = root
        thisSessionInfo.set(s)
        boxes += root
        return s
    }

    @AfterTest
    fun leaveTheGlobalResyncQueueAsWeFoundIt() {
        val mine = boxes.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        boxes.clear()
        thisSessionInfo.remove()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun aBoxWithoutAHintHasAnEmptyOne() {
        val s = fileBackedSession("no-hint")
        val box = Box("plain").also { it.inside = s; s.boxes[it.id] = it; boxes += it }
        assertEquals("", box.parentHint)
    }

    @Test
    fun theHintIsSentToTheCanvasAndSaved() {
        assertTrue(Box.uiResyncProperties.any { it.matches("parentHint") }, "the canvas draws it, so it must be resynced")
        assertTrue(Box.isPersistentName("parentHint"), "a template carries it, so it must be saved")
    }

    @Test
    fun theHintSurvivesASaveAndALoad() {
        val source = fileBackedSession("hint-source")
        val child = Box("child").also { it.inside = source; source.boxes[it.id] = it; boxes += it }
        source.root.connect(child)
        child.parentHint = "Connect under a renderer: select the renderer, then this box, and press G"

        val edn = SavingContext().exportEdnPackage(setOf(source.root, child), source.filename)
        assertTrue("\"parentHint\"" in edn, "the export must carry the hint:\n$edn")

        val opened = fileBackedSession("hint-opened")
        SavingContext().loadFromEdnText(edn, File(opened.filename).path, initIfCreated = false)
        val loaded = opened.boxes["child"] ?: error("child did not load")
        boxes += loaded
        assertEquals(child.parentHint, loaded.parentHint)
    }
}
