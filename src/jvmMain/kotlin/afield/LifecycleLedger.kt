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
 * the run that owned it, if any.
 */
class Ownership internal constructor(
    val kind: String,
    val key: String,
    val ownerId: String?,
    val strand: String?,
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

    override fun toString(): String = "Ownership($kind '$key' owner=$ownerId strand=$strand live=$isLive)"
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
     * to — so the run's end releases it. Pass `scope = null` for something the box does not own, only
     * caused.
     */
    fun claim(
        kind: String,
        key: String,
        slot: Any? = null,
        owner: Box? = RunScope.executingBox(),
        scope: RunScope? = RunScope.currentFor(owner),
        live: (() -> Boolean)? = null,
        release: (() -> Unit)? = null,
    ): Ownership {
        val ownership = Ownership(kind, key, owner?.id, scope?.strand, slot, live, release)
        this.live.add(ownership)
        if (slot != null) slots.put(slot, ownership)?.supersede()
        // Last, so a scope that has already closed releases this at once rather than keeping it.
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
     * By default the executing run owns it, so the run's end removes it. [owned] `false` is for
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
 * What each box of [boxes] still holds: jobs, running strands, stop hooks, ledger ownerships by kind,
 * and whether its K2 engine is live — the `lifecycle-census` backdoor command's answer.
 *
 * `liveTotal` sums everything except the engine, so "a stopped box leaves nothing behind" is
 * `liveTotal == 0`. Ownerships whose box is not in [boxes] are reported under `unattributed` when
 * [includeUnattributed] is set, which is how a registration made with no box executing shows up.
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
                    put("ownedKeys", JsonArray(owned.take(50).map {
                        JsonPrimitive("${it.kind}:${it.key}" + (it.strand?.let { s -> " @$s" } ?: ""))
                    }))
                    put("liveTotal", jobs.size + strands.size + hooks + owned.size)
                })
            }
        })
        if (includeUnattributed) {
            put("unattributed", ownedCounts(entries.filter { it.ownerId == null || it.ownerId !in ids }))
        }
    }
}

private fun ownedCounts(owned: List<Ownership>): JsonObject = buildJsonObject {
    owned.groupingBy { it.kind }.eachCount().toSortedMap().forEach { (kind, count) -> put(kind, count) }
}
