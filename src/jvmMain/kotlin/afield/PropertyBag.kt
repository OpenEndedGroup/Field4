package afield

/**
 * The property bag behind every [Box] — an insertion-ordered, null-tolerant map that is safe to read,
 * write **and iterate** from more than one thread at once.
 *
 * A box's properties are touched by essentially every thread in the process: the main runloop's 500 ms
 * resync/save/file-proxy loop (`Server.kt`), macro and transcript replay on `Dispatchers.IO`
 * (`operation_transcript.kt`), the AI auto-namer on `Runloop.miscPool` (`Ai4AutoBoxNamer.kt`), box
 * execution, and the autosave pass. Until this class existed the bag was a plain `mutableMapOf()`, i.e.
 * an unsynchronised `LinkedHashMap`, and anything that walked it could be hit by a
 * `ConcurrentModificationException` thrown from the iterator — most visibly `Box.resyncAll`, which the
 * nightly loop sighted three times from three different agents.
 *
 * Two things make that race much wider than "someone forgot to lock a write":
 *
 *  - **Readers are writers.** `Delegation.getValue` writes a property's default value back into the bag
 *    on the first read of an unset property, so merely *reading* `box.actions` from a background thread
 *    inserts a key and bumps the backing map's `modCount`.
 *  - **Iteration is everywhere.** Saving, history/undo diffing, the workspace transcript, the value
 *    visualiser, the spreadsheet bridge, the auto-namer and `resyncAll`/`resyncAllExcept` all walk a
 *    box's whole bag.
 *
 * So the guarantee is provided here, once, rather than at the ~30 call sites:
 *
 *  - every single-key operation is serialised on one monitor, which also removes the older hazard of a
 *    `HashMap.get` racing a concurrent resize;
 *  - [keys], [values] and [entries] return **snapshots**, so every `filter` / `forEach` / `map` /
 *    `toList` over a bag iterates a private copy and cannot throw
 *    `ConcurrentModificationException` no matter what other threads do meanwhile.
 *
 * The views **iterate a snapshot but write through to the live map**, which matters more than it sounds.
 * A first cut of this class returned bare copies, and that quietly replaced one bug with a worse one:
 * `properties.keys.remove(name)` or `entries.iterator().remove()` would have updated the copy and been
 * *silently lost*, where before it worked. A silent no-op is harder to find than the
 * `ConcurrentModificationException` it replaced. So instead: reads come from the snapshot (safe under
 * concurrent modification), and `remove` / `removeAll` / `retainAll` / `removeIf` / `iterator().remove()` /
 * `MutableEntry.setValue` all reach the backing map, exactly as a `LinkedHashMap`'s views do.
 *
 * The two operations that stay unsupported are the two `java.util.Map`'s own views refuse — `keys.add` and
 * `entries.add` — plus mutation through [values], where the JDK's "remove one entry that happens to hold
 * this value" semantics are ambiguous enough that nothing should be relying on them. All three throw
 * `UnsupportedOperationException` naming the operation and the alternative, rather than doing nothing.
 *
 * Insertion order is preserved deliberately and is **not** incidental: `SavingContext.toTransfer` builds
 * each persisted box's property map in bag-iteration order, so a `ConcurrentHashMap` here would silently
 * reorder the properties of every saved document. `Collections.synchronizedMap` was the other obvious
 * candidate and does not work either — its iterators are explicitly unprotected, so the
 * `ConcurrentModificationException` would survive untouched.
 *
 * The cost is an uncontended monitor per property read, and it was measured rather than waved at. On a
 * 66-entry bag: a raw `LinkedHashMap` get is 7.56 ns, this is 12.24 ns, and the whole
 * [Box.getPropertyRaw] path is 14.11 ns — so about **+4.7 ns per property read**. A whole-bag walk,
 * where the snapshot copy is the new work, costs 65.7 µs for `resyncAll()` against 64.8 µs for the
 * same `filter` over a plain map of the same size: **+1.3 %**, because matching ~70 resync regexes per
 * property dominates the copy by three orders of magnitude. (GraalVM 25, arm64, 20M/200k iterations,
 * third round after warm-up.)
 */
class PropertyBag : MutableMap<String, Any?> {

    private val backing = LinkedHashMap<String, Any?>()

    override val size: Int
        get() = synchronized(backing) { backing.size }

    override fun isEmpty(): Boolean = synchronized(backing) { backing.isEmpty() }

    override fun containsKey(key: String): Boolean = synchronized(backing) { backing.containsKey(key) }

    override fun containsValue(value: Any?): Boolean = synchronized(backing) { backing.containsValue(value) }

    override fun get(key: String): Any? = synchronized(backing) { backing[key] }

    override fun put(key: String, value: Any?): Any? = synchronized(backing) { backing.put(key, value) }

    override fun remove(key: String): Any? = synchronized(backing) { backing.remove(key) }

    override fun putAll(from: Map<out String, Any?>) {
        synchronized(backing) { backing.putAll(from) }
    }

    override fun clear() {
        synchronized(backing) { backing.clear() }
    }

    /**
     * The keys, in insertion order: iterated from a snapshot, removed through to the live map.
     *
     * `add` is unsupported, as it is on `java.util.Map.keySet()`.
     */
    override val keys: MutableSet<String>
        get() = KeyView(synchronized(backing) { ArrayList(backing.keys) })

    /**
     * The values, in key insertion order — read-only.
     *
     * `java.util.Map.values()` does support `remove`, meaning "drop some one entry that happens to hold
     * this value". Nothing in the tree wants that and it is ambiguous when values repeat, so mutation here
     * throws rather than guessing.
     */
    override val values: MutableCollection<Any?>
        get() = ValueView(synchronized(backing) { ArrayList(backing.values) })

    /**
     * The entries, in insertion order — this is what makes every `filter`/`forEach` over a box's properties
     * safe. Iterated from a snapshot; `iterator().remove()` and `MutableEntry.setValue` reach the live map.
     */
    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
        get() = EntryView(synchronized(backing) { backing.entries.map { it.key to it.value } })

    override fun toString(): String = synchronized(backing) { backing.toString() }

    override fun equals(other: Any?): Boolean = synchronized(backing) { backing == other }

    override fun hashCode(): Int = synchronized(backing) { backing.hashCode() }

    private fun refuse(operation: String, instead: String): Nothing = throw UnsupportedOperationException(
        "PropertyBag: $operation is not supported on this view (see PropertyBag.kt). $instead"
    )

    /**
     * Shared shape of the three views: every read answers from [snapshot], so iteration is immune to
     * whatever other threads are doing to the bag; every write is routed by the subclass.
     */
    private abstract inner class SnapshotView<E>(protected val snapshot: List<E>) : MutableCollection<E> {
        override val size: Int get() = snapshot.size
        override fun isEmpty(): Boolean = snapshot.isEmpty()
        override fun contains(element: E): Boolean = snapshot.contains(element)
        override fun containsAll(elements: Collection<E>): Boolean = snapshot.containsAll(elements)
        override fun toString(): String = snapshot.toString()

        /** Drop this element from the *backing map*; returns whether the snapshot held it. */
        protected abstract fun removeFromBacking(element: E): Boolean

        override fun iterator(): MutableIterator<E> = object : MutableIterator<E> {
            private val walk = snapshot.iterator()
            private var last: E? = null
            private var has = false
            override fun hasNext(): Boolean = walk.hasNext()
            override fun next(): E = walk.next().also { last = it; has = true }
            override fun remove() {
                check(has) { "PropertyBag view iterator: remove() before next()" }
                @Suppress("UNCHECKED_CAST")
                removeFromBacking(last as E)
                has = false
            }
        }

        override fun add(element: E): Boolean = refuse("add", "Use box.setPropertyRaw(name, value).")
        override fun addAll(elements: Collection<E>): Boolean =
            refuse("addAll", "Use box.setPropertyRaw(name, value).")

        override fun remove(element: E): Boolean = removeFromBacking(element)
        override fun removeAll(elements: Collection<E>): Boolean =
            elements.fold(false) { changed, it -> removeFromBacking(it) || changed }
        override fun retainAll(elements: Collection<E>): Boolean =
            snapshot.filterNot { it in elements }.fold(false) { changed, it -> removeFromBacking(it) || changed }
        override fun clear() {
            snapshot.forEach { removeFromBacking(it) }
        }
    }

    private inner class KeyView(snapshot: List<String>) : SnapshotView<String>(snapshot), MutableSet<String> {
        // containsKey before remove, not `remove(k) != null`: the bag holds null values, so a
        // null-valued key would otherwise report "nothing removed" after being removed.
        override fun removeFromBacking(element: String): Boolean =
            synchronized(backing) { backing.containsKey(element).also { backing.remove(element) } }

        override fun equals(other: Any?): Boolean = other is Set<*> && other.size == snapshot.size &&
            other.containsAll(snapshot)

        override fun hashCode(): Int = snapshot.sumOf { it.hashCode() }
    }

    private inner class ValueView(snapshot: List<Any?>) : SnapshotView<Any?>(snapshot) {
        override fun removeFromBacking(element: Any?): Boolean = refuse(
            "removing through values()",
            "Which key it would drop is ambiguous; use box.properties.remove(name)."
        )
    }

    private inner class EntryView(private val pairs: List<Pair<String, Any?>>) :
        SnapshotView<MutableMap.MutableEntry<String, Any?>>(pairs.map { WriteThroughEntry(it.first, it.second) }),
        MutableSet<MutableMap.MutableEntry<String, Any?>> {

        override fun removeFromBacking(element: MutableMap.MutableEntry<String, Any?>): Boolean =
            synchronized(backing) { backing.containsKey(element.key).also { backing.remove(element.key) } }

        override fun equals(other: Any?): Boolean = other is Set<*> && other.size == pairs.size &&
            other.containsAll(snapshot)

        override fun hashCode(): Int = snapshot.sumOf { it.hashCode() }
    }

    /** An entry out of a snapshot whose [setValue] still lands in the live map. */
    private inner class WriteThroughEntry(
        override val key: String,
        private var snapshotValue: Any?
    ) : MutableMap.MutableEntry<String, Any?> {
        override val value: Any? get() = snapshotValue

        override fun setValue(newValue: Any?): Any? {
            val previous = snapshotValue
            snapshotValue = newValue
            synchronized(backing) { backing[key] = newValue }
            return previous
        }

        override fun equals(other: Any?): Boolean =
            other is Map.Entry<*, *> && other.key == key && other.value == snapshotValue

        override fun hashCode(): Int = key.hashCode() xor (snapshotValue?.hashCode() ?: 0)
        override fun toString(): String = "$key=$snapshotValue"
    }
}
