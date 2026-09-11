package afield.stacksl.cpu

/**
 * The evaluator for parsed GLSL shaders, tree-walking and single-threaded per invocation.
 *
 * It holds no state between invocations except the globals the caller supplies, so a raster can
 * be rendered by running one [GlslInvocation] per row across as many threads as there are cores
 * — see [StackSLCpu].
 *
 * #### Two design choices
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
 */

/** What a statement did to control flow. */
private enum class Flow { NORMAL, BREAK, CONTINUE, RETURN, DISCARD }

/**
 * One function-call frame: a flat, append-only stack of `(name, value)` with block scopes as
 * marks into it.
 */
private class Frame {
    private var names = arrayOfNulls<String>(32)
    private var values = arrayOfNulls<GlslValue>(32)
    var size = 0
        private set

    fun declare(name: String, value: GlslValue) {
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

    fun valueAt(index: Int): GlslValue = values[index]!!
    fun setAt(index: Int, value: GlslValue) { values[index] = value }

    fun truncateTo(mark: Int) {
        for (i in mark until size) { names[i] = null; values[i] = null }
        size = mark
    }
}

/**
 * Run one invocation of a [GlslShader].
 *
 * [globals] is the mutable global state for this invocation: the `in` variables the caller
 * supplies (`fragUV`), the uniforms and push-constant members (`pc.params`), and the `out`
 * variables the shader writes (`outColor`), which is how the caller gets the answer back out.
 */
class GlslInvocation internal constructor(
    private val shader: GlslShader,
    private val globals: HashMap<String, GlslValue>,
    private val samplers: GlslSamplers,
    private val blockInstances: Set<String>
) {
    /** True when this invocation executed `discard`. The caller decides what that means. */
    var discarded: Boolean = false
        private set

    private var frame = Frame()
    private var returnValue: GlslValue? = null
    private var depth = 0

    /** Read a global — `outColor` after running `main`, most usefully. */
    fun global(name: String): GlslValue? = globals[name]

    /** Set a global before running, or between runs of the same invocation. */
    fun setGlobal(name: String, value: GlslValue) { globals[name] = value }

    /** Call a function by name with already-evaluated arguments. */
    fun call(name: String, args: List<GlslValue>): GlslValue {
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
            fn.params.forEachIndexed { i, (type, pname) -> frame.declare(pname, coerce(args[i], type)) }
            val flow = exec(fn.body)
            if (flow == Flow.DISCARD) discarded = true
            return returnValue ?: GlslValue(fn.returnType, FloatArray(fn.returnType.components))
        } finally {
            frame = savedFrame
            returnValue = savedReturn
            depth--
        }
    }

    /** Run `main()`. Returns whether it completed without `discard`. */
    fun runMain(): Boolean {
        discarded = false
        call("main", emptyList())
        return !discarded
    }

    // -- statements ----------------------------------------------------------------------------

    private fun exec(stmt: GStmt): Flow = when (stmt) {
        is GBlock -> {
            val mark = frame.size
            var flow = Flow.NORMAL
            for (s in stmt.statements) {
                flow = exec(s)
                if (flow != Flow.NORMAL) break
            }
            frame.truncateTo(mark)
            flow
        }

        is GDeclare -> {
            stmt.declarators.forEach { (name, init) ->
                val v = init?.let { coerce(eval(it), stmt.type) }
                    ?: GlslValue(stmt.type, FloatArray(stmt.type.components))
                frame.declare(name, v)
            }
            Flow.NORMAL
        }

        is GExprStmt -> { eval(stmt.expr); Flow.NORMAL }

        is GIf -> if (eval(stmt.cond).asBool) exec(stmt.then) else stmt.otherwise?.let { exec(it) } ?: Flow.NORMAL

        is GFor -> {
            val mark = frame.size
            stmt.init?.let { exec(it) }
            var flow = Flow.NORMAL
            var iterations = 0
            while (stmt.cond == null || eval(stmt.cond).asBool) {
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
                if (!(first && stmt.isDoWhile) && !eval(stmt.cond).asBool) break
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

    internal fun eval(e: GExpr): GlslValue = when (e) {
        is GLiteral -> e.value

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
            else glslSwizzle(eval(e.target), e.field)
        }

        is GIndex -> {
            val target = eval(e.target)
            val i = eval(e.index).asInt
            if (target.type.isMatrix) {
                val rows = target.type.rows
                if (i !in 0 until target.type.cols) throw GlslEvalError("matrix column $i out of range")
                GlslValue(GlslType.of(target.type.base, rows), FloatArray(rows) { target.c[i * rows + it] })
            } else {
                if (i !in target.c.indices) throw GlslEvalError("component $i out of range for ${target.type}")
                GlslValue(GlslType.of(target.type.base, 1), floatArrayOf(target.c[i]))
            }
        }

        is GConstruct -> glslConstruct(e.type, e.args.map { eval(it) })

        is GCall -> {
            val args = e.args.map { eval(it) }
            shader.functions[e.name]?.let { call(e.name, args) }
                ?: GlslBuiltins.call(e.name, args, samplers)
                ?: throw GlslUnsupported("unknown GLSL function '${e.name}(${args.joinToString { it.type.toString() }})'")
        }

        is GUnary -> when (e.op) {
            "-" -> componentwise(eval(e.operand)) { -it }
            "+" -> eval(e.operand)
            "!" -> GlslValue.bool(!eval(e.operand).asBool)
            else -> throw GlslUnsupported("unary '${e.op}' is not in the StackSL subset")
        }

        is GBinary -> binary(e.op, e)

        is GTernary -> if (eval(e.cond).asBool) eval(e.yes) else eval(e.no)

        is GAssign -> {
            val rhs = eval(e.value)
            val next = if (e.op == "=") rhs else {
                val current = eval(e.target)
                when (e.op) {
                    "+=" -> pairwise(current, rhs) { a, b -> a + b }
                    "-=" -> pairwise(current, rhs) { a, b -> a - b }
                    "*=" -> if (current.type.isMatrix || rhs.type.isMatrix) glslMul(current, rhs)
                    else pairwise(current, rhs) { a, b -> a * b }
                    "/=" -> divide(current, rhs)
                    "%=" -> pairwise(current, rhs) { a, b -> GlslMath.mod(a, b) }
                    "&=", "|=", "^=", "<<=", ">>=" -> bitwise(e.op.dropLast(1), current, rhs)
                    else -> throw GlslUnsupported("assignment '${e.op}'")
                }
            }
            assign(e.target, next)
            next
        }

        is GPostfix -> {
            val current = eval(e.target)
            val one = GlslValue(current.type, FloatArray(current.c.size) { 1f })
            val next = if (e.op == "++") pairwise(current, one) { a, b -> a + b }
            else pairwise(current, one) { a, b -> a - b }
            assign(e.target, next)
            current
        }
    }

    private fun binary(op: String, e: GBinary): GlslValue {
        // `&&` and `||` short-circuit in GLSL, so the right operand must not be evaluated.
        if (op == "&&") return GlslValue.bool(eval(e.left).asBool && eval(e.right).asBool)
        if (op == "||") return GlslValue.bool(eval(e.left).asBool || eval(e.right).asBool)
        val a = eval(e.left)
        val b = eval(e.right)
        return when (op) {
            "+" -> pairwise(a, b) { x, y -> x + y }
            "-" -> pairwise(a, b) { x, y -> x - y }
            "*" -> glslMul(a, b)
            "/" -> divide(a, b)
            "%" -> pairwise(a, b) { x, y -> GlslMath.mod(x, y) }
            "^^" -> GlslValue.bool(a.asBool != b.asBool)
            "&", "|", "^", "<<", ">>" -> bitwise(op, a, b)
            "<" -> GlslValue.bool(a.asFloat < b.asFloat)
            ">" -> GlslValue.bool(a.asFloat > b.asFloat)
            "<=" -> GlslValue.bool(a.asFloat <= b.asFloat)
            ">=" -> GlslValue.bool(a.asFloat >= b.asFloat)
            "==" -> GlslValue.bool(a.c.contentEquals(b.c))
            "!=" -> GlslValue.bool(!a.c.contentEquals(b.c))
            else -> throw GlslUnsupported("binary operator '$op' is not in the StackSL subset")
        }
    }

    /**
     * The integer bit operators, on values stored as `Float`.
     *
     * This is safe *because of a bound the emitter guarantees*, not in general: every `uvec4` the
     * StackSL emitter produces goes through `toBits16`, which is
     * `uvec4(round(clamp(v, 0, 1) * 65535.0))`, so no component ever exceeds 65535. A `Float`
     * represents every integer below 2^24 exactly, so the round trip through `Int` here loses
     * nothing. A genuine 32-bit `uint` would lose its low bits, and a shader that produced one
     * would be silently wrong — so an operand out of range is refused rather than truncated.
     */
    private fun bitwise(op: String, a: GlslValue, b: GlslValue): GlslValue {
        if (a.type.base != GlslBase.INT || b.type.base != GlslBase.INT) {
            throw GlslUnsupported("bit operator '$op' on ${a.type} and ${b.type} — integers only")
        }
        return pairwise(a, b) { x, y ->
            if (Math.abs(x) > EXACT_INT_LIMIT || Math.abs(y) > EXACT_INT_LIMIT) {
                throw GlslEvalError(
                    "bit operator '$op' on $x, $y: outside the range a Float holds exactly, so the " +
                        "result would silently lose low bits"
                )
            }
            val xi = x.toInt()
            val yi = y.toInt()
            when (op) {
                "&" -> xi and yi
                "|" -> xi or yi
                "^" -> xi xor yi
                "<<" -> xi shl yi
                ">>" -> xi ushr yi
                else -> throw GlslUnsupported("bit operator '$op'")
            }.toFloat()
        }
    }

    /**
     * `/`, with GLSL's integer rule: `int / int` truncates towards zero, everything else is
     * float division (including division by zero, which yields an infinity that the shader is
     * expected to handle — the gallery's `main()` guards for exactly that).
     */
    private fun divide(a: GlslValue, b: GlslValue): GlslValue =
        if (a.type.base == GlslBase.INT && b.type.base == GlslBase.INT) {
            pairwise(a, b) { x, y -> (x.toInt() / y.toInt()).toFloat() }
        } else if (b.type.isMatrix || a.type.isMatrix) {
            pairwise(a, b) { x, y -> x / y }
        } else {
            pairwise(a, b) { x, y -> x / y }
        }

    /** Write [value] through an lvalue: a variable, a swizzle of one, or a component of one. */
    private fun assign(target: GExpr, value: GlslValue) {
        when (target) {
            is GVarRef -> {
                val i = frame.indexOf(target.name)
                if (i >= 0) frame.setAt(i, coerce(value, frame.valueAt(i).type))
                else if (target.name in globals) globals[target.name] = coerce(value, globals[target.name]!!.type)
                else throw GlslEvalError("assignment to undefined variable '${target.name}'")
            }

            is GField -> {
                val blockName = blockMemberName(target)
                if (blockName != null) {
                    globals[blockName] = value
                    return
                }
                val current = eval(target.target)
                val idx = swizzleIndices(target.field, current.c.size)
                if (idx.size != value.c.size && value.c.size != 1) {
                    throw GlslEvalError("cannot write ${value.type} into a ${idx.size}-component swizzle")
                }
                val src = FloatArray(idx.size) { value.c[if (value.c.size == 1) 0 else it] }
                assign(target.target, current.withComponents(idx, src))
            }

            is GIndex -> {
                val current = eval(target.target)
                val i = eval(target.index).asInt
                if (current.type.isMatrix) {
                    val rows = current.type.rows
                    val idx = IntArray(rows) { i * rows + it }
                    assign(target.target, current.withComponents(idx, value.c.copyOf()))
                } else {
                    assign(target.target, current.withComponent(i, value.asFloat))
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

    /**
     * Store [value] as [type].
     *
     * The only conversion GLSL performs implicitly here is `int` to `float` (and the reverse via
     * an explicit constructor, which the parser routes through [glslConstruct]). A shape mismatch
     * is an error rather than a reshape, because a silent reshape is how a CPU twin starts
     * producing images that are merely *plausible*.
     */
    private fun coerce(value: GlslValue, type: GlslType): GlslValue = when {
        value.type == type -> value
        value.type.components == type.components && value.type.cols == type.cols ->
            if (type.base == GlslBase.INT) GlslValue(type, FloatArray(value.c.size) { value.c[it].toInt().toFloat() })
            else GlslValue(type, value.c)
        value.type.isScalar -> glslConstruct(type, listOf(value))
        else -> throw GlslEvalError("cannot store ${value.type} in a $type")
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

        /** 2^24: the largest integer a `Float` and its successor both represent exactly. */
        const val EXACT_INT_LIMIT = 16_777_216f
    }
}

/**
 * Prepare a shader for repeated invocation.
 *
 * Globals are seeded from the shader's own declarations (so a `const` initialiser or an
 * `out vec4 outColor` starts out defined) and then overridden by [initialGlobals]. Samplers are
 * bound to their declaration index, which is what a [GlslSamplers] receives as its first
 * argument, so a shader with more than one sampler can still tell them apart.
 */
fun GlslShader.newInvocation(
    initialGlobals: Map<String, GlslValue> = emptyMap(),
    samplers: GlslSamplers = GlslSamplers.REFUSE
): GlslInvocation {
    val g = HashMap<String, GlslValue>()
    globals.forEach { decl ->
        g[decl.name] = GlslValue(decl.type, FloatArray(decl.type.components))
    }
    blocks.forEach { block ->
        block.members.forEach { (type, name) ->
            val key = if (block.instance.isEmpty()) name else "${block.instance}.$name"
            g[key] = GlslValue(type, FloatArray(type.components))
        }
    }
    samplersIndexed().forEach { (index, name) -> g[name] = GlslValue.int(index) }
    val invocation = GlslInvocation(this, g, samplers, blocks.map { it.instance }.filter { it.isNotEmpty() }.toSet())
    // Global initialisers may reference other globals, so they are evaluated after every name
    // exists, in declaration order.
    globals.forEach { decl -> decl.initializer?.let { g[decl.name] = invocation.eval(it) } }
    initialGlobals.forEach { (k, v) -> g[k] = v }
    return invocation
}

private fun GlslShader.samplersIndexed(): List<Pair<Int, String>> = samplers.mapIndexed { i, n -> i to n }
