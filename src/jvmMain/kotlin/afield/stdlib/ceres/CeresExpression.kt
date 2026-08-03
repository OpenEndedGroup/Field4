@file:afield.CompletionCategory("Graphics")

package afield.stdlib.ceres

import kotlin.math.PI

/**
 * Residuals written in Kotlin, compiled to a little register machine, and differentiated exactly by
 * Ceres.
 *
 * Every other constraint in this vocabulary is a C++ functor with a matching opcode, which means a
 * new kind of residual costs a native rebuild, a Kotlin change, and a redeployed dylib. This is the
 * way out: build an expression here, [CeresExpressionResidual] encodes it, and the native `EXPR`
 * opcode evaluates it. The evaluator is templated on the scalar type, so Ceres runs it on
 * `ceres::Jet` and gets **analytic derivatives** — not finite differences — for a residual it has
 * never seen.
 *
 * ```kotlin
 * // "these two points are exactly 2.5 apart", written from scratch
 * val program = buildCeresProgram {
 *     expression(a, b) { (pa, pb) ->
 *         listOf((pa - pb).length() - 2.5.c)
 *     }
 * }
 * ```
 *
 * ## What it costs
 *
 * A hand-written functor is faster: the VM interprets, allocates a slot vector per evaluation, and
 * Ceres cannot specialise the parameter-block sizes the way [ceres::AutoDiffCostFunction] does with
 * a fixed arity. Reach for a real opcode when a residual is hot and settled; reach for this while
 * you are still finding out what the residual should be, or when it is caller-specific and will
 * never belong in the wrapper.
 *
 * ## What to watch
 *
 * - [abs], [min] and [max] are not differentiable at their kinks. Ceres will hand you a subgradient
 *   there, which is usually what you want for a one-sided barrier and is not what you want in the
 *   middle of a smooth objective.
 * - Nothing checks for division by zero or a square root of a negative. A residual that produces a
 *   NaN will make the solve fail rather than corrupt anything, but it will not tell you where.
 * - Ceres forbids one residual block naming the same point twice, so [expression] rejects a
 *   repeated point rather than letting the native side discover it.
 *
 * ## Sharing
 *
 * The expression is a DAG, not a tree: [CeresExpr] instances are hash-consed as you build them, so
 * writing `d / d` evaluates `d` once. That is why the machine is register-based — with named
 * destination slots, sharing is free.
 */
sealed interface CeresExpr {
    operator fun plus(other: CeresExpr): CeresExpr = binary(ExprOp.ADD, this, other)
    operator fun minus(other: CeresExpr): CeresExpr = binary(ExprOp.SUB, this, other)
    operator fun times(other: CeresExpr): CeresExpr = binary(ExprOp.MUL, this, other)
    operator fun div(other: CeresExpr): CeresExpr = binary(ExprOp.DIV, this, other)
    operator fun unaryMinus(): CeresExpr = unary(ExprOp.NEG, this)

    operator fun plus(other: Double): CeresExpr = this + constant(other)
    operator fun minus(other: Double): CeresExpr = this - constant(other)
    operator fun times(other: Double): CeresExpr = this * constant(other)
    operator fun div(other: Double): CeresExpr = this / constant(other)
}

internal enum class ExprOp(val code: Int, val arity: Int) {
    CONST(0, 0),
    PARAM(1, 0),
    ADD(2, 2),
    SUB(3, 2),
    MUL(4, 2),
    DIV(5, 2),
    NEG(6, 1),
    SQRT(7, 1),
    ABS(8, 1),
    SIN(9, 1),
    COS(10, 1),
    TAN(11, 1),
    EXP(12, 1),
    LOG(13, 1),
    POW(14, 2),
    MIN(15, 2),
    MAX(16, 2),
    ATAN2(17, 2),
    SQUARE(18, 1),
    RECIP(19, 1),
}

internal data class ExprConst(val value: Double) : CeresExpr {
    init {
        require(value.isFinite()) { "expression constants must be finite" }
    }
}

/** Component [component] (0=x, 1=y, 2=z) of the expression's [block]-th point. */
internal data class ExprParam(val block: Int, val component: Int) : CeresExpr {
    init {
        require(block >= 0) { "parameter block index must be non-negative" }
        require(component in 0..2) { "component must be 0, 1 or 2" }
    }
}

internal data class ExprNode(val op: ExprOp, val a: CeresExpr, val b: CeresExpr?) : CeresExpr

private fun binary(op: ExprOp, a: CeresExpr, b: CeresExpr): CeresExpr = ExprNode(op, a, b)
private fun unary(op: ExprOp, a: CeresExpr): CeresExpr = ExprNode(op, a, null)

/** A literal. `2.5.c` reads better than `constant(2.5)` inside an expression. */
fun constant(value: Double): CeresExpr = ExprConst(value)

/** A literal, as a property. */
val Double.c: CeresExpr get() = ExprConst(this)

/** A literal, as a property. */
val Int.c: CeresExpr get() = ExprConst(toDouble())

fun sqrt(x: CeresExpr): CeresExpr = unary(ExprOp.SQRT, x)
fun square(x: CeresExpr): CeresExpr = unary(ExprOp.SQUARE, x)
fun reciprocal(x: CeresExpr): CeresExpr = unary(ExprOp.RECIP, x)
fun exp(x: CeresExpr): CeresExpr = unary(ExprOp.EXP, x)
fun ln(x: CeresExpr): CeresExpr = unary(ExprOp.LOG, x)
fun sin(x: CeresExpr): CeresExpr = unary(ExprOp.SIN, x)
fun cos(x: CeresExpr): CeresExpr = unary(ExprOp.COS, x)
fun tan(x: CeresExpr): CeresExpr = unary(ExprOp.TAN, x)

/** Not differentiable at zero; Ceres will use a subgradient. */
fun abs(x: CeresExpr): CeresExpr = unary(ExprOp.ABS, x)

/** Not differentiable where the arguments cross. */
fun min(a: CeresExpr, b: CeresExpr): CeresExpr = binary(ExprOp.MIN, a, b)

/** Not differentiable where the arguments cross. The building block of a one-sided barrier. */
fun max(a: CeresExpr, b: CeresExpr): CeresExpr = binary(ExprOp.MAX, a, b)

fun pow(base: CeresExpr, exponent: CeresExpr): CeresExpr = binary(ExprOp.POW, base, exponent)
fun atan2(y: CeresExpr, x: CeresExpr): CeresExpr = binary(ExprOp.ATAN2, y, x)

/** `max(0, floor - x)`: zero while `x >= floor`, and a penalty below it. */
fun barrierBelow(x: CeresExpr, floor: CeresExpr): CeresExpr = max(0.0.c, floor - x)

/** `max(0, x - ceiling)`: zero while `x <= ceiling`, and a penalty above it. */
fun barrierAbove(x: CeresExpr, ceiling: CeresExpr): CeresExpr = max(0.0.c, x - ceiling)

/** One of the expression's points, as three scalar expressions. */
data class CeresExprVec(val x: CeresExpr, val y: CeresExpr, val z: CeresExpr) {
    operator fun plus(o: CeresExprVec) = CeresExprVec(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: CeresExprVec) = CeresExprVec(x - o.x, y - o.y, z - o.z)
    operator fun times(k: CeresExpr) = CeresExprVec(x * k, y * k, z * k)
    operator fun times(k: Double) = times(k.c)
    operator fun div(k: CeresExpr) = CeresExprVec(x / k, y / k, z / k)
    operator fun unaryMinus() = CeresExprVec(-x, -y, -z)

    infix fun dot(o: CeresExprVec): CeresExpr = x * o.x + y * o.y + z * o.z

    infix fun cross(o: CeresExprVec) = CeresExprVec(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    /** Squared length. Prefer this to `length()` when you only need to compare. */
    fun lengthSquared(): CeresExpr = this dot this

    /**
     * Length, with [epsilon] added under the root so the derivative stays finite at the origin.
     * A plain `sqrt(0)` has an infinite derivative and will poison the Jacobian.
     */
    fun length(epsilon: Double = 1e-20): CeresExpr = sqrt(lengthSquared() + epsilon.c)

    fun distanceTo(o: CeresExprVec, epsilon: Double = 1e-20): CeresExpr = (this - o).length(epsilon)

    companion object {
        val ORIGIN = CeresExprVec(0.0.c, 0.0.c, 0.0.c)

        fun of(v: Vec3) = CeresExprVec(v.x.c, v.y.c, v.z.c)
    }
}

/** Handy when an expression wants a literal angle. */
const val CERES_PI: Double = PI

/**
 * A residual block whose value is computed by [expressions], one residual each, over [points].
 *
 * Build one with [CeresProgramBuilder.expression] rather than directly — that hands you the point
 * expressions already bound.
 */
data class CeresExpressionResidual(
    val points: List<PointId>,
    val expressions: List<CeresExpr>,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        require(points.isNotEmpty()) { "an expression residual needs at least one point" }
        requirePointList(points, 1, allowAll = false)
        require(points.distinct().size == points.size) {
            "Ceres forbids one residual block naming the same point twice; points were $points"
        }
        require(expressions.isNotEmpty()) { "an expression residual needs at least one residual" }
        requireWeight(weight)
    }

    override val requiredFeature = CeresFeature.AbiV2

    override fun encodeLegacy(out: MutableList<Double>) {
        val compiled = compile()
        out.op(CeresOpcode.Expr)
        out += points.size.toDouble()
        points.forEach { out += it.value.toDouble() }
        out += compiled.residualSlots.size.toDouble()
        compiled.residualSlots.forEach { out += it.toDouble() }
        out += compiled.slotCount.toDouble()
        out += compiled.constants.size.toDouble()
        compiled.constants.forEach { out += it }
        out += (compiled.code.size / 4).toDouble()
        compiled.code.forEach { out += it.toDouble() }
        out += weight
    }

    /** The compiled program, exposed so a caller can see what their expression became. */
    fun compile(): CompiledCeresExpression = CeresExpressionCompiler(points.size).compile(expressions)
}

/** The register-machine program an expression compiles to. */
data class CompiledCeresExpression(
    /** Four ints per instruction: `op, dst, a, b`. */
    val code: List<Int>,
    val constants: List<Double>,
    val residualSlots: List<Int>,
    val slotCount: Int,
) {
    val instructionCount: Int get() = code.size / 4

    /** A readable disassembly. The point of an expression VM is that you can look at the program. */
    fun disassemble(): String = buildString {
        for (i in 0 until instructionCount) {
            val op = ExprOp.entries.first { it.code == code[4 * i] }
            val dst = code[4 * i + 1]
            val a = code[4 * i + 2]
            val b = code[4 * i + 3]
            append("s%-3d = ".format(dst))
            when (op) {
                ExprOp.CONST -> append("${constants[a]}")
                ExprOp.PARAM -> append("p$a.${"xyz"[b]}")
                else -> when (op.arity) {
                    1 -> append("${op.name.lowercase()}(s$a)")
                    else -> append("${op.name.lowercase()}(s$a, s$b)")
                }
            }
            appendLine()
        }
        residualSlots.forEachIndexed { at, slot -> appendLine("r$at  = s$slot") }
    }
}

/**
 * Flattens an expression DAG into register-machine instructions.
 *
 * Nodes are memoised by structural identity, so a subexpression written twice — or reached twice
 * through the DAG — is computed once and read from its slot thereafter. Constants are pooled the
 * same way.
 */
private class CeresExpressionCompiler(private val paramCount: Int) {
    private val code = mutableListOf<Int>()
    private val constants = mutableListOf<Double>()
    private val constantSlots = mutableMapOf<Double, Int>()
    private val slots = mutableMapOf<CeresExpr, Int>()
    private var nextSlot = 0

    fun compile(expressions: List<CeresExpr>): CompiledCeresExpression {
        val residualSlots = expressions.map { emit(it) }
        return CompiledCeresExpression(code.toList(), constants.toList(), residualSlots, nextSlot)
    }

    private fun emit(expr: CeresExpr): Int = slots.getOrPut(expr) {
        when (expr) {
            is ExprConst -> {
                val index = constantSlots.getOrPut(expr.value) {
                    constants += expr.value
                    constants.size - 1
                }
                instruction(ExprOp.CONST, index, 0)
            }

            is ExprParam -> {
                require(expr.block < paramCount) {
                    "expression reads point ${expr.block} but only $paramCount were bound"
                }
                instruction(ExprOp.PARAM, expr.block, expr.component)
            }

            is ExprNode -> {
                // Operands first: a slot must be written before it is read.
                val a = emit(expr.a)
                val b = expr.b?.let { emit(it) } ?: 0
                instruction(expr.op, a, b)
            }
        }
    }

    private fun instruction(op: ExprOp, a: Int, b: Int): Int {
        val dst = nextSlot++
        code += op.code
        code += dst
        code += a
        code += b
        return dst
    }
}
