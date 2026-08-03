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
 *
 * ## Free variables
 *
 * An expression can also read *unknowns of its own* — quantities Ceres solves for that are not
 * points. [CeresProgramBuilder.variable] declares one, with an initial value and an optional
 * [CeresManifold], and hands back a [CeresVariableRef] that any number of expressions can name:
 *
 * ```kotlin
 * // fit a sphere: the centre and the radius are unknowns, not points
 * val centre = variable("centre", Vec3(0.0, 0.0, 0.0))
 * val radius = variable("radius", 1.0)
 * points.forEach { i ->
 *     expression(listOf(i), listOf(centre, radius)) { (p), (c, r) ->
 *         listOf(p.distanceTo(c.vec()) - r.scalar)
 *     }
 * }
 * ```
 *
 * This is the mechanism `PLANAR_HESSE` hardcodes in C++ — a plane as a unit normal on a
 * `SphereManifold` plus a scalar offset — made available to a residual written at runtime. It is
 * worth the machinery: on `kkC` the plane-as-a-free-variable formulation converges in 12 iterations
 * to tangency `3e-08` where the same objective with the plane eliminated inside the residual does
 * not converge at all. Well-conditioned auxiliary unknowns are not overhead; they are frequently
 * what makes a problem tractable.
 *
 * The solved values come back in [SolveResult.variables], in declaration order.
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

    /** A component of one of the residual's free variables. See [CeresVariableRef]. */
    VAR(20, 0),
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

/**
 * Component [component] of the residual's [block]-th free variable.
 *
 * [block] is the position in the list of variables handed to
 * [CeresProgramBuilder.expression] — *not* the program-wide declaration index. A residual block only
 * receives the parameter blocks it names, so the VM addresses them locally; [CeresVariableRef] holds
 * the program-wide index and the encoder writes that.
 */
internal data class ExprVar(val block: Int, val component: Int) : CeresExpr {
    init {
        require(block >= 0) { "variable index must be non-negative" }
        require(component >= 0) { "variable component must be non-negative" }
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

// ---------------------------------------------------------------------------------------------
// Free variables
// ---------------------------------------------------------------------------------------------

/**
 * The space a free variable lives in — which is to say, how Ceres is allowed to step it.
 *
 * A manifold is not decoration. `PLANAR_HESSE`'s whole advantage over `PLANAR` is that its plane
 * normal is on a [SPHERE]: three raw components, two effective parameters, and no freedom to change
 * length. Left as a plain 3-vector with a "keep it unit" residual, the same variable would carry a
 * scale direction that the objective barely constrains, and a trust-region minimiser will find it.
 */
enum class CeresManifold(val code: Int) {
    /** Euclidean: every component free, tangent space the same size as the block. */
    NONE(0),

    /** Unit norm (`ceres::SphereManifold`). Needs at least two components. */
    SPHERE(1),

    /**
     * A rotation as a unit quaternion, **`w` first** (`ceres::QuaternionManifold`). Exactly four
     * components, three effective parameters.
     */
    QUATERNION(2),
}

/**
 * An unknown Ceres solves for that is not a point: [dimension] scalars with an initial value and a
 * [manifold].
 *
 * Declare one with [CeresProgramBuilder.variable]. The [name] never crosses the ABI — the native
 * side is handed dimensions and initial values, and this side reconstructs names when it reads the
 * solved values back — so it costs nothing and is worth choosing well; it is what
 * [CompiledCeresExpression.disassemble] and [SolveResult.variables] label things with.
 */
data class CeresVariableDecl(
    val name: String,
    val initial: List<Double>,
    val manifold: CeresManifold = CeresManifold.NONE,
) {
    val dimension: Int get() = initial.size

    init {
        require(name.isNotBlank()) { "a free variable needs a name" }
        require(initial.isNotEmpty()) { "free variable '$name' needs at least one component" }
        require(initial.size <= MAX_DIMENSION) {
            "free variable '$name' has ${initial.size} components; the limit is $MAX_DIMENSION"
        }
        require(initial.all { it.isFinite() }) { "free variable '$name' has a non-finite initial value" }
        when (manifold) {
            CeresManifold.NONE -> Unit
            CeresManifold.SPHERE -> {
                require(initial.size >= 2) {
                    "free variable '$name' is on a sphere manifold, which needs at least two components"
                }
                require(initial.sumOf { it * it } > 1e-20) {
                    "free variable '$name' is on a sphere manifold and cannot start at the origin"
                }
            }

            CeresManifold.QUATERNION -> {
                require(initial.size == 4) {
                    "free variable '$name' is a quaternion, which needs exactly four components (w first)"
                }
                require(initial.sumOf { it * it } > 1e-20) {
                    "free variable '$name' is a quaternion and cannot start at zero"
                }
            }
        }
    }

    companion object {
        /**
         * A bound on a single block's size. Nothing here needs a hundred-component unknown, and the
         * native side allocates from this number, so it is checked rather than trusted.
         */
        const val MAX_DIMENSION: Int = 64

        fun scalar(name: String, initial: Double): CeresVariableDecl =
            CeresVariableDecl(name, listOf(initial))

        fun vector(name: String, initial: Vec3): CeresVariableDecl =
            CeresVariableDecl(name, listOf(initial.x, initial.y, initial.z))

        fun vector(name: String, vararg initial: Double): CeresVariableDecl =
            CeresVariableDecl(name, initial.toList())

        /** A direction on the unit sphere. The seed is normalised for you; it only has to be non-zero. */
        fun unitVector(name: String, initial: Vec3): CeresVariableDecl {
            val length = kotlin.math.sqrt(initial.x * initial.x + initial.y * initial.y + initial.z * initial.z)
            require(length > 1e-10) { "unit-vector variable '$name' needs a non-zero seed direction" }
            return CeresVariableDecl(
                name,
                listOf(initial.x / length, initial.y / length, initial.z / length),
                CeresManifold.SPHERE,
            )
        }

        /** A rotation, `w` first. Normalised for you. */
        fun quaternion(name: String, w: Double, x: Double, y: Double, z: Double): CeresVariableDecl {
            val length = kotlin.math.sqrt(w * w + x * x + y * y + z * z)
            require(length > 1e-10) { "quaternion variable '$name' needs a non-zero seed" }
            return CeresVariableDecl(
                name,
                listOf(w / length, x / length, y / length, z / length),
                CeresManifold.QUATERNION,
            )
        }

        /** The identity rotation. */
        fun rotation(name: String): CeresVariableDecl = quaternion(name, 1.0, 0.0, 0.0, 0.0)
    }
}

/**
 * A declared variable, as the program refers to it.
 *
 * Variables are declared at *program* scope rather than per residual, so several residual blocks can
 * share one — which is the difference between "each face gets its own plane" and "these fifty
 * observations all see the same camera". [index] is the program-wide declaration order, which is also
 * the order [SolveResult.variables] reports the solved values in.
 */
data class CeresVariableRef(val index: Int, val declaration: CeresVariableDecl) {
    init {
        require(index >= 0) { "variable index must be non-negative" }
    }

    val name: String get() = declaration.name
    val dimension: Int get() = declaration.dimension
}

/**
 * One of an expression's free variables, bound to its position in that residual's variable list.
 *
 * Distinct from [CeresVariableRef] on purpose: that one knows where the variable sits in the
 * *program*, this one knows where it sits in *this residual's* parameter blocks, and only the second
 * is what the VM addresses.
 */
class CeresExprVariable internal constructor(
    internal val block: Int,
    val declaration: CeresVariableDecl,
) {
    val name: String get() = declaration.name
    val dimension: Int get() = declaration.dimension

    /** Component [component] of this variable. */
    operator fun get(component: Int): CeresExpr {
        require(component in 0 until dimension) {
            "variable '$name' has $dimension component(s); asked for $component"
        }
        return ExprVar(block, component)
    }

    /** The variable itself, when it is a single scalar. */
    val scalar: CeresExpr
        get() {
            require(dimension == 1) { "variable '$name' has $dimension components, not one" }
            return get(0)
        }

    /** The first three components, as a vector. */
    fun vec(): CeresExprVec {
        require(dimension >= 3) { "variable '$name' has $dimension components; vec() needs three" }
        return CeresExprVec(get(0), get(1), get(2))
    }

    /**
     * A four-component variable read as a rotation, applied to [v] — the quaternion sandwich written
     * out, so no trigonometry and no normalisation appear in the residual.
     *
     * Pair it with [CeresManifold.QUATERNION] and Ceres keeps the block on the unit sphere itself;
     * with [CeresManifold.NONE] the rotation is only a rotation while the quaternion happens to be
     * unit, which is a thing to remember rather than a thing to rely on.
     */
    fun rotate(v: CeresExprVec): CeresExprVec {
        require(dimension == 4) { "variable '$name' has $dimension components; rotate() needs four" }
        val w = get(0)
        val u = CeresExprVec(get(1), get(2), get(3))
        // v + 2 * u x (u x v + w * v), the standard expansion of q v q*.
        val t = (u cross v) + v * w
        return v + (u cross t) * 2.0
    }
}

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
    /**
     * The free variables these expressions read, in the order the expressions' [CeresExprVariable]
     * handles were bound. Declared at program scope, so a variable named here may also be named by
     * other residuals.
     */
    val variables: List<CeresVariableRef> = emptyList(),
) : GeometryConstraint {
    init {
        // No points is legal as long as there are free variables. A fit — "what sphere passes through
        // this fixed data" — has no point unknowns at all: the data are constants in the expression
        // and every unknown is a free variable. Requiring a point would have forced such a residual to
        // name one it does not read, which is the zero-Jacobian-column defect this layer refuses
        // elsewhere.
        require(points.isNotEmpty() || variables.isNotEmpty()) {
            "an expression residual needs at least one point or one free variable"
        }
        requirePointList(points, 0, allowAll = false)
        require(points.distinct().size == points.size) {
            "Ceres forbids one residual block naming the same point twice; points were $points"
        }
        require(variables.map { it.index }.distinct().size == variables.size) {
            "Ceres forbids one residual block naming the same parameter block twice; variables were " +
                variables.map { it.name }
        }
        require(expressions.isNotEmpty()) { "an expression residual needs at least one residual" }
        requireWeight(weight)
        // Compiling here is what validates slots, components and variable dimensions, and it is
        // cheap. An expression that cannot compile should fail where it was written.
        compile()
    }

    /**
     * [CeresFeature.AbiV3], not v2. The `EXPR` operand stream now carries a variable-reference
     * section whether or not it is empty, and a v2 wrapper reading it would take the count as a
     * weight and everything after as opcodes — which is the silent-misparse failure mode the v2 work
     * removed. Gated in Kotlin so a caller gets a clear refusal rather than a plausible answer to a
     * different question.
     */
    override val requiredFeature = CeresFeature.AbiV3

    override fun encodeLegacy(out: MutableList<Double>) {
        val compiled = compile()
        out.op(CeresOpcode.Expr)
        out += points.size.toDouble()
        points.forEach { out += it.value.toDouble() }
        out += variables.size.toDouble()
        variables.forEach { out += it.index.toDouble() }
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
    fun compile(): CompiledCeresExpression =
        CeresExpressionCompiler(points.size, variables.map { it.declaration }).compile(expressions)
}

/** The register-machine program an expression compiles to. */
data class CompiledCeresExpression(
    /** Four ints per instruction: `op, dst, a, b`. */
    val code: List<Int>,
    val constants: List<Double>,
    val residualSlots: List<Int>,
    val slotCount: Int,
    /** The free variables this residual reads, in local (parameter-block) order. */
    val variables: List<CeresVariableDecl> = emptyList(),
) {
    val instructionCount: Int get() = code.size / 4

    /** A readable disassembly. The point of an expression VM is that you can look at the program. */
    fun disassemble(): String = buildString {
        variables.forEachIndexed { at, variable ->
            append("var v%-2d = %s[%d]".format(at, variable.name, variable.dimension))
            if (variable.manifold != CeresManifold.NONE) {
                append(" on ").append(variable.manifold.name.lowercase())
            }
            appendLine(
                variable.initial.joinToString(", ", prefix = "  seed [", postfix = "]") { "%.6g".format(it) },
            )
        }
        for (i in 0 until instructionCount) {
            val op = ExprOp.entries.first { it.code == code[4 * i] }
            val dst = code[4 * i + 1]
            val a = code[4 * i + 2]
            val b = code[4 * i + 3]
            append("s%-3d = ".format(dst))
            when (op) {
                ExprOp.CONST -> append("${constants[a]}")
                ExprOp.PARAM -> append("p$a.${"xyz"[b]}")
                ExprOp.VAR -> append("v$a[$b]")
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

// ---------------------------------------------------------------------------------------------
// A residual family built out of free variables: the Hesse plane
// ---------------------------------------------------------------------------------------------

/** A plane as `{ x : x . normal == offset }`, with `normal` a unit vector. */
data class CeresHessePlane(val normal: Vec3, val offset: Double)

/**
 * The total-least-squares plane through [points], in Hesse normal form: the centroid, and the
 * smallest eigenvector of the scatter matrix about it.
 *
 * This is the closed form of the fit, and it is also exactly the seed the `PLANAR_HESSE` opcode
 * computes in C++ from the pose the problem was compiled with. An expression has to be handed the
 * pose and seed its own plane here — which is not a shortcoming: the caller picks the seed
 * deliberately and can print it, where the opcode's is invisible.
 *
 * The normal's sign is canonicalised (largest-magnitude component positive) so that the same points
 * always give the same seed. `n` and `-n` with `d` and `-d` are the same plane and negate every
 * residual that reads them, which leaves the objective and the step identical — but a seed that
 * flips run to run makes results harder to compare than they need to be.
 */
fun hessePlaneOf(points: List<Vec3>): CeresHessePlane {
    require(points.size >= 3) { "a plane fit needs at least three points" }
    val cx = points.sumOf { it.x } / points.size
    val cy = points.sumOf { it.y } / points.size
    val cz = points.sumOf { it.z } / points.size

    val scatter = DoubleArray(9)
    points.forEach { p ->
        val v = doubleArrayOf(p.x - cx, p.y - cy, p.z - cz)
        for (i in 0..2) for (j in 0..2) scatter[3 * i + j] += v[i] * v[j]
    }

    val (values, vectors) = symmetricEigen3(scatter)
    var smallest = 0
    for (i in 1..2) if (values[i] < values[smallest]) smallest = i
    var nx = vectors[smallest]
    var ny = vectors[3 + smallest]
    var nz = vectors[6 + smallest]
    val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
    if (length < 1e-12) {
        // A perfectly degenerate scatter matrix — every point coincident. Any normal will do, and
        // saying so beats dividing by zero.
        return CeresHessePlane(Vec3(1.0, 0.0, 0.0), cx)
    }
    nx /= length
    ny /= length
    nz /= length
    val dominant = listOf(nx, ny, nz).withIndex().maxBy { kotlin.math.abs(it.value) }
    if (dominant.value < 0.0) {
        nx = -nx
        ny = -ny
        nz = -nz
    }
    return CeresHessePlane(Vec3(nx, ny, nz), nx * cx + ny * cy + nz * cz)
}

/**
 * Eigenvalues and eigenvectors of a symmetric 3x3 matrix, by cyclic Jacobi rotations.
 *
 * Returns the eigenvalues and a row-major matrix whose *columns* are the corresponding unit
 * eigenvectors. Jacobi rather than anything cleverer because 3x3 is small, the iteration is
 * unconditionally stable on a symmetric matrix, and it agrees with `Eigen::SelfAdjointEigenSolver` to
 * machine precision — which matters, since the C++ side of this comparison uses exactly that.
 */
internal fun symmetricEigen3(matrix: DoubleArray): Pair<DoubleArray, DoubleArray> {
    require(matrix.size == 9) { "expected a row-major 3x3 matrix" }
    val a = matrix.copyOf()
    val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    repeat(64) {
        // Largest off-diagonal magnitude; stop when it is negligible against the diagonal.
        val offDiagonal = kotlin.math.abs(a[1]) + kotlin.math.abs(a[2]) + kotlin.math.abs(a[5])
        val scale = kotlin.math.abs(a[0]) + kotlin.math.abs(a[4]) + kotlin.math.abs(a[8])
        if (offDiagonal <= 1e-18 * kotlin.math.max(scale, 1e-300)) return values3(a) to v

        for ((p, q) in listOf(0 to 1, 0 to 2, 1 to 2)) {
            val apq = a[3 * p + q]
            if (kotlin.math.abs(apq) < 1e-300) continue
            val tau = (a[3 * q + q] - a[3 * p + p]) / (2.0 * apq)
            val t = (if (tau >= 0.0) 1.0 else -1.0) / (kotlin.math.abs(tau) + kotlin.math.sqrt(1.0 + tau * tau))
            val c = 1.0 / kotlin.math.sqrt(1.0 + t * t)
            val s = t * c

            // A <- J^T A J and V <- V J, written out rather than looped: three-by-three, and the
            // explicit form is the one that is obviously the similarity transform it claims to be.
            val j = DoubleArray(9)
            j[0] = 1.0; j[4] = 1.0; j[8] = 1.0
            j[3 * p + p] = c
            j[3 * p + q] = s
            j[3 * q + p] = -s
            j[3 * q + q] = c
            val jt = DoubleArray(9) { idx -> j[3 * (idx % 3) + idx / 3] }
            val next = multiply3(multiply3(jt, a), j)
            next.copyInto(a)
            multiply3(v, j).copyInto(v)
        }
    }
    return values3(a) to v
}

private fun values3(a: DoubleArray) = doubleArrayOf(a[0], a[4], a[8])

private fun multiply3(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { idx ->
    val row = idx / 3
    val column = idx % 3
    a[3 * row] * b[column] + a[3 * row + 1] * b[3 + column] + a[3 * row + 2] * b[6 + column]
}

/**
 * `PLANAR_HESSE`, written as an expression: this face's corners against a free unit normal and a free
 * scalar offset.
 *
 * The opcode and this compile to different things — a C++ template against an interpreted register
 * program — but they are the *same* objective on the same parameterisation, and the recorded evidence
 * for that parameterisation is what makes free variables worth having at all: on `kkC` the plane as a
 * free variable converges in 12 iterations to tangency `3e-08`, where the same objective with the
 * plane eliminated inside the residual (`PLANAR_FIT`) does not converge.
 *
 * [pose] is the flat xyz array the problem will be compiled with — the seed the opcode reads for
 * itself and an expression has to be given.
 */
fun CeresProgramBuilder.planarHesseExpression(
    points: List<Int>,
    pose: DoubleArray,
    weight: Double = 1.0,
    name: String = "plane",
): CeresProgramBuilder {
    require(points.size >= 3) { "a planar face needs at least three points" }
    require(points.distinct().size == points.size) {
        "a Hesse-plane expression names each corner once; face $points repeats one"
    }
    points.forEach {
        require(3 * it + 2 < pose.size) { "point $it is outside the pose of ${pose.size / 3} points" }
    }
    val seed = hessePlaneOf(points.map { Vec3(pose[3 * it], pose[3 * it + 1], pose[3 * it + 2]) })
    val normal = unitVariable("$name.n", seed.normal)
    val offset = variable("$name.d", seed.offset)
    return expression(points, listOf(normal, offset), weight) { corners, (n, d) ->
        val direction = n.vec()
        corners.map { (it dot direction) - d.scalar }
    }
}

/**
 * Flattens an expression DAG into register-machine instructions.
 *
 * Nodes are memoised by structural identity, so a subexpression written twice — or reached twice
 * through the DAG — is computed once and read from its slot thereafter. Constants are pooled the
 * same way.
 */
private class CeresExpressionCompiler(
    private val paramCount: Int,
    private val variables: List<CeresVariableDecl>,
) {
    private val code = mutableListOf<Int>()
    private val constants = mutableListOf<Double>()
    private val constantSlots = mutableMapOf<Double, Int>()
    private val slots = mutableMapOf<CeresExpr, Int>()
    private val variablesRead = mutableSetOf<Int>()
    private var nextSlot = 0

    fun compile(expressions: List<CeresExpr>): CompiledCeresExpression {
        val residualSlots = expressions.map { emit(it) }
        // A variable named by a residual but never read gives Ceres a Jacobian column of exact zeros
        // — a rank deficiency the minimiser has to regularise its way around, for a parameter that
        // provably cannot affect the answer. Almost always a typo in the expression.
        variables.indices.forEach { block ->
            require(block in variablesRead) {
                "variable '${variables[block].name}' is named by this expression but never read by it"
            }
        }
        return CompiledCeresExpression(
            code.toList(),
            constants.toList(),
            residualSlots,
            nextSlot,
            variables,
        )
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

            is ExprVar -> {
                require(expr.block < variables.size) {
                    "expression reads free variable ${expr.block} but only ${variables.size} were named"
                }
                val declaration = variables[expr.block]
                require(expr.component < declaration.dimension) {
                    "expression reads component ${expr.component} of '${declaration.name}', " +
                        "which has ${declaration.dimension}"
                }
                variablesRead += expr.block
                instruction(ExprOp.VAR, expr.block, expr.component)
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
