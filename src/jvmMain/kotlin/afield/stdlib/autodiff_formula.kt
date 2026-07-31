@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Function")

package afield.stdlib

/**
 * One declaration of each `Fn` op's pointwise semantics, walked by two evaluators — so `Fn.eval` and
 * the reverse-mode gradient cannot compute different functions.
 *
 * ## The problem this solves
 *
 * `autodiff_fn.kt`'s first draft re-implemented each op's formula in [AD] arithmetic beside the
 * hand-written `Double` closure in `Functions.kt`. That is two transcriptions of one truth, and the
 * only thing holding them together was a runtime value-agreement check that *fell back* on
 * disagreement. A guard is a safety net, not a structure: the two would drift the first time an op's
 * formula changed, and the fallback would hide it.
 *
 * So the per-op formula now lives in exactly one place — [FnFormulas] — as a tiny expression tree
 * ([FnExpr]) over the op's role-addressed children and the sample position `x`. Two walkers consume
 * it:
 *
 * - [evalFormula] in `Double`, transcribed to be **bit-identical** to the hand-written closure
 *   (same operations, same order, same `Math.pow` / `coerceIn` calls);
 * - [evalFormulaAD] in tracked [AD] arithmetic.
 *
 * The AD side therefore has **no per-op code at all**. Adding an op means adding one [FnExpr]; the
 * gradient follows for free, and the number of places a transcription error can hide drops from
 * "once per op" to "once per [FnExpr] kind" — and each kind is oracle-checked once.
 *
 * `FnFormulaAgreementTest` closes the loop: for every op with a formula it asserts the `Double`
 * walker's output is bit-identical (`toRawBits`) to `Functions`' own `eval` across a grid **and** at
 * perturbed parameters. A formula that is not a faithful transcription cannot reach `main`.
 *
 * ## Why `Fn.eval` is not simply replaced by the walker
 *
 * It could be, and the seam is a single one: every op in `Functions` builds its `Fn` through one
 * private factory, `graphFn(domain, name, op, …, eval: (Double) -> Double)` at `Functions.kt:886`, so
 * a formula-derived evaluator could be substituted there without touching the individual factories.
 *
 * It is not, and the reason is measured rather than assumed. `FnFormulaBenchmarkTest` times the walk
 * against the closure on the same graphs, warmed, best of five rounds of 400k evaluations:
 *
 * | graph | hand-written closure | formula walk | ratio |
 * | --- | --- | --- | --- |
 * | `sin(1.5, …)` | 14.4 ns | 67.5 ns | **4.67×** |
 * | `cos(2) * sin(1.5, …)` | 47.3 ns | 161.9 ns | **3.42×** |
 * | `gaussian(…) * sin(…)` | 56.8 ns | 193.3 ns | **3.40×** |
 *
 * A 3.4–4.7× tax on `Fn.eval` is not payable: it is the hottest call in the library, and one
 * `fitGradient` at the defaults is ~8.7·10⁵ whole-graph evaluations, with plotting, `FnMetric`,
 * `evolve` and every sampler on the same path. The cost is the per-node `when` dispatch and the
 * `Child` role lookup (a linear scan of `node.children`) against a closure the JIT inlines wholesale.
 *
 * So the shape landed on is: **one declaration, two walkers, and a test-time proof they agree.** The
 * formula table is the only place an op's semantics are written for differentiation purposes, the AD
 * side has no per-op code, and `theFormulaTableIsABitIdenticalTranscriptionOfFnEval` compares the
 * `Double` walk against `Fn.eval` as **raw bits** across a 129-point grid at three parameter vectors
 * for every op. That is strictly stronger than the runtime value-agreement guard for preventing
 * *drift* — it fails in CI rather than silently demoting a run — and it costs nothing at sample time.
 * The runtime guard stays, because it also catches the cases the table cannot express at all (an
 * unbounded shape domain, an inverted `clamp` range).
 *
 * If the walk ever needs to be the only traversal, the two costs to attack first are the role lookup
 * (pre-resolve `Child(role)` to a child index when the formula is built) and the recursive `when`
 * (flatten to a post-order array of opcodes with a value stack). Neither is done here.
 */

/**
 * A node of an op's formula: an expression over the sample position, the op's role-addressed
 * children, and literals.
 *
 * Deliberately small and total — no user-extensible escape hatch — because every kind here has to be
 * implemented twice (once per walker) and oracle-checked. A subtree may appear more than once; both
 * walkers simply recompute it, which is bit-identical in `Double` and correct in [AD] (adjoints
 * accumulate).
 */
sealed interface FnExpr {

    /** The sample position. Inside an [AtX] body this is the *moved* position. */
    data object X : FnExpr

    /** A constant, carried through with no derivative. */
    data class Lit(val value: Double) : FnExpr

    /** The op's child in [role], evaluated at the current position. */
    data class Child(val role: String) : FnExpr

    /**
     * The normalised position within the op's own domain — the tracked twin of
     * `Domain.finiteSweep().alphaOf(x)`, which `ramp` and the windows sample.
     *
     * Requires a finite domain; `Functions` falls back to a private plot-safe preview window for an
     * unbounded one, and rather than guess at that the walkers throw (which
     * [FnAutoDiff.worstValueDisagreement] turns into a refusal).
     */
    data object Alpha : FnExpr

    /** Sum. */
    data class Add(val a: FnExpr, val b: FnExpr) : FnExpr

    /** Difference. */
    data class Sub(val a: FnExpr, val b: FnExpr) : FnExpr

    /** Product. */
    data class Mul(val a: FnExpr, val b: FnExpr) : FnExpr

    /** Quotient. */
    data class Div(val a: FnExpr, val b: FnExpr) : FnExpr

    /** Negation. */
    data class Neg(val a: FnExpr) : FnExpr

    /** `sin` in radians. */
    data class Sin(val a: FnExpr) : FnExpr

    /** `cos` in radians. */
    data class Cos(val a: FnExpr) : FnExpr

    /** Natural exponential. */
    data class Exp(val a: FnExpr) : FnExpr

    /** Natural logarithm. */
    data class Ln(val a: FnExpr) : FnExpr

    /** Hyperbolic tangent. */
    data class Tanh(val a: FnExpr) : FnExpr

    /** `Math.pow(a, b)` — spelled as the real call, so a literal exponent of 2.0 stays bit-identical
     *  to the hand-written `.pow(2.0)` rather than being quietly rewritten as `a * a`. */
    data class Pow(val a: FnExpr, val b: FnExpr) : FnExpr

    /** The smaller of two. */
    data class Min(val a: FnExpr, val b: FnExpr) : FnExpr

    /** The larger of two. */
    data class Max(val a: FnExpr, val b: FnExpr) : FnExpr

    /** `coerceIn(0.0, 1.0)` — the `clamp01` the shapes and windows use. */
    data class Clamp01(val a: FnExpr) : FnExpr

    /** `coerceIn(lo, hi)`, which **throws** for an inverted range exactly as `Fn.clamp` does. */
    data class CoerceIn(val a: FnExpr, val lo: FnExpr, val hi: FnExpr) : FnExpr

    /** `if (test == 0.0) then else otherwise` — the zero-sigma / zero-amount / zero-scale
     *  short-circuits, differentiated as taken. */
    data class IfZero(val test: FnExpr, val then: FnExpr, val otherwise: FnExpr) : FnExpr

    /** `if (a == b) then else otherwise`. */
    data class IfEq(val a: FnExpr, val b: FnExpr, val then: FnExpr, val otherwise: FnExpr) : FnExpr

    /** `if (a < b) then else otherwise`. */
    data class IfLt(val a: FnExpr, val b: FnExpr, val then: FnExpr, val otherwise: FnExpr) : FnExpr

    /**
     * Evaluates [body] with the position replaced by [newX] — the shape of every x-domain transform.
     *
     * [newX] is evaluated at the *original* position, which is what makes `warp` a single
     * displacement rather than a recursive one.
     */
    data class AtX(val newX: FnExpr, val body: FnExpr) : FnExpr
}

/**
 * The formula table: one [FnExpr] per differentiable `Fn` op, keyed by lower-cased op name.
 *
 * This is the single source of truth the file KDoc describes. Every entry is a transcription of the
 * corresponding closure in `Functions.kt`, and `FnFormulaAgreementTest` asserts each one is
 * bit-identical to it.
 */
object FnFormulas {

    private const val TAU = Math.PI * 2.0

    private fun child(role: String): FnExpr = FnExpr.Child(role)
    private fun lit(v: Double): FnExpr = FnExpr.Lit(v)

    /** `offset + amplitude * sin(TAU * frequency * x + phase)`, in that association order. */
    private fun oscillator(trig: (FnExpr) -> FnExpr): FnExpr = FnExpr.Add(
        child("offset"),
        FnExpr.Mul(
            child("amplitude"),
            trig(FnExpr.Add(FnExpr.Mul(FnExpr.Mul(lit(TAU), child("frequency")), FnExpr.X), child("phase")))
        )
    )

    /** `t * t * (3.0 - 2.0 * t)` — the cubic smoothstep on an already-normalised `t`. */
    private fun smoothstepOf(t: FnExpr): FnExpr =
        FnExpr.Mul(FnExpr.Mul(t, t), FnExpr.Sub(lit(3.0), FnExpr.Mul(lit(2.0), t)))

    /** `0.5 - 0.5 * cos(TAU * t)`-shaped raised-cosine window with the given pedestal and depth. */
    private fun raisedCosine(pedestal: Double, depth: Double): FnExpr = FnExpr.Sub(
        lit(pedestal),
        FnExpr.Mul(lit(depth), FnExpr.Cos(FnExpr.Mul(lit(TAU), FnExpr.Clamp01(FnExpr.Alpha))))
    )

    private val table: Map<String, FnExpr> = buildMap {
        // ---- label-only wrappers and the identity ----
        put("identity", FnExpr.X)
        put("name", child("source"))
        put("over", child("source"))

        // ---- pointwise arithmetic ----
        put("add", FnExpr.Add(child("left"), child("right")))
        put("subtract", FnExpr.Sub(child("left"), child("right")))
        put("multiply", FnExpr.Mul(child("left"), child("right")))
        put("divide", FnExpr.Div(child("left"), child("right")))

        // ---- smooth oscillators (Functions.sin / Functions.cos) ----
        put("sin", oscillator { FnExpr.Sin(it) })
        put("cos", oscillator { FnExpr.Cos(it) })

        // ---- value transforms ----
        put("scaley", FnExpr.Mul(child("source"), child("scale")))
        put("offsety", FnExpr.Add(child("source"), child("offset")))
        put("pow", FnExpr.Pow(child("source"), child("exponent")))
        put("log", FnExpr.Div(FnExpr.Ln(child("source")), FnExpr.Ln(child("base"))))
        put("clamp", FnExpr.CoerceIn(child("source"), child("min"), child("max")))
        put(
            "softclip",
            FnExpr.IfZero(
                child("amount"),
                lit(0.0),
                FnExpr.Div(
                    FnExpr.Tanh(FnExpr.Mul(child("source"), child("amount"))),
                    FnExpr.Tanh(child("amount"))
                )
            )
        )

        // ---- modulation ----
        put(
            "amplitudemod",
            FnExpr.Mul(child("source"), FnExpr.Add(child("bias"), FnExpr.Mul(child("depth"), child("mod"))))
        )
        put("ringmod", FnExpr.Mul(child("source"), FnExpr.Mul(child("depth"), child("mod"))))

        // ---- x-domain transforms: sample the modulators at the ORIGINAL x, then move the lookup ----
        put("shift", FnExpr.AtX(FnExpr.Sub(FnExpr.X, child("dx")), child("source")))
        put(
            "warp",
            FnExpr.AtX(FnExpr.Add(FnExpr.X, FnExpr.Mul(child("depth"), child("mod"))), child("source"))
        )
        put(
            "scalex",
            FnExpr.AtX(
                FnExpr.IfZero(
                    child("scale"),
                    child("about"),
                    FnExpr.Add(FnExpr.Div(FnExpr.Sub(FnExpr.X, child("about")), child("scale")), child("about"))
                ),
                child("source")
            )
        )

        // ---- shapes ----
        put(
            "gaussian",
            FnExpr.IfZero(
                child("sigma"),
                lit(0.0),
                FnExpr.Mul(
                    child("amplitude"),
                    FnExpr.Exp(
                        FnExpr.Mul(
                            lit(-0.5),
                            FnExpr.Pow(FnExpr.Div(FnExpr.Sub(FnExpr.X, child("mean")), child("sigma")), lit(2.0))
                        )
                    )
                )
            )
        )
        // blend(start, end, alpha) == start + (end - start) * alpha
        put(
            "ramp",
            FnExpr.Add(
                child("start"),
                FnExpr.Mul(FnExpr.Sub(child("end"), child("start")), FnExpr.Alpha)
            )
        )
        put(
            "remap",
            FnExpr.IfZero(
                FnExpr.Sub(child("to"), child("from")),
                lit(0.0),
                FnExpr.Div(FnExpr.Sub(FnExpr.X, child("from")), FnExpr.Sub(child("to"), child("from")))
            )
        )
        // Functions.smoothStep sorts its edges, degenerates to a hard step when they coincide, and
        // normalises-then-clamps otherwise.
        run {
            val low = FnExpr.Min(child("edge0"), child("edge1"))
            val high = FnExpr.Max(child("edge0"), child("edge1"))
            val t = FnExpr.Clamp01(FnExpr.Div(FnExpr.Sub(FnExpr.X, low), FnExpr.Sub(high, low)))
            put(
                "smoothstep",
                FnExpr.IfEq(high, low, FnExpr.IfLt(FnExpr.X, low, lit(0.0), lit(1.0)), smoothstepOf(t))
            )
        }

        // ---- windows ----
        put("hannwindow", raisedCosine(0.5, 0.5))
        put("hammingwindow", raisedCosine(0.54, 0.46))
    }

    /** The formula for [op], or null when this op has no declared pointwise semantics. */
    fun of(op: String): FnExpr? = table[op.trim().lowercase()]

    /** Every op with a declared formula, lower-cased. */
    val ops: Set<String> get() = table.keys
}

// ---------------------------------------------------------------------------------------------
// The two walkers
// ---------------------------------------------------------------------------------------------

/**
 * Walks a formula in plain `Double` arithmetic — the reference evaluator, transcribed to be
 * bit-identical to `Functions`' own closures.
 *
 * [childValue] resolves a role to that child's value at the position it is asked for; [alpha]
 * supplies [FnExpr.Alpha]. Both are supplied by the caller because a formula does not know how its
 * children are stored.
 */
fun evalFormula(
    expr: FnExpr,
    x: Double,
    alpha: (Double) -> Double,
    childValue: (role: String, at: Double) -> Double
): Double = when (expr) {
    is FnExpr.X -> x
    is FnExpr.Lit -> expr.value
    is FnExpr.Child -> childValue(expr.role, x)
    is FnExpr.Alpha -> alpha(x)
    is FnExpr.Add -> evalFormula(expr.a, x, alpha, childValue) + evalFormula(expr.b, x, alpha, childValue)
    is FnExpr.Sub -> evalFormula(expr.a, x, alpha, childValue) - evalFormula(expr.b, x, alpha, childValue)
    is FnExpr.Mul -> evalFormula(expr.a, x, alpha, childValue) * evalFormula(expr.b, x, alpha, childValue)
    is FnExpr.Div -> evalFormula(expr.a, x, alpha, childValue) / evalFormula(expr.b, x, alpha, childValue)
    is FnExpr.Neg -> -evalFormula(expr.a, x, alpha, childValue)
    is FnExpr.Sin -> kotlin.math.sin(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Cos -> kotlin.math.cos(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Exp -> kotlin.math.exp(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Ln -> kotlin.math.ln(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Tanh -> kotlin.math.tanh(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Pow -> Math.pow(
        evalFormula(expr.a, x, alpha, childValue),
        evalFormula(expr.b, x, alpha, childValue)
    )
    is FnExpr.Min -> kotlin.math.min(
        evalFormula(expr.a, x, alpha, childValue),
        evalFormula(expr.b, x, alpha, childValue)
    )
    is FnExpr.Max -> kotlin.math.max(
        evalFormula(expr.a, x, alpha, childValue),
        evalFormula(expr.b, x, alpha, childValue)
    )
    is FnExpr.Clamp01 -> evalFormula(expr.a, x, alpha, childValue).coerceIn(0.0, 1.0)
    is FnExpr.CoerceIn -> evalFormula(expr.a, x, alpha, childValue).coerceIn(
        evalFormula(expr.lo, x, alpha, childValue),
        evalFormula(expr.hi, x, alpha, childValue)
    )
    is FnExpr.IfZero -> if (evalFormula(expr.test, x, alpha, childValue) == 0.0) {
        evalFormula(expr.then, x, alpha, childValue)
    } else {
        evalFormula(expr.otherwise, x, alpha, childValue)
    }
    is FnExpr.IfEq -> if (
        evalFormula(expr.a, x, alpha, childValue) == evalFormula(expr.b, x, alpha, childValue)
    ) {
        evalFormula(expr.then, x, alpha, childValue)
    } else {
        evalFormula(expr.otherwise, x, alpha, childValue)
    }
    is FnExpr.IfLt -> if (
        evalFormula(expr.a, x, alpha, childValue) < evalFormula(expr.b, x, alpha, childValue)
    ) {
        evalFormula(expr.then, x, alpha, childValue)
    } else {
        evalFormula(expr.otherwise, x, alpha, childValue)
    }
    is FnExpr.AtX -> evalFormula(expr.body, evalFormula(expr.newX, x, alpha, childValue), alpha, childValue)
}

/**
 * Walks a formula in tracked [AD] arithmetic — the *whole* of the reverse-mode gradient for `Fn`
 * graphs, with no per-op code.
 *
 * Branch-shaped kinds ([FnExpr.IfZero], [FnExpr.IfEq], [FnExpr.IfLt]) are differentiated **as
 * taken**, on the branch the tracked values select; that is the correct derivative of the piecewise
 * function everywhere except on the switch-over itself.
 */
fun evalFormulaAD(
    expr: FnExpr,
    x: AD,
    alpha: (AD) -> AD,
    childValue: (role: String, at: AD) -> AD
): AD = when (expr) {
    is FnExpr.X -> x
    is FnExpr.Lit -> x.tape.constant(expr.value)
    is FnExpr.Child -> childValue(expr.role, x)
    is FnExpr.Alpha -> alpha(x)
    is FnExpr.Add -> evalFormulaAD(expr.a, x, alpha, childValue) + evalFormulaAD(expr.b, x, alpha, childValue)
    is FnExpr.Sub -> evalFormulaAD(expr.a, x, alpha, childValue) - evalFormulaAD(expr.b, x, alpha, childValue)
    is FnExpr.Mul -> evalFormulaAD(expr.a, x, alpha, childValue) * evalFormulaAD(expr.b, x, alpha, childValue)
    is FnExpr.Div -> evalFormulaAD(expr.a, x, alpha, childValue) / evalFormulaAD(expr.b, x, alpha, childValue)
    is FnExpr.Neg -> -evalFormulaAD(expr.a, x, alpha, childValue)
    is FnExpr.Sin -> sin(evalFormulaAD(expr.a, x, alpha, childValue))
    is FnExpr.Cos -> cos(evalFormulaAD(expr.a, x, alpha, childValue))
    is FnExpr.Exp -> exp(evalFormulaAD(expr.a, x, alpha, childValue))
    is FnExpr.Ln -> ln(evalFormulaAD(expr.a, x, alpha, childValue))
    is FnExpr.Tanh -> tanh(evalFormulaAD(expr.a, x, alpha, childValue))
    is FnExpr.Pow -> pow(
        evalFormulaAD(expr.a, x, alpha, childValue),
        evalFormulaAD(expr.b, x, alpha, childValue)
    )
    is FnExpr.Min -> min(
        evalFormulaAD(expr.a, x, alpha, childValue),
        evalFormulaAD(expr.b, x, alpha, childValue)
    )
    is FnExpr.Max -> max(
        evalFormulaAD(expr.a, x, alpha, childValue),
        evalFormulaAD(expr.b, x, alpha, childValue)
    )
    is FnExpr.Clamp01 -> evalFormulaAD(expr.a, x, alpha, childValue).clamp(0.0, 1.0)
    is FnExpr.CoerceIn -> min(
        max(evalFormulaAD(expr.a, x, alpha, childValue), evalFormulaAD(expr.lo, x, alpha, childValue)),
        evalFormulaAD(expr.hi, x, alpha, childValue)
    )
    is FnExpr.IfZero -> if (evalFormulaAD(expr.test, x, alpha, childValue).value == 0.0) {
        evalFormulaAD(expr.then, x, alpha, childValue)
    } else {
        evalFormulaAD(expr.otherwise, x, alpha, childValue)
    }
    is FnExpr.IfEq -> if (
        evalFormulaAD(expr.a, x, alpha, childValue).value ==
            evalFormulaAD(expr.b, x, alpha, childValue).value
    ) {
        evalFormulaAD(expr.then, x, alpha, childValue)
    } else {
        evalFormulaAD(expr.otherwise, x, alpha, childValue)
    }
    is FnExpr.IfLt -> if (
        evalFormulaAD(expr.a, x, alpha, childValue).value <
            evalFormulaAD(expr.b, x, alpha, childValue).value
    ) {
        evalFormulaAD(expr.then, x, alpha, childValue)
    } else {
        evalFormulaAD(expr.otherwise, x, alpha, childValue)
    }
    is FnExpr.AtX -> evalFormulaAD(
        expr.body,
        evalFormulaAD(expr.newX, x, alpha, childValue),
        alpha,
        childValue
    )
}
