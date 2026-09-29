package afield

import afield.logger.lprintln
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * Which of the three box events — stop, re-run, retire — takes a registration back out.
 *
 * | policy | kinds | released by |
 * |---|---|---|
 * | [KEYED] | render modules (every `putOwned` map), Runloop `preRun`/`postRun` hooks, anything claimed into a named slot | retire only: box delete, tombstone, document close |
 * | [ACCUMULATING] | control callbacks (a fresh UUID each run) | the next run of the same strand, and retire |
 * | [RUNNING] | audio patches, patSynth feeders, `streamProcess` children, coroutine jobs | stop, the next run of the strand, and retire |
 *
 * A keyed registration is replaced by the next registration under the same key (see [Ownership]):
 * that is Field's `Map<String, …>` idiom, where the key is chosen so a re-run replaces what the last
 * run put there, and where launching something that goes on after a stop is the point. An
 * accumulating registration has no key to be replaced by, so without the next run releasing it
 * every run would add one. Only what is *running* stops with stop.
 */
enum class ReleasePolicy {
    KEYED, ACCUMULATING, RUNNING;

    companion object {
        /** The policy of a ledger [kind]; an unlisted kind is [KEYED] if it claims a slot, else [RUNNING]. */
        fun forKind(kind: String, hasSlot: Boolean = true): ReleasePolicy = when (kind) {
            LifecycleKinds.CONTROL_CALLBACK -> ACCUMULATING
            LifecycleKinds.AUDIO_PATCH, LifecycleKinds.PATSYNTH_FEEDER, LifecycleKinds.STREAM_PROCESS -> RUNNING
            LifecycleKinds.RENDER_MODULE, LifecycleKinds.RUNLOOP_PRE_RUN, LifecycleKinds.RUNLOOP_POST_RUN -> KEYED
            else -> if (hasSlot) KEYED else RUNNING
        }

        internal val SUPERSEDED: Set<ReleasePolicy> = EnumSet.of(ACCUMULATING, RUNNING)
        internal val RETIRED: Set<ReleasePolicy> = EnumSet.allOf(ReleasePolicy::class.java)
    }
}

/**
 * Something a [RunScope] owns, with the [policy] that says which event releases it. [onRelease]
 * listeners run once, after [resource] is closed by a scope — how `once` learns that what its cached
 * value holds is gone.
 */
class OwnedEntry internal constructor(val resource: AutoCloseable, val policy: ReleasePolicy) {
    private val released = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** True once a scope has released [resource]. */
    val isReleased: Boolean get() = released.get()

    /** Not yet released, and (for an [Ownership]) still held by its subsystem. */
    val isLive: Boolean get() = !released.get() && (resource !is Ownership || resource.isLive)

    /** Run [listener] when this is released; at once if it already has been. */
    fun onRelease(listener: () -> Unit) {
        listeners.add(listener)
        if (released.get() && listeners.remove(listener)) listener()
    }

    internal fun release(onFailure: (Throwable) -> Unit) {
        if (!released.compareAndSet(false, true)) return
        runCatching { resource.close() }.onFailure(onFailure)
        val toRun = listeners.toList()
        listeners.clear()
        toRun.forEach { runCatching(it).onFailure(onFailure) }
    }

    override fun toString(): String = "$policy $resource"
}

/**
 * One run of one box on one strand, and the owner of everything that run started.
 *
 * Box code registers things with subsystems that outlive it — an audio patch in the mix, a slider
 * callback, a Runloop hook, a module on a render target, a child process. A run scope is the single
 * owner of those: whatever the run registers is [own]ed here under a [ReleasePolicy], and each of the
 * three events that end things releases the policies it is responsible for, newest first.
 *
 * **Where scopes come from.** A launch (`launchAll`, `continueAll`, a batch launch) opens a scope on
 * its strand; running the whole box (`execute-all`) opens one on [BoxRunScopes.RUN_STRAND]. Running a
 * fragment adds to the box's current [BoxRunScopes.RUN_STRAND] scope, since running one line does not
 * undo the others. Everything else — another language's box, code a launched loop runs later — lands
 * in that ambient scope through [current]'s fallback. Each box also has one [BoxRunScopes.parent]
 * scope, which no run supersedes: `once` values register there, and library code that makes
 * something meant to outlive the run that asked for it can too.
 *
 * **What the three events do.**
 * - *Supersede* — once a new run of the strand has *executed* (after, not before, so a re-run
 *   replaces what the previous run drew rather than blanking it for the length of a compile), each
 *   older scope of the strand releases its [ReleasePolicy.ACCUMULATING] and [ReleasePolicy.RUNNING]
 *   things and closes. What it held under [ReleasePolicy.KEYED] moves to the box's parent scope and
 *   stays until its key is reused or the box retires: the key, not the run, decides.
 * - *Stop* ([stopTrackedJobs]) releases the [ReleasePolicy.RUNNING] things of every scope the box
 *   has, the parent included, and cancels each scope's [job]. The run scopes stay open holding the
 *   rest — a stopped launch's slider still answers, and the next launch of that strand still
 *   supersedes it, which is why they are not merged into the parent. A stopped run scope remembers
 *   it was stopped: a running thing registered into it afterwards (by a straggler of the launch the
 *   stop invalidated) is released at once, and [BoxRunScopes.ambient] opens a fresh scope rather than
 *   hand it out.
 * - *Retire* ([close]; `retireBoxes`, which delete, tombstone and document close go through)
 *   releases everything.
 *
 * Something registered into a scope that has already ended is treated as though it had been there
 * when it ended: released at once if that event releases its policy, kept otherwise — a superseded
 * scope passes a keyed straggler on to the parent.
 *
 * **Finding the current scope.** Execution sets it for the thread ([element] for coroutines,
 * [withCurrent] for synchronous dispatch). Library code asks [current] (or [currentFor], given the
 * box) and calls [own]; the lifecycle ledger does that on every [LifecycleLedger.claim], which is how
 * audio patches, feeders, control callbacks, Runloop hooks and render modules end up here without
 * their call sites knowing. Their policy comes from their kind ([ReleasePolicy.forKind]) unless the
 * claim says otherwise.
 *
 * [job] is a [SupervisorJob] that stop, supersede and [close] cancel: a parent for coroutines a run
 * starts that should die with it. It is deliberately not one of the box's [Box.jobs], which is what
 * marks a box as running.
 */
class RunScope internal constructor(
    val box: Box,
    val strand: String,
    val kind: String,
) : AutoCloseable {
    /** Opening order across the process; a strand's older scopes have smaller ids. */
    val id: Long = nextId.incrementAndGet()

    /** Cancelled by stop, supersede and [close]; a parent for coroutines that belong to this run. */
    val job: CompletableJob = SupervisorJob()

    private val lock = Any()
    private val owned = ArrayList<OwnedEntry>()
    private var pruneAt = PRUNE_START
    private val closedFlag = AtomicBoolean(false)

    /** Policies this scope has released for good: one of these arriving later is released at once. */
    private val ended: MutableSet<ReleasePolicy> = EnumSet.noneOf(ReleasePolicy::class.java)

    /** True once this scope has been superseded or retired; it owns nothing after that. */
    val isClosed: Boolean get() = closedFlag.get()

    /** True once a stop (or a close) has reached this run scope. The parent scope never reports stopped. */
    val isStopped: Boolean get() = synchronized(lock) { ReleasePolicy.RUNNING in ended }

    /** A coroutine context element that makes this the [current] scope wherever the coroutine runs. */
    val element: CoroutineContext get() = threadCurrent.asContextElement(this)

    /**
     * Take ownership of [resource] under [policy] — by default an [Ownership]'s own, and
     * [ReleasePolicy.RUNNING] for anything else. Returns [resource]. If this scope has already ended,
     * [resource] is released now when the event that ended it releases [policy], and kept otherwise.
     */
    fun <T : AutoCloseable> own(resource: T, policy: ReleasePolicy = defaultPolicy(resource)): T {
        adopt(OwnedEntry(resource, policy))
        return resource
    }

    private fun adopt(entry: OwnedEntry) {
        val verdict = synchronized(lock) {
            when {
                entry.policy in ended -> Verdict.RELEASE
                closedFlag.get() -> Verdict.FORWARD
                else -> {
                    owned.add(entry)
                    if (owned.size >= pruneAt) {
                        owned.removeAll { !it.isLive }
                        pruneAt = maxOf(PRUNE_START, owned.size * 2)
                    }
                    Verdict.KEEP
                }
            }
        }
        when (verdict) {
            Verdict.KEEP -> collectors.get()?.lastOrNull()?.add(entry)
            Verdict.RELEASE -> {
                collectors.get()?.lastOrNull()?.add(entry)
                releaseQuietly(entry)
            }
            Verdict.FORWARD -> box.runScopes.parent.adopt(entry)
        }
    }

    /** Own a release action under [policy]; returns the [AutoCloseable] that runs it, which [disown] accepts. */
    fun own(policy: ReleasePolicy = ReleasePolicy.RUNNING, release: () -> Unit): AutoCloseable =
        own(AutoCloseable { release() }, policy)

    /** Cancel [job] when stop, supersede or retire reaches this scope. Returns [job]. */
    fun ownJob(job: Job): Job {
        own(AutoCloseable { job.cancel() }, ReleasePolicy.RUNNING)
        return job
    }

    /** Stop owning [resource] without closing it — for something that has ended on its own. */
    fun disown(resource: AutoCloseable): Boolean =
        synchronized(lock) { owned.removeAll { it.resource === resource } }

    /** How many owned things are still live (ledger ownerships the subsystem still holds, and the rest). */
    val liveCount: Int get() = synchronized(lock) { owned.count { it.isLive } }

    /** How many owned things under [policy] are still live. */
    fun liveCount(policy: ReleasePolicy): Int =
        synchronized(lock) { owned.count { it.policy == policy && it.isLive } }

    /**
     * Stop's part: release every [ReleasePolicy.RUNNING] thing, newest first, and cancel [job]; the
     * rest stays owned. With [latch] — every run scope — a running thing registered afterwards is
     * released at once. The parent does not latch, since the next run's `once` registers there.
     */
    internal fun stop(latch: Boolean = true) {
        val toRelease = synchronized(lock) {
            if (latch) ended.add(ReleasePolicy.RUNNING)
            val running = owned.filter { it.policy == ReleasePolicy.RUNNING }
            owned.removeAll { it.policy == ReleasePolicy.RUNNING }
            running
        }
        job.cancel()
        toRelease.asReversed().forEach(::releaseQuietly)
    }

    /**
     * Supersede's part: release the [ReleasePolicy.ACCUMULATING] and [ReleasePolicy.RUNNING] things,
     * newest first, hand the [ReleasePolicy.KEYED] ones to the box's parent scope, and close.
     */
    internal fun supersede() {
        if (!closedFlag.compareAndSet(false, true)) return
        val (keyed, toRelease) = synchronized(lock) {
            ended.addAll(ReleasePolicy.SUPERSEDED)
            val split = owned.partition { it.policy == ReleasePolicy.KEYED }
            owned.clear()
            split
        }
        job.cancel()
        toRelease.asReversed().forEach(::releaseQuietly)
        box.runScopes.forget(this)
        if (keyed.isNotEmpty()) {
            val parent = box.runScopes.parent
            keyed.filter { it.isLive }.forEach { parent.adopt(it) }
        }
    }

    /** Retire: release everything owned, newest first, and cancel [job]. Idempotent; one failure does not strand the rest. */
    override fun close() {
        val toRelease = synchronized(lock) {
            ended.addAll(ReleasePolicy.RETIRED)
            owned.toList().also { owned.clear() }
        }
        val first = closedFlag.compareAndSet(false, true)
        job.cancel()
        toRelease.asReversed().forEach(::releaseQuietly)
        if (first) box.runScopes.forget(this)
    }

    private fun releaseQuietly(entry: OwnedEntry) {
        entry.release { failure ->
            lprintln("warning,box", "releasing $entry for box ${box.id} (strand $strand) threw: $failure")
        }
    }

    override fun toString(): String =
        "RunScope(#$id ${box.id} strand=$strand kind=$kind closed=$isClosed stopped=$isStopped)"

    private enum class Verdict { KEEP, RELEASE, FORWARD }

    companion object {
        private const val PRUNE_START = 64
        private val nextId = AtomicLong()
        private val threadCurrent = ThreadLocal<RunScope?>()
        private val collectors = ThreadLocal<ArrayList<MutableList<OwnedEntry>>?>()

        private fun defaultPolicy(resource: AutoCloseable): ReleasePolicy =
            (resource as? Ownership)?.policy ?: ReleasePolicy.RUNNING

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

        /**
         * Run [block] and return, with its result, every [OwnedEntry] a scope took ownership of on
         * this thread while it ran — how `once` knows what a cached value depends on. Registrations
         * made on another thread (an `inMain { }` inside [block]) are not seen.
         */
        fun <T> collectingOwned(block: () -> T): Pair<T, List<OwnedEntry>> {
            val stack = collectors.get() ?: ArrayList<MutableList<OwnedEntry>>().also { collectors.set(it) }
            val mine = ArrayList<OwnedEntry>()
            stack.add(mine)
            try {
                return block() to mine
            } finally {
                stack.removeAt(stack.lastIndex)
                // An enclosing collection depends on whatever this one saw, too.
                stack.lastOrNull()?.addAll(mine)
            }
        }
    }
}

/** Run [block] with this scope as the thread's current [RunScope], so what it registers is owned here. */
fun <T> RunScope.withCurrent(block: () -> T): T = RunScope.withCurrent(this, block)

/**
 * A box's [RunScope]s: at most a few open per strand, the newest of which is the strand's current
 * run, plus the one [parent] scope that no run supersedes. See [RunScope] for when they open and close.
 */
class BoxRunScopes internal constructor(private val box: Box) {
    private val open = CopyOnWriteArrayList<RunScope>()

    @Volatile
    private var parentScope: RunScope? = null

    /** Open a new scope for a run of [strand]; older scopes of the strand stay open until [supersede]. */
    fun begin(strand: String, kind: String): RunScope = RunScope(box, strand, kind).also { open.add(it) }

    /** Supersede every scope of [newer]'s strand that was opened before it: [newer] has replaced them. */
    fun supersede(newer: RunScope) {
        open.filter { it !== newer && it.strand == newer.strand && it.id < newer.id }.forEach { it.supersede() }
    }

    /**
     * The box-level scope no re-run supersedes. What it owns lasts until a stop, for
     * [ReleasePolicy.RUNNING] things, or until the box retires, for everything else. A superseded
     * run's [ReleasePolicy.KEYED] registrations move here, and `once.x { … }` computes with it
     * current, so a value made once is not taken apart by the next run.
     *
     * Library code that makes something meant to outlive the run that asked for it registers here:
     * ```
     * box.runScopes.parent.withCurrent { box.slider("gain", 0.0, 1.0) { gain = it } }
     * ```
     * Once the box has retired this is a fresh, open scope again on the next ask — unless the box
     * was deleted, when it stays the closed one, so a straggler's registration is released at once.
     */
    val parent: RunScope
        get() {
            parentScope?.let { if (!it.isClosed || isTombstoned()) return it }
            synchronized(this) {
                parentScope?.let { if (!it.isClosed || isTombstoned()) return it }
                val fresh = RunScope(box, PARENT_STRAND, "parent")
                if (isTombstoned()) fresh.close()
                parentScope = fresh
                return fresh
            }
        }

    private fun isTombstoned() = box.getPropertyRaw("_tombstone").isTrue()

    /**
     * The newest open [RUN_STRAND] scope that no stop has reached, opening one if there is none —
     * where a fragment run, another language's execution, and code with no installed scope register.
     * Null for a deleted box, so a straggler registering after deletion owns nothing (the ledger still
     * attributes it).
     */
    fun ambient(): RunScope? {
        if (isTombstoned()) return null
        open.lastOrNull { it.strand == RUN_STRAND && !it.isClosed && !it.isStopped }?.let { return it }
        synchronized(this) {
            open.lastOrNull { it.strand == RUN_STRAND && !it.isClosed && !it.isStopped }?.let { return it }
            return begin(RUN_STRAND, "ambient")
        }
    }

    /**
     * Stop's part of the teardown: every scope, the parent included, releases what is
     * [ReleasePolicy.RUNNING]. Returns how many run scopes there were.
     */
    fun stopAll(): Int {
        val scopes = open.toList().asReversed()
        scopes.forEach { it.stop() }
        parentScope?.stop(latch = false)
        return scopes.size
    }

    /** Retire's part: close every scope, the parent last, releasing everything. Returns how many run scopes there were. */
    fun closeAll(): Int {
        val scopes = open.toList().asReversed()
        scopes.forEach { it.close() }
        parentScope?.close()
        return scopes.size
    }

    /** The open run scopes, oldest first, then the parent if it is open. */
    fun snapshot(): List<RunScope> = open.toList() + listOfNotNull(parentScope?.takeIf { !it.isClosed })

    /** How many owned things across all open scopes are still live. */
    val liveCount: Int get() = snapshot().sumOf { it.liveCount }

    /** How many [ReleasePolicy.RUNNING] things across all open scopes are still live: what a stop would release. */
    val liveRunningCount: Int get() = snapshot().sumOf { it.liveCount(ReleasePolicy.RUNNING) }

    internal fun forget(scope: RunScope) {
        open.remove(scope)
    }

    companion object {
        /** The strand of whole-box runs and fragments — everything that is not a launch. */
        const val RUN_STRAND = "__run__"

        /** The strand name of [parent], which no run is on. */
        const val PARENT_STRAND = "__box__"
    }
}
