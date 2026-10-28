package afield.data.table

import afield.math.optimization.ceres.CeresExpr
import afield.math.optimization.ceres.CeresExpressionResidual
import afield.math.optimization.ceres.CeresProgram
import afield.math.optimization.ceres.CeresVariableDecl
import afield.math.optimization.ceres.CeresVariableRef
import afield.math.optimization.ceres.FfmCeresBackend
import afield.math.optimization.ceres.PointCloud
import afield.math.optimization.ceres.RobustLoss
import afield.math.optimization.ceres.SolveOptions
import afield.math.optimization.ceres.Vec3
import afield.math.optimization.ceres.barrierAbove
import afield.math.optimization.ceres.barrierBelow
import afield.math.optimization.ceres.ceresVariableComponent
import afield.math.optimization.ceres.constant
import afield.math.optimization.ceres.evaluateOnJvm
import afield.math.optimization.ceres.remapVariableBlocks
import afield.math.optimization.ceres.variableBlocksRead
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Constraint-solving spreadsheets: cells that hold Ceres variables, formulas that trace back to them,
 * and assertion cells the sheet solves for.
 *
 * A cell whose formula is `free(4)` is a variable the sheet may move, starting at 4. Arithmetic over
 * one yields a [SpreadsheetTraced], a number that also carries the expression that produced it, so a
 * cell written `B3 eq 40` (or `le`, or `ge`) becomes a [SpreadsheetAssertion] the solver can act on:
 * Ceres moves the variables until every assertion holds, each variable preferring the value it had.
 * The three types here are what such a cell holds; the collector and the solver around them are
 * internal to this file.
 */

// Design, strengths and measurements: dev_docs/SPREADSHEET_CONSTRAINTS.md.
// User page: public/docs/spreadsheets.md, "Solving for cells".

/**
 * A number a formula computed from one or more variable cells (`=free(3)`), carrying the expression it
 * was computed by. It displays and coerces as its [value]; arithmetic on it keeps the trace, so an
 * assertion over it can move the variables it came from.
 */
class SpreadsheetTraced internal constructor(val value: Double, internal val expr: CeresExpr) {
    /** The [value] alone, so a traced cell reads as the number it currently is. */
    override fun toString(): String = value.toString()
}

/** The three relations an assertion cell can state. */
enum class SpreadsheetRelation(val symbol: String) { EQ("="), LE("≤"), GE("≥") }

/**
 * An assertion cell's value: `left relation right` at the sheet's current values. The sheet solves so
 * that every assertion holds; one that still does not after the solve is shown violated, with how far.
 */
class SpreadsheetAssertion internal constructor(
    /** Which of the three comparisons the cell states. */
    val relation: SpreadsheetRelation,
    /** The left-hand side at the sheet's current numbers. */
    val left: Double,
    /** The right-hand side at the sheet's current numbers. */
    val right: Double,
    internal val leftExpr: CeresExpr?,
    internal val rightExpr: CeresExpr?,
) {
    /** How far the assertion is from holding; 0 when it holds. */
    val violation: Double
        get() = when (relation) {
            SpreadsheetRelation.EQ -> abs(left - right)
            SpreadsheetRelation.LE -> max(0.0, left - right)
            SpreadsheetRelation.GE -> max(0.0, right - left)
        }

    /**
     * Whether [violation] is inside a tolerance that scales with the two sides,
     * `1e-6 × (1 + |left| + |right|)`, so a comparison between large numbers is not held to an
     * absolute one.
     */
    val satisfied: Boolean get() = violation <= assertionTolerance(left, right)

    /** True when either side depends on a variable cell, so the solver can do something about it. */
    val traced: Boolean get() = leftExpr != null || rightExpr != null

    internal fun residualExpr(): CeresExpr {
        val l = leftExpr ?: constant(left)
        val r = rightExpr ?: constant(right)
        return when (relation) {
            SpreadsheetRelation.EQ -> l - r
            SpreadsheetRelation.LE -> barrierAbove(l, r)
            SpreadsheetRelation.GE -> barrierBelow(l, r)
        }
    }

    /** A tick or a cross, then the comparison as the sheet currently reads it. */
    override fun toString(): String = "${if (satisfied) "✓" else "✗"} $left ${relation.symbol} $right"
}

internal fun assertionTolerance(left: Double, right: Double): Double = 1e-6 * (1.0 + abs(left) + abs(right))

/** A value as `(number, expression-or-null)`, for the traced arithmetic. Null when it is not a scalar. */
internal fun spreadsheetTracedOperand(value: Any?): Pair<Double, CeresExpr?>? = when (val v = unwrapTracedInput(value)) {
    is SpreadsheetTraced -> v.value to v.expr
    else -> SpreadsheetEvalHelpers.scalarOrNull(v)?.let { it to null }
}

private fun unwrapTracedInput(value: Any?): Any? = if (value is SpreadsheetScalar) unwrapTracedInput(value.raw) else value

internal fun spreadsheetTracedBinary(op: SpreadsheetBinaryOp, left: Any?, right: Any?): SpreadsheetTraced? {
    val (lv, le) = spreadsheetTracedOperand(left) ?: return null
    val (rv, re) = spreadsheetTracedOperand(right) ?: return null
    if (le == null && re == null) return null
    val l = le ?: constant(lv)
    val r = re ?: constant(rv)
    return when (op) {
        SpreadsheetBinaryOp.ADD -> SpreadsheetTraced(lv + rv, l + r)
        SpreadsheetBinaryOp.SUBTRACT -> SpreadsheetTraced(lv - rv, l - r)
        SpreadsheetBinaryOp.MULTIPLY -> SpreadsheetTraced(lv * rv, l * r)
        SpreadsheetBinaryOp.DIVIDE -> SpreadsheetTraced(lv / rv, l / r)
        SpreadsheetBinaryOp.REMAINDER -> null
    }
}

/** A unary function that keeps the trace when its argument has one, and is plain arithmetic otherwise. */
internal fun spreadsheetTracedUnary(x: Any?, numeric: (Double) -> Double, symbolic: (CeresExpr) -> CeresExpr): SpreadsheetScalar {
    val operand = spreadsheetTracedOperand(x)
        ?: error("Expected a number, found '${SpreadsheetScalar(x)}'")
    val (value, expr) = operand
    return SpreadsheetScalar(if (expr == null) numeric(value) else SpreadsheetTraced(numeric(value), symbolic(expr)))
}

internal fun spreadsheetTracedBinaryFn(
    a: Any?, b: Any?,
    numeric: (Double, Double) -> Double,
    symbolic: (CeresExpr, CeresExpr) -> CeresExpr,
): SpreadsheetScalar {
    val (av, ae) = spreadsheetTracedOperand(a) ?: error("Expected a number, found '${SpreadsheetScalar(a)}'")
    val (bv, be) = spreadsheetTracedOperand(b) ?: error("Expected a number, found '${SpreadsheetScalar(b)}'")
    val value = numeric(av, bv)
    if (ae == null && be == null) return SpreadsheetScalar(value)
    return SpreadsheetScalar(SpreadsheetTraced(value, symbolic(ae ?: constant(av), be ?: constant(bv))))
}

/** `=free(3)`, the whole formula and nothing else: the only shape a variable cell can take. */
internal val FREE_CELL_PATTERN = Regex("""^\s*=\s*free\s*\(\s*([-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)\s*\)\s*$""")

internal fun freeCellInput(value: Double): String = "=free(${freeLiteral(value)})"

/** Ten significant digits, no exponent, no trailing zeros: what a solved value is written back as. */
internal fun freeLiteral(value: Double): String {
    val safe = if (value == 0.0) 0.0 else value
    val text = BigDecimal(safe).round(MathContext(10)).stripTrailingZeros().toPlainString()
    return if (text == "-0") "0" else text
}

/** One recompute's variables and assertions, gathered as the cells evaluate. */
internal class SheetConstraintCollector {
    data class Variable(val cellKey: String, val previous: Double)

    val variables = mutableListOf<Variable>()
    private val indexByCell = mutableMapOf<String, Int>()
    val assertions = mutableListOf<Pair<String, SpreadsheetAssertion>>()

    fun isVariable(cellKey: String): Boolean = cellKey in indexByCell

    fun declare(cellKey: String, cellInput: String, initial: Double): SpreadsheetTraced {
        require(FREE_CELL_PATTERN.matches(cellInput)) {
            "free(...) must be the whole formula, with a number inside — write =free(3) in a cell of its own " +
                "and compute with that cell"
        }
        require(initial.isFinite()) { "free(...) needs a finite number" }
        val index = indexByCell.getOrPut(cellKey) {
            variables += Variable(cellKey, initial)
            variables.lastIndex
        }
        return SpreadsheetTraced(initial, ceresVariableComponent(index))
    }

    fun assert(cellKey: String?, assertion: SpreadsheetAssertion) {
        assertions += (cellKey ?: "") to assertion
    }
}

/**
 * Strengths, as residual multipliers (Ceres squares them: the cost ratio between two strengths is the
 * square of these). Cassowary's required / strong / weak, approximated by weights and made close to
 * exact by re-anchoring the stays (see [SheetConstraintSolver]). Measured in
 * dev_docs/SPREADSHEET_CONSTRAINTS.md.
 */
internal object SheetStrength {
    const val REQUIRED = 1.0
    const val EDIT = 1e-2
    const val STAY = 1e-4
}

internal data class SheetSolveOutcome(
    val values: DoubleArray,
    val ok: Boolean,
    val phase: String,
    val passes: Int,
    val millis: Double,
    val note: String? = null,
) {
    fun summary(variableCount: Int, assertionCount: Int, violated: Int): String =
        buildString {
            append("solved $variableCount variable${if (variableCount == 1) "" else "s"}, ")
            append("$assertionCount assertion${if (assertionCount == 1) "" else "s"}")
            if (violated > 0) append(", $violated violated")
            append(" · %.1f ms".format(millis))
            if (phase == "edit-yielded") append(" · edit yielded")
            note?.let { append(" · ").append(it) }
        }
}

/**
 * The solve. Variables are `ExprVar(i, 0)` over the sheet's own numbering; every residual is remapped
 * to the blocks it reads, because a Ceres residual block may only name the parameter blocks it uses.
 *
 * - **Phase A, edit held.** Edited variables are substituted as constants, everything else is free with
 *   a [SheetStrength.STAY] pull to its previous value, assertions at [SheetStrength.REQUIRED]. If every
 *   assertion then holds, that is the answer: the edit is kept exactly.
 * - **Phase B, edit yields.** Only when phase A leaves an assertion violated: the edited variables become
 *   free too, held at [SheetStrength.EDIT]. Required beats edit beats stay.
 * - **Re-anchoring.** A weighted stay leaks into an equality by about `(STAY / REQUIRED)^2` of the
 *   distance it resists. Each phase runs [PASSES] solves, re-anchoring the stays at the last solution,
 *   which shrinks that leak geometrically; the minimal-change direction is set by the first pass.
 * - **Bounds.** An inequality between one lone variable and a constant (`B2 ge 0`) becomes a Ceres
 *   parameter bound instead of a barrier residual; contradictory bounds fall back to barriers so the
 *   violation is reported rather than refused.
 */
internal object SheetConstraintSolver {
    const val PASSES = 3

    private val backend: FfmCeresBackend by lazy { FfmCeresBackend() }

    fun solve(previous: DoubleArray, edited: Set<Int>, assertions: List<SpreadsheetAssertion>, passes: Int = PASSES): SheetSolveOutcome {
        val started = System.nanoTime()
        val traced = assertions.filter { it.traced }
        val bounds = simpleBounds(traced, previous.size)
        val residuals = traced.filter { it !in bounds.consumed }.map { it.residualExpr() }

        fun violated(values: DoubleArray): Int = traced.count { a ->
            val l = a.leftExpr?.evaluateOnJvm { b, _ -> values[b] } ?: a.left
            val r = a.rightExpr?.evaluateOnJvm { b, _ -> values[b] } ?: a.right
            SpreadsheetAssertion(a.relation, l, r, null, null).let { !it.satisfied }
        }

        val phaseA = runPhase(previous, pinned = edited, editWeighted = emptySet(), residuals, bounds, passes)
        var outcome = phaseA
        if (phaseA.ok && edited.isNotEmpty() && violated(phaseA.values) > 0) {
            val phaseB = runPhase(previous, pinned = emptySet(), editWeighted = edited, residuals, bounds, passes)
            outcome = if (phaseB.ok && violated(phaseB.values) < violated(phaseA.values)) phaseB.copy(phase = "edit-yielded")
            else phaseA.copy(note = "the edit could not yield: " + (phaseB.note ?: "${violated(phaseB.values)} still violated at ${phaseB.values.toList()}"))
        }
        return outcome.copy(millis = (System.nanoTime() - started) / 1e6)
    }

    private class Bounds(
        val lower: Map<Int, Double>,
        val upper: Map<Int, Double>,
        val consumed: Set<SpreadsheetAssertion>,
    )

    private fun simpleBounds(assertions: List<SpreadsheetAssertion>, count: Int): Bounds {
        val lower = mutableMapOf<Int, Double>()
        val upper = mutableMapOf<Int, Double>()
        val by = mutableMapOf<Int, MutableList<SpreadsheetAssertion>>()
        for (a in assertions) {
            if (a.relation == SpreadsheetRelation.EQ) continue
            val leftVar = a.leftExpr?.loneVariable()
            val rightVar = a.rightExpr?.loneVariable()
            val (variable, isUpper, bound) = when {
                leftVar != null && a.rightExpr == null -> Triple(leftVar, a.relation == SpreadsheetRelation.LE, a.right)
                rightVar != null && a.leftExpr == null -> Triple(rightVar, a.relation == SpreadsheetRelation.GE, a.left)
                else -> continue
            }
            if (variable !in 0 until count) continue
            if (isUpper) upper[variable] = minOf(upper[variable] ?: Double.POSITIVE_INFINITY, bound)
            else lower[variable] = maxOf(lower[variable] ?: Double.NEGATIVE_INFINITY, bound)
            by.getOrPut(variable) { mutableListOf() } += a
        }
        // An empty box cannot be handed to Ceres; leave those assertions as barriers so the conflict
        // shows up as a violation in the cells.
        val contradictory = by.keys.filter { (lower[it] ?: Double.NEGATIVE_INFINITY) > (upper[it] ?: Double.POSITIVE_INFINITY) }
        contradictory.forEach { lower.remove(it); upper.remove(it); by.remove(it) }
        return Bounds(lower, upper, by.values.flatten().toSet())
    }

    private fun CeresExpr.loneVariable(): Int? {
        val read = variableBlocksRead()
        if (read.size != 1) return null
        val only = read.single()
        return if (this == ceresVariableComponent(only)) only else null
    }

    private fun runPhase(
        previous: DoubleArray,
        pinned: Set<Int>,
        editWeighted: Set<Int>,
        residuals: List<CeresExpr>,
        bounds: Bounds,
        passCount: Int,
    ): SheetSolveOutcome {
        val values = previous.copyOf()
        // A held edit outside its own bound (`A1 le 50` and the user typed 100) is held at the bound:
        // that is the whole of what the bound can say, and it keeps the conflict out of the minimiser,
        // which stalls when a weighted pull drives a variable into a Ceres bound (measured: phase B left
        // F 8e-4 off its equality for every later pass). See dev_docs/SPREADSHEET_CONSTRAINTS.md.
        var clamped = false
        for (global in pinned) {
            val c = values[global].coerceIn(bounds.lower[global] ?: Double.NEGATIVE_INFINITY, bounds.upper[global] ?: Double.POSITIVE_INFINITY)
            if (c != values[global]) { values[global] = c; clamped = true }
        }
        val read = residuals.flatMapTo(sortedSetOf()) { it.variableBlocksRead() } + bounds.lower.keys + bounds.upper.keys
        val free = read.filter { it !in pinned }.sorted()
        if (free.isEmpty()) return SheetSolveOutcome(values, ok = true, phase = if (clamped) "edit-yielded" else "nothing-free", passes = 0, millis = 0.0)
        var passes = 0
        val note: String? = null
        val anchors = previous.copyOf()
        repeat(passCount) {
            val local = free.withIndex().associate { (at, global) -> global to at }
            val decls = free.map { global ->
                val lo = bounds.lower[global]
                val hi = bounds.upper[global]
                val start = values[global].coerceIn(lo ?: Double.NEGATIVE_INFINITY, hi ?: Double.POSITIVE_INFINITY)
                CeresVariableDecl(
                    name = "v$global",
                    initial = listOf(start),
                    bounds = if (lo == null && hi == null) emptyMap()
                    else mapOf(0 to (lo ?: Double.NEGATIVE_INFINITY)..(hi ?: Double.POSITIVE_INFINITY)),
                )
            }
            val refs = decls.mapIndexed { i, d -> CeresVariableRef(i, d) }
            val constraints = mutableListOf<CeresExpressionResidual>()
            for (residual in residuals) {
                val substituted = residual.remapVariableBlocks { block, c ->
                    if (block in local) ceresVariableComponent(block, c) else constant(values[block])
                }
                val blocks = substituted.variableBlocksRead().sorted()
                if (blocks.isEmpty()) continue
                val position = blocks.withIndex().associate { (at, global) -> global to at }
                val remapped = substituted.remapVariableBlocks { block, c -> ceresVariableComponent(position.getValue(block), c) }
                constraints += CeresExpressionResidual(
                    points = emptyList(),
                    expressions = listOf(remapped),
                    weight = SheetStrength.REQUIRED,
                    variables = blocks.map { refs[local.getValue(it)] },
                )
            }
            for (global in free) {
                val weight = if (global in editWeighted) SheetStrength.EDIT else SheetStrength.STAY
                constraints += CeresExpressionResidual(
                    points = emptyList(),
                    expressions = listOf(ceresVariableComponent(0) - constant(anchors[global])),
                    weight = weight,
                    variables = listOf(refs[local.getValue(global)]),
                )
            }
            val program = CeresProgram(constraints, RobustLoss.NONE, decls)
            val result = runCatching {
                backend.compile(NO_POINTS, program).use { solver ->
                    solver.solve(
                        NO_POINTS,
                        options = SolveOptions(
                            maxIterations = 200,
                            functionTolerance = 1e-16,
                            gradientTolerance = 1e-16,
                            parameterTolerance = 1e-14,
                        ),
                    )
                }
            }.getOrElse { t ->
                return SheetSolveOutcome(previous.copyOf(), ok = false, phase = "failed", passes = passes, millis = 0.0, note = t.message ?: t.toString())
            }
            if (!result.ok || result.variables.size != free.size) {
                return SheetSolveOutcome(previous.copyOf(), ok = false, phase = "failed", passes = passes, millis = 0.0, note = "Ceres did not converge")
            }
            free.forEachIndexed { at, global -> values[global] = result.variables[at].scalar }
            // Re-anchor the stays at this solution; see the class comment. An edit pull keeps pointing
            // at what the user typed, so a variable an assertion pushes back stops exactly at the limit.
            free.filter { it !in editWeighted }.forEach { anchors[it] = values[it] }
            passes += 1
        }
        return SheetSolveOutcome(values, ok = true, phase = if (clamped) "edit-yielded" else "edit-held", passes = passes, millis = 0.0, note = note)
    }

    /** Ceres' ABI needs a point cloud; a sheet has no points, so it gets one nothing reads. */
    private val NO_POINTS = PointCloud.of(Vec3(0.0, 0.0, 0.0))
}
