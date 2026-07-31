package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seed that motivated making `abs` a real op, kept as a regression test in the form it was
 * reported.
 *
 * `cos(1.9) * sin(1.3, amplitude = 1.0)` fitted fine with `gradient = EXACT`. Putting an `abs()` on
 * the first factor — a strictly harder problem, and a reasonable thing to reach for — was **refused**,
 * because `Fn.abs()` was built with `mapY { … }`, which buries the function in an opaque
 * `(Double) -> Double` closure that carries no formula to differentiate. Nothing about `abs` is
 * undifferentiable; it was a representational accident, and one that also hid the op from `op=`
 * queries and from the rewrite/evolve layer.
 *
 * What this pins, in order of what would hurt most to lose:
 *
 *  1. the seed is **accepted** — `exactFitRefusal` returns null where it used to name `mapY`;
 *  2. the exact path is actually **taken** (`usedExactGradient`), not silently downgraded to finite
 *     differences, which would make the rest of the assertions vacuous;
 *  3. it costs far **fewer loss evaluations** than the finite-difference arm for the same descent;
 *  4. and `sign` is still refused, on purpose — see [FnNonDifferentiable]. Its derivative is zero
 *     wherever it exists, so a fit descending through it would be following a gradient that is
 *     always exactly zero, which is worse than being told no.
 *
 * Deliberately **not** asserted: that exact gradients reach a *lower* RMSE here. They do not, and
 * that is the honest result — on this kinked landscape both arms land in the same place
 * (0.1387 against 0.1388) and the win is the evaluation count, not the fit. The smooth seed on
 * `autodiff_examples.md` is the case where the error itself improves.
 */
class AbsSeedExactFitTest {

    @Test
    fun theAbsSeedIsAcceptedAndFitsWithExactGradients() = with(Functions) {
        val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = cos(1.9).abs() * sin(1.3, amplitude = 1.0)

        assertNull(
            exactFitRefusal(target, seed, Functions.Domain.DEFAULT),
            "an abs seed must no longer be refused by the exact gradient path",
        )

        val fd = fitGradient(target, seed, iterations = 400, lr = 0.05)
        val exact = fitGradient(target, seed, iterations = 400, lr = 0.05, gradient = FnGradientMode.EXACT)

        assertTrue(exact.usedExactGradient, "the exact path must be taken, not quietly downgraded")
        assertTrue(
            exact.finalError < exact.initialError,
            "the fit must descend: ${exact.initialError} -> ${exact.finalError}",
        )
        // 801 against 6801 when this was written: one loss evaluation plus a backward sweep per step,
        // instead of 2n extra evaluations per step for the central differences.
        assertTrue(
            exact.evaluations < fd.evaluations / 2,
            "exact gradients should cost far fewer loss evaluations: " +
                "${exact.evaluations} against ${fd.evaluations}",
        )
        // Same landscape, same optimiser, same step size, so the two arms should agree on where they
        // land even though only one of them is cheap.
        assertTrue(
            kotlin.math.abs(exact.finalError - fd.finalError) < 0.02,
            "the two arms should reach comparable error: ${exact.finalError} vs ${fd.finalError}",
        )

        println(
            "abs seed accepted; finite differences RMSE ${fd.initialError} -> ${fd.finalError} " +
                "in ${fd.evaluations} evals, exact ${exact.initialError} -> ${exact.finalError} " +
                "in ${exact.evaluations} evals"
        )
    }

    @Test
    fun signIsStillRefusedWithAReason() = with(Functions) {
        val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = cos(1.9).sign() * sin(1.3, amplitude = 1.0)

        val refusal = exactFitRefusal(target, seed, Functions.Domain.DEFAULT)
        assertNotNull(refusal, "sign has a zero derivative wherever it exists, so EXACT must refuse it")
        assertTrue(
            refusal.contains("sign"),
            "the refusal should name the op that caused it, found: $refusal",
        )
        println("sign is refused, as intended: $refusal")
    }
}
