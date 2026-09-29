package afield

import afield.logger.lprintln
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * One run of one box on one strand, and the owner of everything that run started.
 *
 * Box code registers things with subsystems that outlive it — an audio patch in the mix, a slider
 * callback, a Runloop hook, a module on a render target, a child process. Each of those used to be
 * released (or not) by its own mechanism, and four user-level events — stop, re-run, delete, close —
 * each reached a different subset. A run scope is the single owner instead: whatever the run
 * registers is [own]ed here, in order, and [close] releases it in reverse order.
 *
 * **Where scopes come from.** A launch (`launchAll`, `continueAll`, a batch launch) opens a scope on
 * its strand; running the whole box (`execute-all`) opens one on [BoxRunScopes.RUN_STRAND]. Once the
 * new run has *executed*, the strand's older scopes are closed ([BoxRunScopes.supersede]) — after,
 * not before, so a re-run replaces what the previous run drew rather than blanking it for the length
 * of a compile. Running a fragment adds to the box's current [BoxRunScopes.RUN_STRAND] scope, since
 * running one line does not undo the others. Everything else — another language's box, code a
 * launched loop runs later — lands in that ambient scope through [current]'s fallback.
 *
 * **What closes them.** A superseding run of the same strand, and stop: [stopTrackedJobs] closes
 * every scope the box has. A thing registered into a scope that is already closed — by a launch stop
 * has already invalidated, say — is released at once.
 *
 * **Finding the current scope.** Execution sets it for the thread ([element] for coroutines,
 * [withCurrent] for synchronous dispatch). Library code asks [current] (or [currentFor], given the
 * box) and calls [own]; the lifecycle ledger does that on every [LifecycleLedger.claim], which is how
 * audio patches, feeders, control callbacks, Runloop hooks and render modules end up here without
 * their call sites knowing.
 *
 * [job] is a [SupervisorJob] that [close] cancels: a parent for coroutines a run starts that should
 * die with it. It is deliberately not one of the box's [Box.jobs], which is what marks a box as
 * running.
 */
class RunScope internal constructor(
    val box: Box,
    val strand: String,
    val kind: String,
) : AutoCloseable {
    /** Opening order across the process; a strand's older scopes have smaller ids. */
    val id: Long = nextId.incrementAndGet()

    /** Cancelled by [close]; a parent for coroutines that belong to this run. */
    val job: CompletableJob = SupervisorJob()

    private val lock = Any()
    private val owned = ArrayList<AutoCloseable>()
    private var pruneAt = PRUNE_START
    private val closedFlag = AtomicBoolean(false)

    /** True once [close] has run; anything [own]ed after that is released immediately. */
    val isClosed: Boolean get() = closedFlag.get()

    /** A coroutine context element that makes this the [current] scope wherever the coroutine runs. */
    val element: CoroutineContext get() = threadCurrent.asContextElement(this)

    /**
     * Take ownership of [resource]: [close] will close it, newest first. Returns [resource]. If this
     * scope is already closed, [resource] is closed now instead.
     */
    fun <T : AutoCloseable> own(resource: T): T {
        val accepted = synchronized(lock) {
            if (closedFlag.get()) {
                false
            } else {
                owned.add(resource)
                if (owned.size >= pruneAt) {
                    owned.removeAll { it is Ownership && !it.isLive }
                    pruneAt = maxOf(PRUNE_START, owned.size * 2)
                }
                true
            }
        }
        if (!accepted) closeQuietly(resource)
        return resource
    }

    /** Own a release action; returns the [AutoCloseable] that runs it, which [disown] accepts. */
    fun own(release: () -> Unit): AutoCloseable = own(AutoCloseable { release() })

    /** Cancel [job] when this scope closes. Returns [job]. */
    fun ownJob(job: Job): Job {
        own(AutoCloseable { job.cancel() })
        return job
    }

    /** Stop owning [resource] without closing it — for something that has ended on its own. */
    fun disown(resource: AutoCloseable): Boolean = synchronized(lock) { owned.remove(resource) }

    /** How many owned things are still live (ledger ownerships the subsystem still holds, and the rest). */
    val liveCount: Int
        get() = synchronized(lock) { owned.count { it !is Ownership || it.isLive } }

    /** Release everything owned, newest first, and cancel [job]. Idempotent; one failure does not strand the rest. */
    override fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        val toClose = synchronized(lock) { owned.toList().also { owned.clear() } }
        job.cancel()
        toClose.asReversed().forEach(::closeQuietly)
        box.runScopes.forget(this)
    }

    private fun closeQuietly(resource: AutoCloseable) {
        runCatching { resource.close() }.onFailure {
            lprintln("warning,box", "closing $resource for box ${box.id} (strand $strand) threw: $it")
        }
    }

    override fun toString(): String = "RunScope(#$id ${box.id} strand=$strand kind=$kind closed=$isClosed)"

    companion object {
        private const val PRUNE_START = 64
        private val nextId = AtomicLong()
        private val threadCurrent = ThreadLocal<RunScope?>()

        /**
         * The scope whatever is executing on this thread belongs to: the one execution installed, or
         * failing that the ambient [BoxRunScopes.RUN_STRAND] scope of the executing box (`Box.context`).
         * Null when no box is executing, and for a deleted box.
         */
        fun current(): RunScope? = threadCurrent.get() ?: Box.context.get()?.runScopes?.ambient()

        /** The box whose run is executing on this thread: the installed scope's box, else `Box.context`. */
        fun executingBox(): Box? = threadCurrent.get()?.box ?: Box.context.get()

        /** [current], but for [box] specifically: its installed scope if that is [box]'s, else [box]'s ambient one. */
        fun currentFor(box: Box?): RunScope? {
            if (box == null) return null
            val installed = threadCurrent.get()
            if (installed != null && installed.box === box) return installed
            return box.runScopes.ambient()
        }

        /** Run [block] with [scope] as this thread's [current] scope. */
        fun <T> withCurrent(scope: RunScope?, block: () -> T): T {
            val previous = threadCurrent.get()
            threadCurrent.set(scope)
            return try {
                block()
            } finally {
                threadCurrent.set(previous)
            }
        }
    }
}

/**
 * A box's open [RunScope]s: at most a few per strand, the newest of which is the strand's current
 * run. See [RunScope] for when they open and close.
 */
class BoxRunScopes internal constructor(private val box: Box) {
    private val open = CopyOnWriteArrayList<RunScope>()

    /** Open a new scope for a run of [strand]; older scopes of the strand stay open until [supersede]. */
    fun begin(strand: String, kind: String): RunScope = RunScope(box, strand, kind).also { open.add(it) }

    /** Close every scope of [newer]'s strand that was opened before it: [newer] has replaced them. */
    fun supersede(newer: RunScope) {
        open.filter { it !== newer && it.strand == newer.strand && it.id < newer.id }.forEach { it.close() }
    }

    /**
     * The newest open [RUN_STRAND] scope, opening one if there is none — where a fragment run, another
     * language's execution, and code with no installed scope register. Null for a deleted box, so a
     * straggler registering after deletion owns nothing (the ledger still attributes it).
     */
    fun ambient(): RunScope? {
        if (box.getPropertyRaw("_tombstone").isTrue()) return null
        open.lastOrNull { it.strand == RUN_STRAND && !it.isClosed }?.let { return it }
        synchronized(this) {
            open.lastOrNull { it.strand == RUN_STRAND && !it.isClosed }?.let { return it }
            return begin(RUN_STRAND, "ambient")
        }
    }

    /** Close every open scope, newest first; returns how many there were. Stop's part of the teardown. */
    fun closeAll(): Int {
        val scopes = open.toList().asReversed()
        scopes.forEach { it.close() }
        return scopes.size
    }

    /** The open scopes, oldest first. */
    fun snapshot(): List<RunScope> = open.toList()

    /** How many owned things across all open scopes are still live. */
    val liveCount: Int get() = open.sumOf { it.liveCount }

    internal fun forget(scope: RunScope) {
        open.remove(scope)
    }

    companion object {
        /** The strand of whole-box runs and fragments — everything that is not a launch. */
        const val RUN_STRAND = "__run__"
    }
}
