package afield.solidsl

import afield.stdlib.ADTape
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The cost of a fit, measured — closing the "performance is unmeasured" risk this branch shipped
 * with rather than leaving it as a sentence.
 *
 * `autodiff.kt` states the shape of it about itself: "Tape size grows with the number of *operations
 * executed*, not with `n`. A loss over 100k samples builds a 100k-node tape per evaluation; that is
 * the memory cost of reverse mode and it is not chunked or checkpointed." This turns that into
 * numbers for *this* package's traffic, so a caller can decide whether their problem fits before
 * they discover it does not.
 *
 * # Tape size is asserted; wall time is measured separately
 *
 * The tape length is deterministic — the same expression over the same sample count executes exactly
 * the same operations every run — so it is asserted, tightly, including the per-sample constant.
 *
 * Wall time is not. It depends on the machine, on what the other two Claude sessions on this box are
 * doing tonight, and on whether the JIT has warmed. Asserting a millisecond figure would produce a
 * test that fails for reasons that have nothing to do with this code, which is worse than no test.
 * So the deterministic suite asserts the tape scaling, while [SolidSLFitCostMeasurements] prints
 * wall-time scaling through the opt-in `jvmMeasurements` task.
 */
class SolidSLFitCostTest {

    /** Tape nodes for one loss evaluation over [n] samples. */
    private fun tapeNodes(t: SolidTemplate, n: Int): Int {
        val target = t.withValues(t.seed).sampleField(count = n, seed = 1)
        val tape = ADTape()
        val vars = t.names.indices.map { tape.variable(t.seed[it]) }
        val field = t.adFieldWith(vars)
        var loss = tape.constant(0.0)
        for (i in 0 until target.size) {
            val e = field.at(tape.constant(target.xs[i]), tape.constant(target.ys[i]), tape.constant(target.zs[i])) -
                target.distances[i]
            loss += e * e
        }
        return tape.size
    }

    @Test
    fun `tape size is linear in the sample count, with a knowable per-sample constant`() {
        val t = fitCostTemplate()
        val counts = listOf(500, 1000, 2000, 4000)
        val nodes = counts.map { tapeNodes(t, it) }

        println("%8s %12s %14s %12s".format("samples", "tape nodes", "nodes/sample", "tape MB"))
        counts.indices.forEach { i ->
            // Five parallel primitive arrays of 8 bytes each: value, two parents, two partials.
            // (The parent arrays are Int, so this is an over-estimate by 8 bytes per node — stated
            // rather than silently corrected, because the over-estimate is the safe direction for a
            // budget.)
            val mb = nodes[i] * 5.0 * 8.0 / (1024 * 1024)
            println("%8d %12d %14.1f %12.2f".format(counts[i], nodes[i], nodes[i].toDouble() / counts[i], mb))
        }

        val perSample = nodes.last().toDouble() / counts.last()
        println("  per-sample cost settles at ${"%.1f".format(perSample)} tape nodes for this expression")
        println("  extrapolated: 100k samples -> ${"%.0f".format(perSample * 100_000)} nodes, " +
            "${"%.0f".format(perSample * 100_000 * 40.0 / (1024 * 1024))} MB per gradient evaluation")

        // Linear, tightly: doubling the samples doubles the tape to within a node or two of overhead.
        (1 until counts.size).forEach { i ->
            val ratio = nodes[i].toDouble() / nodes[i - 1]
            assertTrue(
                ratio > 1.98 && ratio < 2.02,
                "tape growth from ${counts[i - 1]} to ${counts[i]} samples was ${"%.4f".format(ratio)}x, " +
                    "not the 2x that linear-in-samples means",
            )
        }
        // And the per-sample constant is what it is. Floored and ceilinged near the measurement, so a
        // change to the lowering that doubles the work per sample shows up here as a number rather
        // than as a fit that got slower for no stated reason.
        assertTrue(
            perSample > 20.0 && perSample < 80.0,
            "this expression now costs ${"%.1f".format(perSample)} tape nodes per sample, outside the " +
                "band this test was calibrated at — the lowering's per-sample work has changed",
        )
    }

}

/** Opt-in wall-time evidence; excluded from `check` and run by `./gradlew jvmMeasurements`. */
class SolidSLFitCostMeasurements {
    @Test
    fun `measure a fit's wall time scaling across sample counts`() {
        val t = fitCostTemplate()
        val truth = t.seed
        val seed = doubleArrayOf(0.26, 1.05, 0.34)

        // Warm the JIT on a throwaway problem first: a cold first measurement is several times a
        // warm one and would make the scaling ratio meaningless.
        t.fitParameters(t.withValues(truth).sampleField(count = 400, seed = 2), seed)

        val counts = listOf(1000, 2000, 4000)
        val times = counts.map { n ->
            val target = t.withValues(truth).sampleField(count = n, seed = 2)
            val start = System.nanoTime()
            val fit = t.fitParameters(target, seed)
            val ms = (System.nanoTime() - start) / 1e6
            println(
                "%6d samples: %8.1f ms, %3d iterations, %4d evaluations, %6.3f ms/evaluation, loss %.3e".format(
                    n, ms, fit.run.iterations, fit.run.evaluations, ms / fit.run.evaluations, fit.loss,
                ),
            )
            ms to fit.run.evaluations
        }

        // Per-evaluation, because the iteration count is free to differ between problem sizes and a
        // total-time ratio would be measuring that instead.
        val perEval = counts.indices.map { times[it].first / times[it].second }
        println("  ms per evaluation: ${perEval.joinToString(", ") { "%.3f".format(it) }}")
        (1 until counts.size).forEach { i ->
            val ratio = perEval[i] / perEval[i - 1]
            println("    ${counts[i - 1]} -> ${counts[i]} samples: ${"%.2f".format(ratio)}x per evaluation")
        }
    }
}

private fun fitCostTemplate() =
    smoothUnion(sphere(0.45), cylinder(1.90, 0.16), k = 0.18).parameterize {
        param("sphereRadius", "SmoothUnion/a/Sphere.radius")
        param("barHeight", "SmoothUnion/b/Cylinder.height")
        param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
    }.orThrow()
