package afield.stacksl.cpu

/**
 * The value type the GLSL interpreter is generic over, so that one interpreter serves both the
 * `Float` reference path and the differentiable one.
 *
 * #### One interpreter, two algebras
 *
 * The interface factors out the *values*, leaving `GlslInterpreter.kt`'s machine (the statements,
 * the branches, the loops, the call frames, the lvalue writes) as one copy of the control flow,
 * shared by every scalar type. `GlslFloatScalars` is the `Float` instance, and each of its methods
 * is a single `GlslValue` expression, so going through the interface neither widens nor reorders
 * any arithmetic. `StackSLCpuFloatPathManifestTest` holds all 31 gallery programs to committed
 * `Float` bits.
 *
 * Sharing the control flow is what lets a derivative run over **the emitted GLSL**, the artifact
 * the GPU consumes, rather than over a second evaluator written beside it.
 *
 * `V` is a reference type in both instances ([GlslValue] and `AdValue`), so the `Float` path pays no
 * boxing for the generic parameter: one interface dispatch per operation is the entire cost.
 *
 * #### Why the methods take whole values
 *
 * Every method here takes and returns a *whole* GLSL value rather than a component, because
 * `normalize`, `dot`, `length` and the matrix products are not component-wise, and an interface at
 * component granularity would force them to be restated per scalar. As it is, the `Float` instance
 * delegates to `glslMul`, `glslConstruct`, `glslSwizzle` and `GlslBuiltins.call` unchanged.
 */

/** A condition's verdict: decided either way, or (only for a set-valued algebra) not decided. */
internal enum class GlslTruth { TRUE, FALSE, UNKNOWN }

/**
 * Everything the interpreter asks of a value: lifting, arithmetic, construction, swizzles,
 * component writes and the builtin table. One implementation per scalar type.
 */
internal interface GlslScalars<V> {

    /** Lift a parsed literal (which the parser produces as a [GlslValue]) into this algebra. */
    fun lift(v: GlslValue): V

    /** The GLSL type of a value. Shapes are decided by the parser, so this is never a guess. */
    fun typeOf(v: V): GlslType

    /** The default-initialised value of [type] — GLSL's zero, and a function's implicit return. */
    fun zeros(type: GlslType): V

    /** All-ones of [type]; the `++`/`--` increment. */
    fun ones(type: GlslType): V

    /** A `bool` — the result of every comparison and of `&&`, `||`, `^^`, `!`. */
    fun boolOf(b: Boolean): V

    /**
     * Read a value as a branch condition.
     *
     * A condition carries **no derivative** in any differentiable instance: a branch is
     * differentiated *as taken*, which is the correct derivative of the piecewise function
     * everywhere except at the switch-over itself. `autodiff.kt` states the same convention.
     */
    fun asBool(v: V): Boolean

    /**
     * Read a value as a condition that may be undecided. Every point algebra answers TRUE or FALSE,
     * exactly as [asBool]; the interval algebra answers UNKNOWN when its range holds both, and the
     * machine then runs both arms and [join]s them.
     */
    fun truth(v: V): GlslTruth = if (asBool(v)) GlslTruth.TRUE else GlslTruth.FALSE

    /** A `bool` from a three-valued truth. Only an algebra that can say UNKNOWN is handed one. */
    fun boolOfTruth(t: GlslTruth): V = when (t) {
        GlslTruth.TRUE -> boolOf(true)
        GlslTruth.FALSE -> boolOf(false)
        GlslTruth.UNKNOWN -> throw IllegalStateException("a point algebra cannot hold an undecided bool")
    }

    /** `==` as a three-valued truth; [equalComponents] for every point algebra. */
    fun equalTruth(a: V, b: V): GlslTruth = if (equalComponents(a, b)) GlslTruth.TRUE else GlslTruth.FALSE

    /** The smallest value holding both — what an undecided branch leaves behind. Never called on a point algebra. */
    fun join(a: V, b: V): V = throw IllegalStateException("only an interval algebra joins two branches")

    /** Read a value as an array/matrix index or a `textureGather` channel. Also derivative-free. */
    fun asInt(v: V): Int

    /** Unary `-`. */
    fun negate(v: V): V

    /** Every binary operator in the subset, including the integer and bit rules. */
    fun binary(op: String, a: V, b: V): V

    /** `==`/`!=`, which GLSL defines component-wise-and-reduce rather than as a `bvec`. */
    fun equalComponents(a: V, b: V): Boolean

    /** A constructor call: `vec3(…)`, `mat2(…)`, `float(…)`. */
    fun construct(type: GlslType, args: List<V>): V

    /** Read a swizzle: `.xyz`, `.rgba`, `.st`. */
    fun swizzle(v: V, field: String): V

    /** Component [index] of a vector, as a scalar. */
    fun component(v: V, index: Int): V

    /** Column [index] of a matrix, as a vector. */
    fun column(v: V, index: Int): V

    /** [v] with the components at [indices] replaced from [src], broadcasting a scalar [src]. */
    fun withComponents(v: V, indices: IntArray, src: V): V

    /** [v] with one component replaced by the scalar [src]. */
    fun withComponent(v: V, index: Int, src: V): V

    /** Store [v] as [type] — GLSL's only implicit conversion is `int` to `float`. */
    fun coerce(v: V, type: GlslType): V

    /** A GLSL builtin, or `null` if the name is not one (so the caller can report it). */
    fun callBuiltin(name: String, args: List<V>, samplers: GlslSamplers): V?
}

/**
 * The `Float` instance: a binary32 mirror of a fragment shader, and the backend's reference.
 *
 * Every body below is the expression that used to sit inline in `GlslInvocation`, moved and not
 * rewritten. Nothing is widened, nothing is reordered, no operation is fused. [GlslValue]'s file
 * KDoc is the argument for why that matters; `StackSLCpuFloatPathManifestTest` is where it is
 * measured.
 */
internal object GlslFloatScalars : GlslScalars<GlslValue> {

    override fun lift(v: GlslValue): GlslValue = v

    override fun typeOf(v: GlslValue): GlslType = v.type

    override fun zeros(type: GlslType): GlslValue = GlslValue(type, FloatArray(type.components))

    override fun ones(type: GlslType): GlslValue = GlslValue(type, FloatArray(type.components) { 1f })

    override fun boolOf(b: Boolean): GlslValue = GlslValue.bool(b)

    override fun asBool(v: GlslValue): Boolean = v.asBool

    override fun asInt(v: GlslValue): Int = v.asInt

    override fun negate(v: GlslValue): GlslValue = componentwise(v) { -it }

    override fun binary(op: String, a: GlslValue, b: GlslValue): GlslValue = when (op) {
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
        else -> throw GlslUnsupported("binary operator '$op' is not in the StackSL subset")
    }

    override fun equalComponents(a: GlslValue, b: GlslValue): Boolean = a.c.contentEquals(b.c)

    override fun construct(type: GlslType, args: List<GlslValue>): GlslValue = glslConstruct(type, args)

    override fun swizzle(v: GlslValue, field: String): GlslValue = glslSwizzle(v, field)

    override fun component(v: GlslValue, index: Int): GlslValue =
        GlslValue(GlslType.of(v.type.base, 1), floatArrayOf(v.c[index]))

    override fun column(v: GlslValue, index: Int): GlslValue {
        val rows = v.type.rows
        return GlslValue(GlslType.of(v.type.base, rows), FloatArray(rows) { v.c[index * rows + it] })
    }

    override fun withComponents(v: GlslValue, indices: IntArray, src: GlslValue): GlslValue =
        v.withComponents(indices, FloatArray(indices.size) { src.c[if (src.c.size == 1) 0 else it] })

    override fun withComponent(v: GlslValue, index: Int, src: GlslValue): GlslValue =
        v.withComponent(index, src.asFloat)

    override fun coerce(v: GlslValue, type: GlslType): GlslValue = coerceValue(v, type)

    private fun coerceValue(value: GlslValue, type: GlslType): GlslValue = when {
        value.type == type -> value
        value.type.components == type.components && value.type.cols == type.cols ->
            if (type.base == GlslBase.INT) GlslValue(type, FloatArray(value.c.size) { value.c[it].toInt().toFloat() })
            else GlslValue(type, value.c)

        value.type.isScalar -> glslConstruct(type, listOf(value))
        else -> throw GlslEvalError("cannot store ${value.type} in a $type")
    }

    override fun callBuiltin(name: String, args: List<GlslValue>, samplers: GlslSamplers): GlslValue? =
        GlslBuiltins.call(name, args, samplers)

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

    /** 2^24: the largest integer a `Float` and its successor both represent exactly. */
    private const val EXACT_INT_LIMIT = 16_777_216f
}
