package afield.server

import afield.Handled
import afield.RunsAt
import afield.inMain
import afield.installHandlers
import afield.rawHandlers
import afield.thisSession
import io.ktor.websocket.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.ref.WeakReference
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.reflect.full.superclasses

// -------------------------------------------------------------------------------------------------
// Public surface
// -------------------------------------------------------------------------------------------------

/** Anything you want to expose in the inspector implements this. */
interface Browsable {
    fun heading(): String = this::class.simpleName ?: "…"
    fun dir(): Map<String, Any?>? = null
    fun html(): String? = null
    fun version(): Long = 0
}

/** Wrapper: keep all inspector logic inside this method. If null, treat as null. */
interface HasBrowsable { fun browsable(): Browsable? }

/** Register custom HTML renderers for non-Browsable values (leaf fallback). */
object LeafRenderers {
    private val byClass = ConcurrentHashMap<KClass<*>, MutableList<(Any, Browsable?, String?) -> String?>>()

    fun <T : Any> register(klass: KClass<T>, renderer: (T, Browsable?, String?) -> String?) {
        @Suppress("UNCHECKED_CAST")
        val r = renderer as (Any, Browsable?, String?) -> String?
        byClass.computeIfAbsent(klass) { mutableListOf() }.add(r)
    }

    fun render(v: Any, parent: Browsable?, title: String?): String? {
        val rs = byClass[v::class] ?: return null
        for (r in rs) {
            val out = r(v, parent, title)
            if (out != null) return out
        }
        return null
    }

    internal fun defaultLeaf(v: Any?): String =
        when (v) {
            null -> "<i>null</i>"
            is String -> (ToStringHighlighter.render(v))
            is Number, is Boolean -> (ToStringHighlighter.render(v.toString()))
            else -> (ToStringHighlighter.render(v.toString()))
        }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

// -------------------------------------------------------------------------------------------------
// Inspector core
// -------------------------------------------------------------------------------------------------

typealias RootsProvider = () -> Map<String, Any?>

object Inspector {

    // --- Configuration ---------------------------------------------------------------------------

    fun setRoots(provider: RootsProvider) { rootsProvider = provider }

    /**
     * Make a deep-link URL (path) for an object. Multiple calls can produce different tokens; tokens
     * are process-local and may expire if the object is GC'ed. If you need absolute URLs, prepend your
     * own origin, e.g. "http://localhost:9092" + linkPathFor(obj).
     */
    fun linkPathFor(obj: Any, browsePath: String = "/browse", uid : String? = null): String {
        val token = uid ?: "r${idGen.getAndIncrement()}"
        linkTokens[token] = WeakReference(obj)
        return "$browsePath?root=$token"
    }

    /** Convenience alias naming matches your phrasing. */
    @OptIn(ExperimentalUuidApi::class)
    fun linkFor(obj: Any, browsePath: String = "/browse", uid : String = Uuid.random().toString()): String = linkPathFor(obj, browsePath, uid)

    /** Notify watchers that a Browsable changed. Accepts Browsable, HasBrowsable, or raw object. */
    fun changed(obj: Any) {
        val target = unwrapToBrowsable(obj) ?: obj
        val ids = items.entries.asSequence().filter { it.value.obj === target }.map { it.key }.toList()
        if (ids.isEmpty()) return
        val version = (unwrapToBrowsable(target) ?: (target as? Browsable))?.version() ?: 0L
        val push = ChangedPush(nodeId = "", version = version)
        val msgFor: (String) -> String = { nodeId -> json.encodeToString(ChangedPush.serializer(), push.copy(nodeId = nodeId)) }
        ids.forEach { nodeId ->
            nodeVersions[nodeId] = version
            subscribersByNode[nodeId]?.forEach { client -> runCatching { client.send(msgFor(nodeId)) } }
        }
    }

    // --- Internal plumbing -----------------------------------------------------------------------

    private var rootsProvider: RootsProvider = { emptyMap() }

    private enum class Kind { THUNK, LEAF, DIR }

    private data class Item(
        val kind: Kind,
        val thunk: (() -> Resolved)? = null,
        val resolved: Resolved? = null,
        val obj: Any? = null
    )

    private sealed interface Resolved {
        data class Leaf(val html: () -> String, val obj: Any?) : Resolved
        // NOTE: obj is Any? (not Browsable) so Inspector.changed(rawObj) will match.
        data class Dir(val heading: String, val entries: () -> Map<String, Any?>, val obj: Any?) : Resolved
    }

    private val items = ConcurrentHashMap<String, Item>()
    private val idGen = AtomicLong(1)
    private fun newId(): String = "n" + idGen.getAndIncrement()

    // deep-link token store (weak so links don't pin objects)
    private val linkTokens = ConcurrentHashMap<String, WeakReference<Any>>()

    private data class Client(val key: Any, val send: (String) -> Unit) {
        override fun equals(other: Any?) = other is Client && other.key === key
        override fun hashCode(): Int = System.identityHashCode(key)
    }

    private val subscribersByNode = ConcurrentHashMap<String, MutableSet<Client>>()
    private fun subscribe(nodeId: String, client: Client) { subscribersByNode.getOrPut(nodeId) { CopyOnWriteArraySet() }.add(client) }
    private fun unsubscribe(nodeId: String, client: Client) { subscribersByNode[nodeId]?.remove(client) }
    // Track last sent versions so we can push when version() ticks without explicit change().
    private val nodeVersions = ConcurrentHashMap<String, Long>()

    private fun unwrapToBrowsable(any: Any?): Browsable? = when (any) {
        is Browsable   -> any
        is HasBrowsable -> any.browsable()      // may be null
        else           -> null
    }

    private fun resolve(any: Any?, parent: Browsable?, title: String?): Resolved {
        val b0 = unwrapToBrowsable(any)

        return when {
            // 1) The object already is Browsable (or HasBrowsable)
            b0 != null -> {
                val dirNow = b0.dir()
                if (dirNow != null) {
                    Resolved.Dir(
                        heading = b0.heading(),
                        entries = { b0.dir() ?: emptyMap() },
                        obj = any                                 // keep RAW object for change matching
                    )
                } else {
                    Resolved.Leaf(
                        html = {
                            b0.html()
                                ?: LeafRenderers.render(b0, parent, title)
                                ?: LeafRenderers.defaultLeaf(b0)
                        },
                        obj = any
                    )
                }
            }

            // 2) Treat HasBrowsable with null like real null
            any == null || any is HasBrowsable -> Resolved.Leaf(html = { "<i>null</i>" }, obj = null)

            // 3) Try adapters: turn arbitrary types into a Browsable wrapper
            else -> {
                val adapted = BrowsableAdapters.adapt(any, parent, title)
                if (adapted != null) {
                    val dirNow = adapted.dir()
                    if (dirNow != null) {
                        Resolved.Dir(
                            heading = adapted.heading(),
                            entries = { adapted.dir() ?: emptyMap() },
                            obj = any                             // RAW object (so Inspector.changed(any) works)
                        )
                    } else {
                        Resolved.Leaf(
                            html = {
                                adapted.html()
                                    ?: LeafRenderers.render(adapted, parent, title)
                                    ?: LeafRenderers.defaultLeaf(adapted)
                            },
                            obj = any
                        )
                    }
                } else {
                    // 4) Fall back to leaf rendering (existing behavior)
                    Resolved.Leaf(
                        html = {
                            LeafRenderers.render(any, parent, title)
                                ?: LeafRenderers.defaultLeaf(any)
                        },
                        obj = any
                    )
                }
            }
        }
    }



    // --- Protocol --------------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable private data class Request(
        val channel: String? = null,
        val reqId: String,
        val op: String,
        val id: String? = null,
        val watch: Boolean? = null,
        val root: String? = null // NEW: deep-link token
    )

    @Serializable private data class DirResp(
        val reqId: String,
        val ok: Boolean = true,
        val type: String = "dir",
        val parentId: String? = null,
        val id: String? = null,
        val heading: String? = null,
        val entries: List<List<String>>,
        val version: Long? = null
    )

    @Serializable private data class LeafResp(
        val reqId: String,
        val ok: Boolean = true,
        val type: String = "leaf",
        @SerialName("nodeId") val nodeId: String,
        val html: String? = null,
        val value: String? = null,
        val version: Long? = null
    )

    @Serializable private data class ErrorResp(val reqId: String, val ok: Boolean = false, val error: String)
    @Serializable private data class ChangedPush(val type: String = "changed", val nodeId: String, val version: Long? = null)

    fun handle(text: String, clientKey: Any, push: (String) -> Unit): String {
        val client = Client(clientKey) { payload -> push(payload) }
        return try {
            val req = json.decodeFromString(Request.serializer(), text)
            if (req.channel != null && req.channel != "__browsing__") return ""
            when (req.op) {
                "getRoots" -> {
                    // If a deep-link token is provided, start from THAT object (as the unseen parent).
                    val token = req.root
                    if (token != null) {
                        val objAny = linkTokens[token]?.get()
                        if (objAny == null) {
                            return json.encodeToString(ErrorResp.serializer(), ErrorResp(req.reqId, error = "Unknown or expired root token: $token"))
                        }
                        val rootResolved = resolve(objAny, null, "Root")
                        val rootId = newId()
                        return when (rootResolved) {
                            is Resolved.Dir -> {
                                items[rootId] = Item(Kind.DIR, resolved = rootResolved, obj = rootResolved.obj)
                                val entries = rootResolved.entries().map { (name, child) ->
                                    val cid = newId()
                                    val thunk = { resolve(child, (rootResolved.obj as? Browsable), name) }
                                    items[cid] = Item(Kind.THUNK, thunk = thunk)
                                    listOf(name, cid)
                                }
                                val ver = (rootResolved.obj as? Browsable)?.version()
                                ver?.let { nodeVersions[rootId] = it }
                                json.encodeToString(
                                    DirResp.serializer(),
                                    DirResp(
                                        reqId = req.reqId,
                                        parentId = null,
                                        id = rootId,                     // expose the node id for this root
                                        heading = rootResolved.heading,  // first column title = the object's heading
                                        entries = entries,
                                        version = ver
                                    )
                                )
                            }
                            is Resolved.Leaf -> {
                                items[rootId] = Item(Kind.LEAF, resolved = rootResolved, obj = rootResolved.obj)
                                val version = (rootResolved.obj as? Browsable)?.version()
                                version?.let { nodeVersions[rootId] = it }
                                json.encodeToString(
                                    LeafResp.serializer(),
                                    LeafResp(reqId = req.reqId, nodeId = rootId, html = rootResolved.html(), version = version)
                                )
                            }
                        }
                    }

                    // Default: enumerate global roots
                    val roots = rootsProvider()
                    val entries = roots.map { (name, value) ->
                        val id = newId()
                        val thunk = { resolve(value, null, name) }
                        items[id] = Item(Kind.THUNK, thunk = thunk)
                        listOf(name, id)
                    }
                    json.encodeToString(
                        DirResp.serializer(),
                        DirResp(reqId = req.reqId, parentId = null, id = null, heading = "Root", entries = entries)
                    )
                }

                "open" -> {
                    val id = req.id ?: return json.encodeToString(ErrorResp.serializer(), ErrorResp(req.reqId, error = "Missing id"))
                    val item = items[id] ?: return json.encodeToString(ErrorResp.serializer(), ErrorResp(req.reqId, error = "Unknown id: $id"))
                    if (req.watch == true) subscribe(id, client)

                    when (item.kind) {
                        Kind.THUNK -> {
                            val resolved = item.thunk!!.invoke()
                            when (resolved) {
                                is Resolved.Leaf -> {
                                    items[id] = Item(Kind.LEAF, resolved = resolved, obj = resolved.obj)
                                    val version = (resolved.obj as? Browsable)?.version()
                                    version?.let { nodeVersions[id] = it }
                                    json.encodeToString(LeafResp.serializer(), LeafResp(req.reqId, nodeId = id, html = resolved.html(), version = version))
                                }
                                is Resolved.Dir -> {
                                    items[id] = Item(Kind.DIR, resolved = resolved, obj = resolved.obj)
                                    val entries = resolved.entries().map { (name, child) ->
                                        val cid = newId()
                                        val thunk = { resolve(child, (resolved.obj as? Browsable), name) }
                                        items[cid] = Item(Kind.THUNK, thunk = thunk)
                                        listOf(name, cid)
                                    }
                                    val ver = (resolved.obj as? Browsable)?.version()
                                    ver?.let { nodeVersions[id] = it }
                                    json.encodeToString(
                                        DirResp.serializer(),
                                        DirResp(req.reqId, parentId = id, id = id, heading = resolved.heading, entries = entries, version = ver)
                                    )
                                }
                            }
                        }
                        Kind.LEAF -> {
                            val leaf = item.resolved as Resolved.Leaf
                            val version = (leaf.obj as? Browsable)?.version()
                            version?.let { nodeVersions[id] = it }
                            json.encodeToString(LeafResp.serializer(), LeafResp(req.reqId, nodeId = id, html = leaf.html(), version = version))
                        }
                        Kind.DIR -> {
                            val dir = item.resolved as Resolved.Dir
                            val entries = dir.entries().map { (name, child) ->
                                val cid = newId()
                                val thunk = { resolve(child, (dir.obj as? Browsable), name) }
                                items[cid] = Item(Kind.THUNK, thunk = thunk)
                                listOf(name, cid)
                            }
                            val ver = (dir.obj as? Browsable)?.version()
                            ver?.let { nodeVersions[id] = it }
                            json.encodeToString(
                                DirResp.serializer(),
                                DirResp(req.reqId, parentId = id, id = id, heading = dir.heading, entries = entries, version = ver)
                            )
                        }
                    }
                }

                "unsubscribe" -> {
                    val id = req.id ?: return json.encodeToString(ErrorResp.serializer(), ErrorResp(req.reqId, error = "Missing id"))
                    unsubscribe(id, client)
                    json.encodeToString(DirResp.serializer(), DirResp(reqId = req.reqId, ok = true, entries = emptyList()))
                }

                else -> json.encodeToString(ErrorResp.serializer(), ErrorResp(req.reqId, error = "Unknown op: ${req.op}"))
            }
        } catch (t: Throwable) {
            val reqId = runCatching { json.decodeFromString(Request.serializer(), text).reqId }.getOrElse { "?" }
            json.encodeToString(ErrorResp.serializer(), ErrorResp(reqId, ok = false, error = (t.message ?: t.toString())))
        }
    }

    fun handle(text: String): String = handle(text, clientKey = Any()) { /* drop pushes */ }

    // Periodic version polling for watched nodes (best-effort, avoids UI focus issues).
    private val versionPoller = java.util.Timer("inspector-version-poll", true).apply {
        schedule(object : TimerTask() {
            override fun run() {
                try {
                    pollVersions()
                } catch (_: Throwable) { /* ignore */ }
            }
        }, 1000L, 1000L)
    }

    private fun pollVersions() {
        // Snapshot subscriber node ids to avoid holding locks while querying versions.
        val nodes = subscribersByNode.keys.toList()
        for (id in nodes) {
            val item = items[id] ?: continue
            val obj = when (item.kind) {
                Kind.THUNK -> null
                Kind.LEAF  -> item.resolved?.let { (it as? Resolved.Leaf)?.obj }
                Kind.DIR   -> item.resolved?.let { (it as? Resolved.Dir)?.obj }
            } ?: continue
            val b = unwrapToBrowsable(obj) ?: (obj as? Browsable) ?: continue
            val v = b.version()
            val last = nodeVersions[id]
            if (last == null || last != v) {
                nodeVersions[id] = v
                val push = ChangedPush(nodeId = id, version = v)
                val msg = json.encodeToString(ChangedPush.serializer(), push)
                subscribersByNode[id]?.forEach { client -> runCatching { client.send(msg) } }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Handler wiring
// -------------------------------------------------------------------------------------------------

@RunsAt(installHandlers)
fun installInspectorHandler() {
    registerExampleRoots()
    registerExampleAdapters()
    rawHandlers.getOrPut("__browsing__") { mutableSetOf() }.add { text ->
        val s = thisSession.get()
        inMain {
            val out = Inspector.handle(text, clientKey = s) { pushJson -> inMain { s.send(pushJson) } }
            if (out.isNotEmpty()) s.send(out)
        }
        Handled.yes
    }
}

// -------------------------------------------------------------------------------------------------
// Example/demo nodes
// -------------------------------------------------------------------------------------------------

class Clock : Browsable {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val ver = AtomicLong(0)
    private val timer = Timer("clock", true).apply {
        scheduleAtFixedRate(object : TimerTask() {
            override fun run() { ver.incrementAndGet(); Inspector.changed(this@Clock) }
        }, 0L, 1000L)
    }
    override fun heading(): String = "Clock"
    override fun html(): String = "<div style='font-size:24px'><b>${LocalTime.now().format(fmt)}</b></div>"
    override fun version(): Long = ver.get()
}

class Heap : Browsable {
    private val ver = AtomicLong(0)
    private val rt = Runtime.getRuntime()
    private val timer = Timer("heap", true).apply {
        scheduleAtFixedRate(object : TimerTask() {
            override fun run() { ver.incrementAndGet(); Inspector.changed(this@Heap) }
        }, 0L, 2000L)
    }
    override fun heading(): String = "Heap"
    override fun html(): String {
        val used = (rt.totalMemory() - rt.freeMemory()).toDouble() / (1024 * 1024)
        val total = rt.totalMemory().toDouble() / (1024 * 1024)
        val pct = if (total > 0) (used / total * 100).roundToInt() else 0
        return """
            <div style="font-family:inherit">
              <div>Used: <b>${"%.1f".format(used)} MiB</b> / ${"%.1f".format(total)} MiB</div>
              <div style="margin-top:6px;height:6px;background:#111;border:1px solid #222;border-radius:3px;overflow:hidden;">
                <div style="height:100%;width:${pct}%;background:#fff;opacity:.35;"></div>
              </div>
            </div>
        """.trimIndent()
    }
    override fun version(): Long = ver.get()
}

class Numbers(private val n: Int) : Browsable {
    override fun heading(): String = "Numbers"
    override fun dir(): Map<String, Any?> = mapOf("$n" to Numbers(n + 1))
}

class ThreadList : Browsable {
    override fun heading(): String = "Threads"
    override fun html(): String = ToStringHighlighter.render(Thread.getAllStackTraces().keys.joinToString("\n") { it.name })
}

@Suppress("unused")
fun registerExampleRoots() {

    LeafRenderers.register(String::class) { s, _, _ ->
        ToStringHighlighter.render(s) // last resort: always handle
    }

    LeafRenderers.register(String::class) { s, _, title ->
        if (title?.startsWith("code:") == true)
            "<pre style='white-space:pre-wrap;margin:0'>${escapeHtml(s)}</pre>"
        else null
    }
    LeafRenderers.register(String::class) { s, _, _ -> "<div>${escapeHtml(s)}</div>" }

    val clock = Clock()
    val heap  = Heap()

    val url = Inspector.linkFor(clock)        // "/browse?root=r123"
    println(" url for clock is ${url}")

    Inspector.setRoots {
        mapOf(
            "Clock" to clock,
            "Heap" to heap,
            "Numbers" to Numbers(0),
            "Threads" to ThreadList(),
            "Example" to object : Browsable {
                override fun heading(): String = "Example"
                override fun dir(): Map<String, Any?> = mapOf(
                    "String" to "Hello, world! + ${Math.random()}",
                    "List" to listOf(1, 2, 3),
                    "Map" to mapOf(1 to "one", 2 to "two"),
                )
            }
        )
    }
}


typealias AdapterFn = (Any, Browsable?, String?) -> Browsable?
object BrowsableAdapters {

    // Keep registration order per type
    private val byClass = ConcurrentHashMap<KClass<*>, CopyOnWriteArrayList<AdapterFn>>()

    // Cache of "runtime KClass -> flattened adapters considering full hierarchy"
    private val cache = ConcurrentHashMap<KClass<*>, List<AdapterFn>>()

    fun <T : Any> register(klass: KClass<T>, adapter: (T, Browsable?, String?) -> Browsable?) {
        @Suppress("UNCHECKED_CAST")
        val fn = adapter as AdapterFn
        byClass.computeIfAbsent(klass) { CopyOnWriteArrayList() }.add(fn)
        cache.clear() // new registration may affect many subclasses; keep it simple & safe
    }

    fun adapt(v: Any, parent: Browsable?, title: String?): Browsable? {
        // Compute once per runtime class, then reuse
        val chain = cache.computeIfAbsent(v::class) { resolveChain(it) }
        for (fn in chain) {
            val out = fn(v, parent, title)
            if (out != null) return out
        }
        return null
    }

    private fun resolveChain(k: KClass<*>): List<AdapterFn> {
        // Collect KClasses in "most-specific-first" order, without duplicates.
        val ordered = LinkedHashSet<KClass<*>>()

        fun addK(kc: KClass<*>) {
            if (!ordered.add(kc)) return

            // 1) Prefer immediate interfaces (Java view), then recurse (more specific than superclass)
            kc.java.interfaces.forEach { addK(it.kotlin) }

            // 2) Kotlin supertypes (captures mapped types like kotlin.collections.Map)
            kc.supertypes.forEach { (it.classifier as? KClass<*>)?.let(::addK) }

            // 3) Then the superclass (Java view)
            kc.java.superclass?.let { addK(it.kotlin) }
        }

        addK(k)
        ordered.add(Any::class) // allow very generic catch-alls if you want

        // Flatten adapters in that order, preserving registration order within each type
        val out = ArrayList<AdapterFn>()
        for (t in ordered) {
            byClass[t]?.let { out.addAll(it) }
        }
        return out
    }
}

@Suppress("UNCHECKED_CAST")
fun registerExampleAdapters() {
    BrowsableAdapters.register(Map::class) { m: Map<*, *>, parent, title ->
        val allStrings = m.keys.all { it is String } && m.values.all { it is String }
        if (!allStrings) return@register null

        val map = m as Map<String, String>
        object : Browsable {
            override fun heading(): String = title ?: "Map"
            override fun dir(): Map<String, Any?> = map   // values are Strings -> leaves
            // optional: provide a version if the map is known to change
            // override fun version(): Long = (parent as? SomeParent)?.version() ?: 0
        }
    }
}

// Drop-in general-purpose .toString() highlighter for Strings.
object ToStringHighlighter {
    // Very forgiving number pattern: 123, -1.23, 1_000.5, 1e9, -3.2E-4
    private val numberRegex = Regex("""^[+-]?\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?$""")

    // Punctuation we want to show as separate tokens (we deliberately allow '.', '_' and '-' to stay inside words)
    private val punctDelims: Set<Char> = setOf(
        ',', ';', ':', '!', '?',
        '(', ')', '[', ']', '{', '}',
        '<', '>', '/', '\\', '|',
        '@', '#', '$', '%', '^', '&', '*',
        '~', '`', '"', '\''
    )

    fun render(src: String): String {
        val sb = StringBuilder((src.length * 1.25).toInt())
        sb.append("<div class='tostr' style='white-space:pre-wrap;margin:0;font-family:inherit'>")

        var i = 0
        fun isWs(c: Char) = c.isWhitespace()
        fun isPunct(c: Char) = c in punctDelims
        fun isWordLetter(c: Char) = Character.isLetter(c.code)
        fun isDigit(c: Char) = Character.isDigit(c.code)

        while (i < src.length) {
            val c = src[i]

            // 1) Whitespace: keep as-is (pre-wrap preserves it)
            if (isWs(c)) {
                sb.append(c)
                i++
                continue
            }

            // 2) Punctuation run
            if (isPunct(c)) {
                var j = i + 1
                while (j < src.length && isPunct(src[j])) j++
                val token = src.substring(i, j)
                sb.append("<span class='tok punc'>").append(escapeHtml(token)).append("</span>")
                i = j
                continue
            }

            // 3) Non-whitespace, non-punctuation run (could be word/number/symword)
            var j = i
            while (j < src.length && !isWs(src[j]) && !isPunct(src[j])) j++
            val part = src.substring(i, j)

            val type = classify(part)
            val cls = when (type) {
                Tok.NUM    -> "num"
                Tok.WORD   -> "word"
                Tok.SYMWRD -> "sym"
            }
            sb.append("<span class='tok ").append(cls).append("'>").append(escapeHtml(part)).append("</span>")
            i = j
        }

        sb.append("</div>")
        return sb.toString()
    }

    private enum class Tok { NUM, WORD, SYMWRD }

    private fun classify(part: String): Tok {
        if (part.isEmpty()) return Tok.SYMWRD

        // Prefer number if it matches the relaxed pattern
        if (numberRegex.matches(part)) return Tok.NUM

        // WORD = letters-only (unicode aware)
        var allLetters = true
        for (ch in part) {
            if (!Character.isLetter(ch.code)) { allLetters = false; break }
        }
        if (allLetters) return Tok.WORD

        // Otherwise treat as a word-with-symbols
        return Tok.SYMWRD
    }

}


// -------------------------------------------------------------------------------------------------
// Utils
// -------------------------------------------------------------------------------------------------

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
