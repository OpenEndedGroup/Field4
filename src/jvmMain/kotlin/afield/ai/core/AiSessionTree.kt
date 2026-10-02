package afield.ai.core

import afield.Box
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * # A box's AI conversation as a tree, not a list
 *
 * `ai4_chat_history` is one markdown string: what the panel shows, what a new provider thread is seeded
 * from, and what a person can open and read. It stays exactly that. Beside it, in the `ai4_session`
 * property (a `<box>_ai4_session_.jsonl` sidecar), this records the same conversation as entries with
 * parents — Pi's session tree (earendil-works/pi) — so a turn can be rewound to, a request edited and
 * re-sent, and the abandoned branch kept rather than overwritten.
 *
 * ## The markdown stays the truth
 *
 * Every node's `markdown` is the text it appended to the transcript; the transcript for a node is the
 * concatenation along its path from the root ([Session.render]). The tree never decides what the panel
 * shows on its own: before recording a turn it reconciles with the markdown as it actually is. Markdown
 * that has grown past the head (a verdict block an agent task appended, a `/screenshot`) becomes an
 * `append` node; markdown that no longer starts with the head (a hand edit, a sidecar reloaded from
 * disk) becomes a new `import` root. The markdown always wins, and old branches are kept.
 *
 * ## Lines
 *
 * One JSON object per line, discriminated by `t`:
 * - `header` — `{"t":"header","v":1}`
 * - `import` / `append` / `turn` — nodes: `id`, `parent`, `markdown`; a `turn` also carries `user` (the
 *   request), `kind`, `property`, `checkpoint` (the id below), `model`, `usage`, `world` and `memory`
 * - `checkpoint` — what the agent was *able* to do: provider and model, the instruction hash and
 *   fragments, the tool set (hash and names), box-authored tools with their owner and source revision,
 *   the provider-settings revision, and the Field build. Written only when that changes along the path.
 * - `head` — `{"t":"head","id":...,"renderedSha":...}`; the last one wins.
 *
 * Unknown keys and unknown line types are kept as they are, and a line that does not parse is skipped,
 * so a newer or a damaged file never loses the rest. Images in node markdown are stored as
 * `ai4img:<sha>.<ext>` references to `<document>/ai4_blobs/` ([AiImageBlobs]); the `.md` keeps them inline,
 * so nothing that reads the markdown changes.
 *
 * ## State that follows a branch
 *
 * Pi's lesson: rebuild branch state from the branch, never by replaying every event. A `turn` stores the
 * agent's scratchpad and checklist as they were after it; rewinding restores them from the node rewound
 * to. The document itself is not branch state — its edits are undone separately, and only when asked
 * (see `ai-chat-rewind-world`).
 */
object AiSessionTree {
    const val PROPERTY = "ai4_session"
    private const val NODE_TYPES = "import,append,turn"
    private val lock = Any()

    class Session internal constructor(val lines: MutableList<JsonObject>) {
        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        internal fun nodes(): Map<String, JsonObject> =
            lines.filter { it.str("t") in NODE_TYPES.split(',') }.associateBy { it.str("id").orEmpty() }

        fun head(): String? = lines.lastOrNull { it.str("t") == "head" }?.str("id")?.takeIf { it in nodes() }

        /** The nodes from the root to [id], in order. */
        fun path(id: String?): List<JsonObject> {
            val byId = nodes()
            val out = ArrayDeque<JsonObject>()
            var cursor = id
            val seen = HashSet<String>()
            while (cursor != null && seen.add(cursor)) {
                val node = byId[cursor] ?: break
                out.addFirst(node)
                cursor = node.str("parent")
            }
            return out.toList()
        }

        /** The transcript at [id] with image references unresolved. */
        fun renderRaw(id: String?): String = path(id).joinToString("") { it.str("markdown").orEmpty() }

        /** The checkpoint in force at [id]: the last one a turn on its path refers to. */
        fun checkpointAt(id: String?): JsonObject? {
            val ref = path(id).lastOrNull { it.str("checkpoint") != null }?.str("checkpoint") ?: return null
            return lines.lastOrNull { it.str("t") == "checkpoint" && it.str("id") == ref }
        }

        /** Every node no other node names as its parent: the tip of each branch, newest last. */
        fun tips(): List<JsonObject> {
            val all = nodes()
            val parents = all.values.mapNotNull { it.str("parent") }.toSet()
            return lines.filter { it.str("t") in NODE_TYPES.split(',') && it.str("id") !in parents }
        }

        fun text(): String = lines.joinToString("\n") { it.toString() } + "\n"
    }

    fun load(box: Box): Session {
        val raw = box.getPropertyRaw(PROPERTY) as? String ?: ""
        val lines = raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        }.toMutableList()
        if (lines.none { (it["t"] as? JsonPrimitive)?.contentOrNull == "header" }) {
            lines.add(0, buildJsonObject { put("t", "header"); put("v", 1) })
        }
        return Session(lines)
    }

    private fun save(box: Box, session: Session) {
        // Only the newest head line matters; keep the file from growing one line per turn for nothing.
        val lastHead = session.lines.indexOfLast { (it["t"] as? JsonPrimitive)?.contentOrNull == "head" }
        val kept = session.lines.filterIndexed { i, line -> (line["t"] as? JsonPrimitive)?.contentOrNull != "head" || i == lastHead }
        val text = Session(kept.toMutableList()).text()
        if ((box.getPropertyRaw(PROPERTY) as? String) == text) return
        box.setPropertyRaw(PROPERTY, text, doResync = false)
        if (box.isInsideInitialized() && box.inside.writesToDisk) box.inside.markSidecarPending(box.id, PROPERTY)
    }

    fun clear(box: Box) {
        if ((box.getPropertyRaw(PROPERTY) as? String).isNullOrEmpty()) return
        box.setPropertyRaw(PROPERTY, "", doResync = false)
        if (box.isInsideInitialized() && box.inside.writesToDisk) box.inside.markSidecarPending(box.id, PROPERTY)
    }

    private fun newId(prefix: String) = prefix + "-" + UUID.randomUUID().toString().take(12)

    private fun node(type: String, parent: String?, markdown: String, extra: Map<String, JsonElement> = emptyMap()): JsonObject =
        buildJsonObject {
            put("t", type)
            put("id", newId(type.take(1)))
            put("parent", parent?.let(::JsonPrimitive) ?: JsonNull)
            put("ts", System.currentTimeMillis())
            put("markdown", markdown)
            extra.forEach { (k, v) -> put(k, v) }
        }

    private fun setHead(session: Session, id: String, rendered: String) {
        session.lines += buildJsonObject { put("t", "head"); put("id", id); put("renderedSha", sha256(rendered).take(16)) }
    }

    /**
     * Bring [session]'s head into line with [markdown]: nothing if it already renders to it, an `append` if
     * the markdown grew past it, a new `import` root otherwise. Returns the head's id afterwards.
     */
    private fun reconcile(box: Box, session: Session, markdown: String): String? {
        val head = session.head()
        val rendered = AiImageBlobs.inline(session.renderRaw(head), box)
        if (head != null && rendered == markdown) return head
        if (head == null && markdown.isEmpty()) return null
        val grown = head != null && markdown.startsWith(rendered)
        val added = if (grown) node("append", head, AiImageBlobs.extract(markdown.substring(rendered.length), box))
            else node("import", null, AiImageBlobs.extract(markdown, box))
        session.lines += added
        val id = (added["id"] as JsonPrimitive).content
        setHead(session, id, markdown)
        return id
    }

    /** What a turn knew about itself when it finished; the installer fills it from the turn's result. */
    data class TurnMeta(
        val kind: String,
        val property: String,
        val context: ContextReport? = null,
        val model: afield.AiChatModelIdentity? = null,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val cacheReadTokens: Long? = null,
        val rounds: Int = -1,
        val memory: AgentMemoryState.Snapshot? = null,
        val world: Map<String, Long> = emptyMap(),
        /** OperationTranscript cursors around the turn, and the origin its edits were recorded under. */
        val opFrom: Int = -1,
        val opTo: Int = -1,
        val origin: String = "",
        val createdBoxes: List<String> = emptyList(),
    )

    /**
     * Record one finished turn: the transcript went from [before] (when the turn started) to [after]. The
     * head is first reconciled with [before], so anything that changed the markdown in between is kept as
     * its own node. Returns the new turn's id, or null when the turn added nothing.
     */
    fun recordTurn(box: Box, before: String, after: String, meta: TurnMeta): String? = synchronized(lock) {
        val session = load(box)
        val head = reconcile(box, session, before)
        if (after == before) { save(box, session); return null }
        if (!after.startsWith(before)) {
            reconcile(box, session, after)
            save(box, session)
            return null
        }
        val delta = after.substring(before.length)
        val checkpoint = checkpointFor(meta)
        val checkpointHash = (checkpoint["hash"] as JsonPrimitive).content
        val inForce = session.checkpointAt(head)
        val checkpointId = if (inForce != null && (inForce["hash"] as? JsonPrimitive)?.content == checkpointHash) {
            (inForce["id"] as JsonPrimitive).content
        } else {
            session.lines += checkpoint
            (checkpoint["id"] as JsonPrimitive).content
        }
        val turn = node("turn", head, AiImageBlobs.extract(delta, box), buildMap {
            put("kind", JsonPrimitive(meta.kind))
            put("property", JsonPrimitive(meta.property))
            put("user", JsonPrimitive(userText(delta)))
            put("checkpoint", JsonPrimitive(checkpointId))
            meta.model?.let { m ->
                put("model", buildJsonObject {
                    put("provider", m.provider); put("requested", m.requestedModel)
                    m.responseModel?.let { put("response", it) }
                })
            }
            put("usage", buildJsonObject {
                meta.inputTokens?.let { put("in", it) }
                meta.outputTokens?.let { put("out", it) }
                meta.cacheReadTokens?.let { put("cacheRead", it) }
                put("rounds", meta.rounds)
            })
            if (meta.world.isNotEmpty()) put("world", buildJsonObject { meta.world.forEach { (k, v) -> put(k, v) } })
            if (meta.opFrom >= 0) put("op", buildJsonObject {
                put("from", meta.opFrom); put("to", meta.opTo); put("origin", meta.origin)
                put("createdBoxes", buildJsonArray { meta.createdBoxes.forEach { add(JsonPrimitive(it)) } })
            })
            meta.memory?.let { mem ->
                put("memory", buildJsonObject { put("scratchpad", mem.scratchpad); put("checklist", mem.checklistJson) })
            }
        })
        session.lines += turn
        val id = (turn["id"] as JsonPrimitive).content
        setHead(session, id, after)
        save(box, session)
        id
    }

    /** The request of a turn: the text of the first `<<you>>` block in what it appended. */
    internal fun userText(delta: String): String {
        val start = delta.indexOf("<<you>>\n").takeIf { it >= 0 } ?: return ""
        val body = delta.substring(start + "<<you>>\n".length)
        val end = listOf("\n<<ai>>", "\n<<you>>", "\n<<progress").map { body.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: body.length
        return body.substring(0, end).trim()
    }

    /** A capability checkpoint for [meta], with a hash over everything but its own id. */
    internal fun checkpointFor(meta: TurnMeta): JsonObject {
        val build = FieldBuildInfo.current()
        val body = buildJsonObject {
            put("t", "checkpoint")
            meta.model?.let { put("provider", it.provider); put("requestedModel", it.requestedModel) }
            meta.context?.let { c ->
                put("placement", c.placement)
                put("instructionHash", c.instructionHash)
                put("fragments", buildJsonArray { c.fragments.forEach { f -> add(buildJsonObject { put("source", f.source); put("sha8", f.sha8); put("chars", f.chars) }) } })
                put("toolsetHash", sha256(c.tools.sorted().joinToString(",")).take(16))
                put("tools", buildJsonArray { c.tools.sorted().forEach { add(JsonPrimitive(it)) } })
                put("dynamicTools", buildJsonArray { c.dynamicTools.forEach { d -> add(buildJsonObject { put("name", d.name); put("box", d.box); put("sourceRevision", d.sourceRevision) }) } })
            }
            put("providerConfigRevision", aiProviderConfigRevision())
            put("fieldBuild", buildJsonObject { put("sha", build.sha); put("dirty", build.dirty); put("diffSha", build.diffSha); put("source", build.source) })
        }
        val hash = sha256(body.toString()).take(16)
        return JsonObject(body + mapOf("id" to JsonPrimitive(newId("c")), "hash" to JsonPrimitive(hash)))
    }

    // ---- rewinding (used by the ai-chat-history handler) ----------------------------------------------

    /** One user turn on the active path, as the panel numbers them (0-based, in order of appearance). */
    data class UserTurn(val ordinal: Int, val text: String, val nodeId: String, val offsetInNode: Int)

    /** The user turns on the head's path, matching the panel's `<<you>>` blocks one for one. */
    fun userTurns(session: Session): List<UserTurn> {
        val out = mutableListOf<UserTurn>()
        session.path(session.head()).forEach { node ->
            val md = (node["markdown"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val id = (node["id"] as JsonPrimitive).content
            var from = 0
            while (true) {
                val at = md.indexOf("<<you>>\n", from).takeIf { it >= 0 } ?: break
                val validStart = at == 0 || md[at - 1] == '\n'
                if (validStart) out += UserTurn(out.size, userText(md.substring(at)), id, at)
                from = at + 1
            }
        }
        return out
    }

    data class Rewind(val markdown: String, val restoredMemory: AgentMemoryState.Snapshot?, val abandoned: List<JsonObject>, val userText: String)

    /**
     * Move the head to just before user turn [ordinal] on the active path, keeping everything after it as a
     * branch. [expectedText] must match that turn's request (the panel's copy and the server's must agree).
     * Inside an imported block the earlier part becomes a new root, because an import has no finer nodes.
     */
    fun rewind(box: Box, ordinal: Int, expectedText: String?): Rewind = synchronized(lock) {
        val session = load(box)
        reconcile(box, session, AiBoxConversation.read(box))
        val turns = userTurns(session)
        val target = turns.getOrNull(ordinal) ?: throw IllegalArgumentException("No user turn $ordinal (there are ${turns.size}).")
        if (expectedText != null && target.text.trim() != expectedText.trim()) {
            throw IllegalStateException("The conversation changed under the panel: turn $ordinal is no longer that request.")
        }
        val oldPath = session.path(session.head())
        val nodeIndex = oldPath.indexOfFirst { (it["id"] as JsonPrimitive).content == target.nodeId }
        val node = oldPath[nodeIndex]
        // A turn's text starts with the newline that separates it from the one before; that is not content.
        val survivingPrefix = (node["markdown"] as JsonPrimitive).content.substring(0, target.offsetInNode)
        val newHead: String? = if (survivingPrefix.isBlank()) {
            (node["parent"] as? JsonPrimitive)?.contentOrNull
        } else {
            // Part of a node survives: re-root its prefix (an import, or a turn's own extra blocks).
            val prefix = session.renderRaw((node["parent"] as? JsonPrimitive)?.contentOrNull) + survivingPrefix
            val root = node("import", null, prefix.trimEnd('\n') + "\n")
            session.lines += root
            (root["id"] as JsonPrimitive).content
        }
        val markdown = AiImageBlobs.inline(session.renderRaw(newHead), box)
        if (newHead != null) setHead(session, newHead, markdown)
        else session.lines += buildJsonObject { put("t", "head"); put("id", JsonNull); put("renderedSha", sha256("").take(16)) }
        save(box, session)
        val keptIds = session.path(newHead).map { (it["id"] as JsonPrimitive).content }.toSet()
        val restored = session.path(newHead).lastOrNull { it["memory"] != null }?.let { memoryOf(it) }
        AiBoxConversation.write(box, markdown)
        Rewind(markdown, restored, oldPath.filter { (it["id"] as JsonPrimitive).content !in keptIds && it["t"]?.jsonPrimitive?.contentOrNull == "turn" }, target.text)
    }

    /** Make branch tip [index] (as [branches] numbers them) the head. */
    fun switchTo(box: Box, index: Int): Rewind = synchronized(lock) {
        val session = load(box)
        reconcile(box, session, AiBoxConversation.read(box))
        val tip = session.tips().getOrNull(index) ?: throw IllegalArgumentException("No branch $index.")
        val id = (tip["id"] as JsonPrimitive).content
        val markdown = AiImageBlobs.inline(session.renderRaw(id), box)
        setHead(session, id, markdown)
        save(box, session)
        AiBoxConversation.write(box, markdown)
        Rewind(markdown, session.path(id).lastOrNull { it["memory"] != null }?.let { memoryOf(it) }, emptyList(), "")
    }

    /** The branches as `/branches` lists them: tip, turn count, last request, whether it is the head. */
    fun branches(box: Box): String = synchronized(lock) {
        val session = load(box)
        val head = session.head()
        val tips = session.tips()
        if (tips.isEmpty()) return "_No conversation recorded for this box yet._"
        buildString {
            appendLine("| # | turns | last request | |")
            appendLine("| ---: | ---: | --- | --- |")
            tips.forEachIndexed { i, tip ->
                val id = (tip["id"] as JsonPrimitive).content
                val path = session.path(id)
                val requests = path.flatMap { n ->
                    val md = (n["markdown"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    Regex("(?m)^<<you>>\\n").findAll(md).map { userText(md.substring(it.range.first)) }.toList()
                }
                val last = requests.lastOrNull().orEmpty().replace('\n', ' ').replace("|", "\\|").take(80)
                appendLine("| $i | ${requests.size} | $last | ${if (session.path(head).lastOrNull()?.get("id") == tip["id"]) "**current**" else ""} |")
            }
            append("\nSwitch with `/branch <#>`.")
        }
    }

    private fun memoryOf(node: JsonObject): AgentMemoryState.Snapshot? {
        val mem = node["memory"] as? JsonObject ?: return null
        return AgentMemoryState.Snapshot(
            (mem["scratchpad"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            (mem["checklist"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    /** The `op` record of a turn node, for undoing its edits. */
    fun opOf(node: JsonObject): Triple<Int, Int, String>? {
        val op = node["op"] as? JsonObject ?: return null
        return Triple((op["from"] as JsonPrimitive).content.toInt(), (op["to"] as JsonPrimitive).content.toInt(),
            (op["origin"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }

    fun createdBoxesOf(node: JsonObject): List<String> =
        ((node["op"] as? JsonObject)?.get("createdBoxes") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}

/**
 * Images in session-tree markdown, stored once per document rather than once per line and per backup.
 *
 * `data:image/...;base64,...` becomes `ai4img:<sha16>.<ext>`; the bytes go to `<document>/ai4_blobs/` for a
 * document that writes to disk, and to memory otherwise. Resolving puts the data URL back. The reason is
 * the one `saving.kt` records for chat history: base64 images in a sidecar and every backup of it once
 * reached 127 MB.
 */
object AiImageBlobs {
    private val memory = ConcurrentHashMap<String, String>()
    private val DATA_URL = Regex("data:image/([a-z0-9.+-]+);base64,([A-Za-z0-9+/=]+)")
    private val REF = Regex("ai4img:([0-9a-f]{16})\\.([a-z0-9.+-]+)")

    private fun dir(box: Box): File? =
        runCatching { box.inside.takeIf { it.writesToDisk }?.filename?.let { File(it, "ai4_blobs") } }.getOrNull()

    fun extract(markdown: String, box: Box): String = DATA_URL.replace(markdown) { m ->
        val (type, data) = m.destructured
        val sha = sha256(data).take(16)
        val url = m.value
        memory.putIfAbsent(sha, url)
        dir(box)?.let { d ->
            runCatching {
                val file = File(d, "$sha.$type")
                if (!file.exists()) { d.mkdirs(); file.writeBytes(Base64.getDecoder().decode(data)) }
            }
        }
        "ai4img:$sha.$type"
    }

    fun inline(markdown: String, box: Box): String = REF.replace(markdown) { m ->
        val (sha, type) = m.destructured
        memory[sha] ?: dir(box)?.let { d ->
            runCatching { "data:image/$type;base64," + Base64.getEncoder().encodeToString(File(d, "$sha.$type").readBytes()) }.getOrNull()
                ?.also { memory[sha] = it }
        } ?: "data:image/$type;base64,"
    }
}

/**
 * Which Field this is: the checkout's commit, whether the tree was dirty, and a hash of the diff — what a
 * capability checkpoint records so a transcript says which code its tools ran on. Read once, in the
 * background, from `git` in the working directory (a packaged build may ship `field-build-info.properties`
 * instead, which wins). Not a Gradle-generated resource: one that changes on every commit would make every
 * test task out of date.
 */
object FieldBuildInfo {
    data class Info(val sha: String, val dirty: Boolean, val diffSha: String, val source: String)

    @Volatile
    private var cached: Info? = null

    init {
        Thread({ cached = read() }, "field-build-info").apply { isDaemon = true }.start()
    }

    fun current(): Info = cached ?: Info("", false, "", "pending")

    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(File(System.getProperty("user.dir")))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = process.inputStream.bufferedReader().readText()
        if (process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) out else null
    }.getOrNull()

    private fun read(): Info {
        FieldBuildInfo::class.java.getResourceAsStream("/field-build-info.properties")?.use { stream ->
            val p = java.util.Properties().apply { load(stream) }
            return Info(p.getProperty("sha", ""), p.getProperty("dirty") == "true", p.getProperty("diffSha", ""), "packaged")
        }
        val sha = git("rev-parse", "HEAD")?.trim()?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: return Info("", false, "", "unavailable")
        val dirty = git("status", "--porcelain", "--untracked-files=no")?.isNotBlank() ?: false
        val diffSha = if (dirty) git("diff", "HEAD")?.let { sha256(it).take(16) }.orEmpty() else ""
        return Info(sha, dirty, diffSha, "runtime-git")
    }
}
