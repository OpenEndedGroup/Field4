package afield.stacksl.cpu

import afield.stdlib.AD
import afield.stdlib.ADTape
import kotlin.math.absoluteValue

/**
 * Reverse-mode automatic differentiation for the GLSL interpreter: `d(pixel)/d(literal)`.
 *
 * `GlslAdScalars` is a `GlslScalars` instance, so a shader differentiated here runs through the
 * interpreter's one copy of the control flow, over the same parse tree the `Float` reference path
 * walks. Nothing about a GLSL builtin's *meaning* is restated; what is added is a local partial
 * beside each operation.
 *
 * #### Reverse, not forward
 *
 * An image loss over a wire vector is many-inputs-one-output, which is reverse mode's shape:
 * one forward pass plus one backward sweep answers for every wire at once, so the cost does not
 * grow with the number of free parameters. The loss is a **sum over pixels**, so the gradient is a
 * sum of per-pixel backward sweeps, one short tape per pixel, thrown away before the next. That
 * bounds the tape: a 64x64 fit never holds more than one pixel's operations at a time, where a
 * whole-image tape would hold 4096 times as many.
 *
 * #### The primal is bit-identical to the `Float` path
 *
 * [AD] computes in `Double`, and a `Double` shader is the *wrong* shader. `GlslValue.kt`'s overview
 * sets out why: widening an intermediate makes the CPU result more accurate than the GPU's and
 * therefore a worse reference, and `fract` of a large product amplifies a last-bit difference to
 * O(1). On a hash-bearing program a `Double` primal would not be slightly different, it would be a
 * different image.
 *
 * So every node this file pushes stores its value **rounded to `Float`**, at exactly the points the
 * `Float` path rounds. Double rounding is safe for `+ - * /` and `sqrt`: `Double` has more than
 * twice `Float`'s significand, so rounding a `Double`-exact sum to `Float` gives the same answer as
 * the `Float` operation. The differentiable pass's primal image is therefore *the same image* the
 * reference path produces, which `StackSLGradientTest` asserts bit for bit over a named population,
 * while the adjoint is the derivative of the smooth relaxation, which is the quantity a fit needs.
 *
 * #### Derivative conventions
 *
 *  - a branch, a loop bound and an index carry **no** derivative: differentiated as taken, which
 *    is correct for the piecewise function away from the switch-over. `autodiff.kt` says the same.
 *  - `floor`, `ceil`, `trunc`, `round`, `roundEven`, `sign`, `step` and `int(...)` truncation
 *    carry **exactly zero** derivative. `fract` therefore carries derivative **one**, since it is
 *    `x - floor(x)`.
 *  - `abs`, `min` and `max` **split evenly at an exact tie**, which is what a central difference
 *    reports at a kink, so the analytic gradient and the finite-difference oracle agree there.
 *  - a comparison, `any`, `all`, `isnan` and `isinf` produce a derivative-free `bool`.
 *  - `texture()` is **refused**. A bilinear CPU texture fetch is differentiable in principle, but
 *    the two feedback gallery programs are already excluded from every asserted comparison in
 *    this backend, so a derivative through a sampler would have no oracle to check it against.
 *    `StackSLDifferentiabilityTest` counts it as unsupported rather than as zero.
 */

/** A GLSL value whose components are [AD] nodes on one tape: the differentiable [GlslValue]. */
internal class AdValue(val type: GlslType, val c: Array<AD>) {
    init {
        require(c.size == type.components) { "$type needs ${type.components} components, got ${c.size}" }
    }

    /** The primal, as the `Float` path would hold it — which is the point of [fl]. */
    fun primal(): GlslValue = GlslValue(type, FloatArray(c.size) { c[it].value.toFloat() })

    override fun toString(): String = "ad:" + primal().toString()
}

/** Round a `Double` to the nearest `Float` and back — where the `Float` path rounds, this rounds. */
private fun fl(v: Double): Double = v.toFloat().toDouble()

/**
 * The differentiable instance of [GlslScalars], over one [ADTape].
 *
 * One instance per tape, and one tape per pixel; the instance is cheap (it holds the tape and
 * nothing else) and is emphatically not thread-safe, exactly like `GlslInvocation`.
 */
internal class GlslAdScalars(val tape: ADTape) : GlslScalars<AdValue> {

    // -- tape primitives -----------------------------------------------------------------------

    /** A constant: no parents, so no derivative flows into it. */
    fun constant(v: Double): AD = tape.constant(fl(v))

    /** A free parameter — a wire. Its adjoint after a backward sweep is what a fit reads. */
    fun variable(v: Double): AD = tape.variable(fl(v))

    /** One node with one parent: `value` with `∂value/∂x = d`. */
    private fun node(x: AD, value: Double, d: Double): AD = AD(tape, tape.push(value, x.index, d, -1, 0.0))

    /** One node with two parents. */
    private fun node(x: AD, y: AD, value: Double, dx: Double, dy: Double): AD =
        AD(tape, tape.push(value, x.index, dx, y.index, dy))

    /**
     * A node whose value is rounded to `Float` — one node per `Float` operation, not two.
     *
     * Pushing the rounded value directly (rather than an exact node followed by a rounding node)
     * halves the tape and keeps the local partials those of the *unrounded* operation, which is
     * what the smooth relaxation's derivative is.
     */
    private fun fnode(x: AD, value: Double, d: Double): AD = node(x, fl(value), d)

    private fun fnode(x: AD, y: AD, value: Double, dx: Double, dy: Double): AD = node(x, y, fl(value), dx, dy)

    /** Derivative-free: a value the gradient must not flow through (`floor`, a comparison, `int`). */
    private fun frozen(value: Double): AD = tape.constant(fl(value))

    // -- Float-faithful elementary arithmetic --------------------------------------------------

    private fun add(a: AD, b: AD): AD = fnode(a, b, a.value + b.value, 1.0, 1.0)
    private fun sub(a: AD, b: AD): AD = fnode(a, b, a.value - b.value, 1.0, -1.0)
    private fun mul(a: AD, b: AD): AD = fnode(a, b, a.value * b.value, b.value, a.value)

    private fun div(a: AD, b: AD): AD {
        val inv = 1.0 / b.value
        return fnode(a, b, a.value * inv, inv, -a.value * inv * inv)
    }

    private fun neg(a: AD): AD = node(a, -a.value, -1.0)

    private fun mulK(a: AD, k: Double): AD = fnode(a, a.value * k, k)
    private fun subFromK(k: Double, a: AD): AD = fnode(a, k - a.value, -1.0)

    /** `floor`, as the `Float` path computes it, with the zero derivative its jumps require. */
    private fun floorAd(a: AD): AD = frozen(kotlin.math.floor(a.value))

    /** `mod(x, y) = x - y * floor(x / y)` — the same four `Float` roundings, in the same places. */
    private fun modAd(x: AD, y: AD): AD = sub(x, mul(y, floorAd(div(x, y))))

    /** `mix(x, y, a) = x * (1 - a) + y * a` — the spec's form, not the `x + a*(y-x)` rewrite. */
    private fun mixAd(x: AD, y: AD, a: AD): AD = add(mul(x, subFromK(1.0, a)), mul(y, a))

    /** `max`, splitting the derivative evenly at an exact tie, as `autodiff.kt` does. */
    private fun maxAd(a: AD, b: AD): AD = when {
        a.value > b.value -> node(a, b, a.value, 1.0, 0.0)
        a.value < b.value -> node(a, b, b.value, 0.0, 1.0)
        else -> node(a, b, a.value, 0.5, 0.5)
    }

    private fun minAd(a: AD, b: AD): AD = when {
        a.value < b.value -> node(a, b, a.value, 1.0, 0.0)
        a.value > b.value -> node(a, b, b.value, 0.0, 1.0)
        else -> node(a, b, a.value, 0.5, 0.5)
    }

    private fun clampAd(x: AD, lo: AD, hi: AD): AD = minAd(maxAd(x, lo), hi)

    private fun absAd(a: AD): AD =
        node(a, a.value.absoluteValue, if (a.value > 0.0) 1.0 else if (a.value < 0.0) -1.0 else 0.0)

    private fun sqrtAd(a: AD): AD {
        val root = kotlin.math.sqrt(a.value)
        return fnode(a, root, if (root == 0.0 || root.isNaN()) 0.0 else 0.5 / root)
    }

    private fun powAd(x: AD, y: AD): AD {
        val p = Math.pow(x.value, y.value)
        val dx = if (x.value == 0.0) 0.0 else y.value * Math.pow(x.value, y.value - 1.0)
        val dy = if (x.value > 0.0) p * kotlin.math.ln(x.value) else 0.0
        return fnode(x, y, p, guard(dx), guard(dy))
    }

    private fun guard(d: Double): Double = if (d.isNaN() || d.isInfinite()) 0.0 else d

    /** `smoothstep(e0, e1, x)`: clamp then `t*t*(3 - 2t)`, operation for operation. */
    private fun smoothstepAd(e0: AD, e1: AD, x: AD): AD {
        val t = clampAd(div(sub(x, e0), sub(e1, e0)), constant(0.0), constant(1.0))
        return mul(mul(t, t), subFromK(3.0, mulK(t, 2.0)))
    }

    // -- GlslScalars -----------------------------------------------------------------------------

    override fun lift(v: GlslValue): AdValue =
        AdValue(v.type, Array(v.c.size) { constant(v.c[it].toDouble()) })

    /** A wire: the free parameters a fit descends over. */
    fun liftVariables(v: GlslValue): AdValue =
        AdValue(v.type, Array(v.c.size) { variable(v.c[it].toDouble()) })

    override fun typeOf(v: AdValue): GlslType = v.type

    override fun zeros(type: GlslType): AdValue = AdValue(type, Array(type.components) { constant(0.0) })

    override fun ones(type: GlslType): AdValue = AdValue(type, Array(type.components) { constant(1.0) })

    override fun boolOf(b: Boolean): AdValue =
        AdValue(GlslType.BOOL, arrayOf(frozen(if (b) 1.0 else 0.0)))

    override fun asBool(v: AdValue): Boolean = v.c[0].value != 0.0

    override fun asInt(v: AdValue): Int = Math.round(v.c[0].value.toFloat())

    override fun negate(v: AdValue): AdValue = AdValue(v.type, Array(v.c.size) { neg(v.c[it]) })

    override fun binary(op: String, a: AdValue, b: AdValue): AdValue = when (op) {
        "+" -> pair(a, b) { x, y -> add(x, y) }
        "-" -> pair(a, b) { x, y -> sub(x, y) }
        "*" -> matmul(a, b)
        "/" -> if (a.type.base == GlslBase.INT && b.type.base == GlslBase.INT) {
            pair(a, b) { x, y -> frozen((x.value.toInt() / y.value.toInt()).toDouble()) }
        } else {
            pair(a, b) { x, y -> div(x, y) }
        }

        "%" -> pair(a, b) { x, y -> modAd(x, y) }
        "^^" -> boolOf(asBool(a) != asBool(b))
        "<" -> boolOf(a.c[0].value < b.c[0].value)
        ">" -> boolOf(a.c[0].value > b.c[0].value)
        "<=" -> boolOf(a.c[0].value <= b.c[0].value)
        ">=" -> boolOf(a.c[0].value >= b.c[0].value)
        "&", "|", "^", "<<", ">>" -> throw GlslUnsupported(
            "bit operator '$op' has no derivative — differentiating a program that packs bits " +
                "(toBits16, fromBits16) is not supported"
        )

        else -> throw GlslUnsupported("binary operator '$op' is not in the StackSL subset")
    }

    override fun equalComponents(a: AdValue, b: AdValue): Boolean {
        if (a.c.size != b.c.size) return false
        return a.c.indices.all { a.c[it].value.toFloat() == b.c[it].value.toFloat() }
    }

    override fun construct(type: GlslType, args: List<AdValue>): AdValue {
        if (args.isEmpty()) throw GlslEvalError("$type() needs arguments")
        if (args.size == 1 && args[0].type.isScalar) {
            val v = args[0].c[0]
            if (type.isMatrix) {
                val out = Array(type.components) { constant(0.0) }
                for (i in 0 until type.rows) out[i * type.rows + i] = v
                return AdValue(type, out)
            }
            val broadcast = if (type.base == GlslBase.INT) truncateToInt(v) else v
            return AdValue(type, Array(type.components) { broadcast })
        }
        if (args.size == 1 && args[0].type.isMatrix && type.isMatrix) {
            val src = args[0]
            val out = Array(type.components) { constant(0.0) }
            for (col in 0 until type.cols) for (row in 0 until type.rows) {
                out[col * type.rows + row] = when {
                    col < src.type.cols && row < src.type.rows -> src.c[col * src.type.rows + row]
                    col == row -> constant(1.0)
                    else -> constant(0.0)
                }
            }
            return AdValue(type, out)
        }
        val flat = ArrayList<AD>(type.components)
        args.forEach { a -> a.c.forEach { flat += it } }
        if (flat.size < type.components) {
            throw GlslEvalError("$type(...) got ${flat.size} components, needs ${type.components}")
        }
        return AdValue(type, Array(type.components) {
            if (type.base == GlslBase.INT) truncateToInt(flat[it]) else flat[it]
        })
    }

    /** GLSL's float-to-int conversion truncates towards zero, and quantisation has no derivative. */
    private fun truncateToInt(v: AD): AD = frozen(v.value.toFloat().toInt().toDouble())

    override fun swizzle(v: AdValue, field: String): AdValue {
        val idx = swizzleIndices(field, v.c.size)
        return AdValue(GlslType.of(v.type.base, idx.size), Array(idx.size) { v.c[idx[it]] })
    }

    override fun component(v: AdValue, index: Int): AdValue =
        AdValue(GlslType.of(v.type.base, 1), arrayOf(v.c[index]))

    override fun column(v: AdValue, index: Int): AdValue {
        val rows = v.type.rows
        return AdValue(GlslType.of(v.type.base, rows), Array(rows) { v.c[index * rows + it] })
    }

    override fun withComponents(v: AdValue, indices: IntArray, src: AdValue): AdValue {
        val next = v.c.copyOf()
        for (i in indices.indices) next[indices[i]] = src.c[if (src.c.size == 1) 0 else i]
        return AdValue(v.type, next)
    }

    override fun withComponent(v: AdValue, index: Int, src: AdValue): AdValue {
        val next = v.c.copyOf()
        next[index] = src.c[0]
        return AdValue(v.type, next)
    }

    override fun coerce(v: AdValue, type: GlslType): AdValue = when {
        v.type == type -> v
        v.type.components == type.components && v.type.cols == type.cols ->
            if (type.base == GlslBase.INT) AdValue(type, Array(v.c.size) { truncateToInt(v.c[it]) })
            else AdValue(type, v.c.copyOf())

        v.type.isScalar -> construct(type, listOf(v))
        else -> throw GlslEvalError("cannot store ${v.type} in a $type")
    }

    override fun callBuiltin(name: String, args: List<AdValue>, samplers: GlslSamplers): AdValue? =
        builtin(name, args)

    private fun builtin(name: String, a: List<AdValue>): AdValue? = when (name) {
        "radians" -> unary(a[0]) { x -> fnode(x, x.value * Math.PI / 180.0, Math.PI / 180.0) }
        "degrees" -> unary(a[0]) { x -> fnode(x, x.value * 180.0 / Math.PI, 180.0 / Math.PI) }
        "sin" -> unary(a[0]) { x -> fnode(x, kotlin.math.sin(x.value), kotlin.math.cos(x.value)) }
        "cos" -> unary(a[0]) { x -> fnode(x, kotlin.math.cos(x.value), -kotlin.math.sin(x.value)) }
        "tan" -> unary(a[0]) { x ->
            val t = kotlin.math.tan(x.value)
            fnode(x, t, 1.0 + t * t)
        }

        "asin" -> unary(a[0]) { x ->
            fnode(x, kotlin.math.asin(x.value), guard(1.0 / kotlin.math.sqrt(1.0 - x.value * x.value)))
        }

        "acos" -> unary(a[0]) { x ->
            fnode(x, kotlin.math.acos(x.value), guard(-1.0 / kotlin.math.sqrt(1.0 - x.value * x.value)))
        }

        "atan" -> if (a.size == 2) {
            pair(a[0], a[1]) { y, x ->
                val d = x.value * x.value + y.value * y.value
                fnode(y, x, kotlin.math.atan2(y.value, x.value), guard(x.value / d), guard(-y.value / d))
            }
        } else {
            unary(a[0]) { x -> fnode(x, kotlin.math.atan(x.value), 1.0 / (1.0 + x.value * x.value)) }
        }

        "sinh" -> unary(a[0]) { x -> fnode(x, kotlin.math.sinh(x.value), kotlin.math.cosh(x.value)) }
        "cosh" -> unary(a[0]) { x -> fnode(x, kotlin.math.cosh(x.value), kotlin.math.sinh(x.value)) }
        "tanh" -> unary(a[0]) { x ->
            val t = kotlin.math.tanh(x.value)
            fnode(x, t, 1.0 - t * t)
        }

        "pow" -> pair(a[0], a[1]) { x, y -> powAd(x, y) }
        "exp" -> unary(a[0]) { x ->
            val e = kotlin.math.exp(x.value)
            fnode(x, e, e)
        }

        "log" -> unary(a[0]) { x -> fnode(x, kotlin.math.ln(x.value), guard(1.0 / x.value)) }
        "exp2" -> unary(a[0]) { x ->
            val e = Math.pow(2.0, x.value)
            fnode(x, e, e * LN2)
        }

        "log2" -> unary(a[0]) { x -> fnode(x, kotlin.math.log2(x.value), guard(1.0 / (x.value * LN2))) }
        "sqrt" -> unary(a[0]) { x -> sqrtAd(x) }
        "inversesqrt" -> unary(a[0]) { x ->
            val r = 1.0 / kotlin.math.sqrt(x.value)
            fnode(x, r, guard(-0.5 * r / x.value))
        }

        "abs" -> unary(a[0]) { x -> absAd(x) }
        "sign" -> unary(a[0]) { x -> frozen(kotlin.math.sign(x.value)) }
        "floor" -> unary(a[0]) { x -> floorAd(x) }
        "trunc" -> unary(a[0]) { x -> frozen(x.value.toFloat().let { if (it < 0f) kotlin.math.ceil(it) else kotlin.math.floor(it) }.toDouble()) }
        "round" -> unary(a[0]) { x -> frozen(Math.round(x.value.toFloat()).toDouble()) }
        "roundEven" -> unary(a[0]) { x -> frozen(Math.rint(x.value)) }
        "ceil" -> unary(a[0]) { x -> frozen(kotlin.math.ceil(x.value)) }
        "fract" -> unary(a[0]) { x -> sub(x, floorAd(x)) }
        "mod" -> pair(a[0], a[1]) { x, y -> modAd(x, y) }
        "min" -> pair(a[0], a[1]) { x, y -> minAd(x, y) }
        "max" -> pair(a[0], a[1]) { x, y -> maxAd(x, y) }
        "clamp" -> triple(a) { x, lo, hi -> clampAd(x, lo, hi) }

        // `mix(x, y, bvec)` is a SELECT, not an interpolation, and `_safeDiv` depends on it.
        "mix" -> if (a[2].type.base == GlslBase.BOOL) {
            val n = maxOf(a[0].c.size, a[1].c.size, a[2].c.size)
            AdValue(GlslType.of(GlslBase.FLOAT, n), Array(n) { i ->
                val pick = a[2].c[if (a[2].c.size == 1) 0 else i].value != 0.0
                val src = if (pick) a[1] else a[0]
                src.c[if (src.c.size == 1) 0 else i]
            })
        } else {
            triple(a) { x, y, t -> mixAd(x, y, t) }
        }

        "step" -> pair(a[0], a[1]) { e, x -> frozen(if (x.value < e.value) 0.0 else 1.0) }
        "smoothstep" -> triple(a) { e0, e1, x -> smoothstepAd(e0, e1, x) }

        "length" -> scalar(lengthAd(a[0]))
        "distance" -> scalar(lengthAd(pair(a[0], a[1]) { x, y -> sub(x, y) }))
        "dot" -> scalar(dotAd(a[0], a[1]))
        "cross" -> {
            val (p, q) = a
            AdValue(
                GlslType.VEC3,
                arrayOf(
                    sub(mul(p.c[1], q.c[2]), mul(p.c[2], q.c[1])),
                    sub(mul(p.c[2], q.c[0]), mul(p.c[0], q.c[2])),
                    sub(mul(p.c[0], q.c[1]), mul(p.c[1], q.c[0]))
                )
            )
        }

        "normalize" -> {
            val len = lengthAd(a[0])
            AdValue(a[0].type, Array(a[0].c.size) { div(a[0].c[it], len) })
        }

        "faceforward" -> if (dotAd(a[2], a[1]).value < 0.0) a[0] else negate(a[0])
        "reflect" -> {
            val d = dotAd(a[0], a[1])
            pair(a[0], a[1]) { i, n -> sub(i, mul(mulK(d, 2.0), n)) }
        }

        "refract" -> {
            val eta = a[2].c[0]
            val dotni = dotAd(a[1], a[0])
            val k = sub(constant(1.0), mul(mul(eta, eta), subFromK(1.0, mul(dotni, dotni))))
            if (k.value < 0.0) zeros(a[0].type) else {
                val sk = sqrtAd(k)
                pair(a[0], a[1]) { i, n -> sub(mul(eta, i), mul(add(mul(eta, dotni), sk), n)) }
            }
        }

        "lessThan" -> boolPair(a[0], a[1]) { x, y -> x < y }
        "lessThanEqual" -> boolPair(a[0], a[1]) { x, y -> x <= y }
        "greaterThan" -> boolPair(a[0], a[1]) { x, y -> x > y }
        "greaterThanEqual" -> boolPair(a[0], a[1]) { x, y -> x >= y }
        "equal" -> boolPair(a[0], a[1]) { x, y -> x == y }
        "notEqual" -> boolPair(a[0], a[1]) { x, y -> x != y }
        "any" -> boolOf(a[0].c.any { it.value != 0.0 })
        "all" -> boolOf(a[0].c.all { it.value != 0.0 })
        "not" -> AdValue(
            GlslType.of(GlslBase.BOOL, a[0].c.size),
            Array(a[0].c.size) { frozen(if (a[0].c[it].value == 0.0) 1.0 else 0.0) }
        )

        "isnan" -> AdValue(
            GlslType.of(GlslBase.BOOL, a[0].c.size),
            Array(a[0].c.size) { frozen(if (a[0].c[it].value.isNaN()) 1.0 else 0.0) }
        )

        "isinf" -> AdValue(
            GlslType.of(GlslBase.BOOL, a[0].c.size),
            Array(a[0].c.size) { frozen(if (a[0].c[it].value.isInfinite()) 1.0 else 0.0) }
        )

        "matrixCompMult" -> pair(a[0], a[1]) { x, y -> mul(x, y) }
        "transpose" -> {
            val m = a[0]
            val out = Array(m.c.size) { constant(0.0) }
            for (col in 0 until m.type.cols) for (row in 0 until m.type.rows) {
                out[row * m.type.cols + col] = m.c[col * m.type.rows + row]
            }
            AdValue(GlslType.of(GlslBase.FLOAT, m.type.cols, m.type.rows), out)
        }

        "texture", "textureLod", "textureSize", "textureGather" -> throw GlslUnsupported(
            "'$name' has no derivative in this backend: a CPU texture fetch is differentiable in " +
                "principle, but the feedback programs it serves are excluded from every asserted " +
                "comparison here, so there would be no oracle for the answer"
        )

        else -> null
    }

    // -- shape plumbing, mirroring GlslValue.kt's -----------------------------------------------

    private inline fun unary(v: AdValue, op: (AD) -> AD): AdValue =
        AdValue(v.type, Array(v.c.size) { op(v.c[it]) })

    private inline fun pair(a: AdValue, b: AdValue, op: (AD, AD) -> AD): AdValue {
        val an = a.c.size
        val bn = b.c.size
        val type = if (an >= bn) a.type else b.type
        val n = maxOf(an, bn)
        if (an != bn && an != 1 && bn != 1) {
            throw GlslEvalError("cannot combine ${a.type} with ${b.type} component-wise")
        }
        return AdValue(type, Array(n) { i -> op(a.c[if (an == 1) 0 else i], b.c[if (bn == 1) 0 else i]) })
    }

    private inline fun triple(a: List<AdValue>, op: (AD, AD, AD) -> AD): AdValue {
        val n = maxOf(a[0].c.size, a[1].c.size, a[2].c.size)
        val type = listOf(a[0].type, a[1].type, a[2].type).maxByOrNull { it.components }!!
        val out = Array(n) { i ->
            op(
                a[0].c[if (a[0].c.size == 1) 0 else i],
                a[1].c[if (a[1].c.size == 1) 0 else i],
                a[2].c[if (a[2].c.size == 1) 0 else i]
            )
        }
        return AdValue(if (type.components == n) type else GlslType.of(GlslBase.FLOAT, n), out)
    }

    private inline fun boolPair(a: AdValue, b: AdValue, op: (Double, Double) -> Boolean): AdValue {
        val n = maxOf(a.c.size, b.c.size)
        return AdValue(GlslType.of(GlslBase.BOOL, n), Array(n) { i ->
            val x = a.c[if (a.c.size == 1) 0 else i].value
            val y = b.c[if (b.c.size == 1) 0 else i].value
            frozen(if (op(x, y)) 1.0 else 0.0)
        })
    }

    private fun scalar(v: AD): AdValue = AdValue(GlslType.FLOAT, arrayOf(v))

    /** `dot` accumulates in `Float`, one rounding per multiply and one per add. */
    private fun dotAd(a: AdValue, b: AdValue): AD {
        if (a.c.size != b.c.size) throw GlslEvalError("dot(${a.type}, ${b.type})")
        var s = constant(0.0)
        for (i in a.c.indices) s = add(s, mul(a.c[i], b.c[i]))
        return s
    }

    private fun lengthAd(v: AdValue): AD = sqrtAd(dotAd(v, v))

    /** `*`: matrix times vector, matrix times matrix, and the broadcasts `*` also has to cover. */
    private fun matmul(a: AdValue, b: AdValue): AdValue = when {
        a.type.isMatrix && b.type.isMatrix -> {
            if (a.type.cols != b.type.rows) throw GlslEvalError("${a.type} * ${b.type}")
            val rows = a.type.rows
            val cols = b.type.cols
            val out = Array(rows * cols) { constant(0.0) }
            for (col in 0 until cols) for (row in 0 until rows) {
                var s = constant(0.0)
                for (k in 0 until a.type.cols) {
                    s = add(s, mul(a.c[k * rows + row], b.c[col * b.type.rows + k]))
                }
                out[col * rows + row] = s
            }
            AdValue(GlslType.of(GlslBase.FLOAT, rows, cols), out)
        }

        a.type.isMatrix && b.type.isScalar -> unary(a) { mul(it, b.c[0]) }
        a.type.isScalar && b.type.isMatrix -> unary(b) { mul(it, a.c[0]) }

        a.type.isMatrix -> {
            if (a.type.cols != b.c.size) throw GlslEvalError("${a.type} * ${b.type}")
            val out = Array(a.type.rows) { constant(0.0) }
            for (row in 0 until a.type.rows) {
                var s = constant(0.0)
                for (k in 0 until a.type.cols) s = add(s, mul(a.c[k * a.type.rows + row], b.c[k]))
                out[row] = s
            }
            AdValue(GlslType.of(GlslBase.FLOAT, a.type.rows), out)
        }

        b.type.isMatrix -> {
            if (b.type.rows != a.c.size) throw GlslEvalError("${a.type} * ${b.type}")
            val out = Array(b.type.cols) { constant(0.0) }
            for (col in 0 until b.type.cols) {
                var s = constant(0.0)
                for (k in 0 until b.type.rows) s = add(s, mul(a.c[k], b.c[col * b.type.rows + k]))
                out[col] = s
            }
            AdValue(GlslType.of(GlslBase.FLOAT, b.type.cols), out)
        }

        else -> pair(a, b) { x, y -> mul(x, y) }
    }

    private companion object {
        val LN2 = kotlin.math.ln(2.0)
    }
}
