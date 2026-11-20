package afield.lang.stacksl.cpu

import java.util.stream.IntStream

/**
 * An online partial evaluator for the GLSL subset StackSL emits: the first Futamura projection,
 * run on the CPU backend's own interpreter.
 *
 * Give it a shader whose *program* is fixed (the static input) and name the globals that vary per
 * pixel (the dynamic input, normally just `fragUV`). It runs the shader **once**, through the same
 * `GlslMachine` every other CPU path uses, over a staged algebra in which a value is either
 * *static* (a known [GlslValue], folded right away by [GlslFloatScalars], so with the same bits) or
 * *dynamic* (a node of a residual program). What is left when `main()` returns is a straight-line
 * [GlslResidual]: no calls, no loops, no variables, only the arithmetic that depends on the pixel.
 *
 * Nothing about GLSL is restated here, which is the point of doing it this way. The machine does
 * the unfolding: a loop whose condition is static simply runs (the `fbm` octave loop unrolls), a
 * call to a helper simply executes (it inlines), a branch on a static condition takes one arm (the
 * other disappears). Only a branch on a *dynamic* condition is new work, and the machine already
 * had it: the interval algebra makes it run both arms from one state and join them, and the staged
 * algebra's join, [GlslScalars.select], residualizes as `cond ? a : b`.
 *
 * #### What it refuses
 *
 * A loop whose condition is dynamic, an array index that is dynamic, a dynamic branch whose arms
 * leave by different routes (one `return`s, the other falls through), and a `discard` reachable
 * under a dynamic condition. Each throws [GlslUnsupported] with the machine's own message; the
 * StackSL gallery reaches none of them (`GlslPartialEvaluatorTest` runs all of it).
 *
 * #### Exactness
 *
 * Every residual node, when run, calls the very [GlslFloatScalars] / [GlslBuiltins] function the
 * interpreter would have called on the same operands in the same order, and every folded constant
 * came out of those functions too. So a residual is **bit-identical** to the interpreter, not
 * merely close, and that is what the tests assert. The only algebraic rewrite is `x * 1.0 = x`
 * when the types agree, which is exact in binary32.
 */
object GlslPartialEvaluator {

    /**
     * Specialize [source] with respect to everything except [dynamicGlobals].
     *
     * @param staticGlobals globals and push-constant members (`pc.params`) fixed for the residual
     * @param outputs the `out` variables to residualize; the harness templates write `outColor`
     */
    fun specialize(
        source: String,
        dynamicGlobals: Map<String, GlslType> = mapOf("fragUV" to GlslType.VEC2),
        staticGlobals: Map<String, GlslValue> = emptyMap(),
        outputs: List<String> = listOf("outColor")
    ): GlslResidual {
        val shader = parseGlslShader(source)
        require("main" in shader.functionNames) { "shader has no main()" }
        val builder = ResidualBuilder()
        val staged = StagedScalars(builder)
        val initial = HashMap<String, Staged>()
        staticGlobals.forEach { (k, v) -> initial[k] = Staged.Static(v) }
        dynamicGlobals.forEach { (k, t) -> initial[k] = builder.dynamic(RNode(RKind.INPUT, t, name = k)) }
        val machine = shader.newMachine(staged, initial, GlslSamplers.REFUSE)
        machine.runMain()
        if (machine.discarded) throw GlslUnsupported("the shader discards unconditionally; there is no residual to run")
        if (machine.maybeDiscarded) throw GlslUnsupported("a discard under a dynamic condition is not residualized")
        val roots = outputs.associateWith { name ->
            machine.global(name) ?: throw GlslEvalError("shader never wrote '$name'")
        }
        return builder.finish(roots, dynamicGlobals, shader.samplers)
    }

    /**
     * Specialize a StackSL [program] in a fragment [template], exactly as `StackSLCpu.renderProgram`
     * would render it: compile, substitute into `{{ stack.auto.program }}`, then [specialize].
     */
    fun specializeStackSL(
        program: String,
        template: String = StackSLCpu.stillTemplate(),
        pushParams: FloatArray? = null
    ): GlslResidual {
        val expanded = StackSLCpu.expandStackAuto(template, program)
        val statics = buildMap {
            pushParams?.let { put("pc.params", GlslValue(GlslType.VEC4, it.copyOf())) }
        }
        return specialize(expanded, staticGlobals = statics)
    }
}

/** A staged value: known now, or a node of the residual program. */
internal sealed class Staged {
    abstract val type: GlslType

    class Static(val value: GlslValue) : Staged() {
        override val type: GlslType get() = value.type
    }

    class Dynamic(val node: Int, override val type: GlslType) : Staged()
}

/** The kinds of residual operation; each maps onto exactly one [GlslFloatScalars] entry point. */
internal enum class RKind {
    INPUT, CONST, BINARY, NEGATE, CONSTRUCT, SWIZZLE, COMPONENT, COLUMN,
    WITH_COMPONENTS, WITH_COMPONENT, COERCE, BUILTIN, SELECT, LOGIC
}

/**
 * One residual node. A data class so that structurally equal nodes are one node: the builder
 * hash-conses, which is common-subexpression elimination for free (the unrolled `fbm` reads the
 * same `uv * 2.0` many times).
 */
internal data class RNode(
    val kind: RKind,
    val type: GlslType,
    val args: List<Int> = emptyList(),
    val name: String = "",
    val ints: List<Int> = emptyList(),
    /** CONST only; boxed `Float`s compare by bits, so `-0.0` and `0.0` stay distinct. */
    val constant: List<Float>? = null
)

internal class ResidualBuilder {
    val nodes = ArrayList<RNode>()
    private val index = HashMap<RNode, Int>()
    var emitted = 0
        private set

    fun dynamic(node: RNode): Staged.Dynamic {
        emitted++
        val id = index.getOrPut(node) { nodes += node; nodes.size - 1 }
        return Staged.Dynamic(id, node.type)
    }

    fun ref(v: Staged): Int = when (v) {
        is Staged.Dynamic -> v.node
        is Staged.Static -> dynamic(RNode(RKind.CONST, v.value.type, constant = v.value.c.toList())).node
    }

    fun finish(roots: Map<String, Staged>, inputs: Map<String, GlslType>, samplers: List<String>): GlslResidual {
        // Dead-code elimination: keep only what an output reaches. Nodes are created after their
        // arguments, so creation order is already a topological order.
        val live = BooleanArray(nodes.size)
        val work = ArrayDeque<Int>()
        roots.values.filterIsInstance<Staged.Dynamic>().forEach { work += it.node }
        while (work.isNotEmpty()) {
            val n = work.removeLast()
            if (live[n]) continue
            live[n] = true
            nodes[n].args.forEach { work += it }
        }
        val renumber = IntArray(nodes.size) { -1 }
        val kept = ArrayList<RNode>()
        for (i in nodes.indices) if (live[i]) {
            val n = nodes[i]
            renumber[i] = kept.size
            kept += n.copy(args = n.args.map { renumber[it] })
        }
        val outs = roots.mapValues { (_, v) ->
            when (v) {
                is Staged.Static -> GlslResidual.Output.Constant(v.value)
                is Staged.Dynamic -> GlslResidual.Output.Node(renumber[v.node])
            }
        }
        return GlslResidual(kept, outs, inputs, samplers, emitted)
    }
}

/**
 * The staged algebra. Every method folds when its operands are all static, by calling
 * [GlslFloatScalars]; otherwise it emits a node, whose result type is found by running the same
 * [GlslFloatScalars] method on zero placeholders of the operand types (so shape rules — broadcast,
 * matrix products, swizzle widths — are not restated either).
 */
internal class StagedScalars(private val b: ResidualBuilder) : GlslScalars<Staged> {
    private val f = GlslFloatScalars

    private fun st(v: GlslValue) = Staged.Static(v)
    private fun allStatic(vararg v: Staged) = v.all { it is Staged.Static }
    private fun sv(v: Staged) = (v as Staged.Static).value
    private fun placeholder(t: GlslType) = f.zeros(t)

    private fun typeBy(vararg types: GlslType, op: (List<GlslValue>) -> GlslValue): GlslType = try {
        op(types.map { placeholder(it) }).type
    } catch (e: RuntimeException) {
        op(types.map { f.ones(it) }).type
    }

    private fun emit(kind: RKind, type: GlslType, args: List<Staged>, name: String = "", ints: List<Int> = emptyList()) =
        b.dynamic(RNode(kind, type, args.map { b.ref(it) }, name, ints))

    override fun lift(v: GlslValue): Staged = st(v)
    override fun typeOf(v: Staged): GlslType = v.type
    override fun zeros(type: GlslType): Staged = st(f.zeros(type))
    override fun ones(type: GlslType): Staged = st(f.ones(type))
    override fun boolOf(b: Boolean): Staged = st(f.boolOf(b))

    override fun asBool(v: Staged): Boolean =
        if (v is Staged.Static) f.asBool(v.value)
        else throw GlslUnsupported("a dynamic value reached a place that needs a static boolean")

    override fun truth(v: Staged): GlslTruth = if (v is Staged.Static) f.truth(v.value) else GlslTruth.UNKNOWN

    override fun boolOfTruth(t: GlslTruth): Staged =
        if (t == GlslTruth.UNKNOWN) throw GlslUnsupported("an undecided boolean without its operands") else f.boolOfTruth(t).let(::st)

    override fun equalTruth(a: Staged, b: Staged): GlslTruth =
        if (allStatic(a, b)) f.equalTruth(sv(a), sv(b)) else GlslTruth.UNKNOWN

    override fun logic(op: String, a: Staged, b: Staged, verdict: GlslTruth): Staged {
        if (verdict != GlslTruth.UNKNOWN) return st(f.boolOfTruth(verdict))
        // `true && x` is `x`, `false || x` is `x`: exact, and keeps residual conditions short.
        if (op == "&&" || op == "||") {
            val neutral = if (op == "&&") GlslTruth.TRUE else GlslTruth.FALSE
            if (truth(a) == neutral) return b
            if (truth(b) == neutral) return a
        }
        return if (op == "!") emit(RKind.LOGIC, GlslType.BOOL, listOf(a), op) else emit(RKind.LOGIC, GlslType.BOOL, listOf(a, b), op)
    }

    override fun select(cond: Staged, a: Staged, b: Staged): Staged {
        if (a is Staged.Static && b is Staged.Static && a.value.type == b.value.type && a.value.c.contentEquals(b.value.c)) return a
        if (a is Staged.Dynamic && b is Staged.Dynamic && a.node == b.node) return a
        return emit(RKind.SELECT, a.type, listOf(cond, a, b))
    }

    override fun asInt(v: Staged): Int =
        if (v is Staged.Static) f.asInt(v.value) else throw GlslUnsupported("a dynamic array index is not residualized")

    override fun negate(v: Staged): Staged = if (v is Staged.Static) st(f.negate(v.value)) else emit(RKind.NEGATE, v.type, listOf(v))

    override fun binary(op: String, a: Staged, b: Staged): Staged {
        if (allStatic(a, b)) return st(f.binary(op, sv(a), sv(b)))
        val type = typeBy(a.type, b.type) { f.binary(op, it[0], it[1]) }
        if (op == "*") {
            if (isFloatOnes(a) && b.type == type) return b
            if (isFloatOnes(b) && a.type == type) return a
        }
        return emit(RKind.BINARY, type, listOf(a, b), op)
    }

    private fun isFloatOnes(v: Staged) =
        v is Staged.Static && v.value.type.base == GlslBase.FLOAT && v.value.c.all { it == 1f }

    override fun equalComponents(a: Staged, b: Staged): Boolean =
        if (allStatic(a, b)) f.equalComponents(sv(a), sv(b)) else throw GlslUnsupported("a dynamic == reached a static context")

    override fun construct(type: GlslType, args: List<Staged>): Staged =
        if (args.all { it is Staged.Static }) st(f.construct(type, args.map(::sv)))
        else emit(RKind.CONSTRUCT, type, args)

    override fun swizzle(v: Staged, field: String): Staged =
        if (v is Staged.Static) st(f.swizzle(v.value, field))
        else emit(RKind.SWIZZLE, typeBy(v.type) { f.swizzle(it[0], field) }, listOf(v), field)

    override fun component(v: Staged, index: Int): Staged =
        if (v is Staged.Static) st(f.component(v.value, index))
        else emit(RKind.COMPONENT, GlslType.of(v.type.base, 1), listOf(v), ints = listOf(index))

    override fun column(v: Staged, index: Int): Staged =
        if (v is Staged.Static) st(f.column(v.value, index))
        else emit(RKind.COLUMN, GlslType.of(v.type.base, v.type.rows), listOf(v), ints = listOf(index))

    override fun withComponents(v: Staged, indices: IntArray, src: Staged): Staged =
        if (allStatic(v, src)) st(f.withComponents(sv(v), indices, sv(src)))
        else emit(RKind.WITH_COMPONENTS, v.type, listOf(v, src), ints = indices.toList())

    override fun withComponent(v: Staged, index: Int, src: Staged): Staged =
        if (allStatic(v, src)) st(f.withComponent(sv(v), index, sv(src)))
        else emit(RKind.WITH_COMPONENT, v.type, listOf(v, src), ints = listOf(index))

    override fun coerce(v: Staged, type: GlslType): Staged = when {
        v.type == type -> v
        v is Staged.Static -> st(f.coerce(v.value, type))
        else -> emit(RKind.COERCE, type, listOf(v))
    }

    override fun callBuiltin(name: String, args: List<Staged>, samplers: GlslSamplers): Staged? {
        if (args.all { it is Staged.Static }) return f.callBuiltin(name, args.map(::sv), samplers)?.let(::st)
        // Sampling needs a sampler at run time; probe the type with one that answers black.
        val type = typeBy(*args.map { it.type }.toTypedArray()) { f.callBuiltin(name, it, GlslSamplers { _, _ -> GlslValue(GlslType.VEC4, FloatArray(4)) })!! }
        return emit(RKind.BUILTIN, type, args, name)
    }
}

/**
 * What [GlslPartialEvaluator] leaves: a straight-line program over [inputs], in SSA form.
 *
 * It can be printed as a GLSL fragment shader ([toGlsl]) and run on the JVM ([compile]); both give
 * exactly the interpreter's bits.
 */
class GlslResidual internal constructor(
    internal val nodes: List<RNode>,
    internal val outputs: Map<String, Output>,
    val inputs: Map<String, GlslType>,
    private val samplerNames: List<String>,
    /** Nodes the specializer asked for before sharing and dead-code elimination. */
    val emittedBeforeSharing: Int
) {
    internal sealed class Output {
        class Constant(val value: GlslValue) : Output()
        class Node(val index: Int) : Output()
    }

    /** Residual operations, excluding inputs and constants: the per-pixel work that remains. */
    val operationCount: Int get() = nodes.count { it.kind != RKind.INPUT && it.kind != RKind.CONST }

    /** Residual operations by kind and operator, most frequent first — `* 41`, `fract 12`, … */
    fun operationHistogram(): List<Pair<String, Int>> = nodes
        .filter { it.kind != RKind.INPUT && it.kind != RKind.CONST }
        .groupingBy { opLabel(it) }.eachCount()
        .toList().sortedByDescending { it.second }

    private fun opLabel(n: RNode) = when (n.kind) {
        RKind.BINARY, RKind.LOGIC, RKind.BUILTIN -> n.name
        RKind.SWIZZLE -> ".swizzle"
        RKind.CONSTRUCT -> n.type.toString() + "()"
        else -> n.kind.name.lowercase()
    }

    // -- GLSL ------------------------------------------------------------------------------------

    /** The residual as a complete fragment shader: inputs as `in`, then one SSA temp per node. */
    fun toGlsl(): String = buildString {
        appendLine("#version 450")
        var loc = 0
        inputs.forEach { (n, t) -> appendLine("layout(location = ${loc++}) in $t $n;") }
        outputs.keys.forEachIndexed { i, n -> appendLine("layout(location = $i) out vec4 $n;") }
        samplerNames.forEachIndexed { i, n -> appendLine("layout(binding = $i) uniform sampler2D $n;") }
        appendLine("void main() {")
        for (i in nodes.indices) {
            val n = nodes[i]
            if (n.kind == RKind.INPUT || n.kind == RKind.CONST) continue
            when (n.kind) {
                RKind.WITH_COMPONENTS, RKind.WITH_COMPONENT -> {
                    appendLine("    ${n.type} t$i = ${ref(n.args[0])};")
                    val src = n.args[1]
                    val srcType = nodes[src].type
                    val type = n.type
                    if (type.isMatrix) {
                        n.ints.forEachIndexed { k, flat ->
                            val s = if (srcType.isScalar) ref(src) else "${ref(src)}[$k]"
                            appendLine("    t$i[${flat / type.rows}][${flat % type.rows}] = $s;")
                        }
                    } else {
                        val letters = n.ints.joinToString("") { "xyzw"[it].toString() }
                        val s = if (srcType.isScalar && n.ints.size > 1) "${GlslType.of(type.base, n.ints.size)}(${ref(src)})" else ref(src)
                        appendLine("    t$i.$letters = $s;")
                    }
                }
                else -> appendLine("    ${n.type} t$i = ${expr(n)};")
            }
        }
        outputs.forEach { (name, o) ->
            val v = when (o) {
                is Output.Constant -> literal(o.value)
                is Output.Node -> ref(o.index)
            }
            appendLine("    $name = $v;")
        }
        appendLine("}")
    }

    private fun ref(i: Int): String {
        val n = nodes[i]
        return when (n.kind) {
            RKind.INPUT -> n.name
            RKind.CONST -> literal(GlslValue(n.type, n.constant!!.toFloatArray()))
            else -> "t$i"
        }
    }

    private fun expr(n: RNode): String {
        val a = n.args.map { ref(it) }
        return when (n.kind) {
            RKind.BINARY -> "${a[0]} ${n.name} ${a[1]}"
            RKind.NEGATE -> "-${a[0]}"
            RKind.CONSTRUCT, RKind.COERCE -> "${n.type}(${a.joinToString(", ")})"
            RKind.SWIZZLE -> "(${a[0]}).${n.name}"
            RKind.COMPONENT, RKind.COLUMN -> "(${a[0]})[${n.ints[0]}]"
            RKind.BUILTIN -> "${n.name}(${a.joinToString(", ")})"
            RKind.SELECT -> "${a[0]} ? ${a[1]} : ${a[2]}"
            RKind.LOGIC -> if (n.name == "!") "!${a[0]}" else "${a[0]} ${n.name} ${a[1]}"
            else -> error("no expression for ${n.kind}")
        }
    }

    private fun literal(v: GlslValue): String {
        fun scalar(x: Float): String = when (v.type.base) {
            GlslBase.BOOL -> if (x != 0f) "true" else "false"
            GlslBase.INT -> Math.round(x).toString()
            GlslBase.SAMPLER -> samplerNames[Math.round(x)]
            GlslBase.FLOAT -> floatLiteral(x)
        }
        return if (v.type.isScalar) scalar(v.c[0]) else "${v.type}(" + v.c.joinToString(", ") { scalar(it) } + ")"
    }

    /**
     * A literal that the CPU parser (`text.toDouble().toFloat()`) and a GLSL compiler read back to
     * exactly [x]: the shortest `Float` form when it round-trips through `Double`, else the `Double`
     * form, which always does because widening a `Float` is exact.
     */
    private fun floatLiteral(x: Float): String = when {
        x.isNaN() -> "(0.0 / 0.0)"
        x == Float.POSITIVE_INFINITY -> "(1.0 / 0.0)"
        x == Float.NEGATIVE_INFINITY -> "(-1.0 / 0.0)"
        x == 0f && 1f / x < 0f -> "(-0.0)"
        else -> {
            val short = x.toString()
            val text = if (short.toDouble().toFloat().toRawBits() == x.toRawBits()) short else x.toDouble().toString()
            if (x < 0f) "($text)" else text
        }
    }

    // -- JVM ---------------------------------------------------------------------------------

    /**
     * The residual as a flat register program: one slot per node, one step per operation, each step
     * the [GlslFloatScalars] call the interpreter would have made. Not thread-safe; [newRunner] per
     * thread.
     */
    fun newRunner(samplers: GlslSamplers = GlslSamplers.REFUSE): Runner = Runner(samplers)

    inner class Runner internal constructor(private val samplers: GlslSamplers) {
        private val slots = arrayOfNulls<GlslValue>(nodes.size)
        private val inputSlots: List<Pair<String, Int>> =
            nodes.withIndex().filter { it.value.kind == RKind.INPUT }.map { it.value.name to it.index }
        private val steps: Array<(Array<GlslValue?>) -> GlslValue> = Array(nodes.size) { i -> step(nodes[i]) }
        private val firstWork = nodes.indexOfFirst { it.kind != RKind.INPUT && it.kind != RKind.CONST }.let { if (it < 0) nodes.size else it }

        init {
            nodes.forEachIndexed { i, n -> if (n.kind == RKind.CONST) slots[i] = GlslValue(n.type, n.constant!!.toFloatArray()) }
        }

        private fun step(n: RNode): (Array<GlslValue?>) -> GlslValue {
            val f = GlslFloatScalars
            val a = n.args.toIntArray()
            fastStep(n, a)?.let { return it }
            return when (n.kind) {
                RKind.INPUT, RKind.CONST -> { s -> s[0]!! } // never stepped
                RKind.BINARY -> { val op = n.name; { s -> f.binary(op, s[a[0]]!!, s[a[1]]!!) } }
                RKind.NEGATE -> { s -> f.negate(s[a[0]]!!) }
                RKind.CONSTRUCT -> { val t = n.type; { s -> f.construct(t, a.map { s[it]!! }) } }
                RKind.COERCE -> { val t = n.type; { s -> f.coerce(s[a[0]]!!, t) } }
                RKind.SWIZZLE -> { val field = n.name; { s -> f.swizzle(s[a[0]]!!, field) } }
                RKind.COMPONENT -> { val k = n.ints[0]; { s -> f.component(s[a[0]]!!, k) } }
                RKind.COLUMN -> { val k = n.ints[0]; { s -> f.column(s[a[0]]!!, k) } }
                RKind.WITH_COMPONENTS -> { val idx = n.ints.toIntArray(); { s -> f.withComponents(s[a[0]]!!, idx, s[a[1]]!!) } }
                RKind.WITH_COMPONENT -> { val k = n.ints[0]; { s -> f.withComponent(s[a[0]]!!, k, s[a[1]]!!) } }
                RKind.BUILTIN -> { val name = n.name; { s -> f.callBuiltin(name, a.map { s[it]!! }, samplers)!! } }
                RKind.SELECT -> { s -> if (f.asBool(s[a[0]]!!)) s[a[1]]!! else s[a[2]]!! }
                RKind.LOGIC -> when (n.name) {
                    "!" -> { s -> f.boolOf(!f.asBool(s[a[0]]!!)) }
                    "&&" -> { s -> f.boolOf(f.asBool(s[a[0]]!!) && f.asBool(s[a[1]]!!)) }
                    "||" -> { s -> f.boolOf(f.asBool(s[a[0]]!!) || f.asBool(s[a[1]]!!)) }
                    "==" -> { s -> f.boolOf(f.equalComponents(s[a[0]]!!, s[a[1]]!!)) }
                    "!=" -> { s -> f.boolOf(!f.equalComponents(s[a[0]]!!, s[a[1]]!!)) }
                    else -> throw GlslUnsupported("residual logic '${n.name}'")
                }
            }
        }

        /**
         * The commonest residual operations, float `+ - * /` (non-matrix) and float swizzles,
         * written into one value per node that is allocated once and overwritten every pixel.
         * Each performs the same single binary32 operation per component as `pairwise` and
         * `glslSwizzle`, so the bits are unchanged; the allocation and the operator-string
         * dispatch are what go.
         */
        private fun fastStep(n: RNode, a: IntArray): ((Array<GlslValue?>) -> GlslValue)? {
            if (n.type.base != GlslBase.FLOAT || n.type.isMatrix) return null
            val out = GlslValue(n.type, FloatArray(n.type.components))
            val o = out.c
            return when (n.kind) {
                RKind.BINARY -> {
                    val ta = nodes[a[0]].type
                    val tb = nodes[a[1]].type
                    if (ta.base != GlslBase.FLOAT || tb.base != GlslBase.FLOAT || ta.isMatrix || tb.isMatrix) return null
                    val sa = if (ta.components == 1) 0 else 1
                    val sb = if (tb.components == 1) 0 else 1
                    val ia = a[0]
                    val ib = a[1]
                    when (n.name) {
                        "+" -> { s -> val x = s[ia]!!.c; val y = s[ib]!!.c; for (k in o.indices) o[k] = x[k * sa] + y[k * sb]; out }
                        "-" -> { s -> val x = s[ia]!!.c; val y = s[ib]!!.c; for (k in o.indices) o[k] = x[k * sa] - y[k * sb]; out }
                        "*" -> { s -> val x = s[ia]!!.c; val y = s[ib]!!.c; for (k in o.indices) o[k] = x[k * sa] * y[k * sb]; out }
                        "/" -> { s -> val x = s[ia]!!.c; val y = s[ib]!!.c; for (k in o.indices) o[k] = x[k * sa] / y[k * sb]; out }
                        else -> null
                    }
                }
                RKind.SWIZZLE -> {
                    if (nodes[a[0]].type.base != GlslBase.FLOAT) return null
                    val idx = swizzleIndices(n.name, nodes[a[0]].type.components)
                    val ia = a[0]
                    ({ s: Array<GlslValue?> -> val x = s[ia]!!.c; for (k in o.indices) o[k] = x[idx[k]]; out })
                }
                else -> null
            }
        }

        /**
         * Run once with [inputs] bound by name; returns each output. The returned values are
         * overwritten by the next run, so copy one to keep it.
         */
        fun run(inputs: Map<String, GlslValue>): Map<String, GlslValue> {
            for ((name, slot) in inputSlots) slots[slot] = inputs[name] ?: throw GlslEvalError("no value for input '$name'")
            execute()
            return outputs.mapValues { (_, o) -> read(o) }
        }

        internal fun execute() {
            for (i in firstWork until steps.size) {
                val k = nodes[i].kind
                if (k != RKind.INPUT && k != RKind.CONST) slots[i] = steps[i](slots)
            }
        }

        internal fun setInput(slotName: String, v: GlslValue) {
            for ((name, slot) in inputSlots) if (name == slotName) slots[slot] = v
        }

        internal fun read(o: Output): GlslValue = when (o) {
            is Output.Constant -> o.value
            is Output.Node -> slots[o.index]!!
        }
    }

    /**
     * Render the residual over a raster exactly as `StackSLCpu.renderFragment` renders the shader
     * it came from: `fragUV` at pixel centres, rows in parallel, one [Runner] per row.
     */
    fun render(width: Int, height: Int, samplers: GlslSamplers = GlslSamplers.REFUSE, outName: String = "outColor", parallel: Boolean = true): CpuRaster {
        val out = outputs[outName] ?: throw GlslEvalError("residual has no output '$outName'")
        val rgba = FloatArray(width * height * 4)
        val rows = IntStream.range(0, height)
        (if (parallel) rows.parallel() else rows).forEach { y ->
            val runner = newRunner(samplers)
            for (x in 0 until width) {
                runner.setInput("fragUV", StackSLCpu.fragUvAt(x, y, width, height))
                runner.execute()
                val v = runner.read(out)
                val base = (y * width + x) * 4
                for (ch in 0 until minOf(4, v.c.size)) rgba[base + ch] = v.c[ch]
            }
        }
        return CpuRaster(width, height, rgba)
    }
}
