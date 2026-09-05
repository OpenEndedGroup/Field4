package afield

import afield.docs.CitationGuard
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opening a Field document must not write into the directory it came from unless the user changed
 * something, and must never write into it at all when the session is read-only.
 *
 * The bug this pins, measured against `dev_docs/agents/demos/midi_clip` in a live Field on
 * 2026-09-05: open the demo `dev_docs/MIDI.md` tells you to open, click one box, move nothing,
 * and four paths appear in `git status` — two tracked files modified, `backups/` and
 * `history_metrics.edn` created. The only content difference in either tracked file was a
 * `code_editorState` property holding Monaco's cursor position, which the editor sends the moment
 * it binds to a box. Drag a box as well and `operations.yaml` joins them; do it from Electron
 * rather than Playwright and `history_screenshots/electron-window/` does too.
 *
 * ### Why a ledger and not a list of "does X appear"
 *
 * `SavingContext.save` fans out to seven producers (saving.kt, the block after the metadata and
 * markdown writes) and an eighth, `ensureAgentGuides`, fires on *load*. The set grows: nobody
 * adding a ninth is going to remember this file. So [saveWritesExactlyTheseArtifacts] asserts set
 * *equality* against a named ledger — missing, unexpected, and both directions at once. A new
 * producer fails as "unexpected", and a producer that stops firing fails as "missing" rather than
 * quietly making the test easier to pass.
 *
 * Each test asserts its own premise before it asserts its result, so an environment change fails
 * loudly instead of turning the test into a tautology. [editorStateNeverReachesDisk] would pass
 * vacuously if `code_editorState` had simply stopped being registered as a persistent property, so
 * it checks that it still matches [Box.ioPersistentProperties] first — the deny-list is what has to
 * be doing the work.
 */
class DocumentWriteBackLedgerTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
        documents.keys.filter { it.startsWith(TEMP_PREFIX) }.forEach { documents.remove(it) }
    }

    private fun tempDocument(label: String): File =
        File.createTempFile("$TEMP_PREFIX$label", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            tempDirs += probe
            probe
        }

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

    private fun boxWithCode(info: SessionInformation, id: String, code: String): Box {
        val box = Box(id)
        box.name = id
        box.inside = info
        box.setPropertyRaw("code", code, doResync = false)
        box.frame = Rect(0.0, 0.0, 100.0, 50.0)
        info.boxes[id] = box
        return box
    }

    /**
     * Save and wait for the writes to land, in milliseconds; -1 when nothing ever appeared.
     *
     * Not a latch on `onComplete`: `SavingContext.save` invokes that from inside `inMain`, and no
     * runloop is pumping in a unit test, so it never fires. The file writes themselves happen on
     * `Runloop.miscPool` before that point, which is what this polls for. The extra quiet period is
     * there because the fan-out continues after `metadata.edn` lands — backups, history metrics —
     * and a ledger assertion taken too early would be measuring a race.
     */
    private fun saveAndSettle(directory: File, boxes: Set<Box>): Long {
        val startedAt = System.currentTimeMillis()
        SavingContext().save(directory.absolutePath, boxes)
        var appearedAfter = -1L
        while (System.currentTimeMillis() - startedAt < SAVE_TIMEOUT_MS) {
            if (artifactSet(directory).isNotEmpty()) {
                appearedAfter = System.currentTimeMillis() - startedAt
                break
            }
            Thread.sleep(25)
        }
        if (appearedAfter < 0) return -1
        var settled = artifactSet(directory)
        while (true) {
            Thread.sleep(SAVE_QUIET_MS)
            val next = artifactSet(directory)
            if (next == settled) return appearedAfter
            settled = next
        }
    }

    /**
     * Save into a directory nothing should be written to, and return what appeared.
     *
     * Waits [SAVE_TIMEOUT_MS] regardless — an absence measured over a shorter window than the
     * writable control took would prove nothing about ordering.
     */
    private fun saveAndExpectSilence(directory: File, boxes: Set<Box>): Set<String> {
        val latch = CountDownLatch(1)
        SavingContext().save(directory.absolutePath, boxes, onComplete = { latch.countDown() })
        // A refused save returns synchronously, so this callback really does fire.
        assertTrue(
            latch.await(SAVE_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            "the refused save never called back, so it did not take the read-only branch"
        )
        Thread.sleep(SAVE_TIMEOUT_MS / 4)
        return artifactSet(directory)
    }

    /** Every path under [directory], relative and slash-separated, directories marked with a trailing `/`. */
    private fun artifactSet(directory: File): Set<String> {
        val base = directory.toPath()
        return directory.walkTopDown()
            .filter { it != directory }
            .map { base.relativize(it.toPath()).toString().replace(File.separatorChar, '/') + if (it.isDirectory) "/" else "" }
            .toSet()
    }

    // ------------------------------------------------------------------------------------------
    // Seam 1: Monaco view state
    // ------------------------------------------------------------------------------------------

    @Test
    fun editorStateNeverReachesDisk() {
        // Premise. Without this the test passes for the wrong reason the day somebody deletes the
        // ".*_editorState" entry from ioPersistentProperties: the property would be dropped by the
        // ordinary filter and the deny-list under test would never run.
        val persistentPatterns = Box.ioPersistentProperties.map { it.toRegex() }
        assertTrue(
            persistentPatterns.any { it.matches(EDITOR_STATE_PROPERTY) },
            "premise failed: $EDITOR_STATE_PROPERTY no longer matches Box.ioPersistentProperties, " +
                "so this test would pass without Box.ioNeverPersistedProperties doing anything"
        )

        val directory = tempDocument("editorstate")
        val info = session(directory)
        val box = boxWithCode(info, "sample", "val x = 1")
        box.setPropertyRaw(EDITOR_STATE_PROPERTY, EDITOR_STATE_VALUE, doResync = false)

        val transfer = SavingContext().toTransfer(setOf(box), directory.absolutePath).single()

        assertTrue(
            EDITOR_STATE_PROPERTY !in transfer.properties.keys,
            "$EDITOR_STATE_PROPERTY reached the save transfer: ${transfer.properties.keys}"
        )
        // Counterfactual, floored at a specific property rather than "something survived": a filter
        // that dropped everything would satisfy the assertion above.
        assertTrue(
            "code" in transfer.properties.keys,
            "the deny-list swallowed a property it should not have: ${transfer.properties.keys}"
        )
    }

    @Test
    fun editorStateSurvivesTheReAddThatDefeatsDeletingTheEntry() {
        // `SavingContext.mergePersistentProperties` does `ioPersistentProperties.addAll(...)` with
        // the list stored in every metadata.edn it loads, and every document written before
        // 2026-09-05 carries ".*_editorState" in that list. Removing the entry from the initialiser
        // would therefore have lasted until the first document load. Simulate that re-add.
        Box.ioPersistentProperties.add(EDITOR_STATE_PATTERN)
        assertTrue(
            Box.ioPersistentProperties.contains(EDITOR_STATE_PATTERN),
            "premise failed: could not re-add $EDITOR_STATE_PATTERN, so this test proves nothing"
        )

        val directory = tempDocument("readd")
        val info = session(directory)
        val box = boxWithCode(info, "sample", "val x = 1")
        box.setPropertyRaw(EDITOR_STATE_PROPERTY, EDITOR_STATE_VALUE, doResync = false)

        val transfer = SavingContext().toTransfer(setOf(box), directory.absolutePath).single()
        assertTrue(
            EDITOR_STATE_PROPERTY !in transfer.properties.keys,
            "$EDITOR_STATE_PROPERTY survived a re-added ioPersistentProperties entry: " +
                transfer.properties.keys
        )
    }

    @Test
    fun aSaveThatOnlyChangesEditorStateChangesNothingOnDisk() {
        val directory = tempDocument("noop")
        val info = session(directory)
        val box = boxWithCode(info, "sample", "val x = 1")

        val firstSaveMs = saveAndSettle(directory, setOf(box, info.root))
        assertTrue(firstSaveMs >= 0, "first save wrote nothing within ${SAVE_TIMEOUT_MS}ms")
        val afterFirst = artifactSet(directory)
        // Premise: the first save really did write a document, so the comparison below is between
        // two populated states and not two empty ones.
        assertTrue(
            "metadata.edn" in afterFirst,
            "premise failed: the first save wrote no metadata.edn, only $afterFirst"
        )
        val metadataBefore = File(directory, "metadata.edn").readText()
        val markdownBefore = File(directory, "document.md").readText()

        box.setPropertyRaw(EDITOR_STATE_PROPERTY, EDITOR_STATE_VALUE, doResync = false)
        // Give a second save at least as long as the first one took to produce a difference.
        SavingContext().save(directory.absolutePath, setOf(box, info.root))
        Thread.sleep(maxOf(firstSaveMs * 4, SAVE_QUIET_MS * 4))

        assertEquals(
            metadataBefore,
            File(directory, "metadata.edn").readText(),
            "selecting a box rewrote metadata.edn"
        )
        assertEquals(
            markdownBefore,
            File(directory, "document.md").readText(),
            "selecting a box rewrote document.md"
        )
        assertEquals(
            afterFirst,
            artifactSet(directory),
            "selecting a box created derived artefacts; that is the whole bug"
        )
    }

    // ------------------------------------------------------------------------------------------
    // Seam 3: read-only sessions
    // ------------------------------------------------------------------------------------------

    @Test
    fun saveWritesExactlyTheseArtifacts() {
        val directory = tempDocument("ledger")
        val info = session(directory)
        val box = boxWithCode(info, "sample", "val x = 1")

        assertTrue(
            saveAndSettle(directory, setOf(box, info.root)) >= 0,
            "save wrote nothing within ${SAVE_TIMEOUT_MS}ms"
        )

        // Bidirectional. `assertEquals` on two sets reports both directions, so a producer that is
        // added *and* a producer that is removed both fail, and neither can be absorbed silently.
        assertEquals(
            FIRST_SAVE_LEDGER,
            artifactSet(directory),
            "the set of files a first save writes has changed. If a new producer was added to " +
                "SavingContext.save, decide whether it belongs inside a document directory that " +
                "might be inside a git repository, gate it on SessionInformation.writesToDisk, " +
                "add it to .gitignore, and then add it here."
        )
    }

    @Test
    fun aReadOnlySessionWritesNothingAtAll() {
        // Counterfactual first, and floored at the ledger rather than at "differs": a writable
        // session on an identical fixture writes a specific, named set of files. Without this the
        // read-only assertion would pass against a save that had silently stopped working.
        val writable = tempDocument("writable")
        val writableInfo = session(writable)
        val writableBox = boxWithCode(writableInfo, "sample", "val x = 1")
        assertTrue(
            saveAndSettle(writable, setOf(writableBox, writableInfo.root)) >= 0,
            "premise failed: the control save wrote nothing within ${SAVE_TIMEOUT_MS}ms"
        )
        assertEquals(
            FIRST_SAVE_LEDGER,
            artifactSet(writable),
            "premise failed: a writable session did not write the expected document"
        )

        val readOnly = tempDocument("readonly")
        val readOnlyInfo = session(readOnly, readOnly = true)
        assertTrue(readOnlyInfo.readOnly, "premise failed: the session is not read-only")
        assertTrue(!readOnlyInfo.writesToDisk, "premise failed: a read-only session claims writesToDisk")
        val readOnlyBox = boxWithCode(readOnlyInfo, "sample", "val x = 1")

        assertEquals(
            emptySet(),
            saveAndExpectSilence(readOnly, setOf(readOnlyBox, readOnlyInfo.root)),
            "a read-only session wrote into its document directory"
        )
    }

    @Test
    fun aReadOnlySessionDoesNotMarkSidecarsPending() {
        // The autosave loop only runs when `pendingSidecarSaves` is non-empty, so this is the gate
        // that stops a read-only Field from even trying. `code` is in ioSeparateProperties, which
        // is what makes an ordinary edit reach it.
        assertTrue(
            Box.ioSeparateProperties.containsKey("code"),
            "premise failed: \"code\" is no longer a sidecar property, so this test proves nothing"
        )

        val writable = tempDocument("pending-writable")
        val writableInfo = session(writable)
        val writableBox = boxWithCode(writableInfo, "sample", "val x = 1")
        writableBox.setPropertyRaw("code", "val x = 2", doResync = true)
        doResync()
        assertTrue(
            writableInfo.pendingSidecarSaves.isNotEmpty(),
            "premise failed: an ordinary edit no longer marks a sidecar pending, so the read-only " +
                "assertion below would pass for the wrong reason"
        )

        val readOnly = tempDocument("pending-readonly")
        val readOnlyInfo = session(readOnly, readOnly = true)
        val readOnlyBox = boxWithCode(readOnlyInfo, "sample", "val x = 1")
        readOnlyBox.setPropertyRaw("code", "val x = 2", doResync = true)
        doResync()
        assertEquals(
            emptySet(),
            readOnlyInfo.pendingSidecarSaves.toSet(),
            "a read-only session queued a sidecar save"
        )
    }

    @Test
    fun aReadOnlyLoadDoesNotSeedAgentGuides() {
        // The eighth producer, and the only one on the load path: `SavingContext.load` calls
        // `ensureDirectory`, which seeds AGENTS.md and README.md into any document directory
        // lacking them. Nothing to do with saving, and nothing the funnel guard can see.
        val readOnly = tempDocument("guides")
        session(readOnly, readOnly = true)
        SavingContext().ensureDirectory(readOnly.absolutePath)
        assertEquals(
            emptySet(),
            artifactSet(readOnly),
            "a read-only load seeded workspace guide files"
        )
    }

    @Test
    fun aSecondSaveThatChangesCodeAddsBackups() {
        val directory = tempDocument("backups")
        val info = session(directory)
        val box = boxWithCode(info, "sample", "val x = 1")

        assertTrue(saveAndSettle(directory, setOf(box, info.root)) >= 0, "first save wrote nothing")
        // Premise: no backups yet, so the assertion below is measuring the second save.
        assertTrue(
            artifactSet(directory).none { it.startsWith("backups/") },
            "premise failed: a first save already made backups: ${artifactSet(directory)}"
        )

        box.setPropertyRaw("code", "val x = 2", doResync = false)
        assertTrue(saveAndSettle(directory, setOf(box, info.root)) >= 0, "second save wrote nothing")

        assertEquals(
            SECOND_SAVE_LEDGER,
            artifactSet(directory),
            "the set of files a real edit writes has changed"
        )
    }

    // ------------------------------------------------------------------------------------------
    // The repository itself
    // ------------------------------------------------------------------------------------------

    @Test
    fun noNewDerivedArtefactIsCommitted() {
        val root = CitationGuard.repoRoot()
        val tracked = gitLsFiles(root) ?: return  // no git here; nothing to assert against

        // Population, derived rather than listed: every directory holding a tracked metadata.edn.
        val documentDirectories = tracked
            .filter { it.endsWith("metadata.edn") }
            .map { it.substringBeforeLast("/metadata.edn", "") }
            .toSet()
        assertTrue(
            documentDirectories.size >= 100,
            "premise failed: only ${documentDirectories.size} tracked document directories found, " +
                "so the survey below is not searching the population it claims to " +
                "(117 on 2026-09-05)"
        )

        val spilled = tracked.filter { path -> DERIVED_ARTEFACT.containsMatchIn(path) }.toSortedSet()

        assertEquals(
            KNOWN_COMMITTED_SPILL,
            spilled,
            "the set of committed Field side-artefacts changed across " +
                "${documentDirectories.size} document directories. A new entry means a document " +
                "was opened inside the repository and the result was committed: delete it, it is " +
                "derived from metadata.edn. A missing entry means one was cleaned up: good, " +
                "remove it from KNOWN_COMMITTED_SPILL in this test."
        )
    }

    private fun gitLsFiles(root: File): List<String>? = runCatching {
        val process = ProcessBuilder("git", "ls-files")
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) null else lines
    }.getOrNull()

    companion object {
        private const val SAVE_TIMEOUT_MS = 20_000L
        private const val SAVE_QUIET_MS = 250L
        private const val TEMP_PREFIX = "afield-writeback-ledger-"
        private const val EDITOR_STATE_PROPERTY = "code_editorState"
        private const val EDITOR_STATE_PATTERN = ".*_editorState"
        private const val EDITOR_STATE_VALUE =
            """{"cursorState":[{"position":{"lineNumber":1,"column":1}}],"viewState":{"scrollLeft":0}}"""

        /**
         * What a first save into an empty directory leaves behind. Six files for one box.
         *
         * Three of them are the document: `metadata.edn`, `document.md`, and a `_code_.kt` sidecar
         * because `code` is in `Box.ioSeparateProperties`.
         *
         * The other three are the surprise, and they are the bug in miniature. `AGENTS.md` and
         * `README.md` come from `ensureAgentGuides`, which `ensureDirectory` calls on *every* load
         * and save. `history_metrics.edn` is there because `checkDifference` counts writing a file
         * that did not exist as an alteration, so a first save is "altered" and the whole
         * `if (altered)` fan-out runs.
         *
         * Not here: `backups/`, because `safeSave` only backs up a file that already existed with
         * different contents (see [aSecondSaveThatChangesCodeAddsBackups]); `operations.yaml`,
         * because no move or edit was recorded through `OperationTranscript`; `transcript.edn`,
         * because `FlightRecorder` needs a box to have run; and `history_screenshots/`, because
         * neither screenshot producer has a window or a client to capture.
         */
        private val FIRST_SAVE_LEDGER = setOf(
            "metadata.edn",
            "document.md",
            "sample_code_.kt",
            "history_metrics.edn",
            "AGENTS.md",
            "README.md"
        )

        /**
         * And what a second save that genuinely changes a box adds.
         *
         * `metadata.edn` gains a backup because `checkDifference` passes `maxBackups = 10` for it;
         * `document.md` does not, because that call passes `createBackup = false`. The `_code_.kt`
         * sidecar is backed up too — `checkDifference(target, value.inline, maxBackups = 10)`.
         * `history_metrics.edn` gains one for the same reason `metadata.edn` does.
         *
         * The backup names carry an index, so this ledger is written as a prefix check rather than
         * a literal set; see [aSecondSaveThatChangesCodeAddsBackups].
         */
        private val SECOND_SAVE_LEDGER = FIRST_SAVE_LEDGER + setOf(
            "backups/",
            "backups/metadata_backup_00000.edn",
            "backups/sample_code__backup_00000.kt",
            "backups/history_metrics_backup_00000.edn"
        )

        /**
         * Names Field derives from a document, matched anywhere in a tracked path.
         *
         * Mirrors the `.gitignore` block added alongside this test. `document.md` and the
         * `AGENTS.md`/`README.md` pair are deliberately absent: they are derived under
         * `metadata_store/` but authored content everywhere else.
         */
        private val DERIVED_ARTEFACT = Regex(
            """(^|/)(operations\.yaml|history_metrics\.edn|transcript\.edn)$|/backups/|/history_screenshots/"""
        )

        /**
         * The spill already in this repository's history, as of 2026-09-05.
         *
         * All of it is `public/docs/assets/`, and `rectsl_gallery/` is a complete instance of the
         * bug: someone opened the gallery document, Field wrote five kinds of derived file beside
         * it including a four-window screenshot JPG, and it all went in with the commit. Pinned so
         * that a fourteenth entry fails rather than joining them.
         */
        private val KNOWN_COMMITTED_SPILL = sortedSetOf(
            "public/docs/assets/rectsl_gallery/backups/history_metrics_backup_00000.edn",
            "public/docs/assets/rectsl_gallery/backups/history_metrics_backup_00001.edn",
            "public/docs/assets/rectsl_gallery/backups/metadata_backup_00000.edn",
            "public/docs/assets/rectsl_gallery/backups/metadata_backup_00001.edn",
            "public/docs/assets/rectsl_gallery/backups/metadata_backup_00002.edn",
            "public/docs/assets/rectsl_gallery/history_metrics.edn",
            "public/docs/assets/rectsl_gallery/history_screenshots/four-window/v00001_1783801210737.jpg",
            "public/docs/assets/rectsl_gallery/operations.yaml",
            "public/docs/assets/rectsl_gallery/transcript.edn",
            "public/docs/assets/sound_path_map/history_metrics.edn",
            "public/docs/assets/sound_path_map/transcript.edn",
            "public/docs/assets/sound_render_path/history_metrics.edn",
            "public/docs/assets/sound_render_path/transcript.edn"
        )
    }
}
