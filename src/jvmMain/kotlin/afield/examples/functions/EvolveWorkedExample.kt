package afield.examples.functions

import afield.stdlib.FnEvolveRun
import afield.stdlib.FnVarietyRun
import afield.stdlib.Functions
import afield.stdlib.evolve
import afield.stdlib.evolveVariety
import afield.stdlib.graphSignature
import afield.stdlib.matchFitness

/*
FIELD_TEST
id: functions.evolve.worked-example
runner: jvm-main
entrypoint: afield.examples.functions.EvolveWorkedExample
tags: [smoke, examples, functions, evolve, cpu]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "evolve worked example passed"
*/

/**
 * The CPU-only reproducibility floor behind the `evolve` walkthrough in
 * `public/docs/functions_examples.md`.
 *
 * There is no GPU, no Vulkan and no Field window here — and, deliberately, no file output.
 * It builds an adversarial target `Fn`, runs the deterministic (µ+λ) hill-climb [evolve]
 * from a plain seed under a FIXED `rngSeed`, and grows a behaviourally distinct set with
 * [evolveVariety]. [runExample] returns the raw run objects; [main] and the `@Test` assert
 * the run improves on the seed and reproduces bit-for-bit under the fixed seeds.
 *
 * The figures in the doc are LIVE box captures of this same search, produced by the
 * docs-capture pipeline (the document system) — not files this harness writes. Document
 * assets do not come from code outside the document system, so this harness renders nothing
 * and writes nowhere; it exists only to prove the numbers the doc shows are real and stable.
 */
object EvolveWorkedExample {

    /** Fixed so the whole run is reproducible. */
    const val RNG_SEED: Long = 3L
    const val ITERATIONS: Int = 120
    const val LAMBDA: Int = 8
    const val SAMPLES: Int = 160

    // Variety run. Mirrors the `evolveVariety(...)` snippet in the docs so the harness and
    // the documented call stay in lockstep.
    const val VARIETY_COUNT: Int = 6
    const val VARIETY_ITERATIONS: Int = 48
    const val VARIETY_RNG_SEED: Long = 7L
    const val VARIETY_NEIGHBOURS: Int = 3
    const val VARIETY_SAMPLES: Int = 120

    data class Result(
        val run: FnEvolveRun,
        val variety: FnVarietyRun
    )

    /**
     * Run the worked example on the CPU and return the raw evolve / variety objects. Pure
     * computation: no rendering, no file output. Given the fixed seeds it returns the same
     * numbers and graphs every time — that reproducibility is the whole point.
     */
    fun runExample(): Result = with(Functions) {
        // Adversarial target/seed: a half-amplitude sine reached from a full-amplitude
        // COSINE — the wrong op AND the wrong amplitude, so a good run must both substitute
        // the op (via substituteOp) and dial in the numeric leaves (via jitter/nudge).
        val target = sin(frequency = 1.0, amplitude = 0.5)
        val seed = cos(frequency = 1.0, amplitude = 1.0)
        val run = evolve(
            seed = seed,
            iterations = ITERATIONS,
            lambda = LAMBDA,
            rngSeed = RNG_SEED,
            fitness = matchFitness(target, samples = SAMPLES, complexityWeight = 0.01)
        )

        // Variety generation: grow a set of behaviourally distinct gestures from one seed.
        // `normalize = true` measures novelty in *shape*, not amplitude, so the set spreads
        // across genuinely different waveforms instead of ever-louder copies of one gesture.
        val variety = evolveVariety(
            seed = sin(2.0) * 0.5,
            count = VARIETY_COUNT,
            iterations = VARIETY_ITERATIONS,
            rngSeed = VARIETY_RNG_SEED,
            samples = VARIETY_SAMPLES,
            neighbours = VARIETY_NEIGHBOURS,
            normalize = true
        )
        Result(run, variety)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val a = runExample()
        val evolved = a.run
        println("evolve worked example: seed=$RNG_SEED iterations=$ITERATIONS lambda=$LAMBDA")
        println("  initialScore=${evolved.initialScore} bestScore=${evolved.bestScore} improved=${evolved.improved}")
        println("  acceptedMutations=${evolved.steps.size} evaluations=${evolved.evaluations}")
        val variety = a.variety
        println("evolveVariety: count=$VARIETY_COUNT iterations=$VARIETY_ITERATIONS seed=$VARIETY_RNG_SEED")
        println("  results=${variety.results.size} meanPairwiseDistance=${variety.meanPairwiseDistance}")

        check(evolved.improved) { "expected evolution to improve on the seed" }
        check(variety.results.size == VARIETY_COUNT) { "expected $VARIETY_COUNT variety results" }
        check(variety.meanPairwiseDistance > 0.0) { "variety set should be behaviourally spread out" }

        // Determinism: a second run under the same fixed seeds must reproduce the numbers and
        // the evolved graph exactly — the property that makes an evolved gesture safe to keep
        // in live art, and what the doc's committed numbers depend on.
        val b = runExample()
        check(a.run.bestScore == b.run.bestScore) { "bestScore not reproducible" }
        check(a.run.initialScore == b.run.initialScore) { "initialScore not reproducible" }
        check(a.run.best.graphSignature() == b.run.best.graphSignature()) { "evolved graph not reproducible" }
        check(a.variety.meanPairwiseDistance == b.variety.meanPairwiseDistance) { "variety spread not reproducible" }

        println("evolve worked example passed")
    }
}
