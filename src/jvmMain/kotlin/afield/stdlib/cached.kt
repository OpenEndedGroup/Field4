@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import afield.BoxRunScopes
import afield.OwnedEntry
import afield.ReleasePolicy
import afield.RunScope
import java.util.WeakHashMap
import kotlin.reflect.KClass

/**
 * Returns the [T] this box made last time for the same [keys], making one with [make] only when
 * there is none:
 *
 * ```
 * val view = cached<ElectronRTTView>(renderer) { ElectronRTTView(640, 360) }
 * ```
 *
 * The cache key is the type `T` plus [keys], compared with `equals`. Re-running the box with the
 * same `renderer` hands back the same view; a different `renderer` makes a new one.
 *
 * **The old value is closed.** When a run asks for a `T` under keys that miss, every cached `T`
 * of this box that the *current run has not asked for* is evicted, and closed if it is
 * [AutoCloseable] (together with anything its [make] registered — a render module, a slider). So
 * changing a key and re-running replaces the old value rather than leaking it, while one run that
 * asks for several `T`s under different keys (one view per renderer, in a loop) keeps them all.
 * Two `cached<T>` calls with the *same* keys in one box share one value; add a discriminating key
 * (`cached<View>(renderer, "left")`) to keep them apart.
 *
 * **Lifetime.** Like `once`, [make] runs with the box's [BoxRunScopes.parent] scope current, so
 * what it registers belongs to the box, not to the run that happened to compute it. A cached value
 * is never taken apart by a re-run, and survives stop unless something it registered was
 * [ReleasePolicy.RUNNING] (an audio patch, a process) — then stop closes it and the next run makes
 * a fresh one rather than returning a dead one. Retiring the box (delete, document close) closes
 * everything. A value is closed at most once, even when it registered itself with the run scope.
 *
 * `T` should be a library type. A class declared in the box's own code is a new class each time
 * the box compiles, so it never hits, and the stale value (a different type) is only closed when
 * the box retires.
 *
 * A plain inline function, not a macro: `T` is reified, so nothing about the call site needs
 * rewriting. Called outside any box (no `Box.context`), use the [Box] receiver form explicitly.
 */
inline fun <reified T : Any> Box.cached(vararg keys: Any?, noinline make: () -> T): T =
    BoxCache.lookup(this, T::class, keys.toList(), make)

/** The per-box store behind [cached]; public only so the inline [cached] can reach it. */
object BoxCache {
    private class Entry(val type: KClass<*>, val keys: List<Any?>, val value: Any, val registered: List<OwnedEntry>) {
        @Volatile
        var lastScope: RunScope? = null
        private var closed = false

        fun close() {
            synchronized(this) { if (closed) return; closed = true }
            // Releasing what make registered closes the value too when it owned itself.
            registered.forEach { it.release { } }
            val v = value
            if (v is AutoCloseable && registered.none { it.resource === v }) runCatching { v.close() }
        }
    }

    private val boxes = WeakHashMap<Box, MutableList<Entry>>()

    @PublishedApi
    internal fun <T : Any> lookup(box: Box, type: KClass<T>, keys: List<Any?>, make: () -> T): T {
        val scope = RunScope.currentFor(box)
        val evicted = ArrayList<Entry>()
        synchronized(boxes) {
            val entries = boxes.getOrPut(box) { ArrayList() }
            entries.firstOrNull { it.type == type && it.keys == keys }?.let {
                it.lastScope = scope
                @Suppress("UNCHECKED_CAST")
                return it.value as T
            }
            entries.filter { it.type == type && (scope == null || it.lastScope !== scope) }.forEach {
                entries.remove(it)
                evicted.add(it)
            }
        }
        evicted.forEach { it.close() }

        val parent = box.runScopes.parent
        var made: Entry? = null
        val (value, registered) = RunScope.withCurrent(parent) {
            RunScope.collectingOwned {
                val v = make()
                // Own the value too, so retire closes it even if make registered nothing.
                if (v is AutoCloseable) parent.own(AutoCloseable { made?.close() }, ReleasePolicy.KEYED)
                v
            }
        }
        val entry = Entry(type, keys, value, registered).also { it.lastScope = scope }
        made = entry
        synchronized(boxes) { boxes.getOrPut(box) { ArrayList() }.add(entry) }
        // Whatever releases a registration (stop, retire) also retires the cached value.
        val forget = {
            synchronized(boxes) { boxes[box]?.remove(entry) }
            entry.close()
        }
        registered.forEach { it.onRelease(forget) }
        return value
    }

    /** How many values [box] has cached; for tests and inspection. */
    fun size(box: Box): Int = synchronized(boxes) { boxes[box]?.size ?: 0 }
}
