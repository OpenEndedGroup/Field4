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
 * `AutoDiffFnBridgeTest.theFormulaTableIsABitIdenticalTranscriptionOfFnEval` closes the loop: for
 * every op with a formula it asserts the `Double` walker's output is bit-identical (`toRawBits`) to
 * `Functions`' own `eval` across a grid **and** at perturbed parameters. A formula that is not a
 * faithful transcription cannot reach `main`.
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

    /**
     * Absolute value — `kotlin.math.abs`.
     *
     * Kinked at zero, and the derivative reported there is **0**: this library splits a tie evenly,
     * and `|x| = max(x, -x)` under a 0.5/0.5 split gives `0.5·1 + 0.5·(-1) = 0`. That is also
     * exactly what a central difference reports at the corner, so the analytic gradient and the
     * finite-difference oracle agree *at* the kink and not merely either side of it. See the kink
     * convention in `autodiff.kt`.
     */
    data class Abs(val a: FnExpr) : FnExpr

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
        // `exp` is `log`'s counterpart and was for a long time the asymmetry that gave this family
        // away: `log` was a real graph node and `exp` was a `mapY` lambda, so a fit could descend
        // through one and was refused by the other for no mathematical reason. Its derivative is
        // itself. `flip` is a negation (derivative -1) and `abs` a kink (0 at zero, even split).
        put("exp", FnExpr.Exp(child("source")))
        put("flip", FnExpr.Neg(child("source")))
        put("abs", FnExpr.Abs(child("source")))
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
    is FnExpr.Abs -> kotlin.math.abs(evalFormula(expr.a, x, alpha, childValue))
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
    // `abs`'s tracked twin already reports 0 at exactly zero — the even-split convention.
    is FnExpr.Abs -> abs(evalFormulaAD(expr.a, x, alpha, childValue))
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

// ---------------------------------------------------------------------------------------------
// The other 90 ops: why not, and which of them are closable
// ---------------------------------------------------------------------------------------------

/**
 * Why an `Fn` op has no derivative, and whether that is permanent.
 *
 * [closable] is the honest distinction the coverage story needs: `NOT_DIFFERENTIABLE` means there is
 * no derivative to write (a seeded hash, a baked table, an opaque lambda), while `closable = true`
 * means the formula exists and simply has not been transcribed yet, with [missing] naming what it
 * would take. Both are failures of coverage; only one is a failure of *ambition*.
 */
data class FnOpStatus(
    /** One line, in prose, for a diagnostic or a docs table. */
    val reason: String,
    /** True when a derivative could be written; false when there genuinely is not one. */
    val closable: Boolean,
    /** What is missing, when [closable]. Empty otherwise. */
    val missing: String = ""
)

/**
 * Every op in [OpRegistry] that [FnAutoDiff] cannot differentiate, with a declared reason.
 *
 * This exists so the gap cannot grow silently. `FnOpCoverageTest` enumerates
 * [OpRegistry.knownOps], resolves aliases to canonical names, and **fails** if any op appears in
 * neither [FnAutoDiff.differentiableOps] nor here — the same "make the guard part of the build"
 * pattern the KDoc-attachment check uses. Adding an op to `Functions` and `OpRegistry` without either
 * a formula or an explicit "no, because…" is therefore not shippable.
 *
 * Measured by `FnOpCoverageTest`, which prints the fraction rather than asking you to trust it:
 * **31 of 122 canonical ops are differentiable (25.4%)** — 29 formulas plus the two numeric-leaf ops —
 * and of the 91 declared non-differentiable, **35 are closable**. The single highest-value entry in
 * that backlog is the piecewise-oscillator group (`saw`, `triangle`, `square`, the pulses), which
 * needs only an `FnExpr.Floor`, because oscillator fitting is the flagship use of [fitGradient].
 *
 * The 2026-07-31 pass moved four ops out of `mapY` and into the registry, which is why both the
 * numerator and the denominator grew: `abs`, `flip` and `exp` gained formulas, and `sign` gained a
 * node but no derivative. `step` moved from the closable backlog into the same reasoned refusal as
 * `sign`, which is why the closable count fell by one while the declared count rose.
 */
object FnNonDifferentiable {

    private fun MutableMap<String, FnOpStatus>.group(
        reason: String,
        closable: Boolean,
        missing: String,
        vararg ops: String
    ) {
        for (op in ops) put(op.lowercase(), FnOpStatus(reason, closable, if (closable) missing else ""))
    }

    private val table: Map<String, FnOpStatus> = buildMap {
        group(
            "the node carries a user lambda, not a recipe — there is nothing to read a formula out of",
            closable = false, missing = "",
            "lambda", "opaque", "mapX", "mapY", "withEdge"
        )
        group(
            "shapes x or the value through an Easing enum, so the formula is chosen by a string at " +
                "evaluation time rather than fixed by the node",
            closable = true,
            missing = "one FnExpr per Easing constant plus a string-dispatch FnExpr kind (13 easings)",
            "ease", "tween"
        )
        group(
            "a piecewise oscillator: differentiable almost everywhere, with a countable set of corners " +
                "the sample grid will usually miss",
            closable = true,
            missing = "FnExpr.Floor (derivative 0) and a modulo built from it; then the saw/triangle/" +
                "square/pulse formulas transcribe directly. The highest-value gap left, since " +
                "oscillator fitting is the flagship use of fitGradient",
            "saw", "triangle", "square", "pulseTrain", "pulseburst", "pulsepattern"
        )
        group(
            "a seeded generator: the value is a hash of position, so it has no derivative with respect " +
                "to frequency or seed at all (only with respect to amplitude, which is not worth a " +
                "special case on its own)",
            closable = false, missing = "",
            "whiteNoise", "valueNoise", "perlinNoise", "fbm", "fbmPerlin", "randomWalk"
        )
        group(
            "identity lives in baked point/knot data the node carries rather than in role-addressed " +
                "children, so there is no closed form in terms of children to differentiate",
            closable = false, missing = "",
            "piecewise", "piecewiseLinear", "catmullRom", "monotoneCubic", "hermite",
            "bSplineQuadratic", "cosineInterp", "smoothStepInterp", "smootherStepInterp",
            "stepHold", "nearestValue", "rbf", "granular"
        )
        group(
            "selects which sub-Fn is in force from data-defined breakpoints in x, so it is piecewise " +
                "with a variable number of pieces",
            closable = false, missing = "",
            "timeline", "timelineTap", "spliceWindow", "sequence", "sequenceLoop", "randomSequence",
            "weightedSequence", "randomPermute", "randomPermuteLoop", "palindrome", "roundRobin",
            "array", "cut", "replace", "echo", "copyPaste", "randomCopyPaste", "randomRepeat"
        )
        group(
            "restructures the x axis by folding, tiling or wrapping the lookup, which is piecewise in x",
            closable = true,
            missing = "FnExpr.Floor for the period arithmetic; then each is an AtX over a wrapped x",
            "repeat", "mirror", "mirrorRepeat", "tile", "pad", "stretch", "reverse", "quantizeX", "jitterX"
        )
        group(
            "holds the last sampled value, so its output depends on evaluation history rather than on x " +
                "alone",
            closable = false, missing = "",
            "sampleHold", "gateHold"
        )
        group(
            "blends however many scheduled pattern atoms happen to cover x, so both the number of terms " +
                "and where they sit are decided by the rhythm and by cps rather than fixed by the node — " +
                "there is no expression tree of a known shape to differentiate. Finite differences fit it " +
                "perfectly well, and that is the default",
            closable = false, missing = "",
            "pat"
        )
        group(
            "blends or selects among an arbitrary number of item children",
            closable = true,
            missing = "an n-ary FnExpr kind addressing item0..itemN, which the current Child(role) " +
                "cannot express",
            "average", "stack", "select", "morph", "crossfade"
        )
        group(
            "a non-smooth value transform: quantising (derivative 0 almost everywhere) or reflecting " +
                "(piecewise linear with corners)",
            closable = true,
            missing = "clip and saturate are two-line transcriptions; wrapY, quantize and crush " +
                "additionally need FnExpr.Floor, and fold an arc-sine. FnExpr.Abs now exists — abs " +
                "uses it — so rectify is pointwise-expressible, and the one thing it still lacks is " +
                "a way to dispatch on the RectifyMode string the node carries, which no FnExpr kind " +
                "can read",
            "clip", "saturate", "fold", "wrapY", "quantize", "crush", "rectify"
        )
        group(
            "a step function of its input: the derivative is zero everywhere it exists and undefined " +
                "on the crossing itself, so a formula would be honest arithmetic and a dishonest " +
                "gradient — every partial threaded through one is exactly zero, and a fit would " +
                "report convergence without ever having moved. Refusing says that; returning zero " +
                "would hide it",
            closable = false, missing = "",
            // `sign` arrived with the 2026-07-31 first-class-op pass, which gave abs/flip/exp
            // formulas and left this one out on purpose. `step` moved here from the non-smooth
            // value-transform group above at the same time: it was declared closable pending
            // "FnExpr.Floor and/or Abs", but it needs neither (`IfLt` already expresses it) and the
            // real reason it has no formula is the one stated here. Refusing `sign` on this ground
            // while calling `step` a backlog item would have been incoherent.
            "sign", "step"
        )
        group(
            "evaluates a Chebyshev recurrence to a depth given by a *child*, so the expression's own " +
                "shape depends on a runtime value",
            closable = true,
            missing = "a bounded-recurrence FnExpr kind, or a formula per integer order",
            "chebyshev"
        )
        group(
            "not pointwise: computes over a window of samples, so its derivative is with respect to a " +
                "whole neighbourhood rather than one x",
            closable = false, missing = "",
            "derivative", "differentiate", "integral", "convolve", "movingAverage", "blur",
            "gaussianSmooth", "autoScale", "zNormalize", "whitenCdf"
        )
        group(
            "reads a normalised position within a *shape* domain that the node does not carry in a " +
                "recoverable form",
            closable = true,
            missing = "blackmanWindow is recoverable from fn.domain today (three cosine terms); " +
                "gaussianWindow keeps its shape domain only as a formatted string and would need it " +
                "stored structurally first",
            "gaussianWindow", "blackmanWindow"
        )
        group(
            "a parameter helper that reciprocates or rescales a single child",
            closable = true,
            missing = "three one-line FnExpr entries; skipped only because these rarely carry the " +
                "parameters a fit is tuning",
            "period", "periodFromFrequency", "dutyFromWidth"
        )
    }

    /** The declared status of [op], or null when this op has a formula (or is unknown to the registry). */
    fun statusOf(op: String): FnOpStatus? = table[op.trim().lowercase()]

    /** Every op declared non-differentiable, lower-cased. */
    val ops: Set<String> get() = table.keys

    /** Ops whose derivative could be written but has not been — the honest backlog. */
    val closable: List<String> get() = table.entries.filter { it.value.closable }.map { it.key }.sorted()

    /**
     * A one-line explanation for a diagnostic: either the declared reason, or a note that the op is
     * differentiable, or that nothing is known about it.
     */
    fun explain(op: String): String {
        val key = op.trim().lowercase()
        statusOf(key)?.let { return "'$op' is not differentiable: ${it.reason}" + if (it.closable) " (closable: ${it.missing})" else "" }
        if (key in FnAutoDiff.differentiableOps) return "'$op' is differentiable"
        return "'$op' is not classified — see FnFormulas and FnNonDifferentiable"
    }
}
