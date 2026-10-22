package afield.document.persistence

import afield.app.server.SessionInformation
import afield.document.graph.Box

/**
 * A content hash of everything a document would write to `metadata.edn`.
 *
 * Dirty marking ([Box.notePersistentChange]) sees a write that goes through `setPropertyRaw`,
 * `removePropertyRaw`, `connect` or `disconnect`. Two kinds of edit do not:
 *
 * - a collection mutated in place: `box.layerFrames[layer] = rect` changes the map that is already
 *   the property's value, so no property is set (backlog N1020-6);
 * - a write made while the document is still loading (`SessionInformation.loading`): box runtimes
 *   start, and code can run, before the loader clears that flag, and marking is off for the whole
 *   of it so the loader's own writes are not mistaken for edits.
 *
 * Both used to reach disk only if some later marked edit triggered a save. The autosave tick, the
 * detach save and the shutdown flush now also compare this hash against the one recorded when the
 * document last matched disk — when the loader finished reading it, and at every save — and save
 * when they differ.
 *
 * The filter is the one `SavingContext.snapshotBoxes` applies (the document's persistent names,
 * never-persisted names excluded). A box reference hashes as its id, so the walk never recurses
 * through the graph. Null and empty collections hash as absent, because a `Delegation` getter
 * with a default (`layerFrames`, ...) materialises an empty map on first read and that is not an
 * edit. A value of some other class contributes its own `hashCode`; an identity hash makes an
 * in-place change to it invisible here, which is no worse than before.
 */
object PersistedFingerprint {

    fun of(info: SessionInformation): Long {
        val boxes = info.boxes.values.toMutableSet()
        boxes.add(info.root)
        return of(info.filename, boxes)
    }

    fun of(directory: String, boxes: Collection<Box>): Long {
        val patterns = Box.persistentPropertiesFor(directory).map { it.toRegex() }
        val persisted = HashMap<String, Boolean>()
        var total = 0L
        for (box in boxes) {
            // Order-independent across boxes and properties: the stores are hash maps.
            for ((name, value) in box.properties.entries.toList()) {
                if (value == null || isEmpty(value)) continue
                val keep = persisted.getOrPut(name) { patterns.any { name.matches(it) } && !Box.isNeverPersisted(name) }
                if (!keep) continue
                total += mix(box.id.hashCode().toLong() * 31 + name.hashCode(), deep(value))
            }
        }
        return total
    }

    private fun isEmpty(value: Any): Boolean =
        (value is Collection<*> && value.isEmpty()) || (value is Map<*, *> && value.isEmpty())

    private fun deep(value: Any?): Long = when (value) {
        null -> 0L
        is Box -> value.id.hashCode().toLong()
        is List<*> -> value.fold(1L) { acc, v -> acc * 1_000_003L + deep(v) }
        is Set<*> -> value.sumOf { deep(it) } + 7L
        is Map<*, *> -> value.entries.sumOf { (k, v) -> mix(deep(k), deep(v)) } + 13L
        is Array<*> -> value.fold(3L) { acc, v -> acc * 1_000_003L + deep(v) }
        else -> value.hashCode().toLong()
    }

    /** SplitMix64-style finaliser so that summing many entries does not cancel. */
    private fun mix(a: Long, b: Long): Long {
        var z = a * -7046029254386353131L + b
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
}
