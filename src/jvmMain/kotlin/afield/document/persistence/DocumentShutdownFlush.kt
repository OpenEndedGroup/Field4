package afield.document.persistence

import afield.app.diagnostics.logging.lprintln
import afield.app.server.InterruptSignal
import afield.app.server.Server
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.document.workspace.metadata
import afield.runtime.execution.plus
import afield.runtime.lifecycle.loop
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.notation.with
import afield.ui.output.md

import afield.app.server.documents
import afield.app.server.SessionInformation
import afield.audio.synth.*
import afield.document.properties.PropertyDependencies
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import java.util.concurrent.TimeUnit

/**
 * Saves pending document edits when Field shuts down, so a Ctrl-C does not lose the last few seconds of work.
 */

/**
 * Saves, on the way out, the document edits the autosave loop has not reached yet.
 *
 * The autosave loop in `Server.server()` visits documents every ~5 s, so an edit made in the last
 * few seconds before Field stops was simply lost — on SIGTERM always, and on SIGINT once Field
 * started honouring it (see [InterruptSignal]). Measured on 2026-09-29: a box created and its code
 * set through the backdoor, then SIGINT as soon as the `set` was acknowledged, left the document
 * folder with no `metadata.edn` and no sidecar in 3 of 3 trials; with the SIGINT 12 s later it was
 * all there. `dev_docs/SIGINT_SHUTDOWN.md` has the numbers.
 *
 * This is the same save the loop would have made — same predicate (`writesToDisk`, not `loading`,
 * something pending), same box set — run inline on a shutdown-hook thread, because the misc pool
 * the loop normally hands the write to is being shut down by its own hook at the same moment.
 *
 * Bounded: a save that wedges (a stuck lock, a slow disk) must not turn a Ctrl-C back into a
 * process that will not die, so the flush gets [BUDGET_MS] and the JVM exits regardless. A second
 * SIGINT halts at once in any case.
 */
object DocumentShutdownFlush {

    const val BUDGET_MS = 4_000L

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread({ flushWithinBudget() }, "afield-document-flush"))
        }
    }

    private fun flushWithinBudget() {
        val worker = Thread({ flushPendingDocumentSaves() }, "afield-document-flush-worker").apply { isDaemon = true }
        worker.start()
        worker.join(BUDGET_MS)
        if (worker.isAlive) System.err.println("afield: document flush still running after ${BUDGET_MS} ms; exiting anyway")
    }

    /** Saves every document with pending changes, on this thread. Returns how many it saved. */
    fun flushPendingDocumentSaves(): Int {
        var saved = 0
        documents.forEach { (_, info) ->
            runCatching { PropertyDependencies.flush(info, force = true) }
            if (saveIfPending(info, inline = true, reason = "shutdown")) saved++
        }
        return saved
    }

    /**
     * The autosave loop's save of [info], if it needs one: `writesToDisk`, not `loading`, and either
     * something marked pending or a [PersistedFingerprint] that differs from the last one matching
     * disk. [inline] runs the write on this thread; otherwise it goes to the misc pool. [log] prints
     * a line on stderr (the shutdown and detach saves do; the 5 s loop does not). Returns whether a
     * save was made.
     */
    fun saveIfPending(info: SessionInformation, inline: Boolean, reason: String, log: Boolean = true): Boolean {
        if (!info.writesToDisk || info.loading) return false
        // A fingerprint that cannot be taken (a store mutated under us on the shutdown thread) counts as changed.
        val entries = runCatching { PersistedFingerprint.entries(info) }.getOrNull()
        val fingerprint = entries?.values?.sum()
        val unmarked = info.savedFingerprint != null && fingerprint != info.savedFingerprint
        if (info.pendingSidecarSaves.isEmpty() && !unmarked) return false
        if (info.savedFingerprint != null && fingerprint == info.savedFingerprint && marksAreNoOps(info)) {
            // N1020-7: every marked edit put back a value disk already holds (a frame re-sent
            // unchanged, a rename to the same name); rewriting metadata.edn and a backup is churn.
            info.clearSavedSidecars(info.pendingSidecarSnapshot())
            noOpSkips.incrementAndGet()
            return false
        }
        reportUnmarked(info, entries, reason)
        if (unmarked && log) System.err.println("afield: ${info.filename} changed without a marked edit; saving on $reason")
        val started = System.nanoTime()
        return try {
            val allBoxes = info.boxes.values.toMutableSet()
            allBoxes.add(info.root)
            val pendingSnapshot = info.pendingSidecarSnapshot()
            // The snapshot below is taken on this thread before save returns, so this is what it writes.
            PersistedFingerprint.record(info, entries)
            SavingContext().save(info.filename, allBoxes, inline = inline, onComplete = {
                info.clearSavedSidecars(pendingSnapshot)
            })
            if (log) System.err.println(
                "afield: saved ${info.filename} on $reason (${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms)"
            )
            true
        } catch (t: Throwable) {
            PersistedFingerprint.record(info, null)
            if (log) System.err.println("afield: could not save ${info.filename} on $reason: $t")
            else lprintln("error,server", "trouble saving ${info.filename}: $t")
            false
        }
    }

    /** How many saves [saveIfPending] skipped because the marked edits changed nothing; for tests and measurement. */
    val noOpSkips = java.util.concurrent.atomic.AtomicLong()

    /**
     * True when the fingerprint can vouch for every marked property: its current value hashes by
     * content, so an unchanged hash means an unchanged value. A value hashed by identity (a class
     * with no `hashCode` of its own) could have changed in place under the same hash, so it saves.
     */
    private fun marksAreNoOps(info: SessionInformation): Boolean = info.pendingSidecarSaves.all { (uid, property) ->
        val box = info.boxes[uid] ?: info.root.takeIf { it.id == uid } ?: return@all true
        PersistedFingerprint.hashesByContent(box.properties[property])
    }

    /** Channel of the unmarked-edit tripwire's lines; one per `(box, property)` a mutator changed without marking. */
    const val UNMARKED_CHANNEL = "warning,persistence"

    /** The last tripwire report, for tests: `(box id, property)` pairs. */
    @Volatile
    var lastUnmarked: List<Pair<String, String>> = emptyList()
        internal set

    private fun reportUnmarked(info: SessionInformation, entries: Map<Pair<String, String>, Long>?, reason: String) {
        val saved = info.savedFingerprintEntries ?: return
        if (entries == null) return
        val unmarked = PersistedFingerprint.unmarkedChanges(saved, entries, info.pendingSidecarSaves.toSet())
        lastUnmarked = unmarked
        for ((uid, property) in unmarked) {
            val box = info.boxes[uid] ?: info.root.takeIf { it.id == uid }
            val label = box?.let { runCatching { it.name }.getOrNull() } ?: "?"
            lprintln("warning,persistence", "unmarked edit: box $uid ($label) property '$property' changed without notePersistentChange; saved on $reason")
        }
    }
}
