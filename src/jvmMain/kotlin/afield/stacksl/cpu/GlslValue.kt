package afield.stacksl.cpu

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.math.truncate

// ---------------------------------------------------------------------------------------------
// The value model for the GLSL-subset interpreter behind StackSL's CPU backend.
//
// WHY FLOAT AND NOT DOUBLE, EVERYWHERE, WITHOUT EXCEPTION.
//
// The thing being mirrored is a GPU fragment shader whose `float` is IEEE-754 binary32. Every
// intermediate here is therefore a `Float`, and every builtin rounds to `Float` at every step,
// including the ones Kotlin would rather do in `Double` (`kotlin.math.sin` takes and returns
// `Double`, so those are computed wide and narrowed once — see [f1]). Widening an intermediate
// would make the CPU result *more* accurate than the shader and therefore a worse reference:
// the point is to reproduce the shader, not to improve on it.
//
// Two consequences are worth naming up front because they set the honest ceiling on agreement
// with a GPU, and neither is a defect in this file:
//
//   - `sin`, `cos`, `exp`, `log`, `pow`, `atan` are IMPLEMENTATION-DEFINED in GLSL. The spec
//     gives them no accuracy requirement at all beyond monotonicity in places. So no CPU
//     implementation can be bit-exact with a GPU's, even in principle, and any program that
//     reaches one of them can only be compared under a tolerance.
//   - even the exact operations do not compose exactly, because a GPU may CONTRACT `a * b + c`
//     into a fused multiply-add with one rounding while the JVM performs two. Kotlin has no
//     `fma`-free guarantee to offer here either way; what it does have is no automatic
//     contraction, so this interpreter is the *unfused* reading of the shader.
//
// The interesting failure is neither of those. `fract` of a large product amplifies a last-bit
// difference in its argument to O(1) — so `_hash21`, which is multiply/add/fract only and
// therefore exact operation by operation, can still produce a visibly different image from a
// backend that differed by one ulp three operations earlier. That is chaos, not error, and the
// gallery characterisation reports it separately from tolerance.
// ---------------------------------------------------------------------------------------------

/**
 * The scalar element type of a [GlslType]. GLSL's `uint` is folded into `INT` (nothing in the
 * emitted subset relies on unsigned wraparound), and `SAMPLER` is here because a sampler is a
 * value the emitter passes to helper functions — `_blur5(sampler2D tex, …)` — so it has to be
 * something the frame can hold.
 */
enum class GlslBase { FLOAT, INT, BOOL, SAMPLER }

/**
 * A GLSL type in the subset: a scalar, a vector, or a square matrix.
 *
 * [cols] is 1 for scalars and vectors, so `vec3` is `rows=3, cols=1` and `mat3` is
 * `rows=3, cols=3`. Component storage is **column-major**, matching both GLSL's constructor
 * argument order (`mat2(a, b, c, d)` is columns `(a,b)` then `(c,d)`) and its memory layout.
 */
data class GlslType(val base: GlslBase, val rows: Int, val cols: Int = 1) {
    val components: Int get() = rows * cols
    val isMatrix: Boolean get() = cols > 1
    val isScalar: Boolean get() = rows == 1 && cols == 1

    override fun toString(): String = when {
        base == GlslBase.SAMPLER -> "sampler"
        isMatrix -> "mat$cols" + if (rows != cols) "x$rows" else ""
        isScalar -> when (base) {
            GlslBase.FLOAT -> "float"
            GlslBase.INT -> "int"
            GlslBase.BOOL -> "bool"
            GlslBase.SAMPLER -> "sampler"
        }
        else -> when (base) {
            GlslBase.FLOAT -> "vec$rows"
            GlslBase.INT -> "ivec$rows"
            GlslBase.BOOL -> "bvec$rows"
            GlslBase.SAMPLER -> "sampler"
        }
    }

    companion object {
        // Interned. Every component-wise operation and every swizzle produces a value, and
        // without this table each of those would allocate a fresh `GlslType` beside it — for a
        // set of at most 100 distinct shapes, all of which fit in one small array.
        private val interned: Array<GlslType> = Array(GlslBase.entries.size * 5 * 5) { i ->
            val cols = i % 5
            val rows = (i / 5) % 5
            val base = GlslBase.entries[i / 25]
            GlslType(base, rows, cols)
        }

        /** The interned instance for a shape, so `GlslType` never allocates in the hot path. */
        fun of(base: GlslBase, rows: Int, cols: Int = 1): GlslType =
            if (rows in 0..4 && cols in 0..4) interned[base.ordinal * 25 + rows * 5 + cols]
            else GlslType(base, rows, cols)

        val FLOAT = of(GlslBase.FLOAT, 1)
        val INT = of(GlslBase.INT, 1)
        val BOOL = of(GlslBase.BOOL, 1)
        val SAMPLER = of(GlslBase.SAMPLER, 1)
        val VEC2 = of(GlslBase.FLOAT, 2)
        val VEC3 = of(GlslBase.FLOAT, 3)
        val VEC4 = of(GlslBase.FLOAT, 4)
        val MAT2 = of(GlslBase.FLOAT, 2, 2)
        val MAT3 = of(GlslBase.FLOAT, 3, 3)
        val MAT4 = of(GlslBase.FLOAT, 4, 4)

        /** The named type, or `null` if [name] is not a type keyword in the subset. */
        fun byName(name: String): GlslType? = when (name) {
            "sampler1D", "sampler2D", "sampler3D", "samplerCube", "sampler2DArray",
            "sampler2DShadow", "isampler2D", "usampler2D", "samplerBuffer" -> SAMPLER

            "float" -> FLOAT
            "int" -> INT
            "uint" -> INT
            "bool" -> BOOL
            "vec2" -> VEC2
            "vec3" -> VEC3
            "vec4" -> VEC4
            "ivec2" -> of(GlslBase.INT, 2)
            "ivec3" -> of(GlslBase.INT, 3)
            "ivec4" -> of(GlslBase.INT, 4)
            "bvec2" -> of(GlslBase.BOOL, 2)
            "bvec3" -> of(GlslBase.BOOL, 3)
            "bvec4" -> of(GlslBase.BOOL, 4)
            "mat2", "mat2x2" -> MAT2
            "mat3", "mat3x3" -> MAT3
            "mat4", "mat4x4" -> MAT4
            else -> null
        }
    }
}

/**
 * A GLSL value: a [GlslType] and its components, always stored as `Float`.
 *
 * `bool` is stored as `0f`/`1f` and `int` as an exactly-representable `Float`. That is safe for
 * the subset the StackSL emitter produces — its only integers are `for` loop counters bounded by
 * the octave count — and it keeps every operator on one storage path. [asInt] rounds rather than
 * truncates so a counter cannot drift.
 *
 * Instances are immutable; an assignment to a swizzle builds a new value ([withComponents]).
 */
class GlslValue(val type: GlslType, val c: FloatArray) {
    init {
        require(c.size == type.components) { "$type needs ${type.components} components, got ${c.size}" }
    }

    val x: Float get() = c[0]
    val asFloat: Float get() = c[0]
    val asInt: Int get() = Math.round(c[0])
    val asBool: Boolean get() = c[0] != 0f

    /** This value with component [index] replaced — the primitive behind swizzle assignment. */
    fun withComponent(index: Int, value: Float): GlslValue {
        val next = c.copyOf()
        next[index] = value
        return GlslValue(type, next)
    }

    /** This value with the components at [indices] replaced by [values], in order. */
    fun withComponents(indices: IntArray, values: FloatArray): GlslValue {
        val next = c.copyOf()
        for (i in indices.indices) next[indices[i]] = values[i]
        return GlslValue(type, next)
    }

    override fun toString(): String =
        if (type.isScalar) fmt(c[0]) else "$type(" + c.joinToString(", ") { fmt(it) } + ")"

    private fun fmt(v: Float) = when (type.base) {
        GlslBase.BOOL -> if (v != 0f) "true" else "false"
        GlslBase.INT -> Math.round(v).toString()
        GlslBase.FLOAT -> v.toString()
        // A sampler's "value" is its declaration index, which is all a `GlslSamplers` gets.
        GlslBase.SAMPLER -> "sampler#" + Math.round(v)
    }

    companion object {
        fun float(v: Float) = GlslValue(GlslType.FLOAT, floatArrayOf(v))
        fun int(v: Int) = GlslValue(GlslType.INT, floatArrayOf(v.toFloat()))
        fun bool(v: Boolean) = GlslValue(GlslType.BOOL, floatArrayOf(if (v) 1f else 0f))
        fun vec2(x: Float, y: Float) = GlslValue(GlslType.VEC2, floatArrayOf(x, y))
        fun vec3(x: Float, y: Float, z: Float) = GlslValue(GlslType.VEC3, floatArrayOf(x, y, z))
        fun vec4(x: Float, y: Float, z: Float, w: Float) = GlslValue(GlslType.VEC4, floatArrayOf(x, y, z, w))
    }
}

/** Thrown for anything the subset does not cover, with the offending text quoted. */
class GlslUnsupported(message: String) : RuntimeException(message)

/** Thrown when a shader is syntactically or semantically wrong for this interpreter. */
class GlslEvalError(message: String) : RuntimeException(message)

// ---------------------------------------------------------------------------------------------
// Component-wise machinery.
// ---------------------------------------------------------------------------------------------

/** Apply [op] to every component, keeping the type. Widens to `Double` only inside [op]. */
internal inline fun componentwise(v: GlslValue, op: (Float) -> Float): GlslValue {
    val out = FloatArray(v.c.size)
    for (i in out.indices) out[i] = op(v.c[i])
    return GlslValue(v.type, out)
}

/**
 * Apply [op] pairwise, broadcasting a scalar operand against a vector one.
 *
 * GLSL allows `vec4 * float` and `float * vec4` but not `vec4 * vec2`; a size mismatch that is
 * not a scalar broadcast is an error here rather than a silent truncation, because a silent
 * truncation is how a CPU twin starts disagreeing with its shader.
 */
internal inline fun pairwise(a: GlslValue, b: GlslValue, op: (Float, Float) -> Float): GlslValue {
    val an = a.c.size
    val bn = b.c.size
    val type = if (an >= bn) a.type else b.type
    val n = maxOf(an, bn)
    if (an != bn && an != 1 && bn != 1) {
        throw GlslEvalError("cannot combine ${a.type} with ${b.type} component-wise")
    }
    val out = FloatArray(n)
    for (i in 0 until n) out[i] = op(a.c[if (an == 1) 0 else i], b.c[if (bn == 1) 0 else i])
    return GlslValue(type, out)
}

/** Pairwise into a `bool`/`bvec` of the same shape — the `lessThan` family. */
internal inline fun pairwiseBool(a: GlslValue, b: GlslValue, op: (Float, Float) -> Boolean): GlslValue {
    val n = maxOf(a.c.size, b.c.size)
    val out = FloatArray(n)
    for (i in 0 until n) {
        val av = a.c[if (a.c.size == 1) 0 else i]
        val bv = b.c[if (b.c.size == 1) 0 else i]
        out[i] = if (op(av, bv)) 1f else 0f
    }
    return GlslValue(GlslType.of(GlslBase.BOOL, n), out)
}

/** A one-argument transcendental: computed in `Double`, narrowed to `Float` exactly once. */
internal inline fun f1(v: GlslValue, op: (Double) -> Double): GlslValue =
    componentwise(v) { op(it.toDouble()).toFloat() }

// ---------------------------------------------------------------------------------------------
// GLSL builtin semantics, spelled out from the spec rather than from habit.
// ---------------------------------------------------------------------------------------------

internal object GlslMath {
    /** `fract(x) = x - floor(x)`. */
    fun fract(x: Float): Float = x - floor(x)

    /** `mod(x, y) = x - y * floor(x / y)`. Not Kotlin's `%`, which keeps the sign of `x`. */
    fun mod(x: Float, y: Float): Float = x - y * floor(x / y)

    /** `mix(x, y, a) = x * (1 - a) + y * a`, the spec's form, not the `x + a * (y - x)` rewrite. */
    fun mix(x: Float, y: Float, a: Float): Float = x * (1f - a) + y * a

    fun clamp(x: Float, lo: Float, hi: Float): Float = minOf(maxOf(x, lo), hi)

    fun step(edge: Float, x: Float): Float = if (x < edge) 0f else 1f

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp((x - e0) / (e1 - e0), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun sign(x: Float): Float = when {
        x > 0f -> 1f
        x < 0f -> -1f
        else -> 0f
    }
}

/** `length` and friends need the whole vector, so they live outside [componentwise]. */
internal fun glslDot(a: GlslValue, b: GlslValue): Float {
    if (a.c.size != b.c.size) throw GlslEvalError("dot(${a.type}, ${b.type})")
    var s = 0f
    for (i in a.c.indices) s += a.c[i] * b.c[i]
    return s
}

internal fun glslLength(v: GlslValue): Float = sqrt(glslDot(v, v).toDouble()).toFloat()

/**
 * Matrix times column vector, matrix times matrix, and the scalar/vector broadcasts that
 * `*` also has to cover.
 *
 * Column-major throughout: `m.c[col * rows + row]`. Getting this backwards transposes every
 * `mat2` warp in `_fbm` and every `mat3` in the OkLab helpers, and the failure is not subtle —
 * it was worth a dedicated unit test rather than an eyeball.
 */
internal fun glslMul(a: GlslValue, b: GlslValue): GlslValue = when {
    a.type.isMatrix && b.type.isMatrix -> {
        if (a.type.cols != b.type.rows) throw GlslEvalError("${a.type} * ${b.type}")
        val rows = a.type.rows
        val cols = b.type.cols
        val out = FloatArray(rows * cols)
        for (col in 0 until cols) for (row in 0 until rows) {
            var s = 0f
            for (k in 0 until a.type.cols) s += a.c[k * rows + row] * b.c[col * b.type.rows + k]
            out[col * rows + row] = s
        }
        GlslValue(GlslType.of(GlslBase.FLOAT, rows, cols), out)
    }

    a.type.isMatrix && b.type.isScalar -> componentwise(a) { it * b.x }
    a.type.isScalar && b.type.isMatrix -> componentwise(b) { it * a.x }

    a.type.isMatrix -> {
        if (a.type.cols != b.c.size) throw GlslEvalError("${a.type} * ${b.type}")
        val out = FloatArray(a.type.rows)
        for (row in 0 until a.type.rows) {
            var s = 0f
            for (k in 0 until a.type.cols) s += a.c[k * a.type.rows + row] * b.c[k]
            out[row] = s
        }
        GlslValue(GlslType.of(GlslBase.FLOAT, a.type.rows), out)
    }

    b.type.isMatrix -> {
        // Row vector times matrix: `v * m` is `transpose(m) * v`.
        if (b.type.rows != a.c.size) throw GlslEvalError("${a.type} * ${b.type}")
        val out = FloatArray(b.type.cols)
        for (col in 0 until b.type.cols) {
            var s = 0f
            for (k in 0 until b.type.rows) s += a.c[k] * b.c[col * b.type.rows + k]
            out[col] = s
        }
        GlslValue(GlslType.of(GlslBase.FLOAT, b.type.cols), out)
    }

    else -> pairwise(a, b) { x, y -> x * y }
}

/**
 * A GLSL constructor call: `vec3(…)`, `mat2(…)`, `float(…)`.
 *
 * Two rules, both from the spec and both load-bearing in the emitted code:
 * a single scalar argument **broadcasts** (`vec4(0.0)` is all zeros, `mat3(1.0)` is the
 * identity times one), and otherwise the arguments' components are **flattened in order** and
 * the first `n` taken. `vec4(program_in.xy, 0.0, 1.0)` is the emitter's most common line and it
 * relies entirely on the flattening rule.
 */
internal fun glslConstruct(type: GlslType, args: List<GlslValue>): GlslValue {
    if (args.isEmpty()) throw GlslEvalError("$type() needs arguments")
    if (args.size == 1 && args[0].type.isScalar) {
        val v = args[0].x
        if (type.isMatrix) {
            val out = FloatArray(type.components)
            for (i in 0 until type.rows) out[i * type.rows + i] = v
            return GlslValue(type, out)
        }
        return GlslValue(type, FloatArray(type.components) { v })
    }
    if (args.size == 1 && args[0].type.isMatrix && type.isMatrix) {
        // mat3(mat4) etc: take the top-left submatrix, identity-filled.
        val src = args[0]
        val out = FloatArray(type.components)
        for (col in 0 until type.cols) for (row in 0 until type.rows) {
            out[col * type.rows + row] = when {
                col < src.type.cols && row < src.type.rows -> src.c[col * src.type.rows + row]
                col == row -> 1f
                else -> 0f
            }
        }
        return GlslValue(type, out)
    }
    val flat = ArrayList<Float>(type.components)
    args.forEach { a -> a.c.forEach { flat += it } }
    if (flat.size < type.components) {
        throw GlslEvalError("$type(...) got ${flat.size} components, needs ${type.components}")
    }
    return GlslValue(type, FloatArray(type.components) { flat[it] })
}

/**
 * Map a swizzle string to component indices. `xyzw`, `rgba` and `stpq` are the three sets; GLSL
 * forbids mixing them in one selector and so does this, because a mixed selector is far more
 * likely to be a typo than an intent.
 */
internal fun swizzleIndices(field: String, size: Int): IntArray {
    if (field.length !in 1..4) throw GlslEvalError("bad swizzle '.$field'")
    val sets = arrayOf("xyzw", "rgba", "stpq")
    val set = sets.firstOrNull { s -> field.all { it in s } }
        ?: throw GlslEvalError("swizzle '.$field' mixes component sets")
    return IntArray(field.length) {
        val idx = set.indexOf(field[it])
        if (idx >= size) throw GlslEvalError("swizzle '.$field' reads component ${idx + 1} of a $size-component value")
        idx
    }
}

/** Read a swizzle off [v]. A one-character selector yields a scalar, matching GLSL. */
internal fun glslSwizzle(v: GlslValue, field: String): GlslValue {
    val idx = swizzleIndices(field, v.c.size)
    val out = FloatArray(idx.size) { v.c[idx[it]] }
    return GlslValue(GlslType.of(v.type.base, idx.size), out)
}

/**
 * The builtin function table.
 *
 * Every entry is written from the GLSL specification's definition, not from the nearest Kotlin
 * equivalent — see [GlslMath.mod] and [GlslMath.mix] for the two where those differ and the
 * difference is visible in an image. The set is deliberately larger than the 31 gallery programs
 * need: it covers everything `helperSource` in `StackSL.kt` can emit, so a word this gallery
 * happens not to exercise does not silently become "unsupported" the first time someone uses it.
 */
internal object GlslBuiltins {

    /** Names this interpreter knows. Used by the coverage test against the emitter's helpers. */
    val names: Set<String> = setOf(
        "radians", "degrees", "sin", "cos", "tan", "asin", "acos", "atan",
        "sinh", "cosh", "tanh", "pow", "exp", "log", "exp2", "log2", "sqrt", "inversesqrt",
        "abs", "sign", "floor", "trunc", "round", "roundEven", "ceil", "fract", "mod",
        "min", "max", "clamp", "mix", "step", "smoothstep",
        "length", "distance", "dot", "cross", "normalize",
        "faceforward", "reflect", "refract",
        "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual", "equal", "notEqual",
        "any", "all", "not", "isnan", "isinf",
        "matrixCompMult", "transpose",
        "texture", "textureLod"
    )

    fun call(name: String, a: List<GlslValue>, samplers: GlslSamplers): GlslValue? = when (name) {
        "radians" -> componentwise(a[0]) { (it.toDouble() * Math.PI / 180.0).toFloat() }
        "degrees" -> componentwise(a[0]) { (it.toDouble() * 180.0 / Math.PI).toFloat() }
        "sin" -> f1(a[0]) { sin(it) }
        "cos" -> f1(a[0]) { cos(it) }
        "tan" -> f1(a[0]) { tan(it) }
        "asin" -> f1(a[0]) { asin(it) }
        "acos" -> f1(a[0]) { acos(it) }
        "atan" -> if (a.size == 2) {
            pairwise(a[0], a[1]) { y, x -> atan2(y.toDouble(), x.toDouble()).toFloat() }
        } else {
            f1(a[0]) { atan(it) }
        }

        "sinh" -> f1(a[0]) { sinh(it) }
        "cosh" -> f1(a[0]) { cosh(it) }
        "tanh" -> f1(a[0]) { tanh(it) }
        "pow" -> pairwise(a[0], a[1]) { x, y -> x.toDouble().pow(y.toDouble()).toFloat() }
        "exp" -> f1(a[0]) { exp(it) }
        "log" -> f1(a[0]) { ln(it) }
        "exp2" -> componentwise(a[0]) { 2.0.pow(it.toDouble()).toFloat() }
        "log2" -> componentwise(a[0]) { log2(it.toDouble()).toFloat() }
        "sqrt" -> componentwise(a[0]) { sqrt(it.toDouble()).toFloat() }
        "inversesqrt" -> componentwise(a[0]) { (1.0 / sqrt(it.toDouble())).toFloat() }
        "abs" -> componentwise(a[0]) { abs(it) }
        "sign" -> componentwise(a[0]) { GlslMath.sign(it) }
        "floor" -> componentwise(a[0]) { floor(it) }
        "trunc" -> componentwise(a[0]) { truncate(it) }
        "round" -> componentwise(a[0]) { Math.round(it).toFloat() }
        "roundEven" -> componentwise(a[0]) { Math.rint(it.toDouble()).toFloat() }
        "ceil" -> componentwise(a[0]) { ceil(it) }
        "fract" -> componentwise(a[0]) { GlslMath.fract(it) }
        "mod" -> pairwise(a[0], a[1]) { x, y -> GlslMath.mod(x, y) }
        "min" -> pairwise(a[0], a[1]) { x, y -> minOf(x, y) }
        "max" -> pairwise(a[0], a[1]) { x, y -> maxOf(x, y) }
        "clamp" -> ternary(a) { x, lo, hi -> GlslMath.clamp(x, lo, hi) }

        // `mix(x, y, bvec)` is a SELECT, not an interpolation, and the emitter's `_safeDiv`
        // depends on it: `mix(vec4(1e-5), b, greaterThan(abs(b), vec4(1e-5)))`.
        "mix" -> if (a[2].type.base == GlslBase.BOOL) {
            val n = maxOf(a[0].c.size, a[1].c.size, a[2].c.size)
            val out = FloatArray(n) { i ->
                val pick = a[2].c[if (a[2].c.size == 1) 0 else i] != 0f
                val src = if (pick) a[1] else a[0]
                src.c[if (src.c.size == 1) 0 else i]
            }
            GlslValue(GlslType.of(GlslBase.FLOAT, n), out)
        } else {
            ternary(a) { x, y, t -> GlslMath.mix(x, y, t) }
        }

        "step" -> pairwise(a[0], a[1]) { e, x -> GlslMath.step(e, x) }
        "smoothstep" -> ternary(a) { e0, e1, x -> GlslMath.smoothstep(e0, e1, x) }

        "length" -> GlslValue.float(glslLength(a[0]))
        "distance" -> GlslValue.float(glslLength(pairwise(a[0], a[1]) { x, y -> x - y }))
        "dot" -> GlslValue.float(glslDot(a[0], a[1]))
        "cross" -> {
            val (p, q) = a
            GlslValue.vec3(
                p.c[1] * q.c[2] - p.c[2] * q.c[1],
                p.c[2] * q.c[0] - p.c[0] * q.c[2],
                p.c[0] * q.c[1] - p.c[1] * q.c[0]
            )
        }

        "normalize" -> {
            val len = glslLength(a[0])
            componentwise(a[0]) { it / len }
        }

        "faceforward" -> if (glslDot(a[2], a[1]) < 0f) a[0] else componentwise(a[0]) { -it }
        "reflect" -> {
            val d = glslDot(a[0], a[1])
            pairwise(a[0], a[1]) { i, n -> i - 2f * d * n }
        }

        "refract" -> {
            val eta = a[2].x
            val dotni = glslDot(a[1], a[0])
            val k = 1f - eta * eta * (1f - dotni * dotni)
            if (k < 0f) GlslValue(a[0].type, FloatArray(a[0].c.size)) else {
                val sk = sqrt(k.toDouble()).toFloat()
                pairwise(a[0], a[1]) { i, n -> eta * i - (eta * dotni + sk) * n }
            }
        }

        "lessThan" -> pairwiseBool(a[0], a[1]) { x, y -> x < y }
        "lessThanEqual" -> pairwiseBool(a[0], a[1]) { x, y -> x <= y }
        "greaterThan" -> pairwiseBool(a[0], a[1]) { x, y -> x > y }
        "greaterThanEqual" -> pairwiseBool(a[0], a[1]) { x, y -> x >= y }
        "equal" -> pairwiseBool(a[0], a[1]) { x, y -> x == y }
        "notEqual" -> pairwiseBool(a[0], a[1]) { x, y -> x != y }
        "any" -> GlslValue.bool(a[0].c.any { it != 0f })
        "all" -> GlslValue.bool(a[0].c.all { it != 0f })
        "not" -> boolwise(a[0]) { it == 0f }
        "isnan" -> boolwise(a[0]) { it.isNaN() }
        "isinf" -> boolwise(a[0]) { it.isInfinite() }

        "matrixCompMult" -> pairwise(a[0], a[1]) { x, y -> x * y }
        "transpose" -> {
            val m = a[0]
            val out = FloatArray(m.c.size)
            for (col in 0 until m.type.cols) for (row in 0 until m.type.rows) {
                out[row * m.type.cols + col] = m.c[col * m.type.rows + row]
            }
            GlslValue(GlslType.of(GlslBase.FLOAT, m.type.cols, m.type.rows), out)
        }

        "texture" -> samplers.sample(a[0], a[1])
        "textureLod" -> samplers.sample(a[0], a[1])

        else -> null
    }

    private inline fun ternary(a: List<GlslValue>, op: (Float, Float, Float) -> Float): GlslValue {
        val n = maxOf(a[0].c.size, a[1].c.size, a[2].c.size)
        val type = listOf(a[0].type, a[1].type, a[2].type).maxByOrNull { it.components }!!
        val out = FloatArray(n) { i ->
            op(
                a[0].c[if (a[0].c.size == 1) 0 else i],
                a[1].c[if (a[1].c.size == 1) 0 else i],
                a[2].c[if (a[2].c.size == 1) 0 else i]
            )
        }
        return GlslValue(if (type.components == n) type else GlslType.of(GlslBase.FLOAT, n), out)
    }

    private inline fun boolwise(v: GlslValue, op: (Float) -> Boolean): GlslValue =
        GlslValue(GlslType.of(GlslBase.BOOL, v.c.size), FloatArray(v.c.size) { if (op(v.c[it])) 1f else 0f })
}

/**
 * Where `texture(sampler, uv)` gets its answer.
 *
 * The default refuses, because a shader that samples has no defined CPU answer without someone
 * supplying the texture — and returning black would quietly turn a feedback program into a
 * plausible-looking wrong image, which is the failure mode this whole backend exists to prevent.
 */
fun interface GlslSamplers {
    /** [sampler] is the value the sampler name evaluated to; [uv] the coordinate. */
    fun sample(sampler: GlslValue, uv: GlslValue): GlslValue

    companion object {
        val REFUSE = GlslSamplers { _, _ ->
            throw GlslUnsupported(
                "this shader calls texture(); supply a GlslSamplers to say what the texture holds"
            )
        }
    }
}
