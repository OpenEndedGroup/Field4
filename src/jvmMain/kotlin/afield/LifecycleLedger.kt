package afield

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One live thing some box registered with a subsystem that outlives it: an audio patch, a control
 * callback, a Runloop hook, a module added to a render target, a feeder thread.
 *
 * It is also the handle that takes the thing back out. [close] runs the subsystem's release exactly
 * once — unless a later registration has since taken the same [slot], in which case the release is
 * skipped, because what occupies the slot is now somebody else's. That is what lets two runs of a
 * box register under one name (a render module called `"boo"`, a Runloop hook key) without the
 * older run's teardown removing what the newer one just installed.
 *
 * [ownerId] is the box that was executing when the registration happened, [strand] the strand of
 * the run that owned it, if any. [policy] says which box event — stop, re-run, retire — its owning
 * [RunScope] releases it on; see [ReleasePolicy].
 */
class Ownership internal constructor(
    val kind: String,
    val key: String,
    val ownerId: String?,
    val strand: String?,
    val policy: ReleasePolicy,
    private val slot: Any?,
    private val live: (() -> Boolean)?,
    private val release: (() -> Unit)?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** False once closed, superseded, or once the subsystem itself reports the thing gone. */
    val isLive: Boolean
        get() = !closed.get() && (live?.let { runCatching(it).getOrDefault(false) } ?: true)

    /** Take the thing back out of its subsystem, unless a newer registration now holds its slot. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        LifecycleLedger.forget(this)
        val stillOurs = slot == null || LifecycleLedger.releaseSlot(slot, this)
        if (stillOurs) release?.invoke()
    }

    /** A newer registration took the slot: forget this one without releasing anything. */
    internal fun supersede() {
        if (closed.compareAndSet(false, true)) LifecycleLedger.forget(this)
    }

    override fun toString(): String = "Ownership($kind/$policy '$key' owner=$ownerId strand=$strand live=$isLive)"
}

/**
 * Every live [Ownership] in the process, so a box's leftovers can be counted.
 *
 * Each subsystem that holds something on a box's behalf records it here with [claim] when it is
 * registered and closes the returned [Ownership] when it is released. Attribution is the box that
 * was executing at registration (`Box.context`), which is cheap and is the only moment the owner is
 * knowable: an audio patch registered from a return-value handler, or a slider callback registered
 * from box code, carries no reference to its box afterwards.
 *
 * [lifecycleCensus] turns the ledger into per-box counts; `lifecycle-census` exposes that over the backdoor.
 */
object LifecycleLedger {
    private val live: MutableSet<Ownership> = ConcurrentHashMap.newKeySet()
    private val slots = ConcurrentHashMap<Any, Ownership>()

    /**
     * Record that [owner] (by default the executing box) now holds a [kind] thing called [key].
     *
     * [slot] identifies what the thing occupies — `(map, key)` for a keyed registry — so that a later
     * claim on the same slot supersedes this one. [live], when given, is asked at census time whether
     * the subsystem still has it (a finished Runloop sequence, a module removed by hand); [release]
     * is what [Ownership.close] runs to take it out.
     *
     * The ownership is also handed to [scope] — by default the [RunScope] the executing run belongs
     * to — which releases it on the events [policy] names: by default the kind's
     * ([ReleasePolicy.forKind]), so a render module or Runloop hook lasts until the box retires, a
     * control callback until the strand's next run, and an audio patch until stop. Pass
     * `scope = null` for something the box does not own, only caused.
     */
    fun claim(
        kind: String,
        key: String,
        slot: Any? = null,
        owner: Box? = RunScope.executingBox(),
        scope: RunScope? = RunScope.currentFor(owner),
        live: (() -> Boolean)? = null,
        policy: ReleasePolicy = ReleasePolicy.forKind(kind, hasSlot = slot != null),
        release: (() -> Unit)? = null,
    ): Ownership {
        val ownership = Ownership(kind, key, owner?.id, scope?.strand, policy, slot, live, release)
        this.live.add(ownership)
        if (slot != null) slots.put(slot, ownership)?.supersede()
        // Last, so a scope that has already ended releases this at once if the event that ended it
        // releases [policy], rather than keeping it.
        scope?.own(ownership)
        return ownership
    }

    internal fun releaseSlot(slot: Any, ownership: Ownership): Boolean = slots.remove(slot, ownership)

    internal fun forget(ownership: Ownership) {
        live.remove(ownership)
    }

    /** Every ownership still live, pruning the ones whose subsystem has dropped them on its own. */
    fun entries(): List<Ownership> {
        val (alive, dead) = live.toList().partition { it.isLive }
        dead.forEach { it.supersede() }
        return alive
    }
}

/**
 * A slot for [LifecycleLedger.claim]: [key] within the container [owner], compared by the
 * container's *identity*. A `Pair(map, key)` would not do, since a map's `equals` and `hashCode` are
 * over its contents and change as it is written.
 */
class LedgerSlot(val owner: Any, val key: Any?) {
    override fun equals(other: Any?): Boolean = other is LedgerSlot && other.owner === owner && other.key == key
    override fun hashCode(): Int = System.identityHashCode(owner) * 31 + (key?.hashCode() ?: 0)
}

/** Kind names used in the ledger, so the census and its smoke agree on spelling. */
object LifecycleKinds {
    const val AUDIO_PATCH = "audio-patch"
    const val PATSYNTH_FEEDER = "patsynth-feeder"
    const val CONTROL_CALLBACK = "control-callback"
    const val RUNLOOP_PRE_RUN = "runloop-preRun"
    const val RUNLOOP_POST_RUN = "runloop-postRun"
    const val RENDER_MODULE = "render-module"
    const val STREAM_PROCESS = "stream-process"
}

/**
 * A registry of UI control callbacks — sliders, checkboxes, colour pickers — keyed by the id the
 * browser posts back, whose every registration is an [Ownership] in the [LifecycleLedger].
 *
 * The maps these replace were process-wide `ConcurrentHashMap`s that only ever grew: a control gets
 * a fresh UUID each time its box runs, so every run added a callback, and each one pinned the
 * snippet classloader that compiled it.
 */
class OwnedCallbackRegistry<T : Any>(private val name: String) {
    private val callbacks = ConcurrentHashMap<String, T>()

    /**
     * Install [callback] under [id], recorded against the executing box; returns its ownership.
     *
     * By default the executing run owns it as [ReleasePolicy.ACCUMULATING]: the strand's next run
     * removes it (after that run has registered its own, so a re-run leaves no gap), and so does the
     * box's retirement, but a stop does not — a slider made by a launch that has already returned
     * goes on working. [owned] `false` is for
     * library code that caches a control across runs (a study viewer's action id, made once) and
     * removes it itself; it is still attributed, but no run takes it out.
     */
    fun register(id: String, callback: T, owned: Boolean = true): Ownership {
        callbacks[id] = callback
        val owner = RunScope.executingBox()
        return LifecycleLedger.claim(
            LifecycleKinds.CONTROL_CALLBACK,
            "$name:$id",
            slot = LedgerSlot(this, id),
            owner = owner,
            scope = if (owned) RunScope.currentFor(owner) else null,
            live = { callbacks[id] === callback },
            release = { callbacks.remove(id, callback) },
        )
    }

    /** Remove whatever is registered under [id]. */
    fun unregister(id: String) {
        callbacks.remove(id)
    }

    /** The callback registered under [id], if any. */
    operator fun get(id: String): T? = callbacks[id]
}

/**
 * What each box of [boxes] still holds: jobs, running strands, stop hooks, ledger ownerships by kind
 * and by [ReleasePolicy], and whether its K2 engine is live — the `lifecycle-census` backdoor
 * command's answer. `policies` maps every kind present (and every known kind) to its policy.
 *
 * `liveTotal` sums everything except the engine, so "a retired box leaves nothing behind" is
 * `liveTotal == 0`. `liveRunning` counts only what a stop releases — jobs, running strands, stop
 * hooks and [ReleasePolicy.RUNNING] ownerships — so "a stopped box is not running anything" is
 * `liveRunning == 0`, while its keyed and accumulating registrations stay. Ownerships whose box is
 * not in [boxes] are reported under `unattributed` when [includeUnattributed] is set, which is how a
 * registration made with no box executing shows up.
 */
fun lifecycleCensus(boxes: Collection<Box>, includeUnattributed: Boolean = true): JsonObject {
    val entries = LifecycleLedger.entries()
    val byOwner = entries.groupBy { it.ownerId }
    val ids = boxes.map { it.id }.toSet()
    return buildJsonObject {
        put("boxes", buildJsonArray {
            boxes.forEach { box ->
                val owned = byOwner[box.id].orEmpty()
                val jobs = box.jobs.toList()
                val strands = box.currentRunningStates.keys.sorted()
                val hooks = box.stopHooks.size
                add(buildJsonObject {
                    put("uid", box.id)
                    put("name", box.name)
                    put("tombstoned", box.getPropertyRaw("_tombstone").isTrue())
                    put("engine", if (box.liveKi != null) "live" else "none")
                    put("jobs", jobs.size)
                    put("activeJobs", jobs.count { it.isActive })
                    put("runningStrands", JsonArray(strands.map { JsonPrimitive(it) }))
                    put("stopHooks", hooks)
                    // Informational: a scope is an owner, not a resource, so it is not in liveTotal.
                    put("runScopes", JsonArray(box.runScopes.snapshot().map {
                        JsonPrimitive("${it.strand}/${it.kind}#${it.id} live=${it.liveCount}")
                    }))
                    put("owned", ownedCounts(owned))
                    put("ownedByPolicy", buildJsonObject {
                        ReleasePolicy.entries.forEach { p -> put(p.name, owned.count { it.policy == p }) }
                    })
                    put("ownedKeys", JsonArray(owned.take(50).map {
                        JsonPrimitive("${it.kind}:${it.key} [${it.policy}]" + (it.strand?.let { s -> " @$s" } ?: ""))
                    }))
                    put("liveRunning", jobs.size + strands.size + hooks + owned.count { it.policy == ReleasePolicy.RUNNING })
                    put("liveTotal", jobs.size + strands.size + hooks + owned.size)
                })
            }
        })
        put("policies", buildJsonObject {
            val kinds = sortedMapOf<String, ReleasePolicy>()
            listOf(
                LifecycleKinds.AUDIO_PATCH, LifecycleKinds.PATSYNTH_FEEDER, LifecycleKinds.STREAM_PROCESS,
                LifecycleKinds.CONTROL_CALLBACK, LifecycleKinds.RUNLOOP_PRE_RUN, LifecycleKinds.RUNLOOP_POST_RUN,
                LifecycleKinds.RENDER_MODULE,
            ).forEach { kinds[it] = ReleasePolicy.forKind(it) }
            // A claim may override its kind's policy; report what the live ones actually carry.
            entries.forEach { kinds[it.kind] = it.policy }
            kinds.forEach { (kind, policy) -> put(kind, policy.name) }
        })
        if (includeUnattributed) {
            put("unattributed", ownedCounts(entries.filter { it.ownerId == null || it.ownerId !in ids }))
        }
    }
}

private fun ownedCounts(owned: List<Ownership>): JsonObject = buildJsonObject {
    owned.groupingBy { it.kind }.eachCount().toSortedMap().forEach { (kind, count) -> put(kind, count) }
}
