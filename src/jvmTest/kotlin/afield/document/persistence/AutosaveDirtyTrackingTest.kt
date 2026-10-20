package afield.document.persistence

import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.graph.needsResync
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which edits the autosave loop and the shutdown flush consider worth saving.
 *
 * Both save a document only when something is pending in `SessionInformation.pendingSidecarSaves`.
 * Until 2026-10-20 only *sidecar* properties (`code`, shaders, ...) were ever marked, by explicit
 * calls in a few handlers, so a new box with no code, a move, a rename, a delete, a connection or
 * `autorun` never caused a save: each reached disk only if a sidecar edit followed it before Field
 * stopped. Backlog N1019-1 saw it as a box created ~7 s before shutdown that never reached
 * `metadata.edn`. Each test makes one such edit after a clean save, then runs the shutdown flush,
 * and reads the file.
 */
class AutosaveDirtyTrackingTest {

    private val tempDirs = mutableListOf<File>()
    private val opened = mutableListOf<SessionInformation>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { info ->
            documents.remove(info.filename)
            val mine = info.boxes.values.toSet() + info.root
            needsResync.removeAll { (box, _) -> box in mine }
        }
        opened.clear()
        thisSessionInfo.remove()
        Box.context.remove()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    /** A saved document with boxes `a` and `b` under the root, nothing pending. */
    private fun savedDocument(label: String): SessionInformation {
        val directory = Files.createTempDirectory("afield-autosave-$label").toFile().also { tempDirs += it }
        val root = Box("root")
        root.name = "root"
        val info = SessionInformation(filename = directory.absolutePath, documentName = directory.name, root = root)
        root.inside = info
        info.boxes[root.id] = root
        info.agentManaged = true
        info.loading = true // as the loader does; nothing below is an edit
        for (id in listOf("a", "b")) {
            val box = Box(id)
            box.inside = info
            info.boxes[id] = box
            box.name = id
            box.frame = Rect(10.0, 20.0, 100.0, 50.0)
            box.setPropertyRaw("code", "// $id")
            root.connect(box)
        }
        info.loading = false
        documents[info.filename] = info
        thisSessionInfo.set(info)
        opened += info
        SavingContext().save(info.filename, (info.boxes.values + root).toSet(), inline = true)
        info.pendingSidecarSaves.clear()
        return info
    }

    private fun SessionInformation.metadata(): String = File(filename, SavingContext.METADATA_EDN).readText()

    /** Runs the shutdown flush and returns what it left in `metadata.edn`. */
    private fun SessionInformation.afterShutdownFlush(): String {
        assertTrue(pendingSidecarSaves.isNotEmpty(), "the edit left nothing pending, so neither the autosave loop nor the shutdown flush would save it")
        DocumentShutdownFlush.flushPendingDocumentSaves()
        return metadata()
    }

    @Test
    fun aNewBoxWithNoCodeIsSaved() {
        val info = savedDocument("new-box")
        val fresh = Box("fresh")
        fresh.inside = info
        info.boxes["fresh"] = fresh
        fresh.name = "fresh box"
        fresh.frame = Rect(300.0, 400.0, 100.0, 100.0)
        info.root.connect(fresh)
        val text = info.afterShutdownFlush()
        assertTrue("fresh box" in text, "the new box is not in metadata.edn:\n$text")
    }

    @Test
    fun aMoveIsSaved() {
        val info = savedDocument("move")
        info.boxes.getValue("a").frame = Rect(777.0, 555.0, 100.0, 50.0)
        assertTrue("777" in info.afterShutdownFlush())
    }

    @Test
    fun aRenameIsSaved() {
        val info = savedDocument("rename")
        info.boxes.getValue("a").name = "renamed"
        assertTrue("renamed" in info.afterShutdownFlush())
    }

    @Test
    fun aDeleteIsSaved() {
        val info = savedDocument("delete")
        assertFalse("\"_tombstone\" true" in info.metadata())
        val b = info.boxes.getValue("b")
        b.setPropertyRaw("_tombstone", true)
        info.root.disconnect(b)
        assertTrue("\"_tombstone\" true" in info.afterShutdownFlush())
    }

    @Test
    fun aConnectionIsSaved() {
        val info = savedDocument("connect")
        val before = info.metadata()
        info.boxes.getValue("a").connect(info.boxes.getValue("b"))
        val after = info.afterShutdownFlush()
        assertTrue(after.split("\"b\"").size > before.split("\"b\"").size, "a -> b is not in metadata.edn:\n$after")
    }

    @Test
    fun aPlainPersistentPropertyIsSaved() {
        val info = savedDocument("autorun")
        info.boxes.getValue("a").setPropertyRaw("autorun", true)
        assertTrue("\"autorun\" true" in info.afterShutdownFlush())
    }

    @Test
    fun editsThatAreNotPersistedMarkNothing() {
        val info = savedDocument("transient")
        val a = info.boxes.getValue("a")
        a.setPropertyRaw("code_editorState", "{\"line\":3}")
        a.setPropertyRaw("_someRuntimeScratch", 3)
        a.name = "a" // unchanged value
        assertTrue(info.pendingSidecarSaves.isEmpty(), "pending: ${info.pendingSidecarSaves}")
    }

    @Test
    fun writesWhileLoadingMarkNothing() {
        val info = savedDocument("loading")
        info.loading = true
        info.boxes.getValue("a").frame = Rect(1.0, 2.0, 3.0, 4.0)
        info.loading = false
        assertTrue(info.pendingSidecarSaves.isEmpty(), "pending: ${info.pendingSidecarSaves}")
    }

    @Test
    fun externalCopiesAreCapped() {
        val directory = Files.createTempDirectory("afield-external-copies").toFile().also { tempDirs += it }
        val backups = File(directory, "backups").apply { mkdirs() }
        val base = 1_700_000_000_000L
        repeat(EXTERNAL_COPIES_KEPT + 5) { File(backups, "a_code_.kt.external-${base + it}").writeText("v$it") }
        File(backups, "b_code_.kt.external-$base").writeText("other sidecar")
        File(backups, "a_code__backup_00001.kt").writeText("ordinary backup")
        val root = Box("root")
        val info = SessionInformation(filename = directory.absolutePath, documentName = directory.name, root = root)
        root.inside = info
        FileWatchManager(info).pruneExternalCopies(backups, "a_code_.kt")
        val kept = backups.listFiles { f -> f.name.startsWith("a_code_.kt.external-") }.orEmpty().map { it.name }.sorted()
        assertEquals((5 until EXTERNAL_COPIES_KEPT + 5).map { "a_code_.kt.external-${base + it}" }, kept, "the newest copies are kept")
        assertTrue(File(backups, "b_code_.kt.external-$base").exists(), "another sidecar's copy must not be touched")
        assertTrue(File(backups, "a_code__backup_00001.kt").exists(), "ordinary backups must not be touched")
    }
}
