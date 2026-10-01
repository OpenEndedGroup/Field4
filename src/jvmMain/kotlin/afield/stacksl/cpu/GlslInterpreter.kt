package afield.stacksl.cpu

/**
 * The evaluator for parsed GLSL shaders, tree-walking and single-threaded per invocation.
 *
 * It holds no state between invocations except the globals the caller supplies, so a raster can
 * be rendered by running one [GlslInvocation] per row across as many threads as there are cores
 * — see [StackSLCpu].
 *
 * #### Three design choices
 *
 * **Control flow as a return value, not an exception.** Each statement reports what it did to
 * control flow — normal, break, continue, return or discard — as an ordinary return value, and
 * the caller unwinds. The tree-walking pattern normally uses a control-flow exception, but the
 * volumes here are large: `_fbm` returns from inside a five-iteration loop, and a 512×512 render
 * evaluates that `return` 1.3 million times per program, which is too many to assume a
 * stack-trace-bearing exception is free.
 *
 * **One flat frame per function call, with block scopes as marks into it.** Entering a block
 * records the current top of the frame and leaving truncates back to it, giving correct GLSL
 * shadowing with no per-block allocation. Lookup is a backwards linear scan, which for the
 * emitted subset (never more than about a dozen live names) beats a hash map.
 *
 * **Generic in its scalar, with exactly one copy of the control flow.** `GlslMachine` holds the
 * statements, the branches, the loops, the frames and the lvalue writes; what a *value* is comes
 * from a [GlslScalars]. `GlslScalars<GlslValue>` is the binary32 reference path and
 * `GlslScalars<AdValue>` makes the same shader differentiable, so `d(pixel)/d(literal)` flows
 * through this file rather than through a second evaluator — which would agree with itself while
 * both copies drifted from the spec. [GlslInvocation] is the `Float` face of it, and is what
 * every pre-existing caller uses, unchanged.
 */

/** What a statement did to control flow. */
private enum class Flow { NORMAL, BREAK, CONTINUE, RETURN, DISCARD }

/**
 * One function-call frame: a flat, append-only stack of `(name, value)` with block scopes as
 * marks into it.
 *
 * Values live in an `Array<Any?>` because Kotlin cannot allocate an `Array<V>`; the read is an
 * unchecked cast whose safety is entirely local, since [declare] and [setAt] are the only writers
 * and both take a `V`.
 */
private class Frame<V> {
    private var names = arrayOfNulls<String>(32)
    private var values = arrayOfNulls<Any?>(32)
    var size = 0
        private set

    fun declare(name: String, value: V) {
        if (size == names.size) {
            names = names.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        names[size] = name
        values[size] = value
        size++
    }

    /** Innermost declaration of [name], or -1. Backwards, so an inner block shadows an outer one. */
    fun indexOf(name: String): Int {
        for (i in size - 1 downTo 0) if (names[i] == name) return i
        return -1
    }

    @Suppress("UNCHECKED_CAST")
    fun valueAt(index: Int): V = values[index] as V

    fun setAt(index: Int, value: V) { values[index] = value }

    /** The live values, for an undecided branch to run both arms from one state. */
    fun snapshot(): Array<Any?> = values.copyOf(size)

    /** Put back a [snapshot] taken at this same size. */
    fun restore(saved: Array<Any?>) {
        if (saved.size != size) throw GlslUnsupported("an undecided branch declared a variable outside a block")
        saved.copyInto(values, 0, 0, size)
    }

    fun truncateTo(mark: Int) {
        for (i in mark until size) { names[i] = null; values[i] = null }
        size = mark
    }
}

/**
 * Run one invocation of a [GlslShader] over the scalar algebra [s].
 *
 * [globals] is the mutable global state for this invocation: the `in` variables the caller
 * supplies (`fragUV`), the uniforms and push-constant members (`pc.params`), and the `out`
 * variables the shader writes (`outColor`), which is how the caller gets the answer back out.
 */
internal class GlslMachine<V> internal constructor(
    private val shader: GlslShader,
    private val globals: HashMap<String, V>,
    private val samplers: GlslSamplers,
    private val blockInstances: Set<String>,
    private val s: GlslScalars<V>
) {
    /** True when this invocation executed `discard`. The caller decides what that means. */
    var discarded: Boolean = false
        private set

    /**
     * True when an undecided branch had an arm that executed `discard`: some inputs in the region
     * may discard, and the values read afterwards are those of the inputs that do not.
     */
    var maybeDiscarded: Boolean = false
        private set

    private var frame = Frame<V>()
    private var returnValue: V? = null
    private var depth = 0

    /** Read a global — `outColor` after running `main`, most usefully. */
    fun global(name: String): V? = globals[name]

    /** Set a global before running, or between runs of the same invocation. */
    fun setGlobal(name: String, value: V) { globals[name] = value }

    /** Call a function by name with already-evaluated arguments. */
    fun call(name: String, args: List<V>): V {
        val fn = shader.functions[name] ?: throw GlslEvalError("no function '$name' in this shader")
        if (fn.params.size != args.size) {
            throw GlslEvalError("$name expects ${fn.params.size} argument(s), got ${args.size}")
        }
        if (++depth > MAX_CALL_DEPTH) throw GlslEvalError("call depth exceeded at '$name' — recursive shader?")
        val savedFrame = frame
        val savedReturn = returnValue
        frame = Frame()
        returnValue = null
        try {
            fn.params.forEachIndexed { i, (type, pname) -> frame.declare(pname, s.coerce(args[i], type)) }
            val flow = exec(fn.body)
            if (flow == Flow.DISCARD) discarded = true
            return returnValue ?: s.zeros(fn.returnType)
        } finally {
            frame = savedFrame
            returnValue = savedReturn
            depth--
        }
    }

    /** Run `main()`. Returns whether it completed without `discard`. */
    fun runMain(): Boolean {
        discarded = false
        maybeDiscarded = false
        call("main", emptyList())
        return !discarded
    }

    // -- statements ----------------------------------------------------------------------------

    private fun exec(stmt: GStmt): Flow = when (stmt) {
        is GBlock -> {
            val mark = frame.size
            var flow = Flow.NORMAL
            for (inner in stmt.statements) {
                flow = exec(inner)
                if (flow != Flow.NORMAL) break
            }
            frame.truncateTo(mark)
            flow
        }

        is GDeclare -> {
            stmt.declarators.forEach { (name, init) ->
                val v = init?.let { s.coerce(eval(it), stmt.type) } ?: s.zeros(stmt.type)
                frame.declare(name, v)
            }
            Flow.NORMAL
        }

        is GExprStmt -> { eval(stmt.expr); Flow.NORMAL }

        is GIf -> when (s.truth(eval(stmt.cond))) {
            GlslTruth.TRUE -> exec(stmt.then)
            GlslTruth.FALSE -> stmt.otherwise?.let { exec(it) } ?: Flow.NORMAL
            GlslTruth.UNKNOWN -> fork({ exec(stmt.then) }, { stmt.otherwise?.let { exec(it) } ?: Flow.NORMAL })
        }

        is GFor -> {
            val mark = frame.size
            stmt.init?.let { exec(it) }
            var flow = Flow.NORMAL
            var iterations = 0
            while (stmt.cond == null || decided(eval(stmt.cond), "a for-loop condition")) {
                if (++iterations > MAX_LOOP_ITERATIONS) {
                    throw GlslEvalError("loop exceeded $MAX_LOOP_ITERATIONS iterations — non-terminating shader?")
                }
                val inner = exec(stmt.body)
                if (inner == Flow.BREAK) break
                if (inner == Flow.RETURN || inner == Flow.DISCARD) { flow = inner; break }
                stmt.step?.let { eval(it) }
            }
            frame.truncateTo(mark)
            flow
        }

        is GWhile -> {
            var flow = Flow.NORMAL
            var iterations = 0
            var first = true
            while (true) {
                if (!(first && stmt.isDoWhile) && !decided(eval(stmt.cond), "a while-loop condition")) break
                first = false
                if (++iterations > MAX_LOOP_ITERATIONS) {
                    throw GlslEvalError("loop exceeded $MAX_LOOP_ITERATIONS iterations — non-terminating shader?")
                }
                val inner = exec(stmt.body)
                if (inner == Flow.BREAK) break
                if (inner == Flow.RETURN || inner == Flow.DISCARD) { flow = inner; break }
            }
            flow
        }

        is GReturn -> {
            returnValue = stmt.value?.let { eval(it) }
            Flow.RETURN
        }

        GBreak -> Flow.BREAK
        GContinue -> Flow.CONTINUE
        GDiscard -> Flow.DISCARD
        GNoop -> Flow.NORMAL
    }

    // -- expressions ---------------------------------------------------------------------------

    internal fun eval(e: GExpr): V = when (e) {
        is GLiteral -> s.lift(e.value)

        is GVarRef -> {
            val i = frame.indexOf(e.name)
            if (i >= 0) frame.valueAt(i)
            else globals[e.name] ?: throw GlslEvalError("undefined variable '${e.name}'")
        }

        is GField -> {
            // `pc.params` — an interface-block member — is stored flattened, so it is resolved
            // before the swizzle path gets a chance to reject `params` as a component set.
            val blockName = blockMemberName(e)
            if (blockName != null) globals[blockName] ?: throw GlslEvalError("undefined block member '$blockName'")
            else s.swizzle(eval(e.target), e.field)
        }

        is GIndex -> {
            val target = eval(e.target)
            val type = s.typeOf(target)
            val i = s.asInt(eval(e.index))
            if (type.isMatrix) {
                if (i !in 0 until type.cols) throw GlslEvalError("matrix column $i out of range")
                s.column(target, i)
            } else {
                if (i !in 0 until type.components) throw GlslEvalError("component $i out of range for $type")
                s.component(target, i)
            }
        }

        is GConstruct -> s.construct(e.type, e.args.map { eval(it) })

        is GCall -> {
            val args = e.args.map { eval(it) }
            shader.functions[e.name]?.let { call(e.name, args) }
                ?: s.callBuiltin(e.name, args, samplers)
                ?: throw GlslUnsupported(
                    "unknown GLSL function '${e.name}(${args.joinToString { s.typeOf(it).toString() }})'"
                )
        }

        is GUnary -> when (e.op) {
            "-" -> s.negate(eval(e.operand))
            "+" -> eval(e.operand)
            "!" -> s.boolOfTruth(not(s.truth(eval(e.operand))))
            else -> throw GlslUnsupported("unary '${e.op}' is not in the StackSL subset")
        }

        is GBinary -> binary(e.op, e)

        is GTernary -> when (s.truth(eval(e.cond))) {
            GlslTruth.TRUE -> eval(e.yes)
            GlslTruth.FALSE -> eval(e.no)
            GlslTruth.UNKNOWN -> forkValue({ eval(e.yes) }, { eval(e.no) })
        }

        is GAssign -> {
            val rhs = eval(e.value)
            val next = if (e.op == "=") rhs else {
                val current = eval(e.target)
                when (val op = e.op.dropLast(1)) {
                    "+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>" -> s.binary(op, current, rhs)
                    else -> throw GlslUnsupported("assignment '${e.op}'")
                }
            }
            assign(e.target, next)
            next
        }

        is GPostfix -> {
            val current = eval(e.target)
            val one = s.ones(s.typeOf(current))
            val next = if (e.op == "++") s.binary("+", current, one) else s.binary("-", current, one)
            assign(e.target, next)
            current
        }
    }

    private fun binary(op: String, e: GBinary): V {
        // `&&` and `||` short-circuit in GLSL, so the right operand must not be evaluated.
        // Kleene's three-valued logic, which is GLSL's two-valued logic whenever nothing is UNKNOWN.
        if (op == "&&") {
            val l = s.truth(eval(e.left))
            if (l == GlslTruth.FALSE) return s.boolOfTruth(GlslTruth.FALSE)
            val r = s.truth(eval(e.right))
            return s.boolOfTruth(if (r == GlslTruth.FALSE) GlslTruth.FALSE else if (l == GlslTruth.TRUE && r == GlslTruth.TRUE) GlslTruth.TRUE else GlslTruth.UNKNOWN)
        }
        if (op == "||") {
            val l = s.truth(eval(e.left))
            if (l == GlslTruth.TRUE) return s.boolOfTruth(GlslTruth.TRUE)
            val r = s.truth(eval(e.right))
            return s.boolOfTruth(if (r == GlslTruth.TRUE) GlslTruth.TRUE else if (l == GlslTruth.FALSE && r == GlslTruth.FALSE) GlslTruth.FALSE else GlslTruth.UNKNOWN)
        }
        val a = eval(e.left)
        val b = eval(e.right)
        return when (op) {
            "==" -> s.boolOfTruth(s.equalTruth(a, b))
            "!=" -> s.boolOfTruth(not(s.equalTruth(a, b)))
            else -> s.binary(op, a, b)
        }
    }

    private fun not(t: GlslTruth): GlslTruth = when (t) {
        GlslTruth.TRUE -> GlslTruth.FALSE
        GlslTruth.FALSE -> GlslTruth.TRUE
        GlslTruth.UNKNOWN -> GlslTruth.UNKNOWN
    }

    private fun decided(cond: V, what: String): Boolean = when (s.truth(cond)) {
        GlslTruth.TRUE -> true
        GlslTruth.FALSE -> false
        GlslTruth.UNKNOWN -> throw GlslUnsupported("$what is undecided over this region; an interval run cannot join loop iterations")
    }

    // -- undecided branches ----------------------------------------------------------------------

    private inner class Saved(val frameValues: Array<Any?>, val globals: HashMap<String, V>, val returnValue: V?)

    private fun save() = Saved(frame.snapshot(), HashMap(globals), returnValue)

    private fun load(saved: Saved) {
        frame.restore(saved.frameValues)
        globals.clear(); globals.putAll(saved.globals)
        returnValue = saved.returnValue
    }

    /** Join the state [other] left with the current one, slot by slot. */
    private fun mergeWith(other: Saved) {
        val mine = frame.snapshot()
        if (mine.size != other.frameValues.size) throw GlslUnsupported("an undecided branch changed the frame's shape")
        @Suppress("UNCHECKED_CAST")
        for (i in mine.indices) {
            val a = other.frameValues[i] as V
            val b = mine[i] as V
            if (a !== b) frame.setAt(i, s.join(a, b))
        }
        for ((key, a) in other.globals) {
            val b = globals[key] ?: continue
            if (a !== b) globals[key] = s.join(a, b)
        }
        val ra = other.returnValue
        val rb = returnValue
        returnValue = if (ra == null || rb == null) ra ?: rb else if (ra === rb) ra else s.join(ra, rb)
    }

    /**
     * Run both arms of an undecided `if` from the same state and leave the join of what they did.
     *
     * An arm that executes `discard` contributes nothing — the inputs that take it produce no pixel —
     * and sets [maybeDiscarded]. Arms that leave by different routes cannot be joined into one
     * continuation, so that is refused rather than approximated.
     */
    private fun fork(thenArm: () -> Flow, elseArm: () -> Flow): Flow {
        val start = save()
        val fa = thenArm()
        val afterThen = save()
        load(start)
        val fb = elseArm()
        return when {
            fa == Flow.DISCARD && fb == Flow.DISCARD -> Flow.DISCARD
            fa == Flow.DISCARD -> { maybeDiscarded = true; fb }
            fb == Flow.DISCARD -> { maybeDiscarded = true; load(afterThen); fa }
            fa != fb -> throw GlslUnsupported(
                "an undecided branch leaves by two routes ($fa and $fb), which an interval run cannot join"
            )
            else -> { mergeWith(afterThen); fa }
        }
    }

    /** [fork] for a `?:`: both values, from the same state, joined. */
    private fun forkValue(yes: () -> V, no: () -> V): V {
        val start = save()
        val a = yes()
        val afterYes = save()
        load(start)
        val b = no()
        mergeWith(afterYes)
        return s.join(a, b)
    }

    /** Write [value] through an lvalue: a variable, a swizzle of one, or a component of one. */
    private fun assign(target: GExpr, value: V) {
        when (target) {
            is GVarRef -> {
                val i = frame.indexOf(target.name)
                if (i >= 0) {
                    frame.setAt(i, s.coerce(value, s.typeOf(frame.valueAt(i))))
                } else {
                    val existing = globals[target.name]
                        ?: throw GlslEvalError("assignment to undefined variable '${target.name}'")
                    globals[target.name] = s.coerce(value, s.typeOf(existing))
                }
            }

            is GField -> {
                val blockName = blockMemberName(target)
                if (blockName != null) {
                    globals[blockName] = value
                    return
                }
                val current = eval(target.target)
                val idx = swizzleIndices(target.field, s.typeOf(current).components)
                val written = s.typeOf(value).components
                if (idx.size != written && written != 1) {
                    throw GlslEvalError("cannot write ${s.typeOf(value)} into a ${idx.size}-component swizzle")
                }
                assign(target.target, s.withComponents(current, idx, value))
            }

            is GIndex -> {
                val current = eval(target.target)
                val i = s.asInt(eval(target.index))
                val type = s.typeOf(current)
                if (type.isMatrix) {
                    val rows = type.rows
                    val idx = IntArray(rows) { i * rows + it }
                    assign(target.target, s.withComponents(current, idx, value))
                } else {
                    assign(target.target, s.withComponent(current, i, value))
                }
            }

            else -> throw GlslEvalError("not an lvalue")
        }
    }

    private fun blockMemberName(e: GField): String? {
        val v = e.target
        if (v is GVarRef && v.name in blockInstances) return "${v.name}.${e.field}"
        return null
    }

    private companion object {
        /** `_fbm` nests two deep; anything past this is a mistake, not a program. */
        const val MAX_CALL_DEPTH = 64

        /**
         * The emitted `for` loops are all octave loops bounded by `MAX_OCTAVES` (12) or by a
         * literal 5, so this bound is three orders of magnitude of headroom and still terminates
         * a runaway in milliseconds rather than hanging a render of 262144 pixels.
         */
        const val MAX_LOOP_ITERATIONS = 100_000
    }
}

/**
 * One invocation of a shader over binary32 floats — the CPU backend's reference path.
 *
 * A thin, non-generic face on the generic machine: the control flow, the frames and the lvalue
 * writes are shared with every other scalar, and what makes *this* one the reference is
 * [GlslFloatScalars], whose every operation is the binary32 reading of the shader. See
 * [GlslValue]'s file KDoc for why that must not be widened, and
 * `StackSLCpuFloatPathManifestTest` for where it is measured.
 */
class GlslInvocation internal constructor(internal val machine: GlslMachine<GlslValue>) {

    /** True when this invocation executed `discard`. The caller decides what that means. */
    val discarded: Boolean get() = machine.discarded

    /** Read a global — `outColor` after running `main`, most usefully. */
    fun global(name: String): GlslValue? = machine.global(name)

    /** Set a global before running, or between runs of the same invocation. */
    fun setGlobal(name: String, value: GlslValue) { machine.setGlobal(name, value) }

    /** Call a function by name with already-evaluated arguments. */
    fun call(name: String, args: List<GlslValue>): GlslValue = machine.call(name, args)

    /** Run `main()`. Returns whether it completed without `discard`. */
    fun runMain(): Boolean = machine.runMain()

    internal fun eval(e: GExpr): GlslValue = machine.eval(e)
}

/**
 * Prepare a shader for repeated invocation over binary32 floats.
 *
 * Globals are seeded from the shader's own declarations (so a `const` initialiser or an
 * `out vec4 outColor` starts out defined) and then overridden by [initialGlobals]. Samplers are
 * bound to their declaration index, which is what a [GlslSamplers] receives as its first
 * argument, so a shader with more than one sampler can still tell them apart.
 */
fun GlslShader.newInvocation(
    initialGlobals: Map<String, GlslValue> = emptyMap(),
    samplers: GlslSamplers = GlslSamplers.REFUSE
): GlslInvocation = GlslInvocation(newMachine(GlslFloatScalars, initialGlobals, samplers))

/**
 * [newInvocation], generic in the scalar algebra.
 *
 * The seeding *order* is the reason this is one function rather than two per scalar: a global
 * initialiser may reference another global, so initialisers run only once every name exists, and
 * [initialGlobals] is applied last so that a caller's `fragUV` — or a fitted wire value — wins
 * over a declaration's default.
 */
internal fun <V> GlslShader.newMachine(
    scalars: GlslScalars<V>,
    initialGlobals: Map<String, V> = emptyMap(),
    samplers: GlslSamplers = GlslSamplers.REFUSE
): GlslMachine<V> {
    val g = HashMap<String, V>()
    globals.forEach { decl -> g[decl.name] = scalars.zeros(decl.type) }
    blocks.forEach { block ->
        block.members.forEach { (type, name) ->
            val key = if (block.instance.isEmpty()) name else "${block.instance}.$name"
            g[key] = scalars.zeros(type)
        }
    }
    samplersIndexed().forEach { (index, name) -> g[name] = scalars.lift(GlslValue.int(index)) }
    val machine = GlslMachine(
        this, g, samplers, blocks.map { it.instance }.filter { it.isNotEmpty() }.toSet(), scalars
    )
    // Global initialisers may reference other globals, so they are evaluated after every name
    // exists, in declaration order.
    globals.forEach { decl -> decl.initializer?.let { g[decl.name] = machine.eval(it) } }
    initialGlobals.forEach { (k, v) -> g[k] = v }
    return machine
}

private fun GlslShader.samplersIndexed(): List<Pair<Int, String>> = samplers.mapIndexed { i, n -> i to n }
