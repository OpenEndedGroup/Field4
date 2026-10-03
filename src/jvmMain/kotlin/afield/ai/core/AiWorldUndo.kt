package afield.ai.core

import afield.Box
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Which boxes an abandoned turn created can be deleted without taking anyone's later work with them.
 *
 * The world undo after a rewind (`ai-chat-rewind-world`) undoes the abandoned turns' property edits strictly
 * (an edit changed since is skipped) and deletes the boxes they created. A person may since have typed into
 * one of those boxes, moved it, or wired it to their own; the panel promises that anything changed since is
 * left alone. So every turn records a fingerprint of each box its conversation has created, as the turn
 * starts and as it ends ([AiSessionTree.TurnMeta.createdBefore] / `createdAfter`), and a box is deleted only
 * when it is exactly as the last abandoned turn left it, and nobody changed it between abandoned turns either.
 *
 * A fingerprint covers the box's name, frame, links to other boxes and every property that is saved with the
 * document, apart from the chat's own (`ai4_*`). A property at its empty value (`false`, `""`, an empty
 * collection) counts as absent: loading a document fills in defaults such as `autorun false`, and without
 * this an untouched box no longer matched its own fingerprint once the document had been reopened.
 */
object AiWorldUndo {

    fun fingerprint(box: Box): String {
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        val parts = box.properties.entries
            .filter { (name, value) -> !name.startsWith("ai4_") && name != "_tombstone" && !isEmptyValue(value) && patterns.any { name.matches(it) } }
            .map { (name, value) -> "$name=${repr(value)}" }
            .sorted() +
            listOf("children=${repr(box.children)}", "parents=${repr(box.parents)}")
        return MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun isEmptyValue(value: Any?): Boolean = when (value) {
        null, false, "" -> true
        is Collection<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        else -> false
    }

    private fun repr(value: Any?): String = when (value) {
        null -> "null"
        is Box -> "box:${value.id}"
        is Collection<*> -> value.map(::repr).sorted().joinToString(",", "[", "]")
        is Map<*, *> -> value.entries.map { "${repr(it.key)}:${repr(it.value)}" }.sorted().joinToString(",", "{", "}")
        else -> value.toString()
    }

    /** Fingerprints of the live boxes among [uids]. */
    fun fingerprints(boxes: Map<String, Box>, uids: Collection<String>): Map<String, String> =
        uids.distinct().mapNotNull { uid -> boxes[uid]?.takeIf { it.getPropertyRaw("_tombstone") != true }?.let { uid to fingerprint(it) } }.toMap()

    data class Plan(val delete: List<Box>, val keep: List<Pair<Box, String>>)

    /**
     * For [abandoned] (turn nodes, in the order they happened), the boxes they created that may be deleted, and
     * those that must stay, each with the reason.
     */
    fun plan(abandoned: List<JsonObject>, boxes: Map<String, Box>): Plan {
        val delete = mutableListOf<Box>()
        val keep = mutableListOf<Pair<Box, String>>()
        val created = abandoned.flatMap { AiSessionTree.createdBoxesOf(it) }.distinct()
        for (uid in created) {
            val box = boxes[uid]?.takeIf { it.getPropertyRaw("_tombstone") != true } ?: continue
            var expected: String? = null
            var touchedBetween = false
            for (node in abandoned) {
                val before = fingerprintsOf(node, "fpBefore")[uid]
                if (expected != null && before != null && before != expected) touchedBetween = true
                fingerprintsOf(node, "fpAfter")[uid]?.let { expected = it }
            }
            when {
                expected == null -> keep += box to "no record of how the turn left it"
                touchedBetween -> keep += box to "changed between the turns"
                fingerprint(box) != expected -> keep += box to "changed since"
                else -> delete += box
            }
        }
        return Plan(delete, keep)
    }

    private fun fingerprintsOf(node: JsonObject, which: String): Map<String, String> =
        ((node["op"] as? JsonObject)?.get(which) as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()
}
