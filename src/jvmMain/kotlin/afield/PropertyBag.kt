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
 * Snapshot views are the one behaviour change: removing through `keys.iterator()`, or calling `setValue`
 * on an entry from [entries], now updates the copy and is silently lost. Nothing in the tree does either
 * (every removal goes through `properties.remove(name)`), and a snapshot that goes stale is strictly less
 * dangerous than an iterator that throws.
 *
 * Insertion order is preserved deliberately and is **not** incidental: `SavingContext.toTransfer` builds
 * each persisted box's property map in bag-iteration order, so a `ConcurrentHashMap` here would silently
 * reorder the properties of every saved document. `Collections.synchronizedMap` was the other obvious
 * candidate and does not work either — its iterators are explicitly unprotected, so the
 * `ConcurrentModificationException` would survive untouched.
 *
 * The cost is an uncontended monitor per property read. That is deliberate: reads of this map are
 * interleaved with regex matching over ~70 resync patterns and with REPL compilation, so correctness is
 * worth more here than the nanoseconds.
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

    /** A snapshot of the keys, in insertion order. Detached from the bag: removing from it does nothing. */
    override val keys: MutableSet<String>
        get() = synchronized(backing) { LinkedHashSet(backing.keys) }

    /** A snapshot of the values, in key insertion order. Detached from the bag. */
    override val values: MutableCollection<Any?>
        get() = synchronized(backing) { ArrayList(backing.values) }

    /**
     * A snapshot of the entries, in insertion order — this is what makes every `filter`/`forEach` over a
     * box's properties safe. Detached from the bag: `setValue` on one of these entries updates the copy.
     */
    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
        get() = synchronized(backing) { LinkedHashMap(backing).entries }

    override fun toString(): String = synchronized(backing) { backing.toString() }

    override fun equals(other: Any?): Boolean = synchronized(backing) { backing == other }

    override fun hashCode(): Int = synchronized(backing) { backing.hashCode() }
}
