@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import afield.Runloop
import afield.ToHTML
import afield.graphquery.GraphPathRef
import afield.graphquery.GraphWalkOptions
import afield.graphquery.PathSet
import afield.graphquery.escapeHtml
import afield.graphquery.formatGraphFloat
import afield.graphquery.graphPaths
import afield.graphquery.groupRefsBySlot
import afield.graphquery.renderGraphTable
import afield.graphquery.splitPath
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The synth-facing head of the graph-query layer: turn a [PathSet] into a set of *drivable
 * parameters*.
 *
 * Two kinds of slot qualify, behind one [ParamBinding] interface:
 *
 *  - a native [Param] (base + supplier + modulation), grouped by identity so several
 *    addresses for one Param collapse into a single entry with `aliases`;
 *  - any writable numeric property, wrapped in a [ScalarPathState] that gives a plain
 *    `Int`/`Float`/`Double`/… field the same base/supplier/modulation semantics, coercing
 *    back to the field's declared type on write.
 *
 * That second case is the interesting one: it makes *any* object's numeric field
 * modulatable, which is why the generic query core underneath deliberately knows nothing
 * about audio.
 *
 * This used to end "Only this file touches [Runloop]". Checked while auditing this package's KDoc
 * for the sound-path map, and it is not true — [AudioReturnValue], [PatPatternReturnValue] and
 * `BooleanNetworkSequencing` all reach for `Runloop.runLoop` too. What is true is narrower, and is
 * what the sentence was reaching for: the *generic query core* — path resolution, grouping,
 * [ScalarPathState] — has no runloop dependency at all, and this file is where the runloop-bound
 * half of the param layer is confined.
 */

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
    val param: Param
        get() = binding.asParamOrNull()
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
                when (val value = ref.get()) {
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
        val segments = afield.graphquery.splitGlob(pattern)
        return ParamSet(root, entries.filter { afield.graphquery.globMatches(splitPath(it.rawPath), segments) })
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

    override fun toHTML(): String = renderGraphTable(
        title = "ParamSet",
        count = size,
        headers = listOf("path", "base", "state"),
        rows = entries.map { entry ->
            listOf(
                escapeHtml(entry.path) to afield.graphquery.PATH_COLOUR,
                formatGraphFloat(entry.base) to afield.graphquery.VALUE_COLOUR,
                describeState(entry) to afield.graphquery.MUTED_COLOUR
            )
        }
    )

    private fun describeState(entry: ParamPath): String = buildString {
        if (!entry.isNativeParam) {
            append("scalar")
            if (entry.modulation != null) append(" + mod")
            if (entry.supplier != null) append(" + supplier")
        } else {
            append(if (entry.modulation != null) "mod" else "base")
            if (entry.supplier != null) append(" + supplier")
        }
        if (entry.aliases.size > 1) append(" (${entry.aliases.size} aliases)")
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

    /** Re-read the live field so a cached state does not serve a stale base. */
    fun resync() {
        val live = (ref.get() as? Number)?.toFloat() ?: return
        synchronized(lock) {
            if (!isDynamicLocked()) _base = live
        }
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

/**
 * Per-slot scalar state, keyed by (owner identity, slot name) so that repeated queries
 * against the same object drive the same state rather than fighting each other.
 *
 * Held weakly on the owner: a graph that goes out of scope must not be pinned in memory by
 * having once been queried.
 */
private object ScalarPathRegistry {
    private val nullOwner = Any()
    private val byOwner = java.util.WeakHashMap<Any, MutableMap<String, ScalarPathState>>()

    fun stateFor(ref: GraphPathRef): ScalarPathState? {
        val sample = ref.get() as? Number ?: return null
        val owner = ref.owner ?: nullOwner
        val segment = ref.pathSegments.lastOrNull().orEmpty()
        val state = synchronized(this) {
            val bySegment = byOwner.getOrPut(owner) { linkedMapOf() }
            bySegment.getOrPut(segment) { ScalarPathState(ref, sample) }
        }
        // A cached state snapshotted `base` when it was created; pick up any external
        // write since, so `bases()` reports what the field actually holds.
        state.resync()
        return state
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

    /** How many targets are currently being driven — used by tests and diagnostics. */
    val activeCount: Int get() = synchronized(lock) { activeTargets.size }

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
        contexts.computeIfAbsent(name) {
            GraphBindingContext(name = it, sampleRate = sampleRate, bufferSize = 1)
        }
}

// ═══════════════════════════════════════════════════════════════════════════
//  Entry points
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Narrow a generic [PathSet] to the slots that can be driven as parameters — native
 * [Param]s and writable numeric fields.
 */
fun PathSet.params(): ParamSet = ParamSet.from(root, refs)

/** Query this object graph for drivable parameters. */
fun Any?.params(
    pattern: String = "**",
    options: GraphWalkOptions = GraphWalkOptions()
): ParamSet = graphPaths(pattern, options).params()

/** Drive every attached supplier/modulation once, for hosts that are not the runloop. */
fun tickGraphBindings(
    contextName: String,
    deltaTime: Float,
    sampleRate: Float = if (deltaTime > 0f) 1f / deltaTime else 60f
) {
    GraphBindingRuntime.tick(contextName, deltaTime, sampleRate)
}

/** Sugar: `patch matches "**\/cutoff"`. */
infix fun Any?.matches(pattern: String): ParamSet = params(pattern)
