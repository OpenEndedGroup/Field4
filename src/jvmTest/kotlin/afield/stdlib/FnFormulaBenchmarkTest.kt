package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The measurement behind a design decision: **should `Fn.eval` be replaced by the [FnFormulas] tree
 * walk, so there is literally one traversal rather than one declaration and two walkers?**
 *
 * The seam exists — every op in `Functions` builds its `Fn` through one private factory,
 * `graphFn(domain, name, op, …, eval: (Double) -> Double)` at `Functions.kt:886`, so a
 * formula-derived evaluator could be substituted there without touching the individual factories.
 * The question is only what it costs, because `Fn.eval` is the hottest call in the library: one
 * `fitGradient` run at the default settings is 6801 loss evaluations × 128 samples ≈ 8.7·10⁵
 * evaluations of the whole graph, and plotting, `FnMetric`, `evolve` and every sampler go through it
 * too.
 *
 * This is not a microbenchmark harness — there is no JMH in this build — so it warms up, runs several
 * timed rounds and reports the best, which is enough to separate "a few percent" from "several
 * times". The numbers it prints are quoted in `dev_docs/autodiff.md`; re-run it if you want fresh
 * ones on your machine.
 */
class FnFormulaBenchmarkTest {

    private fun timeBest(rounds: Int, iterations: Int, body: (Int) -> Double): Pair<Double, Double> {
        var best = Double.MAX_VALUE
        var checksum = 0.0
        repeat(rounds) {
            val started = System.nanoTime()
            var acc = 0.0
            for (i in 0 until iterations) acc += body(i)
            val elapsed = (System.nanoTime() - started).toDouble() / iterations
            checksum = acc
            if (elapsed < best) best = elapsed
        }
        return best to checksum
    }

    @Test
    fun theFormulaWalkIsMeasuredAgainstTheHandWrittenClosure() = with(Functions) {
        val cases = listOf(
            "sin" to sin(1.5, amplitude = 0.8, offset = 0.2),
            "cos*sin" to (cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)),
            "gaussian*sin" to (gaussian(0.45, 0.25, 0.9) * sin(3.0, amplitude = 0.8, offset = 1.0))
        )
        val iterations = 400_000
        val rounds = 5

        println("--- FnFormula walk vs hand-written Fn.eval closure ---")
        for ((name, fn) in cases) {
            // Warm both paths through the same JIT-visible shapes before timing either.
            var warm = 0.0
            for (i in 0 until 200_000) {
                val x = i / 200_000.0
                warm += fn.eval(x) + FnAutoDiff.evalByFormula(fn, x)
            }
            assertTrue(warm.isFinite(), "warmup produced a non-finite accumulator")

            val (closureNs, closureSum) = timeBest(rounds, iterations) { i -> fn.eval(i / iterations.toDouble()) }
            val (walkNs, walkSum) = timeBest(rounds, iterations) { i ->
                FnAutoDiff.evalByFormula(fn, i / iterations.toDouble())
            }
            // Identical work, so identical sums — the timings are comparing the same computation.
            assertEquals(
                closureSum.toRawBits(), walkSum.toRawBits(),
                "$name: the two paths did not compute the same thing"
            )
            val ratio = walkNs / closureNs
            println(
                "BENCH %-14s closure %7.2f ns   formula-walk %7.2f ns   ratio %5.2fx"
                    .format(name, closureNs, walkNs, ratio)
            )
        }

        // And the thing the whole exercise is for: what a real gradient step costs either way.
        val target = sin(1.5, amplitude = 0.8, offset = 0.2)
        val seed = sin(1.0, amplitude = 1.0, offset = 0.0)
        val domain = Functions.Domain(0.0, 1.0)
        repeat(2) {
            fitGradient(target, seed, domain = domain, iterations = 60, lr = 0.05)
            fitGradient(target, seed, domain = domain, iterations = 60, lr = 0.05, gradient = FnGradientMode.EXACT)
        }
        val fdStart = System.nanoTime()
        val fd = fitGradient(target, seed, domain = domain, iterations = 400, lr = 0.05)
        val fdMs = (System.nanoTime() - fdStart) / 1e6
        val exStart = System.nanoTime()
        val exact = fitGradient(
            target, seed, domain = domain, iterations = 400, lr = 0.05,
            gradient = FnGradientMode.EXACT
        )
        val exMs = (System.nanoTime() - exStart) / 1e6
        println(
            "BENCH fitGradient 400 steps: finite-difference %.1f ms (%d evals, RMSE %.5f)  ".format(
                fdMs, fd.evaluations, fd.finalError
            ) + "exact %.1f ms (%d evals, RMSE %.5f)  speedup %.2fx".format(
                exMs, exact.evaluations, exact.finalError, fdMs / exMs
            )
        )
        assertTrue(exact.usedExactGradient)
    }
}
