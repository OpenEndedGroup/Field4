package afield.ai.tools

import afield.ai.memory.ChatHistory
import afield.ai.memory.UserMemory
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * The AI4 tools over [UserMemory] (notes that persist across sessions and documents) and [ChatHistory] (the
 * workspace's saved chats, which notes cite). Offered when the turn's [ToolExecutionContext.userMemory] /
 * [ToolExecutionContext.chatHistory] are set: every interactive AI4 conversation, the backdoor and MCP, and an
 * agent task only when its request asks for memory.
 */

@JsonClassDescription("Search your memory notes, the ones that persist across sessions and documents. Use it when the memory index in your " +
    "instructions is cut short, or to find every note touching a topic before writing a new one, so you update a note instead of duplicating it. " +
    "Returns each matching note's name, kind, status and description, with the body lines that matched. An empty query lists every note. " +
    "Fails only when memory is off. e.g. query = `shader`, or kind = `trap`.")
@AgentToolMeta("memory", effects = [ToolEffect.AGENT_STATE, ToolEffect.FILESYSTEM_READ])
data class MemorySearchRequest(
    @JsonPropertyDescription("Text to find in a note's name, description or body, case-insensitive; empty lists every note.")
    val query: String = "",
    @JsonPropertyDescription("Only notes of this kind (person, trap, idiom, project, other); empty for all.")
    val kind: String = "",
)

@JsonClassDescription("Read one memory note. Returns it in full: its body, sources, status, when it was updated, and the revision you must pass to " +
    "MemoryWriteRequest to replace it. Use it before relying on a note's details, since the index carries only its one-line description, and before " +
    "updating it. Fails with not found when there is no such note. e.g. name = `thisbox-not-underscore`.")
@AgentToolMeta("memory", effects = [ToolEffect.AGENT_STATE, ToolEffect.FILESYSTEM_READ])
data class MemoryReadRequest(
    @JsonPropertyDescription("The note's name, as the index or MemorySearchRequest lists it.")
    val name: String,
)

@JsonClassDescription("Create or replace a memory note: one fact a later session with this person will need, kept across sessions and " +
    "documents. Use it when the person asks you to remember something, or when something is clearly worth remembering (a preference or " +
    "correction, an idiom or API that proved out, a trap and what resolved it). Do not use it for progress on this document; that goes in its guides. " +
    "To replace a note, pass the revision from MemoryReadRequest; creating needs an empty revision. Returns the saved note and its new revision. " +
    "Fails, changing nothing, on a stale or missing revision (read again and reconcile), a name that is not lowercase-with-hyphens, a missing " +
    "description, or a body over 8000 characters. e.g. name = `rtt-needs-renderer-parent`, kind = `trap`, status = `verified`, " +
    "sources = [`sketches/particles@t-b4.b1`].")
@AgentToolMeta("memory", effects = [ToolEffect.AGENT_STATE, ToolEffect.FILESYSTEM_WRITE])
data class MemoryWriteRequest(
    @JsonPropertyDescription("Lowercase letters, digits and hyphens, at most 64 characters; the note's file name and handle.")
    val name: String,
    @JsonPropertyDescription("One line, at most 200 characters, saying what the note holds; this is its line in every future session's index.")
    val description: String,
    @JsonPropertyDescription("The note itself, Markdown: the fact, why it holds, how you know, and when it applies.")
    val body: String,
    @JsonPropertyDescription("person (who they are, what they want), trap, idiom, project, or other.")
    val kind: String = "other",
    @JsonPropertyDescription("verified only if you checked it (it ran, the person confirmed it, the source says so); otherwise unconfirmed.")
    val status: String = "unconfirmed",
    @JsonPropertyDescription("Earlier chat blocks it came from, as chat@address from HistorySearchRequest, or a file path; may be empty. This conversation's own blocks have no address until the turn is saved.")
    val sources: List<String> = emptyList(),
    @JsonPropertyDescription("The revision from MemoryReadRequest when replacing a note; empty when creating one.")
    val revision: String = "",
    @JsonPropertyDescription("Why you are writing or changing it, in a few words; kept in the memory's history.")
    val reason: String = "",
)

@JsonClassDescription("Delete a memory note that is wrong or no longer useful, with the reason, which the memory's history keeps. Use it " +
    "when a note turned out false, or after merging it into another note; prefer MemoryWriteRequest when the note only needs correcting. " +
    "Returns what was deleted. Fails when there is no such note or no reason is given. e.g. name = `old-shader-path`, reason = `merged into shader-includes`.")
@AgentToolMeta("memory", effects = [ToolEffect.AGENT_STATE, ToolEffect.FILESYSTEM_WRITE])
data class MemoryDeleteRequest(
    @JsonPropertyDescription("The note to delete.")
    val name: String,
    @JsonPropertyDescription("Why it is wrong or no longer needed.")
    val reason: String,
)

@JsonClassDescription("Search the saved AI4 chats of this workspace, on every branch, for a phrase. Use it to check a memory note against " +
    "what was actually said, to find the conversation where something was decided, or to get the address to cite in a note's sources. " +
    "Returns each matching block as chat@address with a snippet; read around one with HistoryReadRequest. Returns no hits, rather than an error, " +
    "when nothing matches. e.g. query = `thisBox`, or chat = `firstDocument` to search one document's chats.")
@AgentToolMeta("memory", effects = [ToolEffect.FILESYSTEM_READ])
data class HistorySearchRequest(
    @JsonPropertyDescription("Phrase to find, case-insensitive; empty lists blocks from the start of each chat.")
    val query: String,
    @JsonPropertyDescription("Only chats with this name, or whose name starts with it (a document name); empty for every chat.")
    val chat: String = "",
    @JsonPropertyDescription("Most hits to return, at most 100.")
    val limit: Int = 30,
)

@JsonClassDescription("Read a stretch of a saved AI4 chat as text, each block headed by its address. Use it after HistorySearchRequest, or to " +
    "follow a memory note's sources back to the conversation. Pass from as a chat@address citation to read from that block to the end of its branch; " +
    "omit from and to for the chat's whole current branch. Returns at most maxChars and says where to continue. Fails when the chat or an address " +
    "does not exist. e.g. from = `firstDocument/sketch@t-a3.b2`.")
@AgentToolMeta("memory", effects = [ToolEffect.FILESYSTEM_READ])
data class HistoryReadRequest(
    @JsonPropertyDescription("The chat's name; may be empty when from is a chat@address citation.")
    val chat: String = "",
    @JsonPropertyDescription("First block: an address, or chat@address; empty for the start of the current branch.")
    val from: String = "",
    @JsonPropertyDescription("Last block: an address, or empty for the end of the branch.")
    val to: String = "",
    @JsonPropertyDescription("Page size in characters, 2000 to 40000.")
    val maxChars: Int = 12000,
)

/** One note in a listing or search: everything but the body, plus the body lines that matched. */
data class MemoryNoteEntry(val name: String, val kind: String, val status: String, val description: String,
                           val updated: String, val sources: List<String> = emptyList(), val matches: List<String> = emptyList())

/** What every memory tool answers with; which fields are filled depends on the tool. */
data class MemoryToolResult(
    val success: Boolean,
    /** One sentence about what happened, and what the tool log records. */
    val message: String,
    /** The folder the notes live in. */
    val dir: String = "",
    /** The note read or written, as front matter and body. */
    val note: String = "",
    /** That note's revision, to pass back when replacing it. */
    val revision: String = "",
    /** True when a write was refused because the revision was stale or missing. */
    val conflict: Boolean = false,
    /** The notes a search found. */
    val entries: List<MemoryNoteEntry> = emptyList(),
) : SummarisesForToolLog {
    override fun toolLogSummary(): String = message
}

/** What the history tools answer with. */
data class HistoryToolResult(
    val success: Boolean,
    val message: String,
    /** Search hits, each `chat@address · kind: snippet`. */
    val hits: List<String> = emptyList(),
    /** The text read. */
    val text: String = "",
    /** Where to continue reading, as an address, when the page stopped early. */
    val next: String = "",
) : SummarisesForToolLog {
    override fun toolLogSummary(): String = message
}

internal object MemoryTools {
    private fun entry(n: UserMemory.Note, matches: List<String> = emptyList()) =
        MemoryNoteEntry(n.name, n.kind, n.status, n.description, n.updated, n.sources, matches)

    private inline fun guarded(action: () -> MemoryToolResult): MemoryToolResult =
        try { action() } catch (e: Exception) { MemoryToolResult(false, "Memory tool failed: ${e.message ?: e.javaClass.simpleName}") }

    fun search(m: UserMemory, req: MemorySearchRequest): MemoryToolResult = guarded {
        val q = req.query.trim().lowercase()
        val kind = req.kind.trim().lowercase()
        val found = m.notes().filter { kind.isEmpty() || it.kind == kind }.mapNotNull { n ->
            if (q.isEmpty()) return@mapNotNull entry(n)
            val lines = n.body.lines().filter { it.lowercase().contains(q) }.take(5).map { it.trim().take(240) }
            if (n.name.contains(q) || n.description.lowercase().contains(q) || lines.isNotEmpty()) entry(n, lines) else null
        }
        MemoryToolResult(true, "${found.size} note(s)" + (if (q.isEmpty()) "" else " matching '${req.query}'") + ".", m.dir.path, entries = found)
    }

    fun read(m: UserMemory, req: MemoryReadRequest): MemoryToolResult = guarded {
        val n = m.read(req.name.trim())
            ?: return@guarded MemoryToolResult(false, "Memory note '${req.name}' not found. MemorySearchRequest with an empty query lists them.", m.dir.path)
        MemoryToolResult(true, "Read note '${n.name}'.", m.dir.path, UserMemory.render(n), n.revision)
    }

    fun write(m: UserMemory, req: MemoryWriteRequest): MemoryToolResult = guarded {
        val o = m.write(req.name.trim(), req.description, req.body, req.kind, req.status, req.sources, req.revision.trim(), req.reason)
        MemoryToolResult(o.ok, o.message, m.dir.path, o.note?.let(UserMemory::render).orEmpty(), o.note?.revision.orEmpty(), o.conflict)
    }

    fun delete(m: UserMemory, req: MemoryDeleteRequest): MemoryToolResult = guarded {
        val o = m.delete(req.name.trim(), req.reason)
        MemoryToolResult(o.ok, o.message, m.dir.path)
    }

    fun historySearch(h: ChatHistory, req: HistorySearchRequest): HistoryToolResult = try {
        val hits = h.search(req.query, req.chat.trim(), req.limit.coerceIn(1, 100))
        HistoryToolResult(true, "${hits.size} block(s) in ${h.workspace.name}" + (if (req.query.isBlank()) "." else " matching '${req.query}'."),
            hits.map { "${it.citation} · ${it.kind}: ${it.snippet}" })
    } catch (e: Exception) { HistoryToolResult(false, "History search failed: ${e.message ?: e.javaClass.simpleName}") }

    fun historyRead(h: ChatHistory, req: HistoryReadRequest): HistoryToolResult = try {
        val page = h.read(req.chat.trim(), req.from.trim(), req.to.trim(), req.maxChars.coerceIn(2000, 40_000))
        HistoryToolResult(true, "Read ${page.text.length} characters" + (page.next?.let { "; continues at $it" } ?: "."),
            text = page.text, next = page.next.orEmpty())
    } catch (e: Exception) { HistoryToolResult(false, "History read failed: ${e.message ?: e.javaClass.simpleName}") }
}

internal fun registerMemoryTools(tools: ToolRegistry) {
    tools.register(MemorySearchRequest::class.java, { it.userMemory != null }) { ctx, req -> MemoryTools.search(ctx.userMemory!!, req) }
    tools.register(MemoryReadRequest::class.java, { it.userMemory != null }) { ctx, req -> MemoryTools.read(ctx.userMemory!!, req) }
    tools.register(MemoryWriteRequest::class.java, { it.userMemory != null }) { ctx, req -> MemoryTools.write(ctx.userMemory!!, req) }
    tools.register(MemoryDeleteRequest::class.java, { it.userMemory != null }) { ctx, req -> MemoryTools.delete(ctx.userMemory!!, req) }
    tools.register(HistorySearchRequest::class.java, { it.chatHistory != null }) { ctx, req -> MemoryTools.historySearch(ctx.chatHistory!!, req) }
    tools.register(HistoryReadRequest::class.java, { it.chatHistory != null }) { ctx, req -> MemoryTools.historyRead(ctx.chatHistory!!, req) }
}
