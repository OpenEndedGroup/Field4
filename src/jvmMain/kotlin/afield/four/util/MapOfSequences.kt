package afield.four.util

import afield.Generator

/**
 * Named sequences advanced one step per [update] — the Runloop's `preRun`/`postRun` hooks, a mesh's
 * `update`. Setting a key replaces whatever ran under it; a sequence that ends is dropped.
 *
 * When [ledgerKind] is given, every [set] is an [afield.Ownership] in the lifecycle ledger, attributed
 * to the box executing at the time, so `lifecycle-census` can say whose hook is still on the Runloop.
 * It is [afield.ReleasePolicy.KEYED]: a stop leaves the hook running, the next [set] of the same key
 * replaces it, and only the box's retirement (delete, document close) takes it out.
 * [setUnowned] is for library code that removes its own hook.
 */
class MapOfSequences<T>(private val ledgerKind: String? = null) {

    private val lock = Any()
    private val starts = mutableMapOf<T, Sequence<Any?>>()
    private var ongoings = mutableMapOf<T, Iterator<Any?>>()

    /** The sequence each started key came from, so [isCurrent] can compare by identity. */
    private val ongoingSources = mutableMapOf<T, Sequence<Any?>>()

    fun update() {
        val current = synchronized(lock) {
            ongoings.entries.map { it.key to it.value }
        }

        current.forEach { (key, iterator) ->
            val stillCurrent = synchronized(lock) {
                ongoings[key] === iterator
            }
            if (!stillCurrent) return@forEach

            val keep = if (iterator.hasNext()) {
                iterator.next()
                iterator.hasNext()
            } else {
                false
            }

            synchronized(lock) {
                if (ongoings[key] === iterator && !keep) {
                    ongoings.remove(key)
                    ongoingSources.remove(key)
                }
            }
        }

        synchronized(lock) {
            starts.forEach { (t, u) ->
                ongoings[t] = u.iterator()
                ongoingSources[t] = u
            }
            starts.clear()
        }
    }

    /** Run [sequence] under [t] from the next [update], replacing whatever ran there. */
    operator fun set(t: T, sequence: Sequence<Any?>) {
        setUnowned(t, sequence)
        claim(t, sequence)
    }

    /**
     * [set] without the ledger entry, for library code that installs a hook on its own schedule and
     * removes it itself — a renderer's geometry hook, a spreadsheet poll — rather than on a box's
     * behalf.
     */
    fun setUnowned(t: T, sequence: Sequence<Any?>) {
        synchronized(lock) {
            starts[t] = sequence
        }
    }

    /** Whether [sequence] is still what runs, or will run, under [t]. */
    fun isCurrent(t: T, sequence: Sequence<Any?>): Boolean = synchronized(lock) {
        starts[t] === sequence || (t !in starts && ongoingSources[t] === sequence)
    }

    /** Remove [t] only if [sequence] is still what it holds. */
    fun removeIfCurrent(t: T, sequence: Sequence<Any?>) {
        synchronized(lock) {
            if (starts[t] === sequence) starts.remove(t)
            if (ongoingSources[t] === sequence) {
                ongoings.remove(t)
                ongoingSources.remove(t)
            }
        }
    }

    fun remove(t: T) {
        synchronized(lock) {
            starts.remove(t)
            ongoings.remove(t)
            ongoingSources.remove(t)
        }
    }

    private fun claim(t: T, sequence: Sequence<Any?>): afield.Ownership? {
        val kind = ledgerKind ?: return null
        return afield.LifecycleLedger.claim(
            kind,
            t.toString(),
            slot = afield.LedgerSlot(this, t),
            live = { isCurrent(t, sequence) },
            release = { removeIfCurrent(t, sequence) },
        )
    }
}

class MapOfGenerators<O, T> {

    val starts = mutableMapOf<String, Generator<O, T>>()
    var ongoings = mutableMapOf<String, Generator<O, T>>()

    fun update(input: O): MutableList<T> {
        val collected = mutableListOf<T>()
        ongoings.values.retainAll {
            val o = it.next(input)
            if (o != null)
                collected.add(o)
            !it.isCompleted
        }
        starts.forEach { (t, u) ->
            val o = u.start()
            if (!u.isCompleted)
                ongoings[t] = u
        }
        starts.clear()
        return collected
    }

    fun updateNoCollect(input: O) {
        val ongoingIterator = ongoings.values.iterator()
        while (ongoingIterator.hasNext()) {
            val gen = ongoingIterator.next()
            gen.next(input)
            if (gen.isCompleted) {
                ongoingIterator.remove()
            }
        }

        val startIterator = starts.entries.iterator()
        while (startIterator.hasNext()) {
            val entry = startIterator.next()
            val gen = entry.value
            gen.start()
            if (!gen.isCompleted) {
                ongoings[entry.key] = gen
            }
            startIterator.remove()
        }
    }

    operator fun set(t: String, g: Generator<O, T>) {
        starts[t] = g
    }

    fun remove(t : String) {
        starts.remove(t)
        ongoings.remove(t)
    }
}
