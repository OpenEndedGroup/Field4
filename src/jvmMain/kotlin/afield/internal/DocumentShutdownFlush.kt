package afield

import java.util.concurrent.TimeUnit

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
            if (!info.writesToDisk || info.loading || info.pendingSidecarSaves.isEmpty()) return@forEach
            val started = System.nanoTime()
            try {
                val allBoxes = info.boxes.values.toMutableSet()
                allBoxes.add(info.root)
                val pendingSnapshot = info.pendingSidecarSnapshot()
                SavingContext().save(info.filename, allBoxes, inline = true, onComplete = {
                    info.clearSavedSidecars(pendingSnapshot)
                })
                saved++
                System.err.println(
                    "afield: saved ${info.filename} on shutdown (${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms)"
                )
            } catch (t: Throwable) {
                System.err.println("afield: could not save ${info.filename} on shutdown: $t")
            }
        }
        return saved
    }
}
