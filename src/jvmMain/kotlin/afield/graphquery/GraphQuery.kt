package afield.graphquery

import afield.Table
import afield.ToHTML
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * A reflective, XPath-style query layer over arbitrary live object graphs.
 *
 * The model is small: [graphPaths] walks any object and returns a [PathSet] of
 * [GraphPathRef]s. A ref is a *live lens* — a slash-joined address plus a getter and, when
 * the underlying slot is mutable, a setter. Nothing is snapshotted, so reading a ref twice
 * can legitimately give two answers.
 *
 * ```kotlin
 * val cutoffs = patch.graphPaths("**\/cutoff")     // every cutoff at any depth
 * patch.graphPaths("osc/" + "*").writable()        // one level down, settable only
 * patch.graphPaths("**" + "/" + "*Alpha")         // a glob inside one segment
 * patch.graphPaths("gain | **" + "/cutoff")        // either pattern, each slot once
 * patch.graphPaths("**").where { (it.get() as? Float ?: 0f) > 0.5f }
 * ```
 *
 * This package is deliberately domain-agnostic: it knows nothing about audio, and in
 * particular it does not touch the runloop. The synth-specific parameter head (`ParamSet`,
 * modulation, supplier binding) lives in `afield.four.synth` and is layered on top via
 * `PathSet.params()`.
 *
 * ### Traversal rules
 *
 * Edges come from [LiveGraphInspectable.liveGraphChildren] when an object opts in, merged
 * ahead of reflected edges (maps by key, lists/arrays by index, everything else by Kotlin
 * member property in primary-constructor order). Descent stops at scalars, strings, enums,
 * primitive arrays, functions, class objects, and anything in the JDK's own packages — see
 * [GraphWalkOptions.descendInto] to override that policy.
 *
 * Cycles are broken by *path-scoped* detection: a node is skipped only when it already
 * appears on the current ancestor chain. A node shared by several edges is therefore
 * expanded under each of them (so an alias is fully queryable), while `a -> b -> a` still
 * terminates. [GraphWalkOptions.maxRefs] bounds the pathological dense-DAG case.
 */

/**
 * Optional protocol for objects that want to expose meaningful live-graph children instead
 * of whatever general reflection would discover. Custom edges win on name collisions.
 */
interface LiveGraphInspectable {
    fun liveGraphChildren(): Iterable<LiveGraphEdge> = emptyList()
}

/** One named, readable — and possibly writable — edge out of a node. */
data class LiveGraphEdge(
    val name: String,
    val getter: () -> Any?,
    val setter: ((Any?) -> Unit)? = null
) {
    constructor(name: String, getter: () -> Any?) : this(name, getter, null)
}

/**
 * Traversal policy.
 *
 * @param includePrivate reflect over private properties too, forcing accessibility.
 * @param includeNulls retained for source compatibility and now honoured: when false,
 *   edges whose current value is null are recorded but not returned. (It was previously
 *   read only by an unreachable code path, so nulls always appeared.)
 * @param maxDepth how many edges deep to expand.
 * @param maxRefs hard bound on collected refs; the walk stops cleanly once reached, which
 *   keeps a densely-shared DAG from blowing up now that aliases are expanded.
 * @param descendInto refines the "should I open this value up?" policy. It receives the
 *   value *and* the default decision, so a caller can prune without silently losing the
 *   built-in guards — `{ value, byDefault -> byDefault && value !is Osc }` — or deliberately
 *   opt into something the default refuses — `{ value, byDefault -> byDefault || value is String }`.
 *   Taking the default as an argument is what stops a one-line prune from accidentally
 *   reflecting into `String` and reporting `name/length`.
 */
data class GraphWalkOptions(
    val includePrivate: Boolean = false,
    val includeNulls: Boolean = true,
    val maxDepth: Int = 32,
    val maxRefs: Int = 200_000,
    val descendInto: ((value: Any, byDefault: Boolean) -> Boolean)? = null
)

/** A live, addressable slot in an object graph. */
class GraphPathRef internal constructor(
    internal val pathSegments: List<String>,
    val owner: Any?,
    private val getter: () -> Any?,
    private val setter: ((Any?) -> Unit)?
) {
    val rawPath: String = pathSegments.joinToString("/")
    val path: String = if (rawPath.isBlank()) "<root>" else rawPath

    /** The last segment of the address — the slot's own name. */
    val name: String get() = pathSegments.lastOrNull().orEmpty()

    /** How many edges from the root this slot sits at. */
    val depth: Int get() = pathSegments.size

    val writable: Boolean get() = setter != null

    fun get(): Any? = getter()

    /** Typed read: the value when it is a [T], else null. */
    fun <T : Any> getAs(type: KClass<T>): T? = type.safeCast(get())

    inline fun <reified T : Any> getAs(): T? = getAs(T::class)

    fun set(value: Any?) {
        val set = setter ?: error("Path '$path' is not writable")
        set(value)
    }

    /** Write only if the slot is writable; reports whether it happened. */
    fun trySet(value: Any?): Boolean {
        val set = setter ?: return false
        set(value)
        return true
    }

    /**
     * The same live slot, re-addressed under [prefix]. Used when a query is continued from a
     * ref rather than from the original root, so `rig / "carrier" / "freq"` still reports
     * `carrier/freq` rather than the bare `freq` the second walk would have produced.
     */
    internal fun rebased(prefix: List<String>): GraphPathRef =
        GraphPathRef(prefix + pathSegments, owner, getter, setter)

    override fun toString(): String = path
}

/**
 * The result of a query: an ordered, immutable collection of live refs.
 *
 * Combinators return new sets and never mutate the receiver, so a set is safe to hold and
 * re-filter. Renders itself as an HTML table, which is what the docs captures show.
 */
class PathSet internal constructor(
    val root: Any?,
    internal val refs: List<GraphPathRef>
) : Iterable<GraphPathRef>, ToHTML {
    val size: Int get() = refs.size
    val isEmpty: Boolean get() = refs.isEmpty()
    val isNotEmpty: Boolean get() = refs.isNotEmpty()

    fun paths(): List<String> = refs.map { it.path }

    fun values(): List<Any?> = refs.map { it.get() }

    fun writable(): PathSet = PathSet(root, refs.filter { it.writable })

    fun dedup(): PathSet = PathSet(root, deduplicateRefs(refs))

    fun deduplicated(): PathSet = dedup()

    /** Glob-filter an existing set with the same `*` / `**` / `|` rules as [graphPaths]. */
    fun matching(pattern: String): PathSet {
        val branches = splitAlternatives(pattern)
        return PathSet(root, refs.filter { globMatchesAny(it.pathSegments, branches) })
    }

    /** Glob-filter *out* the matches — the complement of [matching]. */
    fun excluding(pattern: String): PathSet {
        val branches = splitAlternatives(pattern)
        return PathSet(root, refs.filterNot { globMatchesAny(it.pathSegments, branches) })
    }

    // ── predicates: the XPath `[...]` half of the story ─────────────────────

    /** Keep refs satisfying an arbitrary predicate over the whole ref. */
    fun where(predicate: (GraphPathRef) -> Boolean): PathSet =
        PathSet(root, refs.filter(predicate))

    /** Keep refs whose current *value* satisfies a predicate. */
    fun whereValue(predicate: (Any?) -> Boolean): PathSet =
        PathSet(root, refs.filter { predicate(it.get()) })

    /**
     * Keep refs whose value is a [T] *and* satisfies a typed predicate — the common case,
     * without the cast at every call site.
     */
    fun <T : Any> whereValueIs(type: KClass<T>, predicate: (T) -> Boolean): PathSet =
        PathSet(root, refs.filter { ref -> type.safeCast(ref.get())?.let(predicate) == true })

    inline fun <reified T : Any> whereValueIs(noinline predicate: (T) -> Boolean): PathSet =
        whereValueIs(T::class, predicate)

    /** Keep refs owned by a specific object, compared by identity. */
    fun ownedBy(owner: Any?): PathSet = PathSet(root, refs.filter { it.owner === owner })

    /** Drop refs whose current value is null. */
    fun nonNull(): PathSet = PathSet(root, refs.filter { it.get() != null })

    /** Restrict to a depth range, e.g. `atDepth(1..2)`. */
    fun atDepth(range: IntRange): PathSet = PathSet(root, refs.filter { it.depth in range })

    fun ofType(type: KClass<*>): PathSet = PathSet(root, refs.filter { type.isInstance(it.get()) })

    inline fun <reified T : Any> ofType(): PathSet = ofType(T::class)

    // ── typed terminals ─────────────────────────────────────────────────────

    /**
     * Narrow to a typed [LensSet] over the refs whose value is a [T]. This is the general
     * terminal: the float-only parameter head is one specialization of the same idea.
     */
    fun <T : Any> lenses(type: KClass<T>): LensSet<T> = LensSet(
        type,
        refs.filter { type.isInstance(it.get()) }.map { Lens(it, type) }
    )

    inline fun <reified T : Any> lenses(): LensSet<T> = lenses(T::class)

    fun firstOrNull(): GraphPathRef? = refs.firstOrNull()

    fun first(): GraphPathRef = refs.firstOrNull() ?: error("PathSet is empty")

    /** Look up a single literal path — case-insensitive, like the patterns — or null. */
    operator fun get(path: String): GraphPathRef? {
        val wanted = splitPath(path)
        return refs.firstOrNull { ref ->
            ref.pathSegments.size == wanted.size &&
                ref.pathSegments.indices.all { ref.pathSegments[it].equals(wanted[it], ignoreCase = true) }
        }
    }

    override fun iterator(): Iterator<GraphPathRef> = refs.iterator()

    override fun toString(): String = "PathSet(${size})"

    override fun toHTML(): String = renderGraphTable(
        title = "PathSet",
        count = size,
        columns = listOf("path" to PATH_COLOUR, "value" to VALUE_COLOUR),
        rows = refs.map { ref -> listOf(ref.path, shortValue(ref.get())) }
    )
}

/**
 * A typed handle on one slot. Unlike a bare [GraphPathRef] the type is fixed up front, so
 * `get()` needs no cast and `set()` cannot install the wrong kind of value.
 */
class Lens<T : Any> internal constructor(
    val ref: GraphPathRef,
    val type: KClass<T>
) {
    val path: String get() = ref.path
    val name: String get() = ref.name
    val owner: Any? get() = ref.owner
    val writable: Boolean get() = ref.writable

    /** The value when it is still a [T]; null if the slot has since changed shape. */
    fun get(): T? = type.safeCast(ref.get())

    fun getOrElse(fallback: T): T = get() ?: fallback

    fun set(value: T) = ref.set(value)

    fun trySet(value: T): Boolean = ref.trySet(value)

    /** Read-modify-write through the lens; no-op when the slot no longer holds a [T]. */
    fun update(transform: (T) -> T): Boolean {
        val current = get() ?: return false
        return ref.trySet(transform(current))
    }

    override fun toString(): String = "$path: ${type.simpleName}"
}

/**
 * A set of typed lenses — the general counterpart to the synth `ParamSet`, for graphs whose
 * interesting leaves are strings, booleans, enums, colours, vectors, anything at all.
 *
 * ```kotlin
 * scene.graphPaths("**").lenses<String>().set("hello")
 * scene.graphPaths("**").lenses<Boolean>().update { !it }
 * ```
 */
class LensSet<T : Any> internal constructor(
    val type: KClass<T>,
    val lenses: List<Lens<T>>
) : Iterable<Lens<T>>, ToHTML {
    val size: Int get() = lenses.size
    val isEmpty: Boolean get() = lenses.isEmpty()

    fun paths(): List<String> = lenses.map { it.path }

    fun values(): List<T?> = lenses.map { it.get() }

    /** Present values only, in order. */
    fun presentValues(): List<T> = lenses.mapNotNull { it.get() }

    fun writable(): LensSet<T> = LensSet(type, lenses.filter { it.writable })

    fun where(predicate: (Lens<T>) -> Boolean): LensSet<T> = LensSet(type, lenses.filter(predicate))

    /** Assign one value to every writable lens; returns the number written. */
    fun set(value: T): Int = lenses.count { it.trySet(value) }

    /** Assign positionally, stopping when either side runs out. */
    fun set(values: Iterable<T>): Int =
        lenses.zip(values.toList()).count { (lens, value) -> lens.trySet(value) }

    /** Read-modify-write every lens; returns the number actually written. */
    fun update(transform: (T) -> T): Int = lenses.count { it.update(transform) }

    /** Read-modify-write with the index available. */
    fun updateIndexed(transform: (Int, T) -> T): Int =
        lenses.withIndex().count { (index, lens) -> lens.update { transform(index, it) } }

    fun firstOrNull(): Lens<T>? = lenses.firstOrNull()

    override fun iterator(): Iterator<Lens<T>> = lenses.iterator()

    override fun toString(): String = "LensSet<${type.simpleName}>(${size})"

    override fun toHTML(): String = renderGraphTable(
        title = "LensSet<${type.simpleName ?: "?"}>",
        count = size,
        columns = listOf("path" to PATH_COLOUR, "value" to VALUE_COLOUR, "state" to MUTED_COLOUR),
        rows = lenses.map { lens ->
            listOf(
                lens.path,
                shortValue(lens.get()),
                if (lens.writable) "writable" else "read-only"
            )
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════════
//  Reflection
// ═══════════════════════════════════════════════════════════════════════════

internal object GraphReflectionCache {
    private val publicCache = ConcurrentHashMap<KClass<*>, List<KProperty1<Any, *>>>()
    private val privateCache = ConcurrentHashMap<KClass<*>, List<KProperty1<Any, *>>>()
    private val publicByName = ConcurrentHashMap<KClass<*>, Map<String, KProperty1<Any, *>>>()
    private val privateByName = ConcurrentHashMap<KClass<*>, Map<String, KProperty1<Any, *>>>()

    fun properties(type: KClass<*>, includePrivate: Boolean): List<KProperty1<Any, *>> {
        val cache = if (includePrivate) privateCache else publicCache
        return cache.computeIfAbsent(type) { buildProperties(it, includePrivate) }
    }

    /** Name-keyed view, so the fast path can resolve one segment without building every edge. */
    fun propertyMap(type: KClass<*>, includePrivate: Boolean): Map<String, KProperty1<Any, *>> {
        val cache = if (includePrivate) privateByName else publicByName
        return cache.computeIfAbsent(type) { properties(it, includePrivate).associateBy { p -> p.name } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildProperties(type: KClass<*>, includePrivate: Boolean): List<KProperty1<Any, *>> {
        val constructorOrder = runCatching { type.primaryConstructor?.parameters?.mapNotNull { it.name } }
            .getOrNull() ?: emptyList()
        val discovered = linkedMapOf<String, KProperty1<Any, *>>()
        val candidates = buildList {
            addAll(type.memberProperties)
            if (includePrivate) addAll(type.declaredMemberProperties)
        }
        val ordered = candidates
            .filterIsInstance<KProperty1<Any, *>>()
            .filter { includePrivate || it.visibility == KVisibility.PUBLIC }
            .sortedWith(
                compareBy<KProperty1<Any, *>>(
                    { constructorOrder.indexOf(it.name).let { idx -> if (idx >= 0) idx else Int.MAX_VALUE } },
                    { it.name }
                )
            )
        ordered.forEach { discovered.putIfAbsent(it.name, it) }
        return discovered.values.toList()
    }
}

/** The outcome of resolving a run of literal path segments. */
internal class StaticResolution(
    val ref: GraphPathRef,
    val value: Any?,
    val ancestors: List<Any>,
    /**
     * True when every segment matched an edge name *exactly*. A segment that only matched
     * ignoring case may have case-variant siblings, and this resolver can only ever report
     * one of them — so the pattern layer uses this to decide whether the fast path is still
     * allowed to speak for the full walk.
     */
    val allExact: Boolean = true
)

internal object GraphWalker {

    /** Full traversal, then glob-filter. The general path. */
    fun scan(root: Any?, options: GraphWalkOptions): PathSet {
        if (root == null) return PathSet(root, emptyList())
        val refs = mutableListOf<GraphPathRef>()
        walk(root, emptyList(), 0, ArrayList(), refs, options)
        return PathSet(root, refs)
    }

    /**
     * Collect every ref under [node], addressed relative to [basePath].
     *
     * Cycle detection is path-scoped: [ancestors] holds the chain from the root to [node],
     * compared by identity. A shared node therefore expands under each alias that reaches
     * it, while `a -> b -> a` still stops.
     */
    private fun walk(
        node: Any?,
        basePath: List<String>,
        depth: Int,
        ancestors: MutableList<Any>,
        into: MutableList<GraphPathRef>,
        options: GraphWalkOptions
    ) {
        if (node == null || depth > options.maxDepth || !shouldDescend(node, options)) return
        if (ancestors.any { it === node }) return
        if (into.size >= options.maxRefs) return

        ancestors.add(node)
        try {
            edgesFor(node, options).forEach { edge ->
                if (into.size >= options.maxRefs) return
                val childPath = basePath + edge.name
                val value = safeGet(edge.getter)
                if (value != null || options.includeNulls) {
                    into += GraphPathRef(
                        pathSegments = childPath,
                        owner = node,
                        getter = edge.getter,
                        setter = edge.setter
                    )
                }
                walk(value, childPath, depth + 1, ancestors, into, options)
            }
        } finally {
            ancestors.removeAt(ancestors.size - 1)
        }
    }

    /**
     * Resolve a run of literal segments without touching the rest of the graph — O(depth)
     * instead of O(graph).
     *
     * [StaticResolution.ancestors] is the chain of nodes stepped through, which the caller
     * must feed back into [scanSubtree]. Without it a subtree that loops back up through an
     * ancestor would be expanded here but pruned by a full walk, and the two paths would
     * disagree. The same chain doubles as cycle detection for the literal path itself.
     */
    fun resolveStatic(
        root: Any?,
        segments: List<String>,
        options: GraphWalkOptions
    ): StaticResolution? {
        if (root == null || segments.isEmpty()) return null
        var current: Any? = root
        var ref: GraphPathRef? = null
        var allExact = true
        val chain = mutableListOf<Any>()
        segments.forEachIndexed { index, segment ->
            val node = current ?: return null
            if (!shouldDescend(node, options)) return null
            if (index > options.maxDepth) return null
            if (chain.any { it === node }) return null
            chain.add(node)
            val exact = edgeNamedExactly(node, segment, options)
            val edge = exact ?: edgeNamedIgnoringCase(node, segment, options) ?: return null
            if (exact == null || caseAmbiguous(node, segment, options)) allExact = false
            val value = safeGet(edge.getter)
            ref = GraphPathRef(
                pathSegments = segments.subList(0, index + 1).toList(),
                owner = node,
                getter = edge.getter,
                setter = edge.setter
            )
            current = value
        }
        val resolved = ref ?: return null
        return StaticResolution(resolved, current, chain, allExact)
    }

    /**
     * Walk only the subtree rooted at [node], addressing results relative to [basePath].
     * [seedAncestors] carries the chain already traversed to get here.
     */
    fun scanSubtree(
        node: Any?,
        basePath: List<String>,
        options: GraphWalkOptions,
        seedAncestors: List<Any> = emptyList()
    ): List<GraphPathRef> {
        val refs = mutableListOf<GraphPathRef>()
        walk(node, basePath, basePath.size, ArrayList(seedAncestors), refs, options)
        return refs
    }

    /**
     * One named edge, resolved directly by its exact name. Mirrors [reflectiveEdges] case by
     * case, so this stays O(1)-ish rather than building every edge of the node.
     */
    private fun edgeNamedExactly(node: Any, name: String, options: GraphWalkOptions): LiveGraphEdge? {
        (node as? LiveGraphInspectable)?.liveGraphChildren()?.firstOrNull { it.name == name }
            ?.let { return it }

        return when (node) {
            is Map<*, *> -> {
                val entryKey = node.keys.firstOrNull { (it?.toString() ?: return@firstOrNull false) == name }
                if (entryKey == null && !node.containsKey(name)) return null
                val rawKey = entryKey ?: name
                @Suppress("UNCHECKED_CAST")
                val mutable = node as? MutableMap<Any?, Any?>
                LiveGraphEdge(
                    name = name,
                    getter = { node[rawKey] },
                    setter = mutable?.let { map -> { value: Any? -> map[rawKey] = value } }
                )
            }
            is List<*> -> {
                val index = name.toIntOrNull() ?: return null
                if (index !in node.indices) return null
                @Suppress("UNCHECKED_CAST")
                val mutable = node as? MutableList<Any?>
                LiveGraphEdge(
                    name = name,
                    getter = { node.getOrNull(index) },
                    setter = mutable?.let { list -> { value: Any? -> list[index] = value } }
                )
            }
            is Array<*> -> {
                val index = name.toIntOrNull() ?: return null
                if (index !in node.indices) return null
                @Suppress("UNCHECKED_CAST")
                val mutable = node as Array<Any?>
                LiveGraphEdge(
                    name = name,
                    getter = { node[index] },
                    setter = { value: Any? -> mutable[index] = value }
                )
            }
            else -> {
                val prop = GraphReflectionCache.propertyMap(node::class, options.includePrivate)[name]
                    ?: return null
                propertyEdge(node, prop, options)
            }
        }
    }

    /**
     * Could this segment name more than one edge once case is ignored?
     *
     * Two properties differing only in case are two slots, and a pattern matches both — but
     * [resolveStatic] can only ever report one of them, so it has to know when to stand down.
     *
     * What counts is two *different spellings*, not two hits: a custom edge and a reflected
     * property that share a name are merged into one edge by [edgesFor], so seeing `leaf`
     * twice is not ambiguity. Index names cannot collide on case at all.
     *
     * Cheap on purpose — names only, out of the cached property map or the map's own keys,
     * with no allocation, no edge built and no value read. That is what keeps a named path's
     * cost proportional to its length.
     */
    private fun caseAmbiguous(node: Any, name: String, options: GraphWalkOptions): Boolean {
        var spelling: String? = null
        var ambiguous = false
        fun consider(candidate: String) {
            if (!candidate.equals(name, ignoreCase = true)) return
            val already = spelling
            if (already == null) spelling = candidate else if (already != candidate) ambiguous = true
        }
        (node as? LiveGraphInspectable)?.liveGraphChildren()?.forEach { consider(it.name) }
        when (node) {
            is Map<*, *> -> node.keys.forEach { key -> consider(key?.toString() ?: return@forEach) }
            is List<*>, is Array<*> -> Unit
            else -> GraphReflectionCache.propertyMap(node::class, options.includePrivate)
                .keys.forEach { consider(it) }
        }
        return ambiguous
    }

    /**
     * The same lookup ignoring case, consulted only when the exact name missed. It goes
     * through [edgesFor] deliberately: an edge found this way is then the very edge the full
     * walk would have reported, and the first match wins so the answer is the declaration
     * order one rather than an arbitrary one.
     */
    private fun edgeNamedIgnoringCase(node: Any, name: String, options: GraphWalkOptions): LiveGraphEdge? =
        edgesFor(node, options).firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun edgesFor(node: Any, options: GraphWalkOptions): List<LiveGraphEdge> {
        val custom = (node as? LiveGraphInspectable)?.liveGraphChildren()?.toList().orEmpty()
        val reflected = reflectiveEdges(node, options)
        if (custom.isEmpty()) return reflected

        val merged = linkedMapOf<String, LiveGraphEdge>()
        custom.forEach { merged[it.name] = it }
        reflected.forEach { merged.putIfAbsent(it.name, it) }
        return merged.values.toList()
    }

    private fun reflectiveEdges(node: Any, options: GraphWalkOptions): List<LiveGraphEdge> = when (node) {
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val mutable = node as? MutableMap<Any?, Any?>
            node.keys.mapNotNull { rawKey ->
                val key = rawKey?.toString() ?: return@mapNotNull null
                LiveGraphEdge(
                    name = key,
                    getter = { node[rawKey] },
                    setter = mutable?.let { map -> { value: Any? -> map[rawKey] = value } }
                )
            }
        }
        is List<*> -> {
            @Suppress("UNCHECKED_CAST")
            val mutable = node as? MutableList<Any?>
            node.indices.map { index ->
                LiveGraphEdge(
                    name = index.toString(),
                    getter = { node.getOrNull(index) },
                    setter = mutable?.let { list -> { value: Any? -> list[index] = value } }
                )
            }
        }
        // Arrays are mutable in Kotlin, so their elements get setters just like a
        // MutableList's do. Storing an incompatible value throws ArrayStoreException,
        // which is the correct loud failure rather than something to pre-empt here.
        is Array<*> -> {
            @Suppress("UNCHECKED_CAST")
            val mutable = node as Array<Any?>
            node.indices.map { index ->
                LiveGraphEdge(
                    name = index.toString(),
                    getter = { node[index] },
                    setter = { value: Any? -> mutable[index] = value }
                )
            }
        }
        else -> GraphReflectionCache.properties(node::class, options.includePrivate)
            .map { propertyEdge(node, it, options) }
    }

    private fun propertyEdge(
        node: Any,
        prop: KProperty1<Any, *>,
        options: GraphWalkOptions
    ): LiveGraphEdge {
        // Accessibility is about *reaching* the member, not about which members we chose to
        // expose — that decision was already made by the visibility filter in
        // GraphReflectionCache. A public property of a non-public class (a private nested
        // class, an internal class) still needs setAccessible(true) to invoke; without it
        // prop.get throws IllegalAccessException, safeGet turns that into null, and the walk
        // silently stops descending. So always try to open the accessor.
        ensureAccessible(prop)
        val getter = { prop.get(node) }
        val setter = if (prop is KMutableProperty1<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val mutable = prop as KMutableProperty1<Any, Any?>
            { value: Any? -> mutable.set(node, value) }
        } else null
        return LiveGraphEdge(prop.name, getter, setter)
    }

    /**
     * Best-effort `setAccessible(true)`. A module boundary can refuse, in which case the
     * getter simply fails later and the slot reads as null — the same outcome as before,
     * but now only for members that genuinely cannot be reached.
     */
    private fun ensureAccessible(prop: KProperty1<Any, *>) {
        if (prop.isAccessible) return
        runCatching { prop.isAccessible = true }
    }

    private fun safeGet(getter: () -> Any?): Any? = try {
        getter()
    } catch (_: Throwable) {
        null
    }

    internal fun shouldDescend(value: Any, options: GraphWalkOptions): Boolean {
        val byDefault = defaultShouldDescend(value)
        val refine = options.descendInto ?: return byDefault
        return refine(value, byDefault)
    }

    private fun defaultShouldDescend(value: Any): Boolean = when (value) {
        is Number, is Boolean, is Char, is String, is Enum<*> -> false
        is FloatArray, is DoubleArray, is IntArray, is LongArray,
        is ShortArray, is ByteArray, is BooleanArray, is CharArray -> false
        is Function<*> -> false
        is Class<*>, is KClass<*> -> false
        is Map<*, *>, is List<*>, is Array<*> -> true
        is LiveGraphInspectable -> true
        else -> {
            val pkg = value::class.java.packageName ?: ""
            !(pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg.startsWith("jdk.") || pkg.startsWith("sun."))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  Patterns — parsed once, reusable, with a fast path for literal prefixes
// ═══════════════════════════════════════════════════════════════════════════

/**
 * A parsed query pattern.
 *
 * Compiling a pattern is worth doing when the same query runs repeatedly — the segments are
 * split once, and the literal prefix is exploited to avoid walking the whole graph:
 *
 * ```kotlin
 * val cutoffs = GraphQuery.compile("osc/" + "*" + "/cutoff")
 * every(frame) { cutoffs.resolve(patch).values() }
 * ```
 *
 * `osc` is descended directly, and only that subtree is traversed. A fully literal pattern
 * resolves in O(depth) with no traversal and no wasted allocation at all. A segment holding a
 * within-segment glob (`"osc*"`) is not part of the literal prefix — it has to be matched
 * against the names that are actually there, which means walking. A pattern with more than
 * one `|` branch walks too, and that is the deliberate trade: one walk filtered by every
 * branch at once, which is both simpler and cheaper than a query per branch, and it reports
 * each slot once even when two branches would have claimed it.
 */
class GraphPattern internal constructor(val pattern: String, internal val branches: List<List<String>>) {

    /**
     * The single branch, when there is one. Every optimization below needs one path shape to
     * descend, so alternation deliberately gives all of them up rather than guessing.
     */
    private val onlyBranch: List<String>? = branches.singleOrNull()

    /** Leading segments that are plain names — the part we can descend without searching. */
    internal val staticPrefix: List<String> =
        onlyBranch?.takeWhile { !it.contains('*') } ?: emptyList()

    /** True when the whole pattern is literal, so exactly one slot can match. */
    val isStatic: Boolean get() = onlyBranch != null && staticPrefix.size == onlyBranch.size

    /** How many alternatives this pattern was written with. */
    val alternatives: Int get() = branches.size

    fun matches(path: String): Boolean = globMatchesAny(splitPath(path), branches)

    /** Run this pattern against a root. */
    fun resolve(root: Any?, options: GraphWalkOptions = GraphWalkOptions()): PathSet {
        if (root == null || branches.isEmpty()) return PathSet(root, emptyList())

        // Fully literal: resolve the chain directly, no traversal.
        //
        // `allExact` is what keeps the two strategies honest. A literal segment matches
        // ignoring case, so `"freq"` can reach a slot actually called `Freq` — but if a node
        // holds both, only the walk can report both, while this resolver returns one. So a
        // literal pattern takes the fast path only when it named its slots exactly, and
        // otherwise falls through to the walk below. Getting the case right is the common
        // case, so the optimization is intact in practice.
        if (isStatic) {
            val resolved = GraphWalker.resolveStatic(root, onlyBranch!!, options)
                ?: return PathSet(root, emptyList())
            if (resolved.allExact) {
                if (resolved.value == null && !options.includeNulls) return PathSet(root, emptyList())
                return PathSet(root, listOf(resolved.ref))
            }
        }

        // Literal prefix then wildcards: descend the prefix, walk only that subtree.
        // Only the *final* prefix ref can ever satisfy the pattern (a shorter path cannot
        // match a longer literal prefix), so the intermediate refs are not collected.
        if (staticPrefix.isNotEmpty()) {
            val resolved = GraphWalker.resolveStatic(root, staticPrefix, options)
                ?: return PathSet(root, emptyList())
            if (resolved.allExact) {
                val refs = mutableListOf<GraphPathRef>()
                if (resolved.value != null || options.includeNulls) refs += resolved.ref
                refs += GraphWalker.scanSubtree(resolved.value, staticPrefix, options, resolved.ancestors)
                return PathSet(root, refs.filter { globMatchesAny(it.pathSegments, branches) })
            }
        }

        return GraphWalker.scan(root, options).matching(pattern)
    }

    override fun toString(): String = pattern
}

/** Entry point for compiled queries. */
object GraphQuery {
    private val cache = ConcurrentHashMap<String, GraphPattern>()

    /** Parse (and cache) a pattern for repeated use. */
    fun compile(pattern: String): GraphPattern =
        cache.computeIfAbsent(pattern) { GraphPattern(it, splitAlternatives(it)) }
}

// ═══════════════════════════════════════════════════════════════════════════
//  Entry points
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Walk this object graph and return every slot whose address matches [pattern].
 *
 * `*` matches one segment and `**` matches any number of segments including zero. A `*`
 * *inside* a segment globs within that one name — `"*Alpha"` finds `contourAlpha`, `"osc*"`
 * finds `osc1` and `osc2` — and never crosses a `/`. Names are matched ignoring case, so
 * `"cutoff"` reaches `cutOff`. A `|` separates whole patterns and the matches are unioned:
 * `"**" + "/" + "*alpha | **" + "/w"` collects both, each slot once, in walk order.
 *
 * A literal prefix is resolved directly rather than by traversing everything, so
 * `"osc/filter/cutoff"` costs three property reads. Alternation gives that up — with more
 * than one branch there is no single path to descend, so it walks.
 */
fun Any?.graphPaths(
    pattern: String = "**",
    options: GraphWalkOptions = GraphWalkOptions()
): PathSet = GraphQuery.compile(pattern).resolve(this, options)

/** The single slot at a literal [path] — matched ignoring case — or null. */
fun Any?.graphPath(
    path: String,
    options: GraphWalkOptions = GraphWalkOptions()
): GraphPathRef? = GraphWalker.resolveStatic(this, splitPath(path), options)?.ref

/** Typed lenses over every matching slot holding a [T]. */
inline fun <reified T : Any> Any?.graphLenses(
    pattern: String = "**",
    options: GraphWalkOptions = GraphWalkOptions()
): LensSet<T> = graphPaths(pattern, options).lenses(T::class)

// ═══════════════════════════════════════════════════════════════════════════
//  Path / glob plumbing
// ═══════════════════════════════════════════════════════════════════════════

internal fun splitPath(path: String): List<String> =
    path.split("/").map { it.trim() }.filter { it.isNotEmpty() }

internal fun splitGlob(path: String): List<String> =
    path.split("/").map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Split a pattern into its alternatives on `|`, each already split into segments.
 *
 * `|` separates *whole patterns*, so `"** slash *alpha | ** slash w"` — written with real
 * separators — is two branches, and a slot matches when it satisfies either. The trimming that [splitGlob] already does is what makes
 * the spaces round the bar optional.
 *
 * The corollary is worth stating because it will catch someone: `"**\/freq|gain"` is
 * `"**\/freq"` or `"gain"`, *not* two leaves under `**`. Repeat the prefix on both sides.
 *
 * Empty alternatives are dropped, so a trailing bar is harmless and `"|"` matches nothing.
 */
internal fun splitAlternatives(pattern: String): List<List<String>> =
    pattern.split('|').map { splitGlob(it) }.filter { it.isNotEmpty() }

/** True when [path] satisfies any one of [branches] — the whole of what `|` means. */
internal fun globMatchesAny(path: List<String>, branches: List<List<String>>): Boolean =
    branches.any { globMatches(path, it) }

internal fun globMatches(
    path: List<String>,
    pattern: List<String>,
    pathIndex: Int = 0,
    patternIndex: Int = 0
): Boolean {
    if (patternIndex >= pattern.size) return pathIndex >= path.size
    val token = pattern[patternIndex]
    return when (token) {
        "**" -> {
            if (patternIndex == pattern.lastIndex) true
            else (pathIndex..path.size).any { next -> globMatches(path, pattern, next, patternIndex + 1) }
        }
        "*" -> pathIndex < path.size && globMatches(path, pattern, pathIndex + 1, patternIndex + 1)
        else -> pathIndex < path.size && segmentMatches(path[pathIndex], token) &&
                globMatches(path, pattern, pathIndex + 1, patternIndex + 1)
    }
}

/**
 * Does one path segment satisfy one pattern token?
 *
 * A token that contains a `*` is a glob *within* one segment: `"*Alpha"` finds
 * `contourAlpha`, `"contour*"` finds it too, and neither can cross a `/` — segment-crossing
 * stays the exclusive job of `**`. A token with no `*` is a plain name.
 *
 * Both comparisons ignore case, on the grounds that a slot is usually a name you half
 * remember rather than one you are quoting. The consequence worth knowing is that two
 * properties differing only in case are both matched by one pattern; they are still two
 * distinct slots, and [deduplicateRefs] keeps treating them as such.
 */
internal fun segmentMatches(segment: String, token: String): Boolean =
    if (token.contains('*')) segmentGlob(token).matches(segment)
    else token.equals(segment, ignoreCase = true)

private val segmentGlobs = ConcurrentHashMap<String, Regex>()

/**
 * Compile (and cache) one within-segment glob. Each run of `*` becomes "any run of
 * non-separator characters" and every literal chunk between them is quoted, so a property
 * name containing regex metacharacters cannot smuggle syntax into the pattern.
 */
private fun segmentGlob(token: String): Regex = segmentGlobs.computeIfAbsent(token) { raw ->
    Regex(
        raw.split('*').joinToString("[^/]*") { chunk -> if (chunk.isEmpty()) "" else Regex.escape(chunk) },
        RegexOption.IGNORE_CASE
    )
}

/**
 * Group refs by the slot they address — same owner (by identity), same final segment. Two
 * addresses for one slot land in the same group.
 */
internal fun groupRefsBySlot(refs: List<GraphPathRef>): List<List<GraphPathRef>> {
    val nullOwner = Any()
    val byOwner = IdentityHashMap<Any, LinkedHashMap<String, MutableList<GraphPathRef>>>()
    val groups = mutableListOf<MutableList<GraphPathRef>>()
    refs.forEach { ref ->
        val owner = ref.owner ?: nullOwner
        val segment = ref.pathSegments.lastOrNull().orEmpty()
        val bySegment = byOwner.getOrPut(owner) { linkedMapOf() }
        val group = bySegment.getOrPut(segment) {
            mutableListOf<GraphPathRef>().also { groups += it }
        }
        group += ref
    }
    return groups
}

internal fun deduplicateRefs(refs: List<GraphPathRef>): List<GraphPathRef> =
    groupRefsBySlot(refs).map { it.first() }

@Suppress("UNCHECKED_CAST")
private fun <T : Any> KClass<T>.safeCast(value: Any?): T? =
    if (isInstance(value)) value as T else null

// ═══════════════════════════════════════════════════════════════════════════
//  Shared HTML rendering
// ═══════════════════════════════════════════════════════════════════════════

internal const val PATH_COLOUR = "#8cf"
internal const val VALUE_COLOUR = "#ccc"
internal const val MUTED_COLOUR = "#888"
private const val MAX_HTML_ROWS = 24

/**
 * One table renderer for every result type, so the docs captures look consistent — now a thin
 * adapter over [Table], which is the repo-wide one.
 *
 * [columns] is each column's name paired with the colour its cells take, and [rows] holds plain
 * text: [Table] escapes it, so callers must **not** pre-escape, and the `<script>` case in
 * `GraphQuerySmokeTest` covers that.
 */
internal fun renderGraphTable(
    title: String,
    count: Int,
    columns: List<Pair<String, String>>,
    rows: List<List<String>>
): String {
    val table = Table(columns.map { it.first }, title = "$title ($count)")
    table.maxRows = MAX_HTML_ROWS
    columns.forEach { (name, colour) -> table.color(name, colour) }
    return table.rows(rows).toHTML()
}

internal fun shortValue(value: Any?): String = when (value) {
    null -> "null"
    is Float -> formatGraphFloat(value)
    is Double -> "%.3f".format(value)
    is Number, is Boolean, is Char -> value.toString()
    is String -> if (value.length > 80) value.take(77) + "..." else value
    else -> value::class.simpleName ?: value.toString()
}

internal fun formatGraphFloat(value: Float): String {
    val abs = kotlin.math.abs(value)
    val decimals = when {
        abs >= 100f -> 0
        abs >= 10f -> 1
        abs >= 1f -> 2
        else -> 3
    }
    return String.format(java.util.Locale.US, "%.${decimals}f", value)
}
