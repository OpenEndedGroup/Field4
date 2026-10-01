package afield.stacksl.cpu

import afield.stdlib.FnRange
import afield.stdlib.FnRangeMath

/**
 * Interval arithmetic for the GLSL interpreter: a shader run over a whole *region* of `fragUV` at
 * once, returning a bound on every colour any pixel in it can produce.
 *
 * `GlslIntervalScalars` is the third [GlslScalars] instance, beside the binary32 reference and the
 * reverse-mode one, so it runs **the emitted GLSL** through the interpreter's one copy of the
 * control flow. Nothing about a StackSL word is restated here.
 *
 * #### What is contained
 *
 * The contract is the one `afield.stdlib.FnInterval` states for `Fn`, moved to binary32: for every
 * input inside the intervals, the value the **`Float` reference path** computes lies inside the
 * result (or is NaN, and the result says NaN is possible). Each operation is bounded by
 * [FnRangeMath] in `Double` and the endpoints rounded *outward* to `Float`. That is sound for the
 * correctly rounded operations because rounding to nearest is monotone, and because a `Double`
 * product or sum of two floats rounds to `Float` the way the `Float` operation does (the argument
 * `GlslAd.kt` makes for its primal). The library transcendentals are already widened by
 * [FnRangeMath], and the `Float` path rounds their `Double` result to nearest, which is monotone too.
 *
 * The composite builtins (`mod`, `fract`, `mix`, `smoothstep`, `dot`, `length`) are bounded as the
 * `Float` path spells them, operation by operation, so the bound is of *that* arithmetic and not of
 * a real-number ideal. `fract(x)` gets the one structural rule `FnInterval` also has: inside one unit
 * cell it is endpoint-exact, across a boundary it is `[0, 1]`.
 *
 * #### Branches
 *
 * A comparison of two overlapping intervals is neither true nor false. [truth] answers
 * [GlslTruth.UNKNOWN], and `GlslMachine` then runs **both** arms from the same state and joins what
 * each wrote, with [join]. The machine refuses (with [GlslUnsupported]) the cases a join cannot
 * express: arms that leave by different routes (one returns, the other falls through), and a loop
 * whose condition is undecided. An integer index must be a single value.
 *
 * #### What is refused
 *
 * Builtins with no rule here are refused by name, never guessed: textures, `cross`, `reflect`,
 * `refract`, `faceforward`, the matrix builtins, `tan`, the inverse trigonometric and hyperbolic
 * functions except `tanh`, and the bit operators.
 */

/** One component: every `Float` in `[lo, hi]`, plus NaN when [nan]. Empty (only NaN) when `lo > hi`. */
internal data class Iv(val lo: Float, val hi: Float, val nan: Boolean = false) {
    val isEmpty: Boolean get() = !(lo <= hi)
    val isPoint: Boolean get() = lo == hi && !nan

    fun hull(o: Iv): Iv = when {
        isEmpty -> o.copy(nan = o.nan || nan)
        o.isEmpty -> copy(nan = nan || o.nan)
        else -> Iv(minOf(lo, o.lo), maxOf(hi, o.hi), nan || o.nan)
    }

    operator fun contains(v: Float): Boolean = if (v.isNaN()) nan else !isEmpty && v >= lo && v <= hi

    fun toRange(): FnRange = FnRange(lo.toDouble(), hi.toDouble(), nan)

    override fun toString(): String = (if (isEmpty) "∅" else if (lo == hi) "$lo" else "[$lo, $hi]") + if (nan) "∪NaN" else ""

    companion object {
        fun point(v: Float): Iv = if (v.isNaN()) Iv(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, true) else Iv(v, v)
        val UNIT = Iv(0f, 1f)
        val TRUE = Iv(1f, 1f)
        val FALSE = Iv(0f, 0f)

        /** The outward `Float` rounding of a `Double` range. */
        fun of(r: FnRange): Iv {
            if (r.isEmpty) return Iv(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, r.maybeNaN)
            return Iv(down(r.lo), up(r.hi), r.maybeNaN)
        }

        private fun down(d: Double): Float {
            val f = d.toFloat()
            return if (f.toDouble() > d) Math.nextDown(f) else f
        }

        private fun up(d: Double): Float {
            val f = d.toFloat()
            return if (f.toDouble() < d) Math.nextUp(f) else f
        }
    }
}

/** `sqrt`, correctly rounded and monotone on `[0, inf]`; NaN for any part below zero. */
private fun sqrtRange(r: FnRange): FnRange {
    if (r.isEmpty) return r
    val nan = r.maybeNaN || r.lo < 0.0
    if (r.hi < 0.0) return FnRange(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, true)
    return FnRange(Math.sqrt(maxOf(r.lo, 0.0)), Math.sqrt(r.hi), nan)
}

/** A GLSL value whose components are [Iv]s: the interval [GlslValue]. */
internal class IntervalValue(val type: GlslType, val c: Array<Iv>) {
    init {
        require(c.size == type.components) { "$type needs ${type.components} components, got ${c.size}" }
    }

    /** Whether [v]'s components each lie inside this value's. */
    operator fun contains(v: GlslValue): Boolean = v.c.size == c.size && v.c.indices.all { v.c[it] in c[it] }

    override fun toString(): String = "iv:$type(${c.joinToString()})"
}

/** The interval instance of [GlslScalars]. Stateless, so one instance serves every thread. */
internal object GlslIntervalScalars : GlslScalars<IntervalValue> {

    // -- component rules ---------------------------------------------------------------------

    private inline fun r1(a: Iv, f: (FnRange) -> FnRange): Iv = Iv.of(f(a.toRange()))
    private inline fun r2(a: Iv, b: Iv, f: (FnRange, FnRange) -> FnRange): Iv = Iv.of(f(a.toRange(), b.toRange()))

    private fun add(a: Iv, b: Iv) = r2(a, b, FnRangeMath::add)
    private fun sub(a: Iv, b: Iv) = r2(a, b, FnRangeMath::sub)
    private fun mul(a: Iv, b: Iv) = r2(a, b, FnRangeMath::mul)

    /** `x * x` for one value — only where the two factors are provably the same value. */
    private fun sq(a: Iv) = r1(a, FnRangeMath::square)
    private fun div(a: Iv, b: Iv) = r2(a, b, FnRangeMath::div)
    private fun neg(a: Iv) = Iv(-a.hi, -a.lo, a.nan)
    private fun floor(a: Iv) = r1(a, FnRangeMath::floor)
    private fun minIv(a: Iv, b: Iv) = r2(a, b, FnRangeMath::min)
    private fun maxIv(a: Iv, b: Iv) = r2(a, b, FnRangeMath::max)
    private fun k(v: Float) = Iv.point(v)

    /** `x - floor(x)` in `Float`, endpoint-exact inside a unit cell. */
    private fun fract(a: Iv): Iv {
        if (a.isEmpty) return a
        if (a.lo.isInfinite() || a.hi.isInfinite()) return Iv(0f, 1f, true)
        val kl = kotlin.math.floor(a.lo)
        return if (kl == kotlin.math.floor(a.hi)) Iv(a.lo - kl, a.hi - kl, a.nan) else Iv(0f, 1f, a.nan)
    }

    /** A monotone non-decreasing map, evaluated at the endpoints exactly as the `Float` path would. */
    private inline fun monotone(a: Iv, f: (Float) -> Float): Iv =
        if (a.isEmpty) a else Iv(f(a.lo), f(a.hi), a.nan)

    private fun less(a: Iv, b: Iv): Iv = when {
        a.isEmpty || b.isEmpty -> Iv.FALSE
        a.hi < b.lo && !a.nan && !b.nan -> Iv.TRUE
        a.lo >= b.hi -> Iv.FALSE
        else -> Iv.UNIT
    }

    private fun lessEq(a: Iv, b: Iv): Iv = when {
        a.isEmpty || b.isEmpty -> Iv.FALSE
        a.hi <= b.lo && !a.nan && !b.nan -> Iv.TRUE
        a.lo > b.hi -> Iv.FALSE
        else -> Iv.UNIT
    }

    private fun eq(a: Iv, b: Iv): Iv = when {
        a.isPoint && b.isPoint -> if (a.lo == b.lo) Iv.TRUE else Iv.FALSE
        a.isEmpty || b.isEmpty || a.hi < b.lo || b.hi < a.lo -> Iv.FALSE
        else -> Iv.UNIT
    }

    private fun not(a: Iv): Iv = Iv(1f - a.hi, 1f - a.lo)

    private fun select(pick: Iv, whenFalse: Iv, whenTrue: Iv): Iv = when {
        pick.lo == 1f -> whenTrue
        pick.hi == 0f -> whenFalse
        else -> whenFalse.hull(whenTrue)
    }

    private fun clampIv(x: Iv, lo: Iv, hi: Iv) = minIv(maxIv(x, lo), hi)

    private fun mixIv(x: Iv, y: Iv, a: Iv) = add(mul(x, sub(k(1f), a)), mul(y, a))

    private fun smoothstepIv(e0: Iv, e1: Iv, x: Iv): Iv {
        val t = clampIv(div(sub(x, e0), sub(e1, e0)), k(0f), k(1f))
        // Op by op, as GlslMath.smoothstep rounds. FnRangeMath.cubicSmoothstep's monotone rule is
        // argued for Double rounding; Float's is 2^29 times coarser, so it does not transfer.
        return mul(sq(t), sub(k(3f), mul(k(2f), t)))
    }

    // -- whole values -------------------------------------------------------------------------

    private fun scalar(v: Iv) = IntervalValue(GlslType.FLOAT, arrayOf(v))

    private inline fun unary(v: IntervalValue, f: (Iv) -> Iv) = IntervalValue(v.type, Array(v.c.size) { f(v.c[it]) })

    private inline fun pair(a: IntervalValue, b: IntervalValue, f: (Iv, Iv) -> Iv): IntervalValue {
        val n = maxOf(a.c.size, b.c.size)
        val type = if (a.c.size >= b.c.size) a.type else b.type
        return IntervalValue(type, Array(n) { f(a.c[if (a.c.size == 1) 0 else it], b.c[if (b.c.size == 1) 0 else it]) })
    }

    private inline fun pairBool(a: IntervalValue, b: IntervalValue, f: (Iv, Iv) -> Iv): IntervalValue {
        val n = maxOf(a.c.size, b.c.size)
        return IntervalValue(GlslType.of(GlslBase.BOOL, n), Array(n) { f(a.c[if (a.c.size == 1) 0 else it], b.c[if (b.c.size == 1) 0 else it]) })
    }

    private inline fun triple(a: List<IntervalValue>, f: (Iv, Iv, Iv) -> Iv): IntervalValue {
        val n = maxOf(a[0].c.size, a[1].c.size, a[2].c.size)
        val type = listOf(a[0].type, a[1].type, a[2].type).maxByOrNull { it.components }!!
        return IntervalValue(if (type.components == n) type else GlslType.of(GlslBase.FLOAT, n), Array(n) { i ->
            f(a[0].c[if (a[0].c.size == 1) 0 else i], a[1].c[if (a[1].c.size == 1) 0 else i], a[2].c[if (a[2].c.size == 1) 0 else i])
        })
    }

    private fun dot(a: IntervalValue, b: IntervalValue): Iv {
        if (a.c.size != b.c.size) throw GlslEvalError("dot(${a.type}, ${b.type})")
        var s = k(0f)
        for (i in a.c.indices) s = add(s, mul(a.c[i], b.c[i]))
        return s
    }

    private fun length(v: IntervalValue): Iv = r1(dotSquare(v), ::sqrtRange)

    private fun requirePoint(v: Iv, what: String): Float {
        if (!v.isPoint) throw GlslUnsupported("$what must be a single value under interval evaluation, got $v")
        return v.lo
    }

    override fun lift(v: GlslValue): IntervalValue = IntervalValue(v.type, Array(v.c.size) { Iv.point(v.c[it]) })

    /** The interval `[lo, hi]` per component — a region of inputs, rather than one. */
    fun region(type: GlslType, lo: FloatArray, hi: FloatArray): IntervalValue =
        IntervalValue(type, Array(type.components) { Iv(lo[it], hi[it]) })

    override fun typeOf(v: IntervalValue): GlslType = v.type
    override fun zeros(type: GlslType) = IntervalValue(type, Array(type.components) { Iv.FALSE })
    override fun ones(type: GlslType) = IntervalValue(type, Array(type.components) { Iv.TRUE })
    override fun boolOf(b: Boolean) = IntervalValue(GlslType.BOOL, arrayOf(if (b) Iv.TRUE else Iv.FALSE))

    override fun asBool(v: IntervalValue): Boolean = when (truth(v)) {
        GlslTruth.TRUE -> true
        GlslTruth.FALSE -> false
        GlslTruth.UNKNOWN -> throw GlslUnsupported("an undecided condition reached a place that needs a definite one")
    }

    override fun truth(v: IntervalValue): GlslTruth {
        val x = v.c[0]
        return when {
            !x.nan && (x.hi < 0f || x.lo > 0f) -> GlslTruth.TRUE
            !x.nan && x.lo == 0f && x.hi == 0f -> GlslTruth.FALSE
            else -> GlslTruth.UNKNOWN
        }
    }

    override fun boolOfTruth(t: GlslTruth) = IntervalValue(GlslType.BOOL, arrayOf(when (t) {
        GlslTruth.TRUE -> Iv.TRUE
        GlslTruth.FALSE -> Iv.FALSE
        GlslTruth.UNKNOWN -> Iv.UNIT
    }))

    override fun equalTruth(a: IntervalValue, b: IntervalValue): GlslTruth {
        if (a.c.size != b.c.size) return GlslTruth.FALSE
        val each = a.c.indices.map { eq(a.c[it], b.c[it]) }
        return when {
            each.any { it == Iv.FALSE } -> GlslTruth.FALSE
            each.all { it == Iv.TRUE } -> GlslTruth.TRUE
            else -> GlslTruth.UNKNOWN
        }
    }

    override fun join(a: IntervalValue, b: IntervalValue): IntervalValue {
        if (a.type != b.type) throw GlslEvalError("cannot join ${a.type} with ${b.type}")
        return IntervalValue(a.type, Array(a.c.size) { a.c[it].hull(b.c[it]) })
    }

    override fun asInt(v: IntervalValue): Int = Math.round(requirePoint(v.c[0], "an index"))

    override fun negate(v: IntervalValue) = unary(v) { neg(it) }

    override fun binary(op: String, a: IntervalValue, b: IntervalValue): IntervalValue = when (op) {
        "+" -> pair(a, b) { x, y -> add(x, y) }
        "-" -> pair(a, b) { x, y -> sub(x, y) }
        "*" -> matmul(a, b)
        "/" -> if (a.type.base == GlslBase.INT && b.type.base == GlslBase.INT) {
            pair(a, b) { x, y -> k((requirePoint(x, "int /").toInt() / requirePoint(y, "int /").toInt()).toFloat()) }
        } else {
            pair(a, b) { x, y -> div(x, y) }
        }
        "%" -> pair(a, b) { x, y -> modIv(x, y) }
        "<" -> pairBool(a, b) { x, y -> less(x, y) }
        ">" -> pairBool(a, b) { x, y -> less(y, x) }
        "<=" -> pairBool(a, b) { x, y -> lessEq(x, y) }
        ">=" -> pairBool(a, b) { x, y -> lessEq(y, x) }
        "^^" -> boolOfTruth(truth(a) xor truth(b))
        else -> throw GlslUnsupported("operator '$op' has no interval rule")
    }

    private infix fun GlslTruth.xor(o: GlslTruth): GlslTruth =
        if (this == GlslTruth.UNKNOWN || o == GlslTruth.UNKNOWN) GlslTruth.UNKNOWN
        else if (this != o) GlslTruth.TRUE else GlslTruth.FALSE

    private fun modIv(x: Iv, y: Iv): Iv = sub(x, mul(y, floor(div(x, y))))

    override fun equalComponents(a: IntervalValue, b: IntervalValue): Boolean = when (equalTruth(a, b)) {
        GlslTruth.TRUE -> true
        GlslTruth.FALSE -> false
        GlslTruth.UNKNOWN -> throw GlslUnsupported("an undecided == reached a place that needs a definite one")
    }

    override fun construct(type: GlslType, args: List<IntervalValue>): IntervalValue {
        if (args.isEmpty()) throw GlslEvalError("$type() needs arguments")
        fun toInt(v: Iv): Iv = monotone(v) { it.toInt().toFloat() }
        if (args.size == 1 && args[0].type.isScalar) {
            val v = args[0].c[0]
            if (type.isMatrix) {
                val out = Array(type.components) { Iv.FALSE }
                for (i in 0 until type.rows) out[i * type.rows + i] = v
                return IntervalValue(type, out)
            }
            val broadcast = if (type.base == GlslBase.INT) toInt(v) else v
            return IntervalValue(type, Array(type.components) { broadcast })
        }
        if (args.size == 1 && args[0].type.isMatrix && type.isMatrix) {
            val src = args[0]
            val out = Array(type.components) { Iv.FALSE }
            for (col in 0 until type.cols) for (row in 0 until type.rows) {
                out[col * type.rows + row] = when {
                    col < src.type.cols && row < src.type.rows -> src.c[col * src.type.rows + row]
                    col == row -> Iv.TRUE
                    else -> Iv.FALSE
                }
            }
            return IntervalValue(type, out)
        }
        val flat = ArrayList<Iv>(type.components)
        args.forEach { a -> a.c.forEach { flat += it } }
        if (flat.size < type.components) throw GlslEvalError("$type(...) got ${flat.size} components, needs ${type.components}")
        return IntervalValue(type, Array(type.components) { if (type.base == GlslBase.INT) toInt(flat[it]) else flat[it] })
    }

    override fun swizzle(v: IntervalValue, field: String): IntervalValue {
        val idx = swizzleIndices(field, v.c.size)
        return IntervalValue(GlslType.of(v.type.base, idx.size), Array(idx.size) { v.c[idx[it]] })
    }

    override fun component(v: IntervalValue, index: Int) = IntervalValue(GlslType.of(v.type.base, 1), arrayOf(v.c[index]))

    override fun column(v: IntervalValue, index: Int): IntervalValue {
        val rows = v.type.rows
        return IntervalValue(GlslType.of(v.type.base, rows), Array(rows) { v.c[index * rows + it] })
    }

    override fun withComponents(v: IntervalValue, indices: IntArray, src: IntervalValue): IntervalValue {
        val next = v.c.copyOf()
        for (i in indices.indices) next[indices[i]] = src.c[if (src.c.size == 1) 0 else i]
        return IntervalValue(v.type, next)
    }

    override fun withComponent(v: IntervalValue, index: Int, src: IntervalValue): IntervalValue {
        val next = v.c.copyOf()
        next[index] = src.c[0]
        return IntervalValue(v.type, next)
    }

    override fun coerce(v: IntervalValue, type: GlslType): IntervalValue = when {
        v.type == type -> v
        v.type.components == type.components && v.type.cols == type.cols ->
            if (type.base == GlslBase.INT) unary(v) { x -> monotone(x) { it.toInt().toFloat() } }.let { IntervalValue(type, it.c) }
            else IntervalValue(type, v.c.copyOf())
        v.type.isScalar -> construct(type, listOf(v))
        else -> throw GlslEvalError("cannot store ${v.type} in a $type")
    }

    override fun callBuiltin(name: String, args: List<IntervalValue>, samplers: GlslSamplers): IntervalValue? {
        val a = args
        return when (name) {
            "radians" -> unary(a[0]) { x -> r1(x) { FnRangeMath.mul(it, FnRange.point(Math.PI / 180.0)) } }
            "degrees" -> unary(a[0]) { x -> r1(x) { FnRangeMath.mul(it, FnRange.point(180.0 / Math.PI)) } }
            "sin" -> unary(a[0]) { r1(it, FnRangeMath::sin) }
            "cos" -> unary(a[0]) { r1(it, FnRangeMath::cos) }
            "tanh" -> unary(a[0]) { r1(it, FnRangeMath::tanh) }
            "exp" -> unary(a[0]) { r1(it, FnRangeMath::exp) }
            "log" -> unary(a[0]) { r1(it, FnRangeMath::ln) }
            "sqrt" -> unary(a[0]) { r1(it, ::sqrtRange) }
            "atan" -> if (a.size == 2) pair(a[0], a[1]) { y, x -> r2(y, x, FnRangeMath::atan2) } else unary(a[0]) { r1(it, FnRangeMath::atan) }
            "pow" -> pair(a[0], a[1]) { x, y -> r2(x, y, FnRangeMath::pow) }
            "abs" -> unary(a[0]) { r1(it, FnRangeMath::abs) }
            "sign" -> unary(a[0]) { x -> monotone(x) { GlslMath.sign(it) } }
            "floor" -> unary(a[0]) { floor(it) }
            "ceil" -> unary(a[0]) { x -> monotone(x) { kotlin.math.ceil(it) } }
            "trunc" -> unary(a[0]) { x -> monotone(x) { kotlin.math.truncate(it) } }
            "round" -> unary(a[0]) { x -> monotone(x) { Math.round(it).toFloat() } }
            "fract" -> unary(a[0]) { fract(it) }
            "mod" -> pair(a[0], a[1]) { x, y -> modIv(x, y) }
            "min" -> pair(a[0], a[1]) { x, y -> minIv(x, y) }
            "max" -> pair(a[0], a[1]) { x, y -> maxIv(x, y) }
            "clamp" -> triple(a) { x, lo, hi -> clampIv(x, lo, hi) }
            "mix" -> if (a[2].type.base == GlslBase.BOOL) {
                val n = maxOf(a[0].c.size, a[1].c.size, a[2].c.size)
                IntervalValue(GlslType.of(GlslBase.FLOAT, n), Array(n) { i ->
                    select(a[2].c[if (a[2].c.size == 1) 0 else i], a[0].c[if (a[0].c.size == 1) 0 else i], a[1].c[if (a[1].c.size == 1) 0 else i])
                })
            } else {
                triple(a) { x, y, t -> mixIv(x, y, t) }
            }
            "step" -> pair(a[0], a[1]) { e, x -> select(less(x, e), Iv.TRUE, Iv.FALSE) }
            "smoothstep" -> triple(a) { e0, e1, x -> smoothstepIv(e0, e1, x) }
            "length" -> scalar(length(a[0]))
            "distance" -> scalar(length(pair(a[0], a[1]) { x, y -> sub(x, y) }))
            "dot" -> scalar(if (a[0] === a[1]) dotSquare(a[0]) else dot(a[0], a[1]))
            "normalize" -> { val len = length(a[0]); unary(a[0]) { div(it, len) } }
            "lessThan" -> pairBool(a[0], a[1]) { x, y -> less(x, y) }
            "lessThanEqual" -> pairBool(a[0], a[1]) { x, y -> lessEq(x, y) }
            "greaterThan" -> pairBool(a[0], a[1]) { x, y -> less(y, x) }
            "greaterThanEqual" -> pairBool(a[0], a[1]) { x, y -> lessEq(y, x) }
            "equal" -> pairBool(a[0], a[1]) { x, y -> eq(x, y) }
            "notEqual" -> pairBool(a[0], a[1]) { x, y -> not(eq(x, y)) }
            "any" -> boolOfTruth(a[0].c.fold(GlslTruth.FALSE) { acc, x -> or(acc, bitTruth(x)) })
            "all" -> boolOfTruth(a[0].c.fold(GlslTruth.TRUE) { acc, x -> and(acc, bitTruth(x)) })
            "not" -> unary(a[0]) { not(it) }.let { IntervalValue(GlslType.of(GlslBase.BOOL, it.c.size), it.c) }
            "isnan" -> IntervalValue(GlslType.of(GlslBase.BOOL, a[0].c.size), Array(a[0].c.size) { i ->
                val x = a[0].c[i]
                when { !x.nan -> Iv.FALSE; x.isEmpty -> Iv.TRUE; else -> Iv.UNIT }
            })
            "isinf" -> IntervalValue(GlslType.of(GlslBase.BOOL, a[0].c.size), Array(a[0].c.size) { i ->
                val x = a[0].c[i]
                when {
                    x.isEmpty || (x.lo.isFinite() && x.hi.isFinite()) -> Iv.FALSE
                    x.isPoint -> Iv.TRUE
                    else -> Iv.UNIT
                }
            })
            in GlslBuiltins.names -> throw GlslUnsupported("'$name' has no interval rule yet")
            else -> null
        }
    }

    /** `*` with the reference path's accumulation order: `s = 0; s += a * b` per term. */
    private fun matmul(a: IntervalValue, b: IntervalValue): IntervalValue {
        fun sum(n: Int, term: (Int) -> Iv): Iv { var s = k(0f); for (i in 0 until n) s = add(s, term(i)); return s }
        return when {
            a.type.isMatrix && b.type.isMatrix -> {
                if (a.type.cols != b.type.rows) throw GlslEvalError("${a.type} * ${b.type}")
                val rows = a.type.rows
                IntervalValue(GlslType.of(GlslBase.FLOAT, rows, b.type.cols), Array(rows * b.type.cols) { i ->
                    val col = i / rows; val row = i % rows
                    sum(a.type.cols) { kk -> mul(a.c[kk * rows + row], b.c[col * b.type.rows + kk]) }
                })
            }
            a.type.isMatrix && b.type.isScalar -> unary(a) { mul(it, b.c[0]) }
            a.type.isScalar && b.type.isMatrix -> unary(b) { mul(it, a.c[0]) }
            a.type.isMatrix -> {
                if (a.type.cols != b.c.size) throw GlslEvalError("${a.type} * ${b.type}")
                IntervalValue(GlslType.of(GlslBase.FLOAT, a.type.rows), Array(a.type.rows) { row ->
                    sum(a.type.cols) { kk -> mul(a.c[kk * a.type.rows + row], b.c[kk]) }
                })
            }
            b.type.isMatrix -> {
                if (b.type.rows != a.c.size) throw GlslEvalError("${a.type} * ${b.type}")
                IntervalValue(GlslType.of(GlslBase.FLOAT, b.type.cols), Array(b.type.cols) { col ->
                    sum(b.type.rows) { kk -> mul(a.c[kk], b.c[col * b.type.rows + kk]) }
                })
            }
            else -> pair(a, b) { x, y -> mul(x, y) }
        }
    }

    private fun dotSquare(v: IntervalValue): Iv {
        var s = k(0f)
        for (x in v.c) s = add(s, sq(x))
        return s
    }

    private fun bitTruth(x: Iv): GlslTruth = when {
        x == Iv.TRUE -> GlslTruth.TRUE
        x == Iv.FALSE -> GlslTruth.FALSE
        else -> GlslTruth.UNKNOWN
    }

    private fun or(a: GlslTruth, b: GlslTruth): GlslTruth = when {
        a == GlslTruth.TRUE || b == GlslTruth.TRUE -> GlslTruth.TRUE
        a == GlslTruth.FALSE && b == GlslTruth.FALSE -> GlslTruth.FALSE
        else -> GlslTruth.UNKNOWN
    }

    private fun and(a: GlslTruth, b: GlslTruth): GlslTruth = when {
        a == GlslTruth.FALSE || b == GlslTruth.FALSE -> GlslTruth.FALSE
        a == GlslTruth.TRUE && b == GlslTruth.TRUE -> GlslTruth.TRUE
        else -> GlslTruth.UNKNOWN
    }
}
