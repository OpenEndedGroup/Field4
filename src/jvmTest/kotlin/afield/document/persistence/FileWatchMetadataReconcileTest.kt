package afield.document.persistence

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.output.md

import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.app.server.sessions
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.isTrue
import afield.document.graph.needsResync
import afield.document.workspace.metadata
import afield.runtime.lifecycle.Runloop
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which session boxes a `metadata.edn` watch event may tombstone.
 *
 * On 2026-10-08, on a host at load ~35, `save_reload.log` for one document showed: a save took its
 * snapshot (8 boxes); a backdoor client created box `76e1f1ed`; the save wrote `metadata.edn`, then
 * `document.md`, `operations.yaml` and the sidecars, and registered `metadata.edn` as an internal
 * save only at the very end, ~900 ms in. The file's own watch event had arrived ~60 ms before that,
 * so it was compared against the previous save's size, taken for an external edit, and reconciled.
 * Reconciliation tombstoned every session box missing from the file, `76e1f1ed` included: a box that
 * had never been on disk, gone from `ListBoxesRequest` and refused by later calls.
 *
 * The two tests pin the two halves of the fix. The first replays that sequence through the real
 * save path: `metadata.edn` must be recognised as Field's own write as soon as it is written. The
 * second covers the reconciliation itself, for any `metadata.edn` taken as external: it may remove
 * only boxes that were in the last file Field wrote or loaded, and a genuine external removal still
 * tombstones.
 *
 * The main runloop is not running under test, so `inMain` work (the save's completion, the
 * reconciliation) is queued until [pumpMainUntil] runs `Runloop.runLoop.main` by hand. That is what
 * lets the first test deliver the watch event while the save is still incomplete.
 */
class FileWatchMetadataReconcileTest {

    private val tempDirs = mutableListOf<File>()
    private val sessions = mutableListOf<SessionInformation>()

    @AfterTest
    fun cleanUp() {
        sessions.forEach { info ->
            info.cleanup()
            documents.remove(info.filename)
            val mine = info.boxes.values.toSet() + info.root
            needsResync.removeAll { (box, _) -> box in mine }
        }
        sessions.clear()
        thisSessionInfo.remove()
        Box.context.remove()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDocument(label: String): File =
        Files.createTempDirectory("afield-filewatch-$label").toFile().also { tempDirs += it }

    /**
     * A session on [directory] holding [ids] besides the root, as the backdoor leaves one: registered
     * in `documents` and agent-managed, which also keeps the AI box namer out of the save's
     * completion.
     */
    private fun session(directory: File, vararg ids: String): SessionInformation {
        val root = Box("root")
        root.name = "root"
        val info = SessionInformation(filename = directory.absolutePath, documentName = directory.name, root = root)
        root.inside = info
        info.boxes[root.id] = root
        info.loading = false
        info.agentManaged = true
        documents[directory.absolutePath] = info
        thisSessionInfo.set(info)
        sessions += info
        ids.forEach { plainBox(info, it) }
        return info
    }

    private fun plainBox(info: SessionInformation, id: String): Box {
        val box = Box(id)
        box.name = id
        box.inside = info
        box.frame = Rect(0.0, 0.0, 100.0, 50.0)
        info.boxes[id] = box
        return box
    }

    private fun Box.isTombstoned(): Boolean = getPropertyRaw("_tombstone").isTrue()

    private fun pumpMainUntil(timeoutMs: Long = 10_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            Runloop.runLoop.main.frame()
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /** What an external tool writes: hand-formatted EDN naming [ids] besides the root. */
    private fun externalMetadata(vararg ids: String): String =
        (listOf("root") + ids).joinToString(" ", "{\"version\" 2 \"boxes\" [", "]}\n") { id ->
            "{\"id\" \"$id\" \"properties\" {\"name\" \"$id\"}}"
        }

    /** Like [externalMetadata], with each box's `name` given explicitly. */
    private fun externalMetadataNamed(vararg boxes: Pair<String, String>): String =
        (listOf("root" to "root") + boxes).joinToString(" ", "{\"version\" 2 \"boxes\" [", "]}\n") { (id, name) ->
            "{\"id\" \"$id\" \"properties\" {\"name\" \"$name\"}}"
        }

    /**
     * An external edit of a property of a box already open used to be ignored, and the next save wrote
     * the in-memory value back over it. It is applied now — and only what the file changed is applied:
     * a box the session renamed since the file was written keeps its new name.
     */
    @Test
    fun externalPropertyEditOfAnOpenBoxIsAppliedAndSessionEditsSurvive() {
        val directory = tempDocument("external-property")
        val metadata = File(directory, SavingContext.METADATA_EDN)
        metadata.writeText(externalMetadataNamed("a" to "a", "c" to "c"))
        val loadedAt = metadata.lastModified()
        val info = session(directory, "a", "c")
        // Started now, after the load, so it takes the file as opened as its baseline.
        val watcher = info.fileWatchManager
        info.boxes.getValue("c").name = "c local"

        metadata.writeText(externalMetadataNamed("a" to "a edited", "c" to "c"))
        metadata.setLastModified(loadedAt + 2_000L)
        runBlocking { watcher.handleMetadataChange(metadata) }

        val a = info.boxes.getValue("a")
        assertTrue(pumpMainUntil { a.name == "a edited" }, "the external edit of a's name was not applied (name=${a.name})")
        repeat(5) { Runloop.runLoop.main.frame() }
        kotlin.test.assertEquals("c local", info.boxes.getValue("c").name,
            "c's name changed in the session and not in the file, so the reconcile must not revert it")
    }

    /**
     * The watcher used to call any event "Field's own save" when the file had the size Field wrote and
     * an mtime inside a 15 s grace window, so a same-length edit just after a save was invisible. It
     * compares the content now.
     */
    @Test
    fun aSameLengthEditJustAfterASaveIsSeen() {
        val directory = tempDocument("same-length")
        val info = session(directory, "a")
        info.boxes.getValue("a").name = "a1"
        val metadata = File(directory, SavingContext.METADATA_EDN)
        var saved = false
        SavingContext().save(info.filename, info.boxes.values.toSet(), onComplete = { saved = true }, inline = true)
        assertTrue(pumpMainUntil { saved }, "premise failed: the save never completed")
        runBlocking { info.fileWatchManager.handleMetadataChange(metadata) } // the save's own event
        repeat(5) { Runloop.runLoop.main.frame() }

        val written = metadata.readText()
        assertTrue("\"a1\"" in written, "premise failed: the save did not write a's name")
        val edited = written.replace("\"a1\"", "\"a2\"")
        kotlin.test.assertEquals(written.length, edited.length, "premise failed: the edit changed the length")
        val mtime = metadata.lastModified()
        metadata.writeText(edited)
        metadata.setLastModified(mtime)

        runBlocking { info.fileWatchManager.handleMetadataChange(metadata) }
        val a = info.boxes.getValue("a")
        assertTrue(pumpMainUntil { a.name == "a2" }, "a same-length, same-mtime external edit was taken for Field's own save")
    }

    /**
     * The property reconcile calls a value "edited" when it is not `==` to the same box's value in the
     * previous file. If any stored type lacked value equality (a rect, a sidecar reference, a nested
     * map), every such property would look edited on every external change, and stale disk values
     * would overwrite live ones. So: the same text parsed twice must compare equal, property by property,
     * across real committed documents.
     */
    @Test
    fun theSameMetadataParsedTwiceComparesEqual() {
        val demos = File(System.getProperty("user.dir"), "dev_docs/agents/demos")
        val files = demos.listFiles().orEmpty().map { File(it, SavingContext.METADATA_EDN) }.filter { it.isFile }.take(40)
        assertTrue(files.size >= 10, "premise failed: found only ${files.size} demo documents under $demos")
        val ctx = SavingContext()
        var compared = 0
        for (file in files) {
            val text = file.readText()
            val first = ctx.readEdnTransfersFromString(text).associateBy { it.id }
            val second = ctx.readEdnTransfersFromString(text).associateBy { it.id }
            for ((id, transfer) in first) {
                for ((name, value) in transfer.properties) {
                    compared++
                    kotlin.test.assertEquals(value, second.getValue(id).properties[name],
                        "${file.parentFile.name}: $id.$name (${value?.let { it::class.simpleName }}) is not equal to itself re-read")
                }
            }
        }
        assertTrue(compared > 100, "premise failed: compared only $compared properties")
    }

    @Test
    fun ownMetadataWriteSeenBeforeTheSaveCompletesKeepsABoxCreatedDuringTheSave() {
        val directory = tempDocument("own-write")
        val info = session(directory, "a")
        val metadata = File(directory, SavingContext.METADATA_EDN)

        // A completed save first, so the watcher has an earlier signature for metadata.edn, as it
        // did on the host: the event was compared against the previous save's size.
        var firstSaved = false
        SavingContext().save(info.filename, info.boxes.values.toSet(), onComplete = { firstSaved = true }, inline = true)
        assertTrue(pumpMainUntil { firstSaved }, "premise failed: the first save never completed")

        // The second save writes a different file. Inline, its I/O is done when save() returns;
        // its completion is queued on the main runloop and stays unrun until we pump.
        info.boxes.getValue("a").name = "a renamed"
        var secondSaved = false
        SavingContext().save(info.filename, info.boxes.values.toSet(), onComplete = { secondSaved = true }, inline = true)
        val written = metadata.readText()
        assertTrue("a renamed" in written, "premise failed: the second save did not write metadata.edn")
        assertFalse(secondSaved, "premise failed: the save completed before its watch event was delivered")

        // Created after the snapshot, so not in the file.
        val b = plainBox(info, "b")
        assertFalse("\"b\"" in written, "premise failed: box b reached disk")

        runBlocking { info.fileWatchManager.handleMetadataChange(metadata) }
        assertTrue(pumpMainUntil { secondSaved }, "the second save never completed")
        // Anything the event queued behind the completion runs now.
        repeat(5) { Runloop.runLoop.main.frame() }

        assertSame(b, info.boxes["b"], "box b left the session")
        assertFalse(b.isTombstoned(), "box b, created during the save, was tombstoned by the save's own watch event")
        assertFalse(info.boxes.getValue("a").isTombstoned(), "box a, on disk throughout, was tombstoned")
    }

    @Test
    fun externalRemovalTombstonesOnlyBoxesThatWereOnDisk() {
        val directory = tempDocument("external-removal")
        val metadata = File(directory, SavingContext.METADATA_EDN)
        // The document as opened: a and c on disk. The watcher starts after the load, as it does
        // in the backdoor, and takes these as its baseline.
        metadata.writeText(externalMetadata("a", "c"))
        val loadedAt = metadata.lastModified()
        val info = session(directory, "a", "c")
        val watcher = info.fileWatchManager

        // Never saved.
        val b = plainBox(info, "b")
        // A tool that read the file before b existed removes c and writes it back, b absent too.
        // Its modification time is pushed past the load so the change is unambiguously newer.
        metadata.writeText(externalMetadata("a"))
        metadata.setLastModified(loadedAt + 2_000L)

        runBlocking { watcher.handleMetadataChange(metadata) }
        val c = info.boxes.getValue("c")
        assertTrue(pumpMainUntil { c.isTombstoned() }, "box c, removed from metadata.edn externally, was not tombstoned")
        repeat(5) { Runloop.runLoop.main.frame() }

        assertSame(b, info.boxes["b"], "box b left the session")
        assertFalse(b.isTombstoned(), "box b was never on disk, so an edit of metadata.edn cannot have removed it")
        assertFalse(info.boxes.getValue("a").isTombstoned(), "box a is still in metadata.edn but was tombstoned")
    }
}
