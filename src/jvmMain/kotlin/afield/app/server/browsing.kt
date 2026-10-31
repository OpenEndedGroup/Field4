package afield.app.server

import afield.app.protocol.Handled
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.installHandlers
import afield.app.protocol.rawHandlers
import afield.app.server.thisSession
import afield.data.query.graph.GraphWalkOptions
import afield.data.query.graph.GraphWalker
import afield.data.query.graph.LiveGraphEdge
import afield.data.query.graph.LiveGraphInspectable
import afield.data.query.graph.graphPaths
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

/**
 * The object inspector behind `/browse`: a live, clickable tree of any value, reached from a box
 * with [Inspector.linkFor].
 *
 * A value takes part by implementing [Browsable] (a heading, a directory of named children,
 * optional HTML) or [HasBrowsable]. Other values fall back to [BrowsableAdapters] and to the HTML
 * renderers registered with [LeafRenderers]. [installInspectorHandler] installs the socket handler
 * the browser's inspector page talks to.
 */

// -------------------------------------------------------------------------------------------------
// Public surface
// -------------------------------------------------------------------------------------------------

/**
 * Anything you want to expose in the inspector implements this. A [dir] is also this object's
 * [LiveGraphInspectable] edges, so `graphPaths(...)` reaches the same children the inspector lists.
 */
interface Browsable : LiveGraphInspectable {
    fun heading(): String = this::class.simpleName ?: "…"
    fun dir(): Map<String, Any?>? = null
    override fun liveGraphChildren(): Iterable<LiveGraphEdge> =
        dir()?.keys?.map { k -> LiveGraphEdge(k) { dir()?.get(k) } } ?: emptyList()
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
    private data class RetainedLink(val obj: Any, val createdAtMs: Long)

    /** Entries per `open`/`more` reply. A directory larger than this is paged; see [handle]. */
    const val DEFAULT_PAGE = 200
    private const val MAX_PAGE = 2000

    // --- Configuration ---------------------------------------------------------------------------

    fun setRoots(provider: RootsProvider) { rootsProvider = provider }

    /**
     * Make a deep-link URL (path) for an object. Multiple calls can produce different tokens; tokens
     * are process-local and may expire if the object is GC'ed. If you need absolute URLs, prepend your
     * own origin, e.g. "http://localhost:9092" + linkPathFor(obj).
     */
    fun linkPathFor(obj: Any, browsePath: String = "/browse", uid : String? = null, options: GraphWalkOptions = GraphWalkOptions()): String {
        val token = uid ?: "r${idGen.getAndIncrement()}"
        linkTokens[token] = WeakReference(obj)
        linkOptions[token] = options
        retainLinkToken(token, obj)
        return "$browsePath?root=$token"
    }

    /** Convenience alias naming matches your phrasing. */
    @OptIn(ExperimentalUuidApi::class)
    fun linkFor(obj: Any, browsePath: String = "/browse", uid : String = Uuid.random().toString(), options: GraphWalkOptions = GraphWalkOptions()): String =
        linkPathFor(obj, browsePath, uid, options)

    /** Notify watchers that a Browsable changed. Accepts Browsable, HasBrowsable, or raw object. */
    fun changed(obj: Any) {
        val target = unwrapToBrowsable(obj) ?: obj
        val ids = items.entries.asSequence()
            .filter { it.value.obj === target || it.value.obj === obj }
            .map { it.key }.toList()
        if (ids.isEmpty()) return
        val version = (unwrapToBrowsable(target) ?: (target as? Browsable))?.version() ?: 0L
        ids.forEach { nodeId ->
            nodeVersions[nodeId] = version
            val msg = json.encodeToString(ChangedPush.serializer(), ChangedPush(nodeId = nodeId, version = version))
            subscribersByNode[nodeId]?.forEach { client -> runCatching { client.send(msg) } }
        }
    }

    // --- Internal plumbing -----------------------------------------------------------------------

    private var rootsProvider: RootsProvider = { emptyMap() }

    /**
     * One named child of a directory. [value] is evaluated when the page is listed, never earlier.
     * [segment] is the GraphQuery edge name that reaches it from its parent ([SAME] when the child
     * is reached by the parent's own path, null when no path reaches it, e.g. a primitive array's
     * element or a map key object).
     */
    internal class Child(val name: String, val segment: String? = name, val value: () -> Any?)

    /** [Child.segment] for a child that has its parent's address (the `value` of a map entry). */
    internal const val SAME = "\u0000same"

    /** The walk policy for objects that are neither collections nor [Browsable]. Matches `graphPaths()`. */
    internal fun edgeChildren(any: Any, options: GraphWalkOptions): List<Child> =
        GraphWalker.edges(any, options).map { e -> Child(e.name, e.name.takeIf { '/' !in it }) { e.getter() } }

    internal sealed interface Resolved {
        val obj: Any?
        val type: String

        class Leaf(val html: () -> String, val text: () -> String, override val obj: Any?, override val type: String) : Resolved

        /**
         * A directory. [size] is null when it is not known without iterating (a bare `Iterable`);
         * [page] returns `limit` children from `offset`, so a million-element list costs one page.
         */
        // NOTE: obj is Any? (not Browsable) so Inspector.changed(rawObj) will match.
        class Dir(
            val heading: String,
            val size: () -> Int?,
            val page: (offset: Int, limit: Int) -> List<Child>,
            override val obj: Any?,
            override val type: String,
        ) : Resolved
    }

    private class Item(
        val resolved: Resolved,
        /** The objects above this node, outermost first; used to flag a child that is one of them. */
        val ancestors: List<Any>,
        /** This node's GraphQuery address from the root, or null when no path reaches it. */
        val path: List<String>?,
        val options: GraphWalkOptions,
        /** Stable child ids by name, so a re-listed directory keeps its ids and the client its open rows. */
        val childIds: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    ) {
        val obj: Any? get() = resolved.obj
    }

    private val items = ConcurrentHashMap<String, Item>()
    private val idGen = AtomicLong(1)
    private fun newId(): String = "n" + idGen.getAndIncrement()

    // deep-link token store (weak so links don't pin objects)
    private val linkTokens = ConcurrentHashMap<String, WeakReference<Any>>()
    private val linkOptions = ConcurrentHashMap<String, GraphWalkOptions>()
    private val retainedLinkTokens = LinkedHashMap<String, RetainedLink>()
    private val retainedLinkTokenTtlMs = 10 * 60 * 1000L
    private val retainedLinkTokenMax = 256

    @Synchronized
    private fun retainLinkToken(token: String, obj: Any) {
        val now = System.currentTimeMillis()
        retainedLinkTokens[token] = RetainedLink(obj, now)
        cleanupRetainedLinkTokens(now)
    }

    @Synchronized
    private fun cleanupRetainedLinkTokens(now: Long) {
        val it = retainedLinkTokens.entries.iterator()
        while (it.hasNext()) {
            val (_, entry) = it.next()
            if (now - entry.createdAtMs > retainedLinkTokenTtlMs) {
                it.remove()
            }
        }
        while (retainedLinkTokens.size > retainedLinkTokenMax) {
            val eldestKey = retainedLinkTokens.entries.firstOrNull()?.key ?: break
            retainedLinkTokens.remove(eldestKey)
        }
    }

    private data class Client(val key: Any, val send: (String) -> Unit) {
        override fun equals(other: Any?) = other is Client && other.key === key
        override fun hashCode(): Int = System.identityHashCode(key)
    }

    private val subscribersByNode = ConcurrentHashMap<String, MutableSet<Client>>()
    private fun subscribe(nodeId: String, client: Client) { subscribersByNode.getOrPut(nodeId) { CopyOnWriteArraySet() }.add(client) }
    private fun unsubscribe(nodeId: String, client: Client) {
        val set = subscribersByNode[nodeId] ?: return
        set.remove(client)
        if (set.isEmpty()) { subscribersByNode.remove(nodeId); nodeVersions.remove(nodeId) }
    }
    // Last version seen per watched node, so the poller can push when version() (or, for plain
    // collections and objects, their contents) changes without an explicit changed().
    private val nodeVersions = ConcurrentHashMap<String, Long>()

    private fun unwrapToBrowsable(any: Any?): Browsable? = when (any) {
        is Browsable   -> any
        is HasBrowsable -> any.browsable()      // may be null
        else           -> null
    }

    /** A map entry whose key is not a plain value: shown as a two-row directory, key then value. */
    internal class MapEntryView(val key: Any?, val value: Any?)

    internal fun resolve(any: Any?, parent: Browsable?, title: String?, options: GraphWalkOptions = GraphWalkOptions()): Resolved {
        val b0 = unwrapToBrowsable(any)
        val type = InspectorValues.typeName(any)

        return when {
            // 1) The object already is Browsable (or HasBrowsable)
            b0 != null -> browsableResolved(b0, any, parent, title, type, options)

            // 2) Treat HasBrowsable with null like real null
            any == null || any is HasBrowsable -> Resolved.Leaf(html = { "<i>null</i>" }, text = { "null" }, obj = null, type = "null")

            any is MapEntryView -> Resolved.Dir(
                heading = title ?: "entry",
                size = { 2 },
                page = { o, l -> listOf(Child("key", null) { any.key }, Child("value", SAME) { any.value }).drop(o).take(l) },
                obj = any, type = "Map.Entry",
            )

            // 2.5) Maps, collections and arrays: read live and in pages, so neither a mutation nor a
            // million entries surprises the client.
            any is Map<*, *> -> Resolved.Dir(
                heading = title ?: type,
                size = { any.size },
                page = { o, l -> mapPage(any, o, l) },
                obj = any, type = type,
            )

            any is List<*> -> Resolved.Dir(
                heading = title ?: type,
                size = { any.size },
                page = { o, l ->
                    val end = minOf(any.size, o + l)
                    if (o >= end) emptyList() else (o until end).map { i -> Child("[$i]", "$i") { any[i] } }
                },
                obj = any, type = type,
            )

            any is Iterable<*> -> Resolved.Dir(
                heading = title ?: type,
                size = { (any as? Collection<*>)?.size },
                page = { o, l -> any.asSequence().drop(o).take(l).mapIndexed { i, v -> Child("[${o + i}]", if (any is Collection<*>) "${o + i}" else null) { v } }.toList() },
                obj = any, type = type,
            )

            InspectorValues.isArray(any) -> Resolved.Dir(
                heading = title ?: type,
                size = { java.lang.reflect.Array.getLength(any) },
                page = { o, l ->
                    val n = java.lang.reflect.Array.getLength(any)
                    val end = minOf(n, o + l)
                    // Object arrays are GraphQuery edges by index; primitive arrays are leaves to the walker.
                    val seg = any is Array<*>
                    if (o >= end) emptyList() else (o until end).map { i -> Child("[$i]", if (seg) "$i" else null) { java.lang.reflect.Array.get(any, i) } }
                },
                obj = any, type = type,
            )

            // 3) Try adapters: turn arbitrary types into a Browsable wrapper
            else -> {
                val adapted = BrowsableAdapters.adapt(any, parent, title)
                if (adapted != null) return browsableResolved(adapted, any, parent, title, type, options)

                // 4) Plain objects (data classes, user classes): a directory of exactly the edges
                // graphPaths() walks — LiveGraphInspectable children, then Kotlin properties in
                // constructor order. Values (numbers, strings, enums, JDK types) stay leaves.
                val edges = if (InspectorValues.isPlainValue(any)) emptyList() else edgeChildren(any, options)
                if (edges.isNotEmpty()) {
                    Resolved.Dir(
                        heading = title ?: type,
                        size = { edgeChildren(any, options).size },
                        page = { o, l -> edgeChildren(any, options).drop(o).take(l) },
                        obj = any, type = type,
                    )
                } else {
                    Resolved.Leaf(
                        html = { LeafRenderers.render(any, parent, title) ?: LeafRenderers.defaultLeaf(any) },
                        text = { InspectorValues.fullText(any) },
                        obj = any, type = type,
                    )
                }
            }
        }
    }

    private fun browsableResolved(b: Browsable, raw: Any?, parent: Browsable?, title: String?, type: String, options: GraphWalkOptions): Resolved {
        val dirNow = b.dir()
        return if (dirNow != null) {
            // The walker's edges for b: its dir() first (Browsable is LiveGraphInspectable), then
            // any public properties, so every row is a path graphPaths() resolves.
            Resolved.Dir(
                heading = b.heading(),
                size = { edgeChildren(b, options).size },
                page = { o, l -> edgeChildren(b, options).drop(o).take(l) },
                obj = raw,                                 // keep RAW object for change matching
                type = type,
            )
        } else {
            Resolved.Leaf(
                html = { b.html() ?: LeafRenderers.render(b, parent, title) ?: LeafRenderers.defaultLeaf(b) },
                text = { InspectorValues.fullText(raw) },
                obj = raw, type = type,
            )
        }
    }

    private fun mapPage(m: Map<*, *>, offset: Int, limit: Int): List<Child> {
        // Names are key.toString(), made unique: 1 and "1" are different keys with the same text.
        val seen = HashMap<String, Int>()
        val out = ArrayList<Child>()
        var i = 0
        for ((k, v) in m.entries) {
            val base = k?.toString() ?: "null"
            val n = seen.merge(base, 1, Int::plus)!!
            if (i >= offset + limit) break
            if (i >= offset) {
                val name = if (n == 1) base else "$base ($n)"
                // GraphQuery names a map edge key.toString() and skips null keys; a repeated name
                // reaches only the first, so the later ones are not addressable.
                val seg = if (k == null || n > 1 || '/' in base) null else base
                out += if (InspectorValues.isPlainValue(k)) Child(name, seg) { v } else Child(name, seg) { MapEntryView(k, v) }
            }
            i++
        }
        return out
    }

    // --- Protocol --------------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable private data class Request(
        val channel: String? = null,
        val reqId: String,
        val op: String,
        val id: String? = null,
        val watch: Boolean? = null,
        val root: String? = null, // deep-link token
        val offset: Int? = null,  // "more": first entry wanted
        val limit: Int? = null,   // page size, default DEFAULT_PAGE
        val query: String? = null, // "query": a graphPaths() pattern, run from the root of node [id]
    )

    /**
     * `entries` rows are `[name, id, kind, type, summary, flag, path]`. Clients written against the
     * original `[name, id]` pairs read the first two and ignore the rest. `kind` is `dir` or `leaf`,
     * so a client need not open a child to draw its disclosure triangle; `flag` is `""`, `cycle`
     * (the child is one of its own ancestors) or `error` (reading it threw). `path` is the row's
     * address from the root as a `graphPaths()` / `graphPath()` path, `""` when none reaches it.
     */
    @Serializable
    private data class DirResp(
        val reqId: String,
        val ok: Boolean = true,
        val type: String = "dir",
        val parentId: String? = null,
        val id: String? = null,
        val heading: String? = null,
        val entries: List<List<String>>,
        val version: Long? = null,
        val valueType: String? = null,
        val total: Int? = null,
        val offset: Int = 0,
        val more: Boolean = false,
    )

    @Serializable private data class LeafResp(
        val reqId: String,
        val ok: Boolean = true,
        val type: String = "leaf",
        @SerialName("nodeId") val nodeId: String,
        val html: String? = null,
        val value: String? = null,
        val version: Long? = null,
        val valueType: String? = null,
    )

    /** Reply to `query`: `matches` rows are `[path, kind, type, summary]`, at most `limit` of `total`. */
    @Serializable
    private data class QueryResp(
        val reqId: String,
        val ok: Boolean = true,
        val type: String = "query",
        val query: String,
        val matches: List<List<String>>,
        val total: Int,
        val truncated: Boolean = false,
    )

    @Serializable private data class ErrorResp(val reqId: String, val ok: Boolean = false, val error: String)
    @Serializable private data class ChangedPush(val type: String = "changed", val nodeId: String, val version: Long? = null)

    private fun error(reqId: String, message: String) = json.encodeToString(ErrorResp.serializer(), ErrorResp(reqId, error = message))

    // Never throws: a value that cannot be fingerprinted (an element read throws, a concurrent
    // mutation) is simply not polled, and the request that asked still gets its answer.
    private fun versionOf(obj: Any?): Long? = runCatching {
        (unwrapToBrowsable(obj))?.version() ?: InspectorValues.fingerprint(obj)
    }.getOrNull()

    /** List one page of [item]'s children, minting (or reusing) their ids. */
    private fun listPage(item: Item, dir: Resolved.Dir, offset: Int, limit: Int): Pair<List<List<String>>, Boolean> {
        val ancestors = item.obj?.let { item.ancestors + it } ?: item.ancestors
        val page = dir.page(offset, limit + 1)
        val more = page.size > limit
        val rows = page.take(limit).map { child ->
            val cid = item.childIds.computeIfAbsent(child.name) { newId() }
            val childPath = when (val seg = child.segment) {
                null -> null
                SAME -> item.path
                else -> item.path?.plus(seg)
            }
            val pathText = childPath?.joinToString("/").orEmpty()
            val (value, threw) = try { child.value() to null } catch (t: Throwable) { null to t }
            if (threw != null) {
                val msg = "${threw::class.simpleName}: ${threw.message ?: ""}".trim()
                items[cid] = Item(
                    Resolved.Leaf(html = { "<span class='inspector-error'>${escapeHtml(msg)}</span>" }, text = { threw.stackTraceToString() }, obj = null, type = "error"),
                    ancestors, childPath, item.options,
                )
                return@map listOf(child.name, cid, "leaf", "error", "threw $msg", "error", pathText)
            }
            val resolved = try { resolve(value, dir.obj as? Browsable, child.name, item.options) } catch (t: Throwable) {
                Resolved.Leaf(html = { escapeHtml(t.toString()) }, text = { t.toString() }, obj = value, type = InspectorValues.typeName(value))
            }
            items[cid] = Item(resolved, ancestors, childPath, item.options)
            val cycle = value != null && !InspectorValues.isPlainValue(value) && ancestors.any { it === value }
            val kind = if (resolved is Resolved.Dir) "dir" else "leaf"
            val summary = if (cycle) "↺ " + InspectorValues.summary(value, resolved) else InspectorValues.summary(value, resolved)
            listOf(child.name, cid, kind, resolved.type, summary, if (cycle) "cycle" else "", pathText)
        }
        return rows to more
    }

    private fun reply(reqId: String, id: String, item: Item, offset: Int, limit: Int, parentId: String?): String =
        when (val r = item.resolved) {
            is Resolved.Leaf -> {
                val version = versionOf(r.obj)
                version?.let { nodeVersions[id] = it }
                val html = try { r.html() } catch (t: Throwable) { "<span class='inspector-error'>${escapeHtml(t.toString())}</span>" }
                val text = try { r.text() } catch (t: Throwable) { t.toString() }
                json.encodeToString(LeafResp.serializer(), LeafResp(reqId, nodeId = id, html = html, value = text, version = version, valueType = r.type))
            }
            is Resolved.Dir -> {
                val (entries, more) = listPage(item, r, offset, limit)
                val version = versionOf(r.obj)
                if (offset == 0) version?.let { nodeVersions[id] = it }
                json.encodeToString(
                    DirResp.serializer(),
                    DirResp(
                        reqId = reqId, parentId = parentId, id = id, heading = r.heading, entries = entries,
                        version = version, valueType = r.type, total = runCatching { r.size() }.getOrNull(),
                        offset = offset, more = more,
                    )
                )
            }
        }

    fun handle(text: String, clientKey: Any, push: (String) -> Unit): String {
        val client = Client(clientKey) { payload -> push(payload) }
        return try {
            val req = json.decodeFromString(Request.serializer(), text)
            if (req.channel != null && req.channel != "__browsing__") return ""
            val limit = (req.limit ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)
            when (req.op) {
                "getRoots" -> {
                    // If a deep-link token is provided, start from THAT object (as the unseen parent).
                    val token = req.root
                    if (token != null) {
                        val objAny = linkTokens[token]?.get()
                            ?: return error(req.reqId, "This inspector link has expired (the value it showed is gone). Run the box again.")
                        val rootId = newId()
                        val options = linkOptions[token] ?: GraphWalkOptions()
                        val item = Item(resolve(objAny, null, null, options), emptyList(), emptyList(), options)
                        items[rootId] = item
                        return reply(req.reqId, rootId, item, 0, limit, parentId = null)
                    }

                    // Default: enumerate global roots
                    val roots = rootsProvider()
                    val rootId = newId()
                    val item = Item(Resolved.Dir("Root", { roots.size }, { o, l -> roots.entries.drop(o).take(l).map { (k, v) -> Child(k) { v } } }, roots, "Roots"), emptyList(), emptyList(), GraphWalkOptions())
                    items[rootId] = item
                    reply(req.reqId, rootId, item, 0, limit, parentId = null)
                }

                "open" -> {
                    val id = req.id ?: return error(req.reqId, "Missing id")
                    val item = items[id] ?: return error(req.reqId, "Unknown id: $id")
                    if (req.watch == true) subscribe(id, client)
                    reply(req.reqId, id, item, 0, limit, parentId = id)
                }

                "more" -> {
                    val id = req.id ?: return error(req.reqId, "Missing id")
                    val item = items[id] ?: return error(req.reqId, "Unknown id: $id")
                    if (item.resolved !is Resolved.Dir) return error(req.reqId, "Not a directory: $id")
                    reply(req.reqId, id, item, (req.offset ?: 0).coerceAtLeast(0), limit, parentId = id)
                }

                "query" -> {
                    val id = req.id ?: return error(req.reqId, "Missing id")
                    val item = items[id] ?: return error(req.reqId, "Unknown id: $id")
                    val pattern = req.query?.trim().orEmpty()
                    if (pattern.isEmpty()) return error(req.reqId, "Empty query")
                    query(req.reqId, item, pattern, (req.limit ?: QUERY_LIMIT).coerceIn(1, MAX_PAGE))
                }

                "unsubscribe" -> {
                    val id = req.id ?: return error(req.reqId, "Missing id")
                    unsubscribe(id, client)
                    json.encodeToString(DirResp.serializer(), DirResp(reqId = req.reqId, ok = true, entries = emptyList()))
                }

                else -> error(req.reqId, "Unknown op: ${req.op}")
            }
        } catch (t: Throwable) {
            val reqId = runCatching { json.decodeFromString(Request.serializer(), text).reqId }.getOrElse { "?" }
            json.encodeToString(ErrorResp.serializer(), ErrorResp(reqId, ok = false, error = (t.message ?: t.toString())))
        }
    }

    fun handle(text: String): String = handle(text, clientKey = Any()) { /* drop pushes */ }

    /** Matches listed per `query` reply. */
    const val QUERY_LIMIT = 500
    /** The walk behind one `query` stops after this many slots, so `**` over a huge graph answers. */
    private const val QUERY_MAX_REFS = 20_000

    /**
     * Run [pattern] with `graphPaths` from the root [item] belongs to — the node's own path is
     * prefixed, so the query reads the same as it would in box code against the value given to
     * `tree(...)`. Only an item with a path (every row reached by edges) can be queried.
     */
    private fun query(reqId: String, item: Item, pattern: String, limit: Int): String {
        val base = item.path ?: return error(reqId, "This row has no path from the root, so it cannot be queried")
        val target = item.obj
        val options = item.options.copy(maxRefs = minOf(item.options.maxRefs, QUERY_MAX_REFS))
        val found = target.graphPaths(pattern, options)
        val rows = found.asSequence().take(limit).map { ref ->
            val v = runCatching { ref.get() }.getOrNull()
            val r = runCatching { resolve(v, null, ref.name, item.options) }.getOrNull()
            listOf(
                (base + ref.rawPath.split('/')).joinToString("/"),
                if (r is Resolved.Dir) "dir" else "leaf",
                InspectorValues.typeName(v),
                InspectorValues.summary(v, r),
            )
        }.toList()
        return json.encodeToString(
            QueryResp.serializer(),
            QueryResp(reqId, query = pattern, matches = rows, total = found.size, truncated = found.size >= options.maxRefs),
        )
    }

    /** Number of live node records; for tests that check re-listing reuses ids. */
    internal fun nodeCount(): Int = items.size

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

    /**
     * Push `changed` for every watched node whose version moved: [Browsable.version] for browsables,
     * a content fingerprint ([InspectorValues.fingerprint]) for plain collections, arrays and objects,
     * so `tree(list)` follows `list.add(...)` without anyone calling [changed].
     */
    internal fun pollVersions() {
        // Snapshot subscriber node ids to avoid holding locks while querying versions.
        val nodes = subscribersByNode.keys.toList()
        for (id in nodes) {
            val item = items[id] ?: continue
            val obj = item.obj ?: continue
            val v = runCatching { versionOf(obj) }.getOrNull() ?: continue
            val last = nodeVersions[id]
            if (last == null || last != v) {
                nodeVersions[id] = v
                if (last == null) continue
                val msg = json.encodeToString(ChangedPush.serializer(), ChangedPush(nodeId = id, version = v))
                subscribersByNode[id]?.forEach { client -> runCatching { client.send(msg) } }
            }
        }
    }
}

/**
 * How the inspector names, summarises and takes apart values that are not [Browsable]. Kept apart
 * from [Inspector] so the rules are testable one value at a time.
 */
internal object InspectorValues {
    private const val SUMMARY_CHARS = 120
    private const val TEXT_CHARS = 200_000
    private const val FINGERPRINT_ELEMENTS = 2000

    /** Strings, numbers, booleans, chars, enums: shown as themselves, never taken apart. */
    fun isPlainValue(v: Any?): Boolean =
        v == null || v is CharSequence || v is Number || v is Boolean || v is Char || v is Enum<*> || v is KClass<*> || v is Class<*>

    fun isArray(v: Any?): Boolean = v != null && v.javaClass.isArray

    fun typeName(v: Any?): String {
        if (v == null) return "null"
        val c = v.javaClass
        if (c.isArray) {
            val ct = c.componentType
            return when (ct) {
                Int::class.javaPrimitiveType -> "IntArray"
                Double::class.javaPrimitiveType -> "DoubleArray"
                Float::class.javaPrimitiveType -> "FloatArray"
                Long::class.javaPrimitiveType -> "LongArray"
                Byte::class.javaPrimitiveType -> "ByteArray"
                Short::class.javaPrimitiveType -> "ShortArray"
                Char::class.javaPrimitiveType -> "CharArray"
                Boolean::class.javaPrimitiveType -> "BooleanArray"
                else -> "Array<${ct.simpleName}>"
            }
        }
        return when (v) {
            is String -> "String"
            is Int -> "Int"
            is Double -> "Double"
            is Float -> "Float"
            is Long -> "Long"
            is Boolean -> "Boolean"
            else -> c.simpleName.ifEmpty { c.name.substringAfterLast('.') }
        }
    }

    private fun clip(s: String, n: Int = SUMMARY_CHARS): String =
        if (s.length <= n) s else s.take(n - 1) + "…"

    /** The one-line preview drawn beside a name. */
    fun summary(v: Any?, resolved: Inspector.Resolved? = null): String = try {
        when {
            v == null -> "null"
            v is CharSequence -> {
                val s = v.toString()
                val oneLine = s.replace("\n", "⏎")
                "\"" + clip(oneLine) + "\"" + if (s.length > SUMMARY_CHARS) " (${s.length} chars)" else ""
            }
            v is Enum<*> -> v.name
            isPlainValue(v) -> v.toString()
            v is Inspector.MapEntryView -> clip("${v.key} → ${summary(v.value)}")
            v is Map<*, *> -> "${v.size} " + if (v.size == 1) "entry" else "entries"
            v is Collection<*> -> "${v.size} " + if (v.size == 1) "item" else "items"
            isArray(v) -> java.lang.reflect.Array.getLength(v).let { "$it " + if (it == 1) "item" else "items" }
            v is Iterable<*> -> "iterable"
            resolved is Inspector.Resolved.Dir && !overridesToString(v) -> {
                val names = runCatching { resolved.page(0, 4).map { it.name } }.getOrDefault(emptyList())
                val n = runCatching { resolved.size() }.getOrNull() ?: names.size
                "{" + names.joinToString(", ") + if (n > names.size) ", …}" else "}"
            }
            else -> clip(v.toString().replace("\n", "⏎"))
        }
    } catch (t: Throwable) {
        "toString threw ${t::class.simpleName}"
    }

    /** The full text a "copy" button puts on the clipboard. */
    fun fullText(v: Any?): String = try {
        clip(v?.toString() ?: "null", TEXT_CHARS)
    } catch (t: Throwable) {
        "toString threw $t"
    }

    private fun overridesToString(v: Any): Boolean =
        runCatching { v.javaClass.getMethod("toString").declaringClass != Any::class.java }.getOrDefault(true)

    private fun elementHash(v: Any?): Int = when {
        v == null -> 0
        isPlainValue(v) -> v.hashCode()
        else -> System.identityHashCode(v)
    }

    /**
     * A cheap content hash for change polling: size plus the first [FINGERPRINT_ELEMENTS] elements
     * (value hash for plain values, identity for objects). Null for values that are not polled.
     */
    fun fingerprint(v: Any?): Long? = when {
        v == null || isPlainValue(v) -> null
        v is Inspector.MapEntryView -> null
        v is Map<*, *> -> {
            var h = v.size.toLong()
            var n = 0
            for ((k, x) in v.entries) { if (n++ >= FINGERPRINT_ELEMENTS) break; h = h * 31 + elementHash(k) * 17 + elementHash(x) }
            h
        }
        v is Collection<*> -> {
            var h = v.size.toLong()
            var n = 0
            for (x in v) { if (n++ >= FINGERPRINT_ELEMENTS) break; h = h * 31 + elementHash(x) }
            h
        }
        isArray(v) -> {
            val len = java.lang.reflect.Array.getLength(v)
            var h = len.toLong()
            for (i in 0 until minOf(len, FINGERPRINT_ELEMENTS)) h = h * 31 + elementHash(java.lang.reflect.Array.get(v, i))
            h
        }
        else -> Inspector.edgeChildren(v, GraphWalkOptions()).takeIf { it.isNotEmpty() }
            ?.fold(17L) { h, c -> h * 31 + elementHash(runCatching { c.value() }.getOrNull()) }
    }
}

// -------------------------------------------------------------------------------------------------
// Handler wiring
// -------------------------------------------------------------------------------------------------

/**
 * Every [BrowsableAdapters] registration the server makes, in one place. A `register*BrowsingAdapters()`
 * function defined anywhere in `src/jvmMain` must be called from here, or `column()`/`tree()` never see
 * its types (the Assimp/glTF adapters sat unregistered from their introduction until 2026-10-28);
 * `InspectorTest` scans the source and fails on one that is not. Idempotent.
 */
fun registerAllBrowsingAdapters() {
    if (!allBrowsingAdaptersRegistered.compareAndSet(false, true)) return
    registerExampleRoots()
    registerExampleAdapters()
    registerRendererBrowsingAdapters()
    registerAssimpBrowsingAdapters()
}

private val allBrowsingAdaptersRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

@RunsAt(installHandlers)
fun installInspectorHandler() {
    registerAllBrowsingAdapters()
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
        if (!m.keys.all { it is String }) return@register null

        val entries = m.entries.associate { (k, v) -> (k as String) to v }
        object : Browsable {
            override fun heading(): String = title ?: "Map"
            override fun dir(): Map<String, Any?> = entries
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
