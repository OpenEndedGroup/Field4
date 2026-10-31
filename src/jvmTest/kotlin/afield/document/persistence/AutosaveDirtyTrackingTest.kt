package afield.document.persistence

import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.graph.layerFrames
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
        // The loop's own save, so whatever it records about "what disk holds" is recorded here too.
        info.markSidecarPending("a", "code")
        assertTrue(DocumentShutdownFlush.saveIfPending(info, inline = true, reason = "test fixture"))
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

    /** Runs the shutdown flush, which must save although nothing was marked, and returns `metadata.edn`. */
    private fun SessionInformation.afterUnmarkedShutdownFlush(): String {
        assertEquals(1, DocumentShutdownFlush.flushPendingDocumentSaves(), "the shutdown flush did not save an edit that bypassed dirty marking")
        return metadata()
    }

    @Test
    fun anInPlaceCollectionMutationIsSaved() {
        // N1020-6: `box.layerFrames[layer] = rect` mutates the map already stored, so no property is set.
        val info = savedDocument("in-place")
        val a = info.boxes.getValue("a")
        // The map exists already, as it does for a box loaded with layer frames, and that state is saved.
        a.layerFrames["start"] = Rect(1.0, 1.0, 10.0, 10.0)
        DocumentShutdownFlush.flushPendingDocumentSaves()
        info.pendingSidecarSaves.clear()
        a.rawLayerFrames()["overview"] = Rect(901.0, 902.0, 10.0, 10.0)
        assertTrue(info.pendingSidecarSaves.isEmpty(), "this edit is meant to bypass marking: ${info.pendingSidecarSaves}")
        assertTrue("901" in info.afterUnmarkedShutdownFlush(), "the layer frame is not in metadata.edn")
    }

    @Test
    fun aWriteWhileLoadingIsSavedOnceLoaded() {
        // N1020-6: box code that runs before the loader clears `loading` writes with marking off.
        val info = savedDocument("during-load")
        info.loading = true
        info.boxes.getValue("a").setPropertyRaw("autorun", true)
        assertEquals(0, DocumentShutdownFlush.flushPendingDocumentSaves(), "nothing may be saved while loading")
        info.loading = false
        assertTrue("\"autorun\" true" in info.afterUnmarkedShutdownFlush())
    }

    @Test
    fun anUntouchedDocumentIsNotSaved() {
        val info = savedDocument("idle")
        // A Delegation default materialises an empty map on first read, through setPropertyRaw; that is
        // not an edit, but until 2026-10-22 it marked the document, so merely reading layerFrames saved it.
        info.boxes.values.forEach { it.layerFrames }
        info.boxes.getValue("a").setPropertyRaw("code_editorState", "{\"line\":9}")
        assertEquals(0, DocumentShutdownFlush.flushPendingDocumentSaves(), "an unchanged document was saved")
    }

    /** The stored map itself, bypassing the marking view `Box.layerFrames` returns: a stand-in for any in-place mutation. */
    @Suppress("UNCHECKED_CAST")
    private fun Box.rawLayerFrames(): MutableMap<String, Rect> = getPropertyRaw("layerFrames") as MutableMap<String, Rect>

    @Test
    fun aLayerFrameWrittenThroughTheBoxIsMarked() {
        // Caught by the tripwire in a live session: box code's `thisBox.layerFrames[layer] = rect`.
        val info = savedDocument("layer-frame-view")
        val a = info.boxes.getValue("a")
        a.layerFrames["start"] = Rect(1.0, 1.0, 10.0, 10.0)
        assertTrue(("a" to "layerFrames") in info.pendingSidecarSaves, "an in-place layer frame write was not marked")
        assertEquals(1, DocumentShutdownFlush.flushPendingDocumentSaves())
        assertEquals(emptyList(), DocumentShutdownFlush.lastUnmarked)
        assertTrue(a.getPropertyRaw("layerFrames") !is afield.document.graph.PersistentMapView<*, *>, "the view must not be stored")
    }

    @Test
    fun theTripwireNamesTheUnmarkedProperty() {
        // N1022-2: the save still happens, and the tripwire says which box and property bypassed marking.
        val info = savedDocument("tripwire")
        val a = info.boxes.getValue("a")
        a.layerFrames["start"] = Rect(1.0, 1.0, 10.0, 10.0)
        DocumentShutdownFlush.flushPendingDocumentSaves()
        info.pendingSidecarSaves.clear()
        a.rawLayerFrames()["overview"] = Rect(901.0, 902.0, 10.0, 10.0)
        info.boxes.getValue("b").name = "renamed b" // marked: must not be reported
        DocumentShutdownFlush.flushPendingDocumentSaves()
        assertEquals(listOf("a" to "layerFrames"), DocumentShutdownFlush.lastUnmarked)
    }

    @Test
    fun aMarkedEditThatChangesNothingIsNotSaved() {
        // N1020-7: `set-string` marks a sidecar property on every message, even one carrying the text the
        // box already has (an editor re-sending its buffer); each rewrote metadata.edn and a backup.
        val info = savedDocument("no-op")
        val a = info.boxes.getValue("a")
        a.setPropertyRaw("code", "// a")
        info.markSidecarPending("a", "code")
        assertEquals(0, DocumentShutdownFlush.flushPendingDocumentSaves(), "an unchanged document was rewritten")
        assertTrue(info.pendingSidecarSaves.isEmpty(), "the no-op marks should be cleared")
        a.frame = Rect(11.0, 20.0, 100.0, 50.0)
        assertEquals(1, DocumentShutdownFlush.flushPendingDocumentSaves(), "a real move after a skipped no-op must save")
    }

    @Test
    fun aMarkedValueHashedByIdentityStillSaves() {
        val info = savedDocument("identity")
        class Opaque(var n: Int)
        val a = info.boxes.getValue("a")
        assertFalse(PersistedFingerprint.hashesByContent(Opaque(1)))
        assertTrue(PersistedFingerprint.hashesByContent(listOf(Rect(0.0, 0.0, 1.0, 1.0), "x", 3)))
        a.setPropertyRaw("autorun", true)
        assertEquals(1, DocumentShutdownFlush.flushPendingDocumentSaves())
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

    /**
     * Backlog N1030-4(d): a template's boxes were on disk after a normal stop in only 2 of 4 runs.
     * This inserts the way `insert-template` does ([afield.app.server.insertTemplateInto]) into a
     * saved document, stops at once (the shutdown flush, no autosave tick), and reads the file.
     */
    @Test
    fun aTemplateInsertIsSaved() {
        val template = savedDocument("template-source")
        documents.remove(template.filename)
        val info = savedDocument("template-target")
        thisSessionInfo.set(info)
        val inserted = afield.app.server.insertTemplateInto(info, "template-source", template.filename)
        assertEquals(2, inserted.size, "the template's two boxes")
        val text = info.afterShutdownFlush()
        inserted.forEach { assertTrue(it.id in text, "inserted box ${it.id} is not in metadata.edn:\n$text") }
    }
}
