package afield.ai.conversation

import afield.document.graph.Box
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
 * document, apart from the chat's own (`ai4_*`). Values are written out typed and quoted, lists in their own
 * order (their order can be a polygon's vertices or an authored sequence) and only sets and maps sorted, so
 * two different values never print alike. The one normalisation is for [LOADER_DEFAULTS], properties that
 * loading a document fills in when absent, so an untouched box still matches its own fingerprint after its
 * document is reopened.
 */

/** Fingerprints the boxes a chat turn created and decides which of them a world undo may delete. */
object AiWorldUndo {

    /** Properties the document loader adds with these values when a box does not have them. */
    internal val LOADER_DEFAULTS: Map<String, (Any?) -> Boolean> = mapOf(
        "autorun" to { v -> v == false },
        "preamble" to { v -> v == "" },
        "nested" to { v -> v is Collection<*> && v.isEmpty() },
    )

    /** A 16-hex-digit SHA-256 prefix over [box]'s name, frame, links and saved properties, excluding `ai4_*`. */
    fun fingerprint(box: Box): String {
        val patterns = Box.ioPersistentProperties.map { it.toRegex() }
        val parts = box.properties.entries
            .filter { (name, value) ->
                !name.startsWith("ai4_") && name != "_tombstone" && patterns.any { name.matches(it) } &&
                    LOADER_DEFAULTS[name]?.invoke(value) != true
            }
            .map { (name, value) -> quote(name) + "=" + canonical(value) }
            .sorted() +
            listOf("children=" + canonical(box.children), "parents=" + canonical(box.parents))
        return MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun quote(text: String): String = kotlinx.serialization.json.JsonPrimitive(text).toString()

    /** A typed, unambiguous text for [value]: lists keep their order, sets and maps are sorted. */
    internal fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is String -> "s" + quote(value)
        is Boolean -> "b:$value"
        is Number -> "n:${value::class.java.simpleName}:$value"
        is Box -> "box:" + quote(value.id)
        is Set<*> -> value.map(::canonical).sorted().joinToString(",", "#{", "}")
        is Map<*, *> -> value.entries.map { canonical(it.key) + "=" + canonical(it.value) }.sorted().joinToString(",", "{", "}")
        // Any other collection is taken to be ordered: reading an unordered one as ordered can only keep a box
        // that could have been deleted, never delete one that was changed.
        is Collection<*> -> value.joinToString(",", "[", "]") { canonical(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value::class.java.name + ":" + quote(value.toString())
    }

    /** Fingerprints of the live boxes among [uids]. */
    fun fingerprints(boxes: Map<String, Box>, uids: Collection<String>): Map<String, String> =
        uids.distinct().mapNotNull { uid -> boxes[uid]?.takeIf { it.getPropertyRaw("_tombstone") != true }?.let { uid to fingerprint(it) } }.toMap()

    /** The outcome of [plan]: boxes to delete, and boxes to keep paired with the reason they stay. */
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
