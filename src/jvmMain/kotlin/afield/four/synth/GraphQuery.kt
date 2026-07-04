package afield.four.synth

import afield.Runloop
import afield.ToHTML
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * Optional protocol for objects that want to expose meaningful live-graph
 * children instead of whatever general reflection would discover.
 */
interface LiveGraphInspectable {
    fun liveGraphChildren(): Iterable<LiveGraphEdge> = emptyList()
}

data class LiveGraphEdge(
    val name: String,
    val getter: () -> Any?,
    val setter: ((Any?) -> Unit)? = null
) {
    constructor(name: String, getter: () -> Any?) : this(name, getter, null)
}

data class GraphWalkOptions(
    val includePrivate: Boolean = false,
    val includeNulls: Boolean = false,
    val maxDepth: Int = 32
)

class GraphPathRef internal constructor(
    internal val pathSegments: List<String>,
    val owner: Any?,
    private val getter: () -> Any?,
    private val setter: ((Any?) -> Unit)?
) {
    val rawPath: String = pathSegments.joinToString("/")
    val path: String = if (rawPath.isBlank()) "<root>" else rawPath
    val writable: Boolean get() = setter != null

    fun get(): Any? = getter()

    fun set(value: Any?) {
        val set = setter ?: error("Path '$path' is not writable")
        set(value)
    }

    override fun toString(): String = path
}

class PathSet internal constructor(
    private val root: Any?,
    internal val refs: List<GraphPathRef>
) : Iterable<GraphPathRef>, ToHTML {
    val size: Int get() = refs.size
    val isEmpty: Boolean get() = refs.isEmpty()

    fun paths(): List<String> = refs.map { it.path }

    fun values(): List<Any?> = refs.map { it.get() }

    fun writable(): PathSet = PathSet(root, refs.filter { it.writable })

    fun dedup(): PathSet = PathSet(root, deduplicateRefs(refs))

    fun deduplicated(): PathSet = dedup()

    fun matching(pattern: String): PathSet {
        val segments = splitGlob(pattern)
        return PathSet(root, refs.filter { globMatches(it.pathSegments, segments) })
    }

    fun params(): ParamSet = ParamSet.from(root, refs)

    fun ofType(type: KClass<*>): PathSet = PathSet(root, refs.filter { type.isInstance(it.get()) })

    inline fun <reified T : Any> ofType(): PathSet = ofType(T::class)

    fun firstOrNull(): GraphPathRef? = refs.firstOrNull()

    override fun iterator(): Iterator<GraphPathRef> = refs.iterator()

    override fun toString(): String = "PathSet(${size})"

    override fun toHTML(): String = buildString {
        append("<div style='font-family:\"Iosevka Web\",monospace;font-size:12px;color:#ccc;'>")
        append("<div style='color:#0af;margin-bottom:6px;'>PathSet")
        append(" (${size})")
        append("</div>")
        append("<table style='border-collapse:collapse;width:100%;'>")
        append("<tr><th style='text-align:left;color:#0af;padding:2px 6px 2px 0;'>path</th><th style='text-align:left;color:#0af;padding:2px 0;'>value</th></tr>")
        refs.take(24).forEach { ref ->
            append("<tr>")
            append("<td style='padding:2px 6px 2px 0;vertical-align:top;color:#8cf;'>${escapeHtml(ref.path)}</td>")
            append("<td style='padding:2px 0;vertical-align:top;color:#ccc;'>${escapeHtml(shortValue(ref.get()))}</td>")
            append("</tr>")
        }
        if (refs.size > 24) {
            append("<tr><td colspan='2' style='padding-top:4px;color:#888;'>… ${refs.size - 24} more</td></tr>")
        }
        append("</table></div>")
    }
}

enum class ParamModulationMode {
    REPLACE,
    ADD
}

internal interface ParamBinding {
    val isNativeParam: Boolean
    fun asParamOrNull(): Param?
    fun getBase(): Float
    fun setBase(value: Float)
    fun getSupplier(): (SynthContext.() -> Float)?
    fun setSupplier(value: (SynthContext.() -> Float)?)
    fun getModulation(): Signal?
    fun setModulation(value: Signal?)
}

private class NativeParamBinding(
    private val param: Param
) : ParamBinding {
    override val isNativeParam: Boolean = true

    override fun asParamOrNull(): Param = param

    override fun getBase(): Float = param.base

    override fun setBase(value: Float) {
        param.base = value
    }

    override fun getSupplier(): (SynthContext.() -> Float)? = param.supplier

    override fun setSupplier(value: (SynthContext.() -> Float)?) {
        param.supplier = value
    }

    override fun getModulation(): Signal? = param.mod

    override fun setModulation(value: Signal?) {
        param.mod = value
    }
}

private class NumericPathBinding(
    private val state: ScalarPathState
) : ParamBinding {
    override val isNativeParam: Boolean = false

    override fun asParamOrNull(): Param? = null

    override fun getBase(): Float = state.base

    override fun setBase(value: Float) {
        state.base = value
    }

    override fun getSupplier(): (SynthContext.() -> Float)? = state.supplier

    override fun setSupplier(value: (SynthContext.() -> Float)?) {
        state.supplier = value
    }

    override fun getModulation(): Signal? = state.modulation

    override fun setModulation(value: Signal?) {
        state.modulation = value
    }
}

class ParamPath internal constructor(
    private val primaryRef: GraphPathRef,
    val aliases: List<String>,
    private val binding: ParamBinding
) {
    val path: String get() = primaryRef.path
    val rawPath: String get() = primaryRef.rawPath
    val isNativeParam: Boolean get() = binding.isNativeParam
    val param: Param get() = binding.asParamOrNull()
        ?: error("Path '$path' refers to a scalar value, not a synth Param")
    fun paramOrNull(): Param? = binding.asParamOrNull()

    var base: Float
        get() = binding.getBase()
        set(value) {
            binding.setBase(value)
        }

    var supplier: (SynthContext.() -> Float)?
        get() = binding.getSupplier()
        set(value) {
            binding.setSupplier(value)
        }

    var modulation: Signal?
        get() = binding.getModulation()
        set(value) {
            binding.setModulation(value)
        }

    fun clearModulation(): ParamPath {
        binding.setModulation(null)
        return this
    }

    fun clearSupplier(): ParamPath {
        binding.setSupplier(null)
        return this
    }

    fun modulate(signal: Signal, mode: ParamModulationMode = ParamModulationMode.REPLACE): ParamPath {
        modulation = when (mode) {
            ParamModulationMode.REPLACE -> signal
            ParamModulationMode.ADD -> modulation?.let { it + signal } ?: signal
        }
        return this
    }

    override fun toString(): String = path
}

class ParamSet internal constructor(
    private val root: Any?,
    internal val entries: List<ParamPath>
) : Iterable<ParamPath>, ToHTML {
    companion object {
        internal fun from(root: Any?, refs: List<GraphPathRef>): ParamSet {
            val grouped = IdentityHashMap<Param, MutableList<GraphPathRef>>()
            val scalarRefs = mutableListOf<GraphPathRef>()
            refs.forEach { ref ->
                val value = ref.get()
                when (value) {
                    is Param -> grouped.getOrPut(value) { mutableListOf() }.add(ref)
                    is Number -> if (ref.writable) scalarRefs += ref
                }
            }
            val entries = mutableListOf<ParamPath>()
            grouped.entries.forEach { (param, paramRefs) ->
                val primary = paramRefs.first()
                val aliases = paramRefs.map { it.path }.distinct()
                entries += ParamPath(
                    primaryRef = GraphPathRef(
                        pathSegments = primary.pathSegments,
                        owner = primary.owner,
                        getter = { param },
                        setter = null
                    ),
                    aliases = aliases,
                    binding = NativeParamBinding(param)
                )
            }
            groupRefsBySlot(scalarRefs).forEach { scalarGroup ->
                val primary = scalarGroup.first()
                val state = ScalarPathRegistry.stateFor(primary) ?: return@forEach
                entries += ParamPath(
                    primaryRef = primary,
                    aliases = scalarGroup.map { it.path }.distinct(),
                    binding = NumericPathBinding(state)
                )
            }
            return ParamSet(
                root = root,
                entries = entries.sortedBy { it.path }
            )
        }
    }

    val size: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    fun paths(): List<String> = entries.map { it.path }

    fun params(): List<Param> = entries.mapNotNull { it.paramOrNull() }

    fun bases(): List<Float> = entries.map { it.base }

    fun matching(pattern: String): ParamSet {
        val segments = splitGlob(pattern)
        return ParamSet(root, entries.filter { globMatches(splitPath(it.rawPath), segments) })
    }

    fun set(value: Float): ParamSet = apply {
        entries.forEach { it.base = value }
    }

    fun set(values: Iterable<Float>): ParamSet = apply {
        entries.zip(values).forEach { (entry, value) -> entry.base = value }
    }

    fun set(block: ParamSetScope.(ParamPath, Int) -> Float): ParamSet = apply {
        val scope = ParamSetScope(this)
        entries.forEachIndexed { index, entry ->
            entry.base = scope.block(entry, index)
        }
    }

    fun offset(delta: Float): ParamSet = apply {
        entries.forEach { it.base += delta }
    }

    fun scale(factor: Float): ParamSet = apply {
        entries.forEach { it.base *= factor }
    }

    fun clamp(minValue: Float, maxValue: Float): ParamSet = apply {
        entries.forEach { it.base = it.base.coerceIn(minValue, maxValue) }
    }

    fun random(range: ClosedFloatingPointRange<Float>, random: Random = Random.Default): ParamSet = apply {
        val lo = min(range.start, range.endInclusive)
        val hi = max(range.start, range.endInclusive)
        entries.forEach { it.base = random.nextFloat() * (hi - lo) + lo }
    }

    fun choose(values: Iterable<Float>, random: Random = Random.Default): ParamSet = apply {
        val options = values.toList()
        if (options.isEmpty()) return@apply
        entries.forEach { it.base = options[random.nextInt(options.size)] }
    }

    fun modulate(signal: Signal, mode: ParamModulationMode = ParamModulationMode.REPLACE): ParamSet = apply {
        entries.forEach { it.modulate(signal, mode) }
    }

    fun modulate(
        mode: ParamModulationMode = ParamModulationMode.REPLACE,
        block: ParamSetScope.(ParamPath, Int) -> Signal?
    ): ParamSet = apply {
        val scope = ParamSetScope(this)
        entries.forEachIndexed { index, entry ->
            scope.block(entry, index)?.let { entry.modulate(it, mode) }
        }
    }

    fun clearModulation(): ParamSet = apply {
        entries.forEach { it.clearModulation() }
    }

    fun supply(block: SynthContext.(ParamPath, Int, ParamSetScope) -> Float): ParamSet = apply {
        val scope = ParamSetScope(this)
        entries.forEachIndexed { index, entry ->
            entry.supplier = { block(entry, index, scope) }
        }
    }

    fun clearSuppliers(): ParamSet = apply {
        entries.forEach { it.clearSupplier() }
    }

    fun rewrite(block: ParamSetScope.() -> Unit): ParamSet = apply {
        ParamSetScope(this).block()
    }

    override fun iterator(): Iterator<ParamPath> = entries.iterator()

    override fun toString(): String = "ParamSet(${size})"

    override fun toHTML(): String = buildString {
        append("<div style='font-family:\"Iosevka Web\",monospace;font-size:12px;color:#ccc;'>")
        append("<div style='color:#0af;margin-bottom:6px;'>ParamSet")
        append(" (${size})")
        append("</div>")
        append("<table style='border-collapse:collapse;width:100%;'>")
        append("<tr><th style='text-align:left;color:#0af;padding:2px 6px 2px 0;'>path</th><th style='text-align:left;color:#0af;padding:2px 6px 2px 0;'>base</th><th style='text-align:left;color:#0af;padding:2px 0;'>state</th></tr>")
        entries.take(24).forEach { entry ->
            append("<tr>")
            append("<td style='padding:2px 6px 2px 0;vertical-align:top;color:#8cf;'>${escapeHtml(entry.path)}</td>")
            append("<td style='padding:2px 6px 2px 0;vertical-align:top;color:#ccc;'>${formatGraphFloat(entry.base)}</td>")
            append("<td style='padding:2px 0;vertical-align:top;color:#888;'>")
            if (!entry.isNativeParam) {
                append("scalar")
                if (entry.modulation != null) append(" + mod")
                if (entry.supplier != null) append(" + supplier")
            } else {
                append(if (entry.modulation != null) "mod" else "base")
                if (entry.supplier != null) append(" + supplier")
            }
            if (entry.aliases.size > 1) append(" (${entry.aliases.size} aliases)")
            append("</td>")
            append("</tr>")
        }
        if (entries.size > 24) {
            append("<tr><td colspan='3' style='padding-top:4px;color:#888;'>… ${entries.size - 24} more</td></tr>")
        }
        append("</table></div>")
    }
}

class ParamSetScope internal constructor(
    private val set: ParamSet
) {
    val size: Int get() = set.size
    val entries: List<ParamPath> get() = set.entries
    val values: List<Float> get() = entries.map { it.base }
    val mean: Float get() = if (entries.isEmpty()) 0f else values.sum() / entries.size.toFloat()
    val min: Float get() = values.minOrNull() ?: 0f
    val max: Float get() = values.maxOrNull() ?: 0f

    operator fun get(index: Int): ParamPath = entries[index]

    fun matching(pattern: String): ParamSet = set.matching(pattern)

    fun setAll(value: Float) {
        entries.forEach { it.base = value }
    }

    fun set(values: Iterable<Float>) {
        entries.zip(values).forEach { (entry, value) -> entry.base = value }
    }

    fun normalize(range: ClosedFloatingPointRange<Float> = 0f..1f) {
        if (entries.isEmpty()) return
        val start = min(range.start, range.endInclusive)
        val end = max(range.start, range.endInclusive)
        val lo = min
        val hi = max
        if (kotlin.math.abs(hi - lo) < 1e-6f) {
            val midpoint = (start + end) * 0.5f
            entries.forEach { it.base = midpoint }
            return
        }
        entries.forEach { entry ->
            val t = (entry.base - lo) / (hi - lo)
            entry.base = start + (end - start) * t
        }
    }

    fun jitter(amount: Float, random: Random = Random.Default) {
        if (amount == 0f) return
        entries.forEach { entry ->
            entry.base += (random.nextFloat() * 2f - 1f) * amount
        }
    }

    fun ramp(range: ClosedFloatingPointRange<Float>) {
        if (entries.isEmpty()) return
        val start = range.start
        val end = range.endInclusive
        if (entries.size == 1) {
            entries.first().base = (start + end) * 0.5f
            return
        }
        entries.forEachIndexed { index, entry ->
            val t = index.toFloat() / (entries.lastIndex).toFloat()
            entry.base = start + (end - start) * t
        }
    }
}

private object GraphReflectionCache {
    private val publicCache = ConcurrentHashMap<KClass<*>, List<KProperty1<Any, *>>>()
    private val privateCache = ConcurrentHashMap<KClass<*>, List<KProperty1<Any, *>>>()

    fun properties(type: KClass<*>, includePrivate: Boolean): List<KProperty1<Any, *>> {
        val cache = if (includePrivate) privateCache else publicCache
        return cache.computeIfAbsent(type) { buildProperties(it, includePrivate) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildProperties(type: KClass<*>, includePrivate: Boolean): List<KProperty1<Any, *>> {
        val constructorOrder = type.primaryConstructor?.parameters?.mapNotNull { it.name } ?: emptyList()
        val discovered = linkedMapOf<String, KProperty1<Any, *>>()
        val candidates = buildList {
            addAll(type.memberProperties)
            if (includePrivate) addAll(type.declaredMemberProperties)
        }
        val ordered = candidates
            .filterIsInstance<KProperty1<Any, *>>()
            .filter { includePrivate || it.visibility == KVisibility.PUBLIC }
            .sortedWith(compareBy<KProperty1<Any, *>>(
                { constructorOrder.indexOf(it.name).let { idx -> if (idx >= 0) idx else Int.MAX_VALUE } },
                { it.name }
            ))
        ordered.forEach { discovered.putIfAbsent(it.name, it) }
        return discovered.values.toList()
    }
}

private object GraphWalker {
    fun scan(root: Any?, options: GraphWalkOptions): PathSet {
        if (root == null) return PathSet(root, emptyList())

        val refs = mutableListOf<GraphPathRef>()
        val visited = IdentityHashMap<Any, Unit>()

        fun walk(node: Any?, depth: Int) {
            if (node == null || depth > options.maxDepth || !shouldDescend(node)) return
            if (visited.put(node, Unit) != null) return

            edgesFor(node, options).forEach { edge ->
                val childValue = safeGet(edge.getter) ?: run {
                    if (options.includeNulls) {
                        refs += GraphPathRef(
                            pathSegments = edgePath(node, edge.name, refs),
                            owner = node,
                            getter = edge.getter,
                            setter = edge.setter
                        )
                    }
                    return@forEach
                }
                val pathSegments = edgePath(node, edge.name, refs)
                refs += GraphPathRef(
                    pathSegments = pathSegments,
                    owner = node,
                    getter = edge.getter,
                    setter = edge.setter
                )
                walk(childValue, depth + 1)
            }
        }

        fun walkNode(node: Any?, path: List<String>, depth: Int) {
            if (node == null || depth > options.maxDepth || !shouldDescend(node)) return
            if (visited.put(node, Unit) != null) return

            edgesFor(node, options).forEach { edge ->
                val childPath = path + edge.name
                refs += GraphPathRef(
                    pathSegments = childPath,
                    owner = node,
                    getter = edge.getter,
                    setter = edge.setter
                )
                walkNode(safeGet(edge.getter), childPath, depth + 1)
            }
        }

        walkNode(root, emptyList(), 0)
        return PathSet(root, refs)
    }

    private fun edgePath(node: Any, name: String, existing: List<GraphPathRef>): List<String> {
        val found = existing.lastOrNull { it.get() === node } ?: return listOf(name)
        return found.pathSegments + name
    }

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
                    setter = mutable?.let { map -> { value -> map[rawKey] = value } }
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
                    setter = mutable?.let { list -> { value -> list[index] = value } }
                )
            }
        }
        is Array<*> -> node.indices.map { index ->
            LiveGraphEdge(
                name = index.toString(),
                getter = { node[index] },
                setter = null
            )
        }
        else -> GraphReflectionCache.properties(node::class, options.includePrivate).mapNotNull { prop ->
            val getter = {
                prop.isAccessible = options.includePrivate
                prop.get(node)
            }
            val setter = if (prop is KMutableProperty1<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val mutable = prop as KMutableProperty1<Any, Any?>
                { value: Any? ->
                    mutable.isAccessible = options.includePrivate
                    mutable.set(node, value)
                }
            } else null
            LiveGraphEdge(prop.name, getter, setter)
        }
    }

    private fun safeGet(getter: () -> Any?): Any? = try {
        getter()
    } catch (_: Throwable) {
        null
    }

    private fun shouldDescend(value: Any): Boolean = when (value) {
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

private fun splitPath(path: String): List<String> =
    path.split("/").map { it.trim() }.filter { it.isNotEmpty() }

private fun splitGlob(path: String): List<String> =
    path.split("/").map { it.trim() }.filter { it.isNotEmpty() }

private fun globMatches(path: List<String>, pattern: List<String>, pathIndex: Int = 0, patternIndex: Int = 0): Boolean {
    if (patternIndex >= pattern.size) return pathIndex >= path.size
    val token = pattern[patternIndex]
    return when (token) {
        "**" -> {
            if (patternIndex == pattern.lastIndex) true
            else (pathIndex..path.size).any { next -> globMatches(path, pattern, next, patternIndex + 1) }
        }
        "*" -> pathIndex < path.size && globMatches(path, pattern, pathIndex + 1, patternIndex + 1)
        else -> pathIndex < path.size && token == path[pathIndex] && globMatches(path, pattern, pathIndex + 1, patternIndex + 1)
    }
}

private fun shortValue(value: Any?): String = when (value) {
    null -> "null"
    is Float -> formatGraphFloat(value)
    is Double -> "%.3f".format(value)
    is Number, is Boolean, is Char -> value.toString()
    is String -> if (value.length > 80) value.take(77) + "..." else value
    else -> value::class.simpleName ?: value.toString()
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun formatGraphFloat(value: Float): String {
    val abs = kotlin.math.abs(value)
    val decimals = when {
        abs >= 100f -> 0
        abs >= 10f -> 1
        abs >= 1f -> 2
        else -> 3
    }
    return String.format(java.util.Locale.US, "%.${decimals}f", value)
}

private fun groupRefsBySlot(refs: List<GraphPathRef>): List<List<GraphPathRef>> {
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

private fun deduplicateRefs(refs: List<GraphPathRef>): List<GraphPathRef> =
    groupRefsBySlot(refs).map { it.first() }

private fun coerceNumericValue(sample: Number, value: Float): Any = when (sample) {
    is Float -> value
    is Double -> value.toDouble()
    is Int -> value.roundToInt()
    is Long -> value.roundToLong()
    is Short -> value.roundToInt().toShort()
    is Byte -> value.roundToInt().toByte()
    else -> value
}

internal interface GraphRuntimeDriven {
    fun isGraphDynamic(): Boolean
    fun tickGraph(context: GraphBindingContext)
    fun refreshGraphNow(context: GraphBindingContext)
}

internal class GraphBindingContext(
    val name: String,
    sampleRate: Float,
    bufferSize: Int
) {
    internal val synth = SynthContext(sampleRate, bufferSize)
    private var scratch = FloatArray(bufferSize.coerceAtLeast(1))

    val tickId: Long get() = synth.cycleId
    val time: Float get() = synth.time

    fun sample(signal: Signal): Float {
        signal.render(scratch, 1, synth)
        return scratch[0]
    }

    internal fun advance(deltaTime: Float) {
        synth.cycleId += 1
        synth.time += deltaTime
    }
}

private class ScalarPathState(
    private val ref: GraphPathRef,
    private val sample: Number
) : GraphRuntimeDriven {
    private val lock = Any()
    private var _base: Float = (ref.get() as? Number)?.toFloat()
        ?: error("Path '${ref.path}' no longer resolves to a numeric value")
    private var _supplier: (SynthContext.() -> Float)? = null
    private var _modulation: Signal? = null

    var base: Float
        get() = synchronized(lock) { _base }
        set(value) {
            val shouldApplyImmediately = synchronized(lock) {
                _base = value
                !isDynamicLocked()
            }
            if (shouldApplyImmediately) apply(value)
        }

    var supplier: (SynthContext.() -> Float)?
        get() = synchronized(lock) { _supplier }
        set(value) {
            synchronized(lock) {
                _supplier = value
            }
            GraphBindingRuntime.update(this)
        }

    var modulation: Signal?
        get() = synchronized(lock) { _modulation }
        set(value) {
            synchronized(lock) {
                _modulation = value
            }
            GraphBindingRuntime.update(this)
        }

    fun isDynamic(): Boolean = synchronized(lock) { isDynamicLocked() }

    override fun isGraphDynamic(): Boolean = isDynamic()

    override fun tickGraph(context: GraphBindingContext) {
        val (base, supplier, modulation) = synchronized(lock) {
            Triple(_base, _supplier, _modulation)
        }
        val supplied = supplier?.invoke(context.synth) ?: base
        val value = supplied + (modulation?.let { context.sample(it) } ?: 0f)
        apply(value)
    }

    override fun refreshGraphNow(context: GraphBindingContext) {
        if (isDynamic()) tickGraph(context) else apply(base)
    }

    private fun apply(value: Float) {
        ref.set(coerceNumericValue(sample, value))
    }

    private fun isDynamicLocked(): Boolean = _supplier != null || _modulation != null
}

private object ScalarPathRegistry {
    private val nullOwner = Any()
    private val byOwner = IdentityHashMap<Any, MutableMap<String, ScalarPathState>>()

    fun stateFor(ref: GraphPathRef): ScalarPathState? {
        val sample = ref.get() as? Number ?: return null
        val owner = ref.owner ?: nullOwner
        val segment = ref.pathSegments.lastOrNull().orEmpty()
        return synchronized(this) {
            val bySegment = byOwner.getOrPut(owner) { linkedMapOf() }
            bySegment.getOrPut(segment) { ScalarPathState(ref, sample) }
        }
    }
}

private class NativeParamRuntimeState(
    private val param: Param
) : GraphRuntimeDriven {
    override fun isGraphDynamic(): Boolean = false

    override fun tickGraph(context: GraphBindingContext) = Unit

    override fun refreshGraphNow(context: GraphBindingContext) {
        param.refreshGraphControl(context)
    }
}

private object ParamRuntimeRegistry {
    private val states = IdentityHashMap<Param, NativeParamRuntimeState>()

    fun stateFor(param: Param): NativeParamRuntimeState = synchronized(this) {
        states.getOrPut(param) { NativeParamRuntimeState(param) }
    }
}

internal object GraphBindingRuntime {
    private const val RUNLOOP_HOOK_KEY = "graphquery.dynamicBindings"
    private val contexts = ConcurrentHashMap<String, GraphBindingContext>()
    private val activeTargets = linkedSetOf<GraphRuntimeDriven>()
    private val lock = Any()
    private var hookInstalled = false
    private var lastRunloopNanos = 0L

    fun update(target: GraphRuntimeDriven) {
        synchronized(lock) {
            installRunloopHookIfNeeded()
            if (target.isGraphDynamic()) activeTargets += target else activeTargets -= target
        }
        target.refreshGraphNow(contextFor("runloop", 1f / 60f))
    }

    fun update(param: Param) {
        update(ParamRuntimeRegistry.stateFor(param))
    }

    fun tick(contextName: String, deltaTime: Float, sampleRate: Float = if (deltaTime > 0f) 1f / deltaTime else 60f) {
        val targets = synchronized(lock) { activeTargets.toList() }
        if (targets.isEmpty()) return
        val context = contextFor(contextName, sampleRate)
        context.advance(deltaTime)
        targets.forEach { it.tickGraph(context) }
    }

    private fun installRunloopHookIfNeeded() {
        if (hookInstalled) return
        Runloop.runLoop.preRun[RUNLOOP_HOOK_KEY] = sequence {
            while (true) {
                tickRunloop()
                yield(Unit)
            }
        }
        hookInstalled = true
    }

    private fun tickRunloop() {
        val now = System.nanoTime()
        val deltaTime = if (lastRunloopNanos == 0L) {
            1f / 60f
        } else {
            ((now - lastRunloopNanos).coerceAtLeast(1L) / 1_000_000_000.0).toFloat()
        }
        lastRunloopNanos = now
        tick("runloop", deltaTime, sampleRate = if (deltaTime > 0f) 1f / deltaTime else 60f)
    }

    private fun contextFor(name: String, sampleRate: Float): GraphBindingContext =
        contexts.compute(name) { _, existing ->
            if (existing != null) existing else GraphBindingContext(name = name, sampleRate = sampleRate, bufferSize = 1)
        }!!
    }

fun Any?.graphPaths(
    pattern: String = "**",
    options: GraphWalkOptions = GraphWalkOptions()
): PathSet = GraphWalker.scan(this, options).matching(pattern)

fun Any?.params(
    pattern: String = "**",
    options: GraphWalkOptions = GraphWalkOptions()
): ParamSet = graphPaths(pattern, options).params()

fun tickGraphBindings(
    contextName: String,
    deltaTime: Float,
    sampleRate: Float = if (deltaTime > 0f) 1f / deltaTime else 60f
) {
    GraphBindingRuntime.tick(contextName, deltaTime, sampleRate)
}

infix fun Any?.matches(pattern: String): ParamSet = params(pattern)
