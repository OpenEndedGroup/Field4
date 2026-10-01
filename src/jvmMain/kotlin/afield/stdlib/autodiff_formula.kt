@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Function")

package afield.stdlib

/**
 * One declaration of each `Fn` op's pointwise semantics, walked by two evaluators — so `Fn.eval` and
 * the reverse-mode gradient cannot compute different functions.
 *
 * ## One declaration, two walkers
 *
 * The per-op formula lives in exactly one place — [FnFormulas] — as a tiny expression tree
 * ([FnExpr]) over the op's role-addressed children and the sample position `x`. Two walkers consume
 * it:
 *
 * - [evalFormula] in `Double`, transcribed to be **bit-identical** to the hand-written closure in
 *   `Functions.kt` (same operations, same order, same `Math.pow` / `coerceIn` calls);
 * - [evalFormulaAD] in tracked [AD] arithmetic.
 *
 * The AD side therefore has **no per-op code at all**. Adding an op means adding one [FnExpr] and
 * the gradient follows, so a transcription error has one place to hide per [FnExpr] kind rather
 * than one per op.
 *
 * `AutoDiffFnBridgeTest.theFormulaTableIsABitIdenticalTranscriptionOfFnEval` is what holds the two
 * walkers together: for every op with a formula it compares the `Double` walker's output to
 * `Functions`' own `eval` as raw bits (`toRawBits`), over a 129-point grid at three parameter
 * vectors. A formula that is not a faithful transcription fails in CI.
 *
 * `FnAutoDiff.agrees` — the runtime value-agreement check the fitters run before switching to
 * exact gradients — stays as well, because it catches what the table cannot express at all: an
 * unbounded shape domain, an inverted `clamp` range.
 *
 * ## The cost of the walk
 *
 * Making the walk the only traversal would take one edit: every op in `Functions` builds its `Fn`
 * through one private factory, `graphFn(domain, name, op, …, eval: (Double) -> Double)`, so a
 * formula-derived evaluator could go in there without touching the individual factories.
 *
 * It is not done because the walk costs several times a closure call, and `Fn.eval` is where that
 * lands hardest: it is the hottest call in the library, and one `fitGradient` at the defaults is
 * `200 × (2·leaves + 1) × 128` whole-graph evaluations, with plotting, `FnMetric`, `evolve` and
 * every sampler on the same path. The cost is the per-node `when` dispatch and the `Child` role
 * lookup — a linear scan of `node.children` — against a closure the JIT inlines wholesale.
 * `FnFormulaBenchmarkTest` prints the ratio for three graphs; it asserts only that the two paths
 * accumulate bit-identical sums, so those timings are a report and not a contract.
 *
 * The two costs to attack first would be that role lookup (pre-resolve `Child(role)` to a child
 * index when the formula is built) and the recursive `when` (flatten to a post-order array of
 * opcodes with a value stack).
 */

/**
 * A node of an op's formula: an expression over the sample position, the op's role-addressed
 * children, and literals.
 *
 * Small and total — no user-extensible escape hatch — because every kind here has to be
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

    /**
     * `kotlin.math.floor` — the greatest integer not above the value.
     *
     * A staircase, and the derivative reported is **0**: that is not a fudge, it is the true
     * derivative everywhere the function is differentiable, which is everywhere except a measure-zero
     * set of integers. At an integer there is no derivative and 0 is what a central difference
     * reports either side, so the analytic answer and the finite-difference oracle agree.
     *
     * That makes it useless *on its own* — a fit whose only path to a parameter runs through a bare
     * floor will report convergence without moving — and valuable *inside* something else, which is
     * the whole reason it is here. Period arithmetic is `t - p·floor(t/p)`, and the wrapped time is
     * a perfectly good differentiable function of `p` on either side of a wrap. See the
     * piecewise-oscillator entry in [FnNonDifferentiable], which named this kind as the missing
     * piece before it existed.
     */
    data class Floor(val a: FnExpr) : FnExpr

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

    /**
     * `kotlin.math.atan` — the principal arc tangent, in `(-pi/2, pi/2)`.
     *
     * Smooth at every double, with derivative `1/(1+x²)`, and that is the rarity worth pointing at.
     * The last two kinds added to this family report a derivative of **zero** — [Floor] everywhere,
     * [Abs] at its kink — correctly, and uselessly on their own. An angle built out of this one
     * carries a real gradient, so a fit can descend through a heading rather than stalling on it.
     *
     * It is **not** how [Atan2] is built. See that entry for why not.
     */
    data class Atan(val a: FnExpr) : FnExpr

    /**
     * `kotlin.math.atan2(y, x)` — the quadrant-correct angle of the vector `(x, y)`, in `[-pi, pi]`.
     *
     * A kind of its own rather than `Atan(y/x)` plus a quadrant fix-up, and the reason is a signed
     * zero. `RectSLSimplify`'s rule table already records that `atan2(+0.0, -1.0)` is `+pi` while
     * `atan2(-0.0, -1.0)` is `-pi` — half a turn apart — and that a rewrite which loses the sign of a
     * zero can therefore spin a rectangle through 180 degrees. The composed form loses it in its
     * first operation: `-0.0 / -1.0` is `+0.0`, so `atan(y/x) + pi` answers `+pi` where the truth is
     * `-pi`, and nothing downstream can recover it, because every comparison an [FnExpr] can make
     * treats `+0.0` and `-0.0` as equal. `AutoDiffFnBridgeTest` measures that failure rather than
     * leaving this paragraph as a claim.
     *
     * The partials are `∂/∂y = x/(x²+y²)` and `∂/∂x = -y/(x²+y²)` — finite and correctly signed as
     * `x` crosses zero, which is the other reason `atan(y/x)` is the wrong shape for a differentiated
     * angle.
     */
    data class Atan2(val y: FnExpr, val x: FnExpr) : FnExpr

    /**
     * `kotlin.math.hypot` — `√(a²+b²)`, computed without an intermediate overflow.
     *
     * Here because a *threshold test* has to be transcribed exactly to be worth anything.
     * `afield.rectsl.HeadingTrack` widens its sampling window while `hypot(dx, dy) <= 1e-9`, and
     * `sqrt(dx*dx + dy*dy)` is a different function from `Math.hypot` by up to an ulp — enough to
     * take the other branch within an ulp of the threshold, which is a visible half-frame of the
     * wrong rotation rather than a rounding error. The derivative is the unit vector `(a, b)/hypot`,
     * and zero at the origin, where there is no direction to report.
     */
    data class Hypot(val a: FnExpr, val b: FnExpr) : FnExpr

    /** `Math.pow(a, b)` — spelled as the real call, so a literal exponent of 2.0 stays bit-identical
     *  to the hand-written `.pow(2.0)` rather than being rewritten as `a * a`. */
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

    /**
     * The period-and-phase guard shared by [Functions.saw], `triangle` and `square`: [offset] when
     * `|period|` is zero, else [body] applied to `fract((x - phase) / |period|)`.
     *
     * The fraction is spelled `t - floor(t)` because that is `Functions.fract`, and the interval walker
     * recognises that shape structurally (as it does the smoothstep cubic) and bounds it to the
     * fractional part rather than to `t - floor(t)` taken as two unrelated numbers.
     */
    private fun periodic(body: (fraction: FnExpr) -> FnExpr): FnExpr {
        val p = FnExpr.Abs(child("period"))
        val t = FnExpr.Div(FnExpr.Sub(FnExpr.X, child("phase")), p)
        return FnExpr.IfZero(p, child("offset"), body(FnExpr.Sub(t, FnExpr.Floor(t))))
    }

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

        // ---- piecewise oscillators (Functions.saw / triangle / square) ----
        // Each is `offset + amplitude * shape(fract((x - phase) / |period|))`, with the closures'
        // association order kept so FnFormulaAgreementTest can hold them bit-identical. The corners
        // have a zero-measure gradient problem, not a formula problem: `d fract(t)/dt` is 1 wherever
        // it exists, which is what lets a fit move period and phase.
        put(
            "saw",
            periodic { f ->
                FnExpr.Add(
                    child("offset"),
                    FnExpr.Mul(child("amplitude"), FnExpr.Sub(FnExpr.Mul(f, lit(2.0)), lit(1.0)))
                )
            }
        )
        put(
            "triangle",
            periodic { f ->
                val twice = FnExpr.Mul(f, lit(2.0))
                val v = FnExpr.IfLt(f, lit(0.5), twice, FnExpr.Sub(lit(2.0), twice))
                FnExpr.Add(
                    child("offset"),
                    FnExpr.Mul(child("amplitude"), FnExpr.Sub(FnExpr.Mul(v, lit(2.0)), lit(1.0)))
                )
            }
        )
        put(
            "square",
            periodic { f ->
                FnExpr.Add(
                    child("offset"),
                    FnExpr.IfLt(f, FnExpr.Clamp01(child("duty")), child("amplitude"), FnExpr.Neg(child("amplitude")))
                )
            }
        )

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
        // `floor` is the staircase FnNonDifferentiable had been naming as its highest-value gap: on
        // its own the derivative is zero almost everywhere, but it is the one primitive the
        // period arithmetic of the piecewise oscillators is missing, and it is what lets RectSL's
        // `loop-time` and `pingpong-time` lower into an Fn at all.
        put("floor", FnExpr.Floor(child("source")))
        // The angle family. `atan` is the smooth one — a real derivative at every double, which is
        // what `floor` and `sign` are not — and `atan2` is a primitive rather than `atan(y/x)` with a
        // quadrant fix-up, because the fix-up is half a turn wrong at a negative zero. `hypot` came
        // with it: RectSL's HeadingTrack decides *which* atan2 to take by comparing a length to
        // 1e-9, and a length that is an ulp out takes the other branch. See the FnExpr entries.
        put("atan", FnExpr.Atan(child("source")))
        put("atan2", FnExpr.Atan2(child("source"), child("x")))
        put("hypot", FnExpr.Hypot(child("source"), child("y")))
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

    /**
     * Ops whose **value** is declared here but which are deliberately kept out of [ops], and so out of
     * [FnAutoDiff.differentiableOps].
     *
     * `step` and `sign` are refused by the gradient on purpose — see their entry in
     * [FnNonDifferentiable]: a formula would be honest arithmetic and a dishonest gradient. The
     * arithmetic is still worth having. It is what lets `evalFormulaInterval` bound them (and so bound
     * a signal assembled piecewise from them), and what lets [FnAutoDiff.evalByFormula] pin them
     * bit-identical to `Fn.eval`. [of] never returns these, so the AD walk cannot reach one.
     *
     * Both are written so a NaN takes the branch the closure takes: `x >= edge` is false for NaN,
     * which is not the same as `!(x < edge)`, and `Math.signum` returns its argument for NaN and ±0.
     */
    private val valueOnlyTable: Map<String, FnExpr> = buildMap {
        // Functions.step: if (x >= edge) 1.0 else 0.0. Written as "not below, and neither side NaN"
        // rather than "above, or equal": the two agree on every double, but only this shape lets an
        // interval that *starts* at the edge be decided — `edge < x || x == edge` cannot tell, over
        // [edge, edge + d], that one of the two always holds.
        put(
            "step",
            FnExpr.IfLt(
                FnExpr.X, child("edge"), lit(0.0),
                FnExpr.IfEq(FnExpr.X, FnExpr.X, FnExpr.IfEq(child("edge"), child("edge"), lit(1.0), lit(0.0)), lit(0.0))
            )
        )
        // Functions.sign: kotlin.math.sign, i.e. Math.signum
        put(
            "sign",
            FnExpr.IfLt(
                lit(0.0), child("source"), lit(1.0),
                FnExpr.IfLt(child("source"), lit(0.0), lit(-1.0), child("source"))
            )
        )
    }

    /** The formula for [op], or null when this op has no declared pointwise semantics. */
    fun of(op: String): FnExpr? = table[op.trim().lowercase()]

    /**
     * The formula that defines [op]'s *value* — [of], or else a [valueOnlyOps] entry. What the
     * `Double` and interval walkers read; never what the AD walk reads.
     */
    fun valueOf(op: String): FnExpr? = op.trim().lowercase().let { table[it] ?: valueOnlyTable[it] }

    /** Every op with a declared formula, lower-cased. */
    val ops: Set<String> get() = table.keys

    /** Ops with a declared value formula and a deliberately refused gradient, lower-cased. */
    val valueOnlyOps: Set<String> get() = valueOnlyTable.keys
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
    is FnExpr.Floor -> kotlin.math.floor(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Sin -> kotlin.math.sin(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Cos -> kotlin.math.cos(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Exp -> kotlin.math.exp(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Ln -> kotlin.math.ln(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Tanh -> kotlin.math.tanh(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Atan -> kotlin.math.atan(evalFormula(expr.a, x, alpha, childValue))
    is FnExpr.Atan2 -> kotlin.math.atan2(
        evalFormula(expr.y, x, alpha, childValue),
        evalFormula(expr.x, x, alpha, childValue)
    )
    is FnExpr.Hypot -> kotlin.math.hypot(
        evalFormula(expr.a, x, alpha, childValue),
        evalFormula(expr.b, x, alpha, childValue)
    )
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
    // A floor is recorded as a *constant* on the tape rather than as a tracked unary. That is the
    // whole of its derivative rule: the adjoint through it is exactly zero, with no node to
    // back-propagate along, which is both correct almost everywhere and free.
    is FnExpr.Floor -> x.tape.constant(
        kotlin.math.floor(evalFormulaAD(expr.a, x, alpha, childValue).value)
    )
    is FnExpr.Sin -> sin(evalFormulaAD(expr.a, x, alpha, childValue))
    is FnExpr.Cos -> cos(evalFormulaAD(expr.a, x, alpha, childValue))
    is FnExpr.Exp -> exp(evalFormulaAD(expr.a, x, alpha, childValue))
    is FnExpr.Ln -> ln(evalFormulaAD(expr.a, x, alpha, childValue))
    is FnExpr.Tanh -> tanh(evalFormulaAD(expr.a, x, alpha, childValue))
    // All three are already in `autodiff.kt` with the right partials — `atan` at :330, `atan2` at
    // :471 and `hypot` at :481 — so this side of the table is a lookup, not a transcription. That
    // asymmetry is the point of the file: the gradient of a new op is free once its formula exists.
    is FnExpr.Atan -> atan(evalFormulaAD(expr.a, x, alpha, childValue))
    is FnExpr.Atan2 -> atan2(
        evalFormulaAD(expr.y, x, alpha, childValue),
        evalFormulaAD(expr.x, x, alpha, childValue)
    )
    is FnExpr.Hypot -> hypot(
        evalFormulaAD(expr.a, x, alpha, childValue),
        evalFormulaAD(expr.b, x, alpha, childValue)
    )
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
 * [closable] is the distinction the coverage story needs: `NOT_DIFFERENTIABLE` means there is
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
 * Measured by `FnOpCoverageTest`, which prints the fraction rather than asking you to trust it, so
 * the number in `dev_docs/autodiff.md` can be checked rather than believed. The single highest-value
 * entry in the backlog is still the piecewise-oscillator group (`saw`, `triangle`, `square`, the
 * pulses), because oscillator fitting is the flagship use of [fitGradient] — but as of 2026-08-06 it
 * is a pure transcription job rather than a missing primitive: `FnExpr.Floor` now exists, added
 * because RectSL's `loop-time` and `pingpong-time` needed exactly the same period arithmetic, so
 * `t - p * floor(t / p)` is expressible today.
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
            "a shaped pulse generator: the edge and body shapes are chosen by PulseEdge/PulseProfile " +
                "enums and each cycle is jittered by a seeded hash of its index",
            closable = true,
            missing = "an FnExpr per PulseEdge × PulseProfile pair and a jitter-free special case; saw, " +
                "triangle and square were transcribed on 2026-10-01 with the fract shape " +
                "`t - floor(t)`, which is the template",
            "pulseTrain", "pulseburst", "pulsepattern"
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
            missing = "only the transcription now: FnExpr.Floor exists, so the period arithmetic is " +
                "available and each of these is an AtX over a wrapped x",
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
            missing = "clip and saturate are two-line transcriptions; wrapY, quantize and crush are " +
                "now transcriptions too, since FnExpr.Floor exists, and fold needs an arc-sine. " +
                "FnExpr.Abs also exists — abs uses it — so rectify is pointwise-expressible, and the " +
                "one thing it still lacks is " +
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
            // Their *values* are declared in `FnFormulas.valueOnlyOps` since 2026-09-23, so the
            // interval walk can bound them and `evalByFormula` pins them; the gradient still refuses.
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

    /** Ops whose derivative could be written but has not been — the real backlog. */
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
