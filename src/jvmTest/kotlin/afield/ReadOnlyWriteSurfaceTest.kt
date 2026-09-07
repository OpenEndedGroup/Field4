package afield

import afield.server.FileProxyWriteResult
import afield.server.createProxyTarget
import afield.server.writeFileProxyContents
import afield.stdlib.saved
import java.io.File
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A read-only session must not write, and the three writers that used to are pinned here.
 *
 * `DocumentWriteBackLedgerTest` covers the autosave path: `SavingContext.save` refuses at the funnel
 * and the four session-level gates stop the interactive loop. This file covers the writers that are
 * not on that path at all. On 2026-09-07 a survey of the gates —
 * `git grep -n 'ReadOnlyDocuments'` (four sites: `Server.kt:310`, `agent_backdoor.kt:156`,
 * `saving.kt:310`, `saving.kt:1668`) and `git grep -n 'writesToDisk'` (five: `Boxes.kt:976`,
 * `Server.kt:2063`, `Server.kt:2374`, `agent_backdoor.kt:686`, `history.kt:2853`) — did not mention
 * any of the following, and each one was observed writing in a read-only session before the gate
 * that this file now asserts:
 *
 * 1. `writeFileProxyContents`, reached from the `set-string` handler (`Server.kt`) whenever the
 *    edited property is called `contents`. Typing in a file-proxy box wrote the user's file.
 * 2. `createProxyTarget` for mode `document`/`document-copy`, reached by dropping a file on the
 *    canvas. Wrote bytes *inside the document directory*.
 * 3. `SavedJsonValue`, the delegate behind `var n by saved { 0 }`, which writes
 *    `<document dir>/<box id>/<property>.json`. Reached from `setValue` **and** from `getValue` —
 *    a first read with no file yet computes the default and writes it — so in a read-only session
 *    merely *running* a box wrote into the document directory.
 *
 * ### Why every test here has a writable control
 *
 * The house pattern from `DocumentWriteBackLedgerTest`: an assertion that a file did *not* appear
 * passes just as happily when the producer has been deleted, renamed, or broken by an unrelated
 * change. So each test first drives the identical fixture through a **writable** session and asserts
 * the write really does land, with the expected content — a floor, not just "something happened".
 * Only then does it assert the read-only refusal. If the producer ever stops working the control
 * fails first, and says so.
 */
class ReadOnlyWriteSurfaceTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        // `documents` is process-global and other tests iterate it, so remove only our own keys.
        // They are absolute temp paths, hence the match on the last segment.
        documents.keys
            .filter { it.substringAfterLast('/').startsWith(TEMP_PREFIX) }
            .forEach { documents.remove(it) }
        thisSessionInfo.remove()
        Box.context.remove()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDocument(label: String): File =
        File.createTempFile("$TEMP_PREFIX$label", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            tempDirs += probe
            probe
        }

    /**
     * A session on [directory], registered in `documents` and installed as the ambient session.
     *
     * Both of those matter: `ReadOnlyDocuments.blocksWritesTo` looks the path up in `documents`, and
     * `createProxyTarget` reads `thisSessionInfo` rather than taking a session as an argument.
     */
    private fun session(directory: File, readOnly: Boolean = false): SessionInformation {
        val root = Box("root")
        root.name = "root"
        val info = SessionInformation(
            filename = directory.absolutePath,
            documentName = directory.name,
            root = root,
            readOnly = readOnly
        )
        root.inside = info
        documents[directory.absolutePath] = info
        thisSessionInfo.set(info)
        return info
    }

    /** A box carrying the two properties that make `isFileProxy()` true, in `document` mode. */
    private fun fileProxyBox(info: SessionInformation, id: String, relativePath: String): Box {
        val box = Box(id)
        box.name = id
        box.inside = info
        box.setPropertyRaw("file_proxy_mode", "document", doResync = false)
        box.setPropertyRaw("file_proxy_path", relativePath, doResync = false)
        box.frame = Rect(0.0, 0.0, 100.0, 50.0)
        info.boxes[id] = box
        return box
    }

    private fun plainBox(info: SessionInformation, id: String): Box {
        val box = Box(id)
        box.name = id
        box.inside = info
        box.frame = Rect(0.0, 0.0, 100.0, 50.0)
        info.boxes[id] = box
        return box
    }

    // ------------------------------------------------------------------------------------------
    // Escape 1: writeFileProxyContents
    // ------------------------------------------------------------------------------------------

    @Test
    fun editingAFileProxyInAReadOnlySessionDoesNotWriteTheFile() {
        // Control. The same call on a writable session must actually rewrite the file, or the
        // read-only assertion below would pass against a writer that had simply stopped working.
        val writableDir = tempDocument("proxy-writable")
        val writableInfo = session(writableDir)
        val writableTarget = File(writableDir, PROXY_FILE_NAME)
        writableTarget.writeText(ORIGINAL_TEXT)
        val writableBox = fileProxyBox(writableInfo, "proxy", PROXY_FILE_NAME)

        writeFileProxyContents(writableBox, EDITED_TEXT)
        assertEquals(
            EDITED_TEXT,
            writableTarget.readText(),
            "premise failed: a writable session did not write the file proxy through, so this test " +
                "cannot tell a read-only refusal from a broken writer"
        )

        val readOnlyDir = tempDocument("proxy-readonly")
        val readOnlyInfo = session(readOnlyDir, readOnly = true)
        assertTrue(readOnlyInfo.readOnly, "premise failed: the session is not read-only")
        assertTrue(!readOnlyInfo.writesToDisk, "premise failed: a read-only session claims writesToDisk")
        val readOnlyTarget = File(readOnlyDir, PROXY_FILE_NAME)
        readOnlyTarget.writeText(ORIGINAL_TEXT)
        val readOnlyBox = fileProxyBox(readOnlyInfo, "proxy", PROXY_FILE_NAME)

        writeFileProxyContents(readOnlyBox, EDITED_TEXT)

        assertEquals(
            ORIGINAL_TEXT,
            readOnlyTarget.readText(),
            "a read-only session wrote the file behind a file-proxy box"
        )
    }

    @Test
    fun aRefusedFileProxyWriteIsDistinguishableFromNotBeingAFileProxy() {
        // A silent `false` would be indistinguishable from "this box is not a file proxy", which is
        // an ordinary, expected answer — the `set-string` handler asks about every box whose edited
        // property happens to be called `contents`. So the refusal has to be its own result.
        val readOnlyDir = tempDocument("proxy-result")
        val readOnlyInfo = session(readOnlyDir, readOnly = true)
        val target = File(readOnlyDir, PROXY_FILE_NAME)
        target.writeText(ORIGINAL_TEXT)

        val proxy = fileProxyBox(readOnlyInfo, "proxy", PROXY_FILE_NAME)
        val notAProxy = plainBox(readOnlyInfo, "ordinary")

        val refused = writeFileProxyContents(proxy, EDITED_TEXT)
        val irrelevant = writeFileProxyContents(notAProxy, EDITED_TEXT)

        assertTrue(
            refused != irrelevant,
            "a read-only refusal ($refused) reads the same as a box that is not a file proxy " +
                "($irrelevant), so no caller can act on it"
        )
        assertEquals(
            FileProxyWriteResult.Refused,
            refused,
            "a read-only file-proxy write should report itself refused"
        )
        assertEquals(
            FileProxyWriteResult.NotAFileProxy,
            irrelevant,
            "premise failed: an ordinary box no longer reports NotAFileProxy, so the comparison " +
                "above is not measuring what it claims"
        )
    }

    // ------------------------------------------------------------------------------------------
    // Escape 2: createProxyTarget, i.e. dropping a file onto the canvas
    // ------------------------------------------------------------------------------------------

    @Test
    fun droppingAFileIntoAReadOnlyDocumentWritesNothing() {
        val bytes = DROPPED_TEXT.toByteArray()
        val request = CreateFileProxyBox(
            mode = "document-copy",
            fileName = DROPPED_FILE_NAME,
            contentsBase64 = Base64.getEncoder().encodeToString(bytes)
        )

        // Control, floored at the exact file name and content rather than "the directory is not
        // empty": a copy that landed under the wrong name would still satisfy a weaker assertion.
        val writableDir = tempDocument("drop-writable")
        session(writableDir)
        createProxyTarget(request)
        val copied = File(writableDir, DROPPED_FILE_NAME)
        assertTrue(
            copied.isFile,
            "premise failed: a writable session did not copy the dropped file to ${copied.path}; " +
                "found ${writableDir.list()?.toList()}"
        )
        assertEquals(
            DROPPED_TEXT,
            copied.readText(),
            "premise failed: the writable control copied the wrong bytes"
        )

        val readOnlyDir = tempDocument("drop-readonly")
        session(readOnlyDir, readOnly = true)

        // The refusal is an exception and not a silent no-op: the handler's contract is to return a
        // created proxy, and there is no proxy to return. Returning a fake one would put a box on
        // the canvas pointing at a file that does not exist.
        val failure = runCatching { createProxyTarget(request) }.exceptionOrNull()

        assertEquals(
            emptyList(),
            readOnlyDir.list()?.toList()?.sorted() ?: emptyList(),
            "dropping a file into a read-only document wrote into the document directory"
        )
        assertTrue(
            failure != null,
            "a read-only session accepted the drop and reported success, so the client would show " +
                "a file-proxy box for a file that was never written"
        )
        assertTrue(
            failure.message.orEmpty().contains("read-only", ignoreCase = true),
            "the refusal does not say why: ${failure.message}"
        )
    }

    // ------------------------------------------------------------------------------------------
    // Escape 3: saved { } — the unlisted producer, and the only one that fires on a plain read
    // ------------------------------------------------------------------------------------------

    /**
     * A holder whose delegate has to reach for `Box.context`.
     *
     * `SavedJsonValue.resolveBox` takes `thisRef` when it is a `Box` and the ambient `Box.context`
     * otherwise. A non-`Box` holder is the shape a box's own code has — `var n by saved { 0 }` at
     * the top level of a box compiles into a class that is not a `Box` — so this exercises the
     * context path a real sketch uses rather than the receiver shortcut.
     */
    private class SavedCounter {
        var counter: Int by saved { 0 }
    }

    @Test
    fun assigningToASavedPropertyInAReadOnlySessionWritesNothing() {
        val writableDir = tempDocument("saved-writable")
        val writableInfo = session(writableDir)
        val writableBox = plainBox(writableInfo, "sketch")
        Box.context.set(writableBox)
        SavedCounter().counter = 7
        val writtenFile = File(File(writableDir, writableBox.id), "counter.json")
        assertTrue(
            writtenFile.isFile,
            "premise failed: a writable session did not write $writtenFile, so this test cannot " +
                "tell a read-only refusal from a broken delegate"
        )
        assertEquals(
            "7",
            writtenFile.readText().trim(),
            "premise failed: the writable control wrote something other than the assigned value"
        )

        val readOnlyDir = tempDocument("saved-readonly")
        val readOnlyInfo = session(readOnlyDir, readOnly = true)
        val readOnlyBox = plainBox(readOnlyInfo, "sketch")
        Box.context.set(readOnlyBox)
        SavedCounter().counter = 7

        assertEquals(
            emptyList(),
            readOnlyDir.walkTopDown().filter { it != readOnlyDir }.map { it.name }.toList().sorted(),
            "a read-only session wrote a saved{} value into the document directory"
        )
    }

    @Test
    fun readingASavedPropertyForTheFirstTimeInAReadOnlySessionWritesNothing() {
        // The surprising half. `getValue` writes the default when there is no file yet, so a
        // read-only session that merely *runs* a box with `var n by saved { 0 }` used to leave a
        // file behind without the user touching anything.
        val writableDir = tempDocument("savedread-writable")
        val writableInfo = session(writableDir)
        val writableBox = plainBox(writableInfo, "sketch")
        Box.context.set(writableBox)
        assertEquals(0, SavedCounter().counter, "premise failed: the default was not returned")
        assertTrue(
            File(File(writableDir, writableBox.id), "counter.json").isFile,
            "premise failed: a first read on a writable session no longer writes the default, so " +
                "the read-only assertion below would pass for the wrong reason"
        )

        val readOnlyDir = tempDocument("savedread-readonly")
        val readOnlyInfo = session(readOnlyDir, readOnly = true)
        val readOnlyBox = plainBox(readOnlyInfo, "sketch")
        Box.context.set(readOnlyBox)

        // Still has to hand the box its value: refusing to write must not turn into refusing to run.
        assertEquals(
            0,
            SavedCounter().counter,
            "a read-only session broke reading a saved{} property instead of just not writing it"
        )
        assertEquals(
            emptyList(),
            readOnlyDir.walkTopDown().filter { it != readOnlyDir }.map { it.name }.toList().sorted(),
            "reading a saved{} property in a read-only session wrote its default to disk"
        )
    }

    // ------------------------------------------------------------------------------------------
    // The predicate itself, in one place
    // ------------------------------------------------------------------------------------------

    @Test
    fun theReadOnlyPredicateAgreesWithTheSessionFlagForBoxes() {
        val writableDir = tempDocument("predicate-writable")
        val writableBox = plainBox(session(writableDir), "b")
        val readOnlyDir = tempDocument("predicate-readonly")
        val readOnlyBox = plainBox(session(readOnlyDir, readOnly = true), "b")

        assertTrue(
            !ReadOnlyDocuments.blocksWritesFrom(writableBox),
            "the predicate blocks a writable session's box"
        )
        assertTrue(
            ReadOnlyDocuments.blocksWritesFrom(readOnlyBox),
            "the predicate lets a read-only session's box write"
        )
        // A box with no session at all must not be treated as writable by default: an ungated
        // producer reached through a detached box is exactly how the next escape gets in.
        assertEquals(
            ReadOnlyDocuments.global,
            ReadOnlyDocuments.blocksWritesFrom(Box("orphan")),
            "a box with no initialised session should fall back to the global switch"
        )
    }

    companion object {
        private const val TEMP_PREFIX = "afield-readonly-write-surface-"
        private const val PROXY_FILE_NAME = "notes.txt"
        private const val ORIGINAL_TEXT = "as committed\n"
        private const val EDITED_TEXT = "edited in a read-only session\n"
        private const val DROPPED_FILE_NAME = "dropped.txt"
        private const val DROPPED_TEXT = "dropped bytes\n"
    }
}
