package afield.stdlib

import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge tests: that the [AD] re-interpretation of an `Fn` graph computes the *same function*
 * `eval` does, that its gradient with respect to the numeric leaves lines up index-for-index with
 * [extractParameters], and — the load-bearing one — that turning the exact path *on* is genuinely
 * opt-in, because the default path is proved byte-identical to what it was before this branch.
 *
 * The golden bit patterns in [defaultFitGradientPathIsByteIdenticalToTheHistoricOne] were captured
 * by running exactly these two calls at the base commit f7d1601b, before `FunctionFit.kt` and
 * `LeastSquares.kt` were touched. They are raw `Double` bits, not approximations, so any change to
 * the finite-difference path at all — a reordered operation, a different accumulation — turns them
 * red.
 */
class AutoDiffFnBridgeTest {

    private val domain = Functions.Domain(0.0, 1.0)

    // ---- byte-identical default path ----------------------------------------------------------

    @Test
    fun defaultFitGradientPathIsByteIdenticalToTheHistoricOne() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0)
        val run = fitGradient(target, seed, domain = domain, iterations = 40, lr = 0.05)

        val goldenLosses = longArrayOf(
            4606819026628907982, 4605651185953400618, 4604437820768833126, 4603254038873775482,
            4601624154133676934, 4599656213696528381, 4597875188533065687, 4595662388802294315,
            4595081793113493823, 4596112233137871072, 4597573971202327005, 4598437059878650152,
            4598734886397517245, 4598769877676012209, 4598565933074407114, 4598126931940019534,
            4596943696204224482, 4595516569982319508, 4594074280460423748, 4592564669232427417,
            4592703875274173067, 4593871023643658620, 4594457425501546041, 4594648100670645909,
            4594372133922763915, 4593650023727483059, 4591661422961021121, 4590048636992190918,
            4590264685133641452, 4591344295393415473, 4591688083574112055, 4590927588415266895,
            4589296373364435657, 4586262297822096789, 4586801349226081594, 4588568358046213859,
            4588715435999025802, 4586884136755643532, 4582419456544326133, 4582624432825194878,
            4585537856142012933
        )
        val goldenParams = longArrayOf(
            4609487479766113692, 4582128490773111086, 4605461832591020245, 4596611110952523993
        )
        assertEquals(goldenLosses.size, run.losses.size, "loss trajectory length changed")
        for (i in goldenLosses.indices) {
            assertEquals(
                goldenLosses[i], run.losses[i].toRawBits(),
                "loss[$i] drifted: ${run.losses[i]} vs golden ${Double.fromBits(goldenLosses[i])}"
            )
        }
        for (i in goldenParams.indices) {
            assertEquals(
                goldenParams[i], run.parameters[i].toRawBits(),
                "theta[$i] drifted: ${run.parameters[i]} vs golden ${Double.fromBits(goldenParams[i])}"
            )
        }
        assertEquals(361, run.evaluations, "evaluation count changed, so the FD path changed")
        assertFalse(run.usedExactGradient, "the default must stay on finite differences")
        assertEquals(FnGradientMode.FINITE_DIFFERENCE, run.config.gradient)
    }

    @Test
    fun defaultLeastSquaresPathIsByteIdenticalToTheHistoricOne() {
        val xs = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
        val ys = xs.map { 2.3 * kotlin.math.exp(-1.7 * it) + 0.4 }
        val result = leastSquares(doubleArrayOf(1.0, -1.0, 0.0)) { p ->
            DoubleArray(xs.size) { i -> p[0] * kotlin.math.exp(p[1] * xs[i]) + p[2] - ys[i] }
        }
        val goldenX = longArrayOf(4612361558371493471, -4613037098315598965, 4600877379321698773)
        for (i in goldenX.indices) {
            assertEquals(goldenX[i], result.x[i].toRawBits(), "x[$i] drifted: ${result.x[i]}")
        }
        assertEquals(4187048580466343936L, result.cost.toRawBits(), "cost drifted: ${result.cost}")
        assertEquals(6, result.iterations)
        assertEquals("improvement", result.reason)
    }

    // ---- the interpreter computes the same function --------------------------------------------

    /** A spread of graphs made only of ops in [FnAutoDiff.differentiableOps]. */
    private fun differentiableGraphs(): List<Pair<String, Functions.Fn>> = with(Functions) {
        listOf(
            "sin" to sin(1.5, amplitude = 0.8, offset = 0.2),
            "sin+cos" to (sin(1.0, amplitude = 0.6, offset = 0.1) + cos(2.0, amplitude = 0.4)),
            "cos*sin" to (cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)),
            "sin-cos" to (sin(1.3, amplitude = 0.9) - cos(0.7, amplitude = 0.3)),
            "sin/const" to (sin(1.1, amplitude = 0.5, offset = 2.0) / c(1.7, Functions.Domain.ALL)),
            "scaleY.offsetY" to sin(1.2, amplitude = 0.7).scaleY(1.3).offsetY(-0.2),
            "shift" to sin(2.0, amplitude = 0.55).shift(0.1),
            "gaussian" to gaussian(0.5, 0.2, 1.0),
            "gaussian*sin" to (gaussian(0.45, 0.25, 0.9) * sin(3.0, amplitude = 0.8, offset = 1.0)),
            "named" to sin(1.4, amplitude = 0.65).withName("gesture"),
            // Value transforms. `pow` and `log` keep their source strictly positive at every theta the
            // oracle test probes, so neither the base nor the exponent partial goes undefined.
            "pow" to sin(1.2, amplitude = 0.8, offset = 1.5).pow(2.0),
            "log" to sin(1.0, amplitude = 0.4, offset = 2.0).log(2.0),
            "clamp" to sin(1.4, amplitude = 1.5).clamp(-0.8, 0.8),
            "softClip" to sin(1.1, amplitude = 1.2).softClip(1.5),
            // The ex-`mapY` shapers, first-class ops since 2026-07-31. `exp` is kept on a source in
            // roughly -0.2..0.8 so the value stays O(1) and the oracle's relative tolerance means
            // something. `abs` is deliberately given a source that genuinely *crosses* zero — the
            // whole point of the op — so the folded branch is exercised, and x = 0 is on both sample
            // grids with `sin(…, phase = 0)` exactly zero there, so the kink itself is inside the
            // oracle comparison rather than only either side of it.
            "exp" to sin(1.0, amplitude = 0.5, offset = 0.3).exp(),
            "flip" to sin(1.2, amplitude = 0.7).flip(),
            "abs" to sin(1.3, amplitude = 0.9).abs(),
            // `floor` on a source that spans several integers, so the staircase really steps inside
            // the oracle's grid rather than sitting on one tread.
            "floor" to sin(1.1, amplitude = 3.0, offset = 0.4).floor(),
            // And a floor doing the job it was added for: a modulo, `t - p*floor(t/p)`, whose value
            // is a genuinely differentiable function of the period away from the wraps.
            "modulo" to (identity(Functions.Domain.ALL) -
                (c(0.3, Functions.Domain.ALL) * (identity(Functions.Domain.ALL) / c(0.3, Functions.Domain.ALL)).floor())),
            // The user-reported acceptance shape: an abs in the middle of a product, which is what
            // `fitGradient(gradient = EXACT)` used to refuse outright.
            "cos.abs*sin" to (cos(1.9).abs() * sin(1.3, amplitude = 1.0)),
            // x-domain transforms.
            "scaleX" to sin(2.0, amplitude = 0.6).scaleX(1.5, 0.25),
            "warp" to sin(2.0, amplitude = 0.6).warp(cos(1.0), 0.1),
            // Modulation.
            "amplitudeMod" to sin(2.0, amplitude = 0.7).amplitudeMod(cos(1.0), 0.5, 1.0),
            "ringMod" to sin(2.0, amplitude = 0.7).ringMod(cos(1.0), 0.6),
            // Shapes and windows, all on a bounded shape domain — the exact path refuses an unbounded
            // one rather than guessing at the plot-safe preview window Functions falls back to.
            "ramp" to ramp(0.2, 0.9, Functions.Domain(0.0, 1.0)),
            "remap" to remap(0.1, 0.9, Functions.Domain(0.0, 1.0)),
            "smoothstep" to smoothStep(0.25, 0.75, Functions.Domain(0.0, 1.0)),
            "sin*hann" to (sin(1.3, amplitude = 0.8) * hannWindow(Functions.Domain(0.0, 1.0))),
            "sin*hamming" to (sin(1.1, amplitude = 0.7) * hammingWindow(Functions.Domain(0.0, 1.0))),
            // `identity` is x itself; `over` is the relabel-only domain wrapper withDomain() emits.
            "identity*sin" to (identity(Functions.Domain(0.0, 1.0)) * sin(1.6, amplitude = 0.9)),
            "over" to sin(1.25, amplitude = 0.7).withDomain(Functions.Domain(0.0, 1.0))
        )
    }

    /**
     * Guards [differentiableGraphs] against drift: every op in [FnAutoDiff.differentiableOps] must
     * actually appear in one of the graphs above, so adding an op to the interpreter without an
     * agreement-and-gradient check fails here rather than shipping unverified.
     */
    @Test
    fun everyDifferentiableOpAppearsInAtLeastOneCheckedGraph() {
        val seen = mutableSetOf<String>()
        fun walk(fn: Functions.Fn) {
            val node = fn.graphNode()
            seen += node.op.lowercase()
            for (edge in node.children) walk(edge.fn)
        }
        for ((_, fn) in differentiableGraphs()) walk(fn)
        val unchecked = FnAutoDiff.differentiableOps.filterNot { it in seen }
        assertTrue(
            unchecked.isEmpty(),
            "ops in FnAutoDiff.differentiableOps with no graph in differentiableGraphs(): $unchecked"
        )
    }

    @Test
    fun everyDifferentiableGraphIsRecognisedAndReproducesEval() {
        val samples = DoubleArray(64) { it / 63.0 }
        for ((name, fn) in differentiableGraphs()) {
            assertTrue(FnAutoDiff.supports(fn), "$name should be supported, unsupported ops: ${FnAutoDiff.unsupportedOps(fn)}")
            val worst = FnAutoDiff.worstValueDisagreement(fn, samples)
            assertTrue(worst < 1e-12, "$name: interpreter differs from Fn.eval by $worst")
            assertTrue(FnAutoDiff.agrees(fn, samples), "$name should pass the agreement guard")
        }
    }

    /**
     * **The pin.** For every graph above, the `Double` walk of the one [FnFormulas] entry must be
     * *bit-identical* to `Functions`' own `eval` — not close, identical, compared as raw bits — across
     * a 129-point grid and at three perturbed parameter vectors.
     *
     * This is what replaces "two interpreters held together by a runtime guard" with "one declaration
     * and a test-time proof that it is a faithful transcription". Change an op's formula in
     * `Functions.kt` without changing [FnFormulas] and this goes red before it can ship, instead of
     * the exact gradient silently differentiating the old formula and the runtime guard quietly
     * demoting the run.
     */
    @Test
    fun theFormulaTableIsABitIdenticalTranscriptionOfFnEval() {
        val grid = DoubleArray(129) { it / 128.0 }
        for ((name, fn) in differentiableGraphs()) {
            val base = fn.extractParameters()
            val thetas = listOf(
                base,
                DoubleArray(base.size) { base[it] * 1.3 + 0.11 },
                DoubleArray(base.size) { base[it] - 0.19 }
            )
            for ((index, theta) in thetas.withIndex()) {
                val applied = fn.applyParameters(theta)
                for (x in grid) {
                    val direct = applied.eval(x)
                    val viaFormula = FnAutoDiff.evalByFormula(applied, x)
                    assertEquals(
                        direct.toRawBits(), viaFormula.toRawBits(),
                        "$name/theta$index at x=$x: Fn.eval gave $direct, the formula walk gave $viaFormula"
                    )
                }
            }
        }
    }

    /**
     * The AD walk and the `Double` walk of the same formula must agree on the *value* too — the third
     * corner of the triangle, and the one the runtime guard checks. Held to 1e-13 rather than bits
     * because the AD path accumulates through tape nodes.
     */
    @Test
    fun theAdWalkAgreesWithTheDoubleWalkOnValue() {
        val grid = DoubleArray(65) { it / 64.0 }
        for ((name, fn) in differentiableGraphs()) {
            for (x in grid) {
                val viaFormula = FnAutoDiff.evalByFormula(fn, x)
                val viaAd = fn.gradientAt(x).value
                if (!viaFormula.isFinite() && !viaAd.isFinite()) continue
                assertEquals(
                    viaFormula, viaAd, 1e-13 * (1.0 + kotlin.math.abs(viaFormula)),
                    "$name at x=$x: Double walk $viaFormula, AD walk $viaAd"
                )
            }
        }
    }

    @Test
    fun leafIndicesLineUpWithExtractParameters() {
        for ((name, fn) in differentiableGraphs()) {
            val theta = fn.extractParameters()
            assertEquals(theta.size, FnAutoDiff.leafIndices(fn).size, "$name: leaf count mismatch")
            // The interpreter, given the graph's own theta, must reproduce the graph's own value.
            for (x in listOf(0.05, 0.37, 0.62, 0.99)) {
                val g = fn.gradientAt(x)
                assertEquals(fn.eval(x), g.value, 1e-12, "$name at $x")
                assertEquals(theta.size, g.gradient.size, "$name: gradient width")
            }
        }
    }

    @Test
    fun theExactGraphGradientMatchesACentralDifferenceOracle() {
        val samples = DoubleArray(48) { it / 47.0 }
        for ((name, fn) in differentiableGraphs()) {
            val base = fn.extractParameters()
            // Three genuinely different theta points per graph, not just the one it was built with.
            val points = listOf(
                base,
                DoubleArray(base.size) { base[it] * 1.4 + 0.13 },
                DoubleArray(base.size) { base[it] - 0.27 }
            )
            for ((pointIndex, theta) in points.withIndex()) {
                // A plain sum-of-values objective isolates the graph's own derivative from any
                // metric's; the RMSE case is checked separately below.
                val exact = fn.valueAndGradOverSamples(theta, samples) { ys -> sum(ys) }
                val plain: (DoubleArray) -> Double = { t ->
                    val applied = fn.applyParameters(t)
                    samples.sumOf { applied.eval(it) }
                }
                assertEquals(plain(theta), exact.value, 1e-9 * (1.0 + plain(theta).absoluteValue), "$name/$pointIndex value")
                for (i in theta.indices) {
                    val h = 1e-5 * (1.0 + theta[i].absoluteValue)
                    val plus = theta.copyOf().also { it[i] += h }
                    val minus = theta.copyOf().also { it[i] -= h }
                    val oracle = (plain(plus) - plain(minus)) / (2.0 * h)
                    val scale = 1.0 + oracle.absoluteValue
                    assertTrue(
                        (exact.gradient[i] - oracle).absoluteValue / scale < 1e-5,
                        "$name/$pointIndex d/dtheta$i: exact ${exact.gradient[i]}, oracle $oracle"
                    )
                }
            }
        }
    }

    @Test
    fun theExactRmseGradientMatchesACentralDifferenceOfTheRealLoss() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8, offset = 0.2)
        val cases = listOf(
            "sin" to sin(1.0, amplitude = 1.0, offset = 0.0),
            "sin+cos" to (sin(1.2, amplitude = 0.9) + cos(1.7, amplitude = 0.5)),
            "gaussian*sin" to (gaussian(0.4, 0.3, 1.1) * sin(2.2, amplitude = 0.7, offset = 0.4))
        )
        for ((name, seed) in cases) {
            val objective = exactFitObjective(target, seed, domain, samples = 64, metric = FnAdMetric.rmse)
            assertNotNull(objective, "$name: exact objective should be available")
            val loss = FnMetric.rmse.lossAgainst(target, domain, 64)
            val base = seed.extractParameters()
            val points = listOf(base, DoubleArray(base.size) { base[it] + 0.31 }, DoubleArray(base.size) { base[it] * 0.6 })
            for ((pointIndex, theta) in points.withIndex()) {
                val exact = objective(theta)
                assertEquals(loss(seed.applyParameters(theta)), exact.value, 1e-12, "$name/$pointIndex loss value")
                for (i in theta.indices) {
                    val h = 1e-6 * (1.0 + theta[i].absoluteValue)
                    val plus = loss(seed.applyParameters(theta.copyOf().also { it[i] += h }))
                    val minus = loss(seed.applyParameters(theta.copyOf().also { it[i] -= h }))
                    val oracle = (plus - minus) / (2.0 * h)
                    val scale = 1.0 + oracle.absoluteValue
                    assertTrue(
                        (exact.gradient[i] - oracle).absoluteValue / scale < 1e-5,
                        "$name/$pointIndex dRMSE/dtheta$i: exact ${exact.gradient[i]}, oracle $oracle"
                    )
                }
            }
        }
    }

    // ---- honest refusal -----------------------------------------------------------------------

    @Test
    fun nonDifferentiableGraphsAreRefusedWithADiagnostic() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8)
        val sawSeed = saw(1.0, amplitude = 0.9)
        assertFalse(FnAutoDiff.supports(sawSeed))
        assertTrue("saw" in FnAutoDiff.unsupportedOps(sawSeed).map { it.lowercase() })
        assertNull(exactFitObjective(target, sawSeed, domain))
        val refusal = exactFitRefusal(target, sawSeed, domain)
        assertNotNull(refusal)
        assertTrue("saw" in refusal, "refusal should name the offending op: $refusal")

        // EXACT is loud about it.
        val failure = runCatching {
            fitGradient(target, sawSeed, domain = domain, iterations = 5, gradient = FnGradientMode.EXACT)
        }.exceptionOrNull()
        assertNotNull(failure, "EXACT must throw for a non-differentiable seed")
        assertTrue("saw" in (failure.message ?: ""), "message should name the op: ${failure.message}")

        // AUTO is quiet about it, and says so afterwards.
        val auto = fitGradient(target, sawSeed, domain = domain, iterations = 5, gradient = FnGradientMode.AUTO)
        assertFalse(auto.usedExactGradient, "AUTO should have fallen back")
        assertEquals(FnGradientMode.AUTO, auto.config.gradient)
    }

    @Test
    fun anUnrecognisedMetricIsRefusedByExactAndToleratedByAuto() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8)
        val seed = sin(1.0, amplitude = 1.0)
        // Numerically identical to FnMetric.rmse but a fresh instance, so FnAdMetric.of cannot
        // recognise it — the deliberate strictness documented on FnAdMetric.of.
        val lookalike = FnMetric.lp(2.0)
        assertNull(FnAdMetric.of(lookalike))
        val failure = runCatching {
            fitGradient(target, seed, domain = domain, iterations = 5, metric = lookalike, gradient = FnGradientMode.EXACT)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue("adMetric" in (failure.message ?: ""), "message should point at adMetric: ${failure.message}")

        val auto = fitGradient(target, seed, domain = domain, iterations = 5, metric = lookalike, gradient = FnGradientMode.AUTO)
        assertFalse(auto.usedExactGradient)

        // Passing the differentiable form explicitly unlocks the exact path.
        val explicit = fitGradient(
            target, seed, domain = domain, iterations = 5, metric = lookalike,
            gradient = FnGradientMode.EXACT, adMetric = FnAdMetric.rmse
        )
        assertTrue(explicit.usedExactGradient)
    }

    @Test
    fun aSeedNarrowerThanTheFitDomainIsRefused() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8)
        // c(...) carries a bounded domain, so this seed only exists on 0..0.5.
        val narrow = sin(1.0, amplitude = 1.0) * c(1.0, Functions.Domain(0.0, 0.5))
        assertNull(exactFitObjective(target, narrow, domain))
        val refusal = exactFitRefusal(target, narrow, domain)
        assertNotNull(refusal)
        assertTrue("outside" in refusal, "refusal should mention the domain mismatch: $refusal")
    }

    // ---- the exact path is better, and agrees on the answer ------------------------------------

    @Test
    fun theExactPathReachesTheSameMinimumWithFarFewerEvaluations() = with(Functions) {
        val target = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0)
        val fd = fitGradient(target, seed, domain = domain, iterations = 400, lr = 0.05)
        val exact = fitGradient(
            target, seed, domain = domain, iterations = 400, lr = 0.05,
            gradient = FnGradientMode.EXACT
        )
        assertTrue(exact.usedExactGradient)
        // Adam at a fixed step size wobbles around the optimum rather than settling on it, so the
        // claim here is "both land in the same small neighbourhood", not "both hit zero". Machine
        // precision on this same landscape is what L-BFGS-on-exact-gradients is for; see
        // AutoDiffOptimizeTest and the fn_autodiff_demo transcript.
        assertTrue(exact.finalError < 0.02, "exact fit final RMSE ${exact.finalError}")
        assertTrue(fd.finalError < 0.02, "finite-difference fit final RMSE ${fd.finalError}")
        assertTrue(exact.finalError < exact.initialError * 0.05, "exact fit barely improved: ${exact.initialError} -> ${exact.finalError}")
        // Observed at the time of writing: exact 0.00379 vs finite-difference 0.00998 — the exact
        // gradient lands in a better spot, which is the expected direction (no truncation error in
        // the descent direction) but is asserted only as "no worse", since one landscape is one
        // datapoint and Adam's wobble is what dominates either way.
        assertTrue(
            exact.finalError <= fd.finalError * 1.05,
            "the exact path should be no worse: exact ${exact.finalError} vs finite-difference ${fd.finalError}"
        )
        // 4 parameters, so finite differences costs ~9 loss evaluations per step against ~2.
        assertTrue(
            exact.evaluations * 3 < fd.evaluations,
            "exact used ${exact.evaluations} evaluations, finite differences ${fd.evaluations}"
        )
    }

    @Test
    fun fitBestAndFitHybridForwardTheGradientMode() = with(Functions) {
        val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = cos(3.4) * sin(1.0, amplitude = 2.0)
        val best = fitBest(
            target, seed, domain = domain, restarts = 3, iterations = 60,
            gradient = FnGradientMode.EXACT
        )
        assertTrue(best.usedExactGradient, "fitBest should forward EXACT to its inner fits")
        assertTrue(best.finalError < best.initialError)
    }

    // ---- least squares ------------------------------------------------------------------------

    @Test
    fun leastSquaresADAgreesWithTheFiniteDifferenceSolverAndItsJacobianIsExact() {
        val xs = doubleArrayOf(0.0, 0.2, 0.35, 0.5, 0.66, 0.8, 1.0, 1.4)
        val truth = doubleArrayOf(2.3, -1.7, 0.4)
        val ys = xs.map { truth[0] * kotlin.math.exp(truth[1] * it) + truth[2] }

        val plainResiduals: (DoubleArray) -> DoubleArray = { p ->
            DoubleArray(xs.size) { i -> p[0] * kotlin.math.exp(p[1] * xs[i]) + p[2] - ys[i] }
        }
        val adResiduals: (List<AD>) -> List<AD> = { p ->
            xs.indices.map { i -> p[0] * exp(p[1] * xs[i]) + p[2] - ys[i] }
        }

        // The exact Jacobian must match a central-difference Jacobian at three distinct points.
        for (point in listOf(doubleArrayOf(1.0, -1.0, 0.0), doubleArrayOf(2.0, -2.0, 1.0), doubleArrayOf(0.4, -0.3, -0.7))) {
            val exact = jacobian(point) { p -> adResiduals(p) }
            for (row in xs.indices) {
                for (col in point.indices) {
                    val h = 1e-6 * (1.0 + point[col].absoluteValue)
                    val plus = plainResiduals(point.copyOf().also { it[col] += h })[row]
                    val minus = plainResiduals(point.copyOf().also { it[col] -= h })[row]
                    val oracle = (plus - minus) / (2.0 * h)
                    val scale = 1.0 + oracle.absoluteValue
                    assertTrue(
                        (exact[row][col] - oracle).absoluteValue / scale < 1e-6,
                        "J[$row][$col] at ${point.toList()}: exact ${exact[row][col]}, oracle $oracle"
                    )
                }
            }
        }

        val fd = leastSquares(doubleArrayOf(1.0, -1.0, 0.0), residuals = plainResiduals)
        val ad = leastSquaresAD(doubleArrayOf(1.0, -1.0, 0.0), residuals = adResiduals)
        for (i in truth.indices) {
            assertTrue(
                (ad.x[i] - truth[i]).absoluteValue < 1e-8,
                "exact-Jacobian solver recovered ${ad.x[i]} for parameter $i, truth ${truth[i]}"
            )
            assertTrue(
                (ad.x[i] - fd.x[i]).absoluteValue < 1e-6,
                "the two solvers disagree on parameter $i: ${ad.x[i]} vs ${fd.x[i]}"
            )
        }
        assertTrue(ad.cost < 1e-20, "noiseless data should reach ~0 cost, got ${ad.cost}")
    }

    // ---- registry hygiene ---------------------------------------------------------------------

    @Test
    fun everyDifferentiableOpIsAKnownOpInTheRegistry() {
        val unknown = FnAutoDiff.differentiableOps.filterNot { OpRegistry.knows(it) }
        assertTrue(
            unknown.isEmpty(),
            "FnAutoDiff names ops OpRegistry has never heard of: $unknown — go through OpRegistry"
        )
    }
}
