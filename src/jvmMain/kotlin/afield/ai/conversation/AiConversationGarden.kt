package afield.ai.conversation

import afield.document.graph.Box
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/** An opt-in structured conversation; the old panel's transcript and provider state are untouched. */
object AiConversationGarden {
    const val PROPERTY = "ai4_garden"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun id() = UUID.randomUUID().toString()
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Synchronized fun read(box: Box): ConversationGarden {
        val raw = box.getPropertyRaw(PROPERTY) as? String ?: return ConversationGarden()
        return json.decodeFromString<ConversationGarden>(raw).also { require(it.version == 1) { "Unsupported garden version ${it.version}" } }
    }

    private fun save(box: Box, garden: ConversationGarden): ConversationGarden {
        check(!box.isInsideInitialized() || !box.inside.readOnly) { "This document is read-only." }
        val used = garden.nodes.flatMap { n -> (n.context + n.passages).map { it.revision } + n.edits.flatMap { listOf(it.before, it.after) } }.toSet()
        val kept = garden.copy(revision = garden.revision + 1, texts = garden.texts.filterKeys { it in used })
        box.setPropertyRaw(PROPERTY, json.encodeToString(kept), doResync = false)
        if (box.isInsideInitialized() && box.inside.writesToDisk) box.inside.markSidecarPending(box.id, PROPERTY)
        AiBoxConversation.ensurePanel(box, "Garden")
        return kept
    }

    /** Import once, explicitly. Existing linear histories remain readable and independently usable. */
    @Synchronized fun initialize(box: Box): ConversationGarden {
        val garden = read(box)
        if (box.getPropertyRaw(PROPERTY) != null) return garden
        val history = AiBoxConversation.read(box)
        if (history.isBlank()) return save(box, garden)
        val root = GardenNode(id(), status = "imported")
        val (passages, texts) = passages(box, history, emptyList(), emptyMap())
        return save(box, garden.copy(nodes = listOf(root.copy(passages = passages)), texts = texts))
    }

    /** Paragraph boundaries outside fenced code; list/table blocks stay together. */
    internal fun paragraphs(markdown: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var fence: Char? = null
        var fenceSize = 0
        for (line in markdown.lines()) {
            val trimmed = line.trimStart()
            val mark = trimmed.firstOrNull()
            val run = if (mark == '`' || mark == '~') trimmed.takeWhile { it == mark }.length else 0
            if (run >= 3 && fence == null) { fence = mark; fenceSize = run }
            else if (fence != null && mark == fence && run >= fenceSize && trimmed.drop(run).isBlank()) fence = null
            if (line.isBlank() && fence == null) {
                if (current.isNotBlank()) { result += current.toString().trimEnd(); current.clear() }
            } else { current.append(line).append('\n') }
        }
        if (current.isNotBlank()) result += current.toString().trimEnd()
        return result
    }

    private fun passages(box: Box, markdown: String, old: List<GardenPassage>, previousTexts: Map<String, String>): Pair<List<GardenPassage>, Map<String, String>> {
        val texts = previousTexts.toMutableMap()
        val out = mutableListOf<GardenPassage>()
        parseAiChatBlocks(markdown).forEach { block ->
            val parts = if (block.isProgress) listOf(block.content) else paragraphs(block.content)
            parts.forEach { text ->
                val stored = AiImageBlobs.extract(text, box)
                val revision = hash(stored)
                texts[revision] = stored
                val earlier = old.getOrNull(out.size)
                val priorText = earlier?.let { previousTexts[it.revision] }
                // A growing streaming tail keeps its identity. A replacement gets a new identity.
                val passageId = earlier?.takeIf { it.role == block.role && (priorText == stored || stored.startsWith(priorText.orEmpty())) }?.id ?: id()
                out += GardenPassage(passageId, revision, block.role, block.model)
            }
        }
        return out to texts
    }

    @Synchronized fun enqueue(box: Box, prompt: String, anchor: GardenAnchor?, property: String, anchorText: String = ""): Pair<ConversationGarden, String> {
        require(prompt.isNotBlank() && prompt.length <= 100_000) { "Write a reply (at most 100000 characters)." }
        require(property.isNotBlank()) { "Choose a box property." }
        var garden = initialize(box)
        val parent = anchor?.let { a -> garden.nodes.singleOrNull { it.id == a.node } ?: error("Reply source no longer exists.") }
        require(anchor == null || anchor.mode in setOf("through", "whole")) { "Unknown context mode." }
        val context = if (anchor == null) emptyList() else {
            val sourceNode = requireNotNull(parent)
            val index = sourceNode.passages.indexOfFirst { it.id == anchor.passage }
            require(index >= 0) { "That paragraph was replaced. Refresh and select it again." }
            val current = sourceNode.passages[index]
            if (current.revision != anchor.revision) {
                // A reply may arrive after another streaming chunk. Accept only an authentic prefix
                // of this passage or an earlier revision already pinned by an accepted child.
                val snapshot = AiImageBlobs.extract(anchorText, box)
                val pinned = garden.nodes.filter { it.anchor?.node == sourceNode.id }.flatMap { it.context }
                    .any { it.id == anchor.passage && it.revision == anchor.revision }
                val growing = snapshot.isNotEmpty() && hash(snapshot) == anchor.revision &&
                    garden.texts.getValue(current.revision).startsWith(snapshot)
                require(pinned || growing) { "That paragraph changed. Refresh and select it again." }
                if (growing) garden = garden.copy(texts = garden.texts + (anchor.revision to snapshot))
            }
            val own = if (anchor.mode == "whole") sourceNode.passages else sourceNode.passages.take(index + 1).map {
                if (it.id == anchor.passage) it.copy(revision = anchor.revision) else it
            }
            val request = if (sourceNode.prompt.isEmpty()) emptyList() else {
                listOf(GardenPassage("${sourceNode.id}:request", hash(sourceNode.prompt), "you"))
            }
            sourceNode.context + request + own
        }
        val texts = garden.texts.toMutableMap()
        if (parent != null && parent.prompt.isNotEmpty()) texts[hash(parent.prompt)] = parent.prompt
        val node = GardenNode(id(), prompt.trim(), anchor, context,
            tags = Regex("(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_-]+)").findAll(prompt).map { it.groupValues[1].lowercase() }.distinct().toList(), property = property)
        return save(box, garden.copy(nodes = garden.nodes + node, texts = texts)) to node.id
    }

    @Synchronized fun context(box: Box, nodeId: String): String {
        val garden = read(box)
        val node = garden.nodes.single { it.id == nodeId }
        return node.context.joinToString("\n") { "<<${it.role}>>\n${garden.texts.getValue(it.revision)}" } +
            node.anchor?.quote?.takeIf { it.isNotBlank() }?.let { "\n[The new reply is attached to this quotation: $it]\n" }.orEmpty()
    }

    @Synchronized fun update(box: Box, nodeId: String, markdown: String): ConversationGarden {
        val garden = read(box)
        val node = garden.nodes.single { it.id == nodeId }
        val (passages, texts) = passages(box, markdown, node.passages, garden.texts)
        return save(box, garden.copy(nodes = garden.nodes.map { if (it.id == nodeId) it.copy(passages = passages) else it }, texts = texts))
    }

    @Synchronized fun status(box: Box, nodeId: String, status: String, error: String = ""): ConversationGarden {
        require(status in setOf("running", "complete", "failed", "queued"))
        val garden = read(box)
        require(garden.nodes.any { it.id == nodeId }) { "Unknown reply." }
        return save(box, garden.copy(nodes = garden.nodes.map { if (it.id == nodeId) it.copy(status = status, error = error) else it }))
    }

    @Synchronized fun recover(box: Box): ConversationGarden {
        val garden = read(box)
        if (garden.nodes.none { it.status == "running" }) return garden
        AiBoxConversation.running(box, false)
        return save(box, garden.copy(nodes = garden.nodes.map { if (it.status == "running") it.copy(status = "failed", error = "Interrupted. Partial output retained; reply to continue.") else it }))
    }

    @Synchronized fun edit(owner: Box, nodeId: String, target: Box, property: String, before: String, after: String) {
        if (before == after) return
        val garden = read(owner)
        val node = garden.nodes.single { it.id == nodeId }
        val a = hash(before); val b = hash(after)
        val edit = GardenEdit(id(), target.id, target.name, property, a, b)
        save(owner, garden.copy(nodes = garden.nodes.map { if (it.id == nodeId) node.copy(edits = node.edits + edit) else it }, texts = garden.texts + mapOf(a to before, b to after)))
    }

    /** Only current text is inlined for transport; stored image references stay content-addressed. */
    fun forDisplay(box: Box, garden: ConversationGarden): ConversationGarden = garden.copy(texts = garden.texts.mapValues { AiImageBlobs.inline(it.value, box) })
}
