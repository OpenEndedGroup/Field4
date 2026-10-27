package afield.document.persistence

import afield.app.server.SessionInformation
import afield.document.graph.Box

/**
 * The content hash that catches a document edit dirty marking did not see.
 *
 * Field decides whether to save by marking: a write through `setPropertyRaw`, `removePropertyRaw`,
 * `connect` or `disconnect` tells the session there is something to write. A collection mutated in
 * place and a write made while the document is still loading go through none of them. So the
 * autosave tick, the detach save and the shutdown flush also hash what they would write and compare
 * that against the hash from the last time the document matched disk, saving when the two differ.
 *
 * The per-property entries behind the hash are kept as well, which lets a tripwire name the
 * `(box id, property)` pairs that changed without being marked. Each one names a mutator that
 * should have marked its own write.
 */

/** A content hash of a document's persistent properties, for "has this changed since the last save?" */
object PersistedFingerprint {

    fun of(info: SessionInformation): Long = entries(info).values.sum()

    fun of(directory: String, boxes: Collection<Box>): Long = entries(directory, boxes).values.sum()

    /** Per `(box id, property)` hashes; [of] is their sum. Kept so the tripwire can name what changed. */
    fun entries(info: SessionInformation): Map<Pair<String, String>, Long> {
        val boxes = info.boxes.values.toMutableSet()
        boxes.add(info.root)
        return entries(info.filename, boxes)
    }

    fun entries(directory: String, boxes: Collection<Box>): Map<Pair<String, String>, Long> {
        val patterns = Box.persistentPropertiesFor(directory).map { it.toRegex() }
        val persisted = HashMap<String, Boolean>()
        val out = HashMap<Pair<String, String>, Long>()
        for (box in boxes) {
            for ((name, value) in box.properties.entries.toList()) {
                if (value == null || isEmpty(value)) continue
                val keep = persisted.getOrPut(name) { patterns.any { name.matches(it) } && !Box.isNeverPersisted(name) }
                if (!keep) continue
                out[box.id to name] = mix(box.id.hashCode().toLong() * 31 + name.hashCode(), deep(value))
            }
        }
        return out
    }

    /** Records [entries] as what disk holds now (after a load or a save). */
    fun record(info: SessionInformation, entries: Map<Pair<String, String>, Long>?) {
        info.savedFingerprintEntries = entries
        info.savedFingerprint = entries?.values?.sum()
    }

    /**
     * The tripwire (backlog N1022-2): the `(box id, property)` keys whose hash differs between
     * [saved] and [now] (added, removed or changed) and that nothing marked in [marked]. Each one is
     * a mutator that bypassed [Box.notePersistentChange]; the save still writes it, but the
     * mutator should be fixed.
     */
    fun unmarkedChanges(
        saved: Map<Pair<String, String>, Long>,
        now: Map<Pair<String, String>, Long>,
        marked: Set<Pair<String, String>>,
    ): List<Pair<String, String>> {
        val changed = (saved.keys + now.keys).filter { saved[it] != now[it] && it !in marked }
        return changed.sortedWith(compareBy({ it.first }, { it.second }))
    }

    /** Whether [deep] of [value] is a function of its content (no identity hash anywhere inside). */
    fun hashesByContent(value: Any?): Boolean = when (value) {
        null, is Box, is String, is Number, is Boolean, is Char, is Enum<*> -> true
        is Collection<*> -> value.all { hashesByContent(it) }
        is Map<*, *> -> value.entries.all { hashesByContent(it.key) && hashesByContent(it.value) }
        is Array<*> -> value.all { hashesByContent(it) }
        else -> runCatching { value.javaClass.getMethod("hashCode").declaringClass != Any::class.java }.getOrDefault(false)
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
