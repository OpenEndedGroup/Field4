package afield.ai.core

import afield.*
import afield.stdlib.SourceStudy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Expose an existing history to AI4's States browser. Callbacks only read saved data; they must not
 * restore state or run admission checks. [read] returns metadata; [inspect] reads one exact saved ID.
 * Re-running replaces this named provider and the run's lifecycle owns its registration.
 */
fun Box.stateTree(name: String, label: String, read: () -> StateTreeSnapshot, inspect: (String) -> String) {
    require(name.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Use a simple state tree name" }
    val key = "_stateTree:$name"
    val binding = StateTreeBinding(this, name, label, read, inspect)
    properties[key] = binding
    LifecycleLedger.claim("state-tree", "${id}:$name", slot = LedgerSlot(properties, key), owner = this,
        scope = RunScope.currentFor(this), live = { properties[key] === binding },
        policy = ReleasePolicy.ACCUMULATING, release = { if (properties[key] === binding) properties.remove(key) })
}

/** Browse a SourceStudy without invoking its compiler or candidate checks. */
fun Box.stateTree(name: String, label: String, study: SourceStudy<*>) = stateTree(name, label, {
    StateTreeSnapshot(study.allRevisions().map { StateTreeNode(it.id, listOfNotNull(it.parent), it.description,
        "source", it.created.orEmpty()) }, study.currentId(), "Saved source revisions; live edits may differ.")
}, study::savedSource)

internal class StateTreeBinding(val owner: Box, val name: String, val label: String,
    val read: () -> StateTreeSnapshot, val inspect: (String) -> String)

/** Read-only projection shared by the browser and its chat references. No navigation or restoration. */
object StateTreeBrowser {
    private const val PAGE_SIZE = 400
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Bound text before it reaches either the browser or a model; never inline image blobs. */
    internal fun excerpt(text: String, limit: Int): String {
        val cleaned = text.replace(Regex("data:[^\\s)]*"), "[image omitted]")
            .replace(Regex("ai4img:[^\\s)]*"), "[saved image]")
        return if (cleaned.length <= limit) cleaned else cleaned.take(limit) + "\n[Excerpt; ${cleaned.length} characters total]"
    }

    private fun conversation(box: Box): StateTreeBinding = StateTreeBinding(box, "conversation", "Conversation", {
        val s = AiSessionTree.load(box)
        val nodes = s.nodes().values.map { n -> StateTreeNode(n.str("id").orEmpty(), listOfNotNull(n.str("parent")),
            excerpt(n.str("user")?.takeIf { it.isNotBlank() } ?: AiSessionTree.userText(n.str("markdown").orEmpty())
                .ifBlank { "${n.str("t")} transcript" }, 180), n.str("t").orEmpty(), n.str("at").orEmpty()) }
        StateTreeSnapshot(nodes, s.head(), "Conversation history and registered snapshots. This is not a complete document backup.")
    }, { id ->
        val s = AiSessionTree.load(box)
        val node = requireNotNull(s.nodes()[id]) { "This conversation state is no longer available. Refresh the tree." }
        val snapshots = linkedMapOf<String, String>()
        s.path(id).forEach { n -> ((n["branch"] as? JsonObject)?.get("after") as? JsonObject)?.forEach { (k, v) ->
            snapshots[k] = (v as? JsonPrimitive)?.contentOrNull.orEmpty()
        } }
        buildString {
            appendLine("Conversation state: $id")
            appendLine("Parents: ${node.str("parent") ?: "none"}")
            appendLine("Registered state snapshots: ${if (snapshots.isEmpty()) "none recorded" else snapshots.entries.joinToString { "${it.key}=${it.value}" }}")
            appendLine("This records a conversation position; it does not assert a complete document snapshot.")
            appendLine("\nTurn excerpt:\n${excerpt(node.str("markdown").orEmpty(), 8000)}")
        }
    })

    fun read(boxes: Collection<Box>, request: StateTreeRequest): StateTreeReply = runCatching {
        require(boxes.any { it.id == request.uid }) { "Chat box no longer exists." }
        val bindings = boxes.distinct().flatMap { box ->
            val providers = box.properties.values.filterIsInstance<StateTreeBinding>().filter { it.owner === box }
            if ((box.getPropertyRaw(AiSessionTree.PROPERTY) as? String).isNullOrBlank()) providers else listOf(conversation(box)) + providers
        }
        val indexed = bindings.associateBy { "${it.owner.id}/${it.name}" }
        val sources = indexed.map { (id, b) -> StateTreeSource(id, "${b.owner.getPropertyRaw("name") ?: b.owner.id} · ${b.label}".take(200)) }
        if (request.source.isEmpty()) return@runCatching StateTreeReply(sources = sources)
        val binding = requireNotNull(indexed[request.source]) { "History provider is unavailable. Run its box, then refresh." }
        val snapshot = binding.read()
        require(snapshot.nodes.map { it.id }.distinct().size == snapshot.nodes.size) { "History provider returned duplicate state IDs." }
        if (request.node.isNotEmpty()) {
            val n = requireNotNull(snapshot.nodes.find { it.id == request.node }) { "Saved state is no longer available. Refresh the tree." }
            val detail = excerpt(binding.inspect(n.id), 10000)
            val label = sources.first { it.id == request.source }.label
            // Literal quoted evidence in the draft, never hidden instructions or an automatic send.
            val reference = "State reference: ${request.source} / ${n.id}\n" +
                "History: $label\nTitle: ${excerpt(n.title, 180)}\n" +
                "Selected for discussion only; no state was restored. Saved-state excerpt follows:\n" +
                detail.lineSequence().joinToString("\n") { "> $it" }
            StateTreeReply(source = request.source, detail = detail, reference = reference)
        } else {
            val offset = request.offset.coerceIn(0, snapshot.nodes.size)
            val end = (snapshot.nodes.size - offset).coerceAtLeast(0)
            val page = snapshot.nodes.subList((end - PAGE_SIZE).coerceAtLeast(0), end).map {
                it.copy(title = excerpt(it.title, 180), created = it.created.take(80))
            }
            StateTreeReply(source = request.source, tree = snapshot.copy(nodes = page, note = excerpt(snapshot.note, 1000)),
                total = snapshot.nodes.size, offset = offset)
        }
    }.getOrElse { StateTreeReply(error = it.message ?: "Could not read this state history.") }
}
