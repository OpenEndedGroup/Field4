package afield.lang.solidsl

import afield.math.autodiff.OptimizeMethod
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Adam against LBFGS on every fit problem solidsl already has, measured after `adBox3`'s interior
 * `NaN` was fixed.
 *
 * The guidance in [SolidFitOptions.method], [fitToPointCloud] and `SolidSLApproximationTest` was
 * written before that fix, from runs whose LBFGS was searching along a `NaN` direction. This file
 * re-measures it. Each problem is run from several starts (the problem's own seed, then the seed
 * jittered by up to ±15% per parameter with a fixed [Random]) and, where the target is sampled,
 * several sampling seeds, so a conclusion rests on a population rather than on one lucky descent.
 *
 * LBFGS runs with `iterations = 500`; Adam with the settings each problem's own test or docs page
 * uses. The numbers are printed as a table — `dev_docs/SOLIDSL_OPTIMIZER_MEASUREMENTS.md` carries a
 * copy — and the assertions pin only the conclusions the guidance now states, with floors well below
 * what was measured so that a slower machine or a constant tweak does not flip them.
 */
class SolidSLOptimizerComparisonTest {

    private data class Outcome(
        val problem: String,
        val method: OptimizeMethod,
        val run: Int,
        val iterations: Int,
        val reason: String,
        val improvement: Double,
        val relativeRms: Double,
        val worstParamError: Double?,
        val millis: Long,
    )

    private fun jittered(base: DoubleArray, run: Int): DoubleArray {
        if (run == 0) return base.copyOf()
        val r = Random(1000 + run)
        return DoubleArray(base.size) { base[it] * (1.0 + r.nextDouble(-0.15, 0.15)) }
    }

    private fun worst(values: DoubleArray, truth: DoubleArray?): Double? =
        truth?.indices?.maxOf { abs(values[it] - truth[it]) }

    private val lbfgs = SolidFitOptions(method = OptimizeMethod.LBFGS, iterations = 500)

    private fun fieldProblem(
        name: String,
        template: SolidTemplate,
        targetOf: (Int) -> SolidSamples,
        start: DoubleArray,
        truth: DoubleArray?,
        adam: SolidFitOptions,
        runs: Int,
    ): List<Outcome> = (0 until runs).flatMap { run ->
        val samples = targetOf(run)
        val s = jittered(start, run)
        listOf(lbfgs, adam).map { opts ->
            val t0 = System.nanoTime()
            val fit = template.fitParameters(samples, s, opts)
            Outcome(
                name, opts.method, run, fit.run.iterations, fit.run.reason,
                fit.improvementFactor, fit.rmsError / samples.rms, worst(fit.values, truth),
                (System.nanoTime() - t0) / 1_000_000,
            )
        }
    }

    private fun print(outcomes: List<Outcome>) {
        println("| problem | method | run | iterations | stop | improvement | rms / target rms | worst param error | ms |")
        println("|---|---|---|---|---|---|---|---|---|")
        for (o in outcomes) {
            println(
                "| ${o.problem} | ${o.method} | ${o.run} | ${o.iterations} | ${o.reason} | " +
                    "${"%.3g".format(o.improvement)} | ${"%.4f".format(o.relativeRms)} | " +
                    "${o.worstParamError?.let { "%.2e".format(it) } ?: "n/a"} | ${o.millis} |",
            )
        }
    }

    private fun threeSpheres(): SolidTemplate = smoothUnion(
        smoothUnion(sphere(0.5).at(0.0, 0.0, 0.7), sphere(0.5), 0.35),
        sphere(0.5).at(0.0, 0.0, -0.7),
        0.35,
    ).parameterize {
        param("endRadius") { it.node == "Sphere" && it.path.contains("Translate") }
        param("middleRadius", "SmoothUnion/a/SmoothUnion/b/Sphere.radius")
        param("topZ", "SmoothUnion/a/SmoothUnion/a/Translate.z")
        param("bottomZ", "SmoothUnion/b/Translate.z")
    }.orThrow()

    private fun cubeAndBar(): SolidTemplate =
        (cuboid(1.0) + cylinder(1.0, 1.0).turned(y = 1.0)).parameterize {
            param("cubeSize") { it.node == "Cuboid" }
            param("barHeight") { it.node == "Cylinder" && it.field == "height" }
            param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
            param("barTilt") { it.node == "Rotate" && it.field == "y" }
        }.orThrow()

    private fun plate(): SolidTemplate =
        (
            cuboid(3.0, 2.0, 0.5) -
                cylinder(3.0, 0.35).at(0.9, 0.0, 0.0) -
                cylinder(3.0, 0.35).at(-0.9, 0.0, 0.0)
            ).parameterize {
            param("width", "Difference/0/Cuboid.x")
            param("thickness", "Difference/0/Cuboid.z")
            param("bore") { it.node == "Cylinder" && it.field.startsWith("radius") }
        }.orThrow()

    private fun lobes(): SolidTemplate = smoothUnion(
        smoothUnion(sphere(0.50), sphere(0.35).at(-0.80, 0.0, 0.0), 0.25),
        sphere(0.42).at(0.80, 0.0, 0.0),
        0.25,
    ).parameterize {
        param("middle", "SmoothUnion/a/SmoothUnion/a/Sphere.radius")
        param("left", "SmoothUnion/a/SmoothUnion/b/Translate/Sphere.radius")
        param("right", "SmoothUnion/b/Translate/Sphere.radius")
    }.orThrow()

    @Test
    fun `adam and lbfgs on the three field-fit problems`() {
        val runs = 4
        val rod = cylinder(2.2, 0.5).rounded(0.12)
        val bodyAndBar = sphere(0.62) + cylinder(2.20, 0.28)
        val plateTruth = doubleArrayOf(3.0, 0.5, 0.35)
        val outcomes =
            fieldProblem(
                "plate (exact recovery)", plate(),
                { plate().withValues(plateTruth).sampleField(count = 2400, seed = 5 + it) },
                doubleArrayOf(2.2, 0.86, 0.60), plateTruth,
                SolidFitOptions(method = OptimizeMethod.ADAM, iterations = 3000, learningRate = 0.02), runs,
            ) +
                fieldProblem(
                    "three spheres ~ rod (approximation)", threeSpheres(),
                    { rod.sampleField(count = 3000, seed = 17 + it, margin = 0.3) },
                    threeSpheres().seed, null,
                    SolidFitOptions(method = OptimizeMethod.ADAM, iterations = 900, learningRate = 0.02), runs,
                ) +
                fieldProblem(
                    "cube+bar ~ sphere+bar (partial)", cubeAndBar(),
                    { bodyAndBar.sampleField(count = 3000, seed = 17 + it) },
                    doubleArrayOf(1.35, 1.60, 0.42, 25.0), null,
                    SolidFitOptions(method = OptimizeMethod.ADAM, iterations = 6000, learningRate = 0.015), runs,
                )
        print(outcomes)

        fun of(problem: String, m: OptimizeMethod) = outcomes.filter { it.problem.startsWith(problem) && it.method == m }

        // Exact recovery: LBFGS reaches machine precision on every start; Adam does not get close.
        assertTrue(of("plate", OptimizeMethod.LBFGS).all { it.improvement > 1e12 }, "LBFGS no longer recovers the plate")
        assertTrue(
            of("plate", OptimizeMethod.LBFGS).zip(of("plate", OptimizeMethod.ADAM)).all { (l, a) -> l.improvement > a.improvement },
            "Adam beat LBFGS on an exact recovery",
        )
        // Approximation problems: LBFGS is at least as good as Adam's documented settings, every run.
        for (p in listOf("three spheres", "cube+bar")) {
            of(p, OptimizeMethod.LBFGS).zip(of(p, OptimizeMethod.ADAM)).forEach { (l, a) ->
                assertTrue(
                    l.relativeRms <= a.relativeRms * 1.01,
                    "$p run ${l.run}: LBFGS left ${l.relativeRms}, Adam ${a.relativeRms}",
                )
            }
        }
    }

    @Test
    fun `adam and lbfgs on the point-cloud problems`() {
        val truth = doubleArrayOf(0.50, 0.35, 0.42)
        val seed = doubleArrayOf(0.30, 0.56, 0.20)
        val adam = SolidFitOptions(method = OptimizeMethod.ADAM, iterations = 1200, learningRate = 0.01)
        val outcomes = mutableListOf<Outcome>()
        for (run in 0 until 4) {
            for (variant in listOf("full", "noisy", "one-sided")) {
                val full = lobes().withValues(truth).samplePointCloud(
                    count = if (variant == "one-sided") 4000 else 3000, seed = 4 + run,
                    noise = if (variant == "noisy") 0.004 else 0.0,
                )
                val cloud = if (variant == "one-sided") full.filter { x, _, _ -> x < 0.10 } else full
                val s = jittered(seed, run)
                for (opts in listOf(lbfgs, adam)) {
                    val t0 = System.nanoTime()
                    val fit = lobes().fitToPointCloud(cloud, s, opts)
                    // The one-sided cloud cannot see `right`, so its error is left out of the worst case.
                    val seen = if (variant == "one-sided") listOf(0, 1) else listOf(0, 1, 2)
                    outcomes += Outcome(
                        "cloud $variant", opts.method, run, fit.run.iterations, fit.run.reason,
                        fit.initialLoss / fit.loss.coerceAtLeast(1e-300), sqrt(fit.loss),
                        seen.maxOf { abs(fit.values[it] - truth[it]) },
                        (System.nanoTime() - t0) / 1_000_000,
                    )
                }
            }
        }
        print(outcomes)
        // The column labelled "rms / target rms" holds the absolute rms field value here: a cloud
        // has no target distances to normalise by.
        val l = outcomes.filter { it.method == OptimizeMethod.LBFGS && it.problem == "cloud full" }
        assertTrue(l.all { it.worstParamError!! < 1e-3 }, "LBFGS no longer recovers a clean full cloud: $l")
        // On every cloud, LBFGS lands where Adam does — the fitToPointCloud default rests on this.
        val byMethod = outcomes.groupBy { it.problem to it.run }
        for ((key, pair) in byMethod) {
            val lb = pair.single { it.method == OptimizeMethod.LBFGS }
            val ad = pair.single { it.method == OptimizeMethod.ADAM }
            assertTrue(lb.relativeRms <= ad.relativeRms * 1.01, "$key: LBFGS rms ${lb.relativeRms}, Adam ${ad.relativeRms}")
            assertTrue(lb.iterations < ad.iterations, "$key: LBFGS took ${lb.iterations}, Adam ${ad.iterations}")
        }
    }
}
