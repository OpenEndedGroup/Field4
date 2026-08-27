package afield.solidsl

import afield.manifold.ManifoldLibrary
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fit's **output**, checked by Manifold — an oracle that shares no code with the gradient that
 * produced it *or* with the scalar field the gradient was checked against.
 *
 * `SolidAgreement`'s whole argument is that two implementations sharing no code agreeing to five
 * figures beats any number of assertions against remembered constants. This branch added a third
 * reading and checked it two ways: against central differences (a numerical oracle over the *same*
 * arithmetic) and against `toScalarField` (an independent implementation, but of the *value*, not of
 * the fit). Neither says anything about the thing a user actually gets back, which is a
 * [SolidExpr] that is supposed to *be* the target.
 *
 * This does. Take the fitted expression, hand it to Manifold's exact CSG kernel, and compare its
 * volume, surface area and genus with the target's — all three computed in C++, by a boolean kernel
 * that has never seen an [afield.stdlib.AD] tape or a marching cube. The chain being closed is:
 *
 * ```
 * points --(AD gradient, reverse mode)--> parameters --(substitution)--> SolidExpr --(Manifold)--> volume
 *                                                                                                    |
 *                                                     the target's own volume, the same way <---------+
 * ```
 *
 * # An oracle that agrees with everything is not an oracle
 *
 * So the discriminating power is tested too, in the same run. `SolidSLApproximationTest` fits a model
 * that provably *cannot* represent its target; if Manifold called that a match as well, this file
 * would be measuring nothing. It does not — the second test here asserts the oracle **rejects** the
 * approximation by a margin far outside the tolerance the first one passes at. A pass and a fail from
 * the same instrument is what makes the pass mean something.
 *
 * # The subject is not the ball-and-rod
 *
 * `SolidExpr.SmoothUnion` is one of the five nodes `exactSolid()` refuses outright, so the shape the
 * rest of this branch fits cannot be handed to Manifold at all. That is the inversion this branch
 * keeps running into — the constructs the exact kernel refuses hardest are the ones the gradient
 * reading likes best — and here it bites: to use the exact oracle at all, the subject has to be
 * written in the CSG-able fragment of the algebra. A bored plate is, and it brings a bonus the
 * blended shapes cannot: a **genus**. Two through-holes are genus 2, and a bore radius fitted wrong
 * enough to break out of the plate would change that integer, which no tolerance on a volume would
 * have to be chosen to catch.
 */
class SolidSLFitExactOracleTest {

    private val quality = SolidQuality(segments = 192, edgeLengthFraction = 1.0 / 90.0)

    /** A plate with two through-bores: entirely inside the fragment of the algebra Manifold accepts. */
    private fun plateTemplate(): SolidTemplate =
        (
            cuboid(3.0, 2.0, 0.5) -
                cylinder(3.0, 0.35).at(0.9, 0.0, 0.0) -
                cylinder(3.0, 0.35).at(-0.9, 0.0, 0.0)
            ).parameterize {
            param("width", "Difference/0/Cuboid.x")
            param("thickness", "Difference/0/Cuboid.z")
            // Four slots — both radii of both bores — driven by one name. The plate has two holes of
            // the same size because the parameterisation says so, not because the fit was lucky.
            param("bore") { it.node == "Cylinder" && it.field.startsWith("radius") }
        }.orThrow()

    private val truth = doubleArrayOf(3.0, 0.5, 0.35)
    private val seed = doubleArrayOf(2.2, 0.86, 0.60)

    @Test
    fun `Manifold agrees that the fitted solid is the target`() {
        if (!ManifoldLibrary.isAvailable) {
            println("SolidSLFitExactOracleTest: skipping — ${ManifoldLibrary.describe()}")
            return
        }
        val template = plateTemplate()

        // PREMISE, asserted before the oracle is trusted: this subject really does lower exactly.
        // If it ever refuses, the "oracle" below silently becomes Manifold's level set over the very
        // field the fit was computed from — self-consistency wearing cross-validation's clothes,
        // which is the exact overclaim `AgreementKind` exists to prevent.
        assertEquals(
            emptyList(), template.base.allRefusals().map { it.why },
            "PREMISE FAILED: the subject no longer lowers to exact CSG, so Manifold would be marching " +
                "the same field the gradient came from and this test would not be an independent check",
        )

        val target = template.withValues(truth)
        val samples = target.sampleField(count = 2400, seed = 5)

        val fit = template.fitParameters(samples, seed)
        println(fit.report())

        val measured = compare(target, fit.fitted)
        println(measured.report())

        assertTrue(fit.deadParameters.isEmpty(), "parameters could not move: ${fit.deadParameters}")
        truth.indices.forEach { i ->
            assertTrue(
                abs(fit.values[i] - truth[i]) < 1e-6,
                "${template.names[i]} recovered as ${fit.values[i]}, wanted ${truth[i]}",
            )
        }

        // The oracle's verdict. Volume and area are `double` integrations inside Manifold over a
        // boolean result it computed itself; nothing here came from the gradient or from a march.
        // Measured 8.0e-12 and 9.6e-11 — Manifold's `double` integration of two independently
        // constructed boolean results, agreeing to eleven figures.
        assertTrue(
            measured.relativeVolume < 1e-9,
            "Manifold says the fitted solid and the target differ in volume by " +
                "${"%.3e".format(measured.relativeVolume)} — ${measured.report()}",
        )
        assertTrue(
            measured.relativeArea < 1e-8,
            "Manifold says they differ in surface area by ${"%.3e".format(measured.relativeArea)}",
        )
        assertEquals(
            measured.targetGenus, measured.fittedGenus,
            "the fitted solid has a different number of holes than the target",
        )
        // And the shape is the one this test thinks it is: a plate with two through-bores.
        assertEquals(2, measured.targetGenus, "the target is not genus 2, so the subject is not what the KDoc says")
    }

    /**
     * A record of a wrong diagnosis, kept because the wrong diagnosis is the instructive part.
     *
     * Before `adBox3`'s interior `NaN` was fixed, LBFGS on this shape reported
     * `0 iterations (line search)` — a *converged* optimisation that moved nothing. I wrote a
     * paragraph explaining it: a plate with two bores is a pointwise `max` of four fields, the loss
     * is piecewise-smooth, and a line search across a seam fails and calls it convergence. That
     * story is plausible, it matches what `SolidFitOptions.method`'s KDoc already said, and it is
     * **wrong**. The gradient was `NaN` at every sample inside the plate, so the line search had
     * nothing to search along.
     *
     * The fix moved this problem from "LBFGS cannot do it" to `5.4e20x` in **thirteen** iterations,
     * and it is asserted here so the wrong explanation cannot come back. The general lesson is the
     * one worth carrying: *a solver reporting immediate convergence is far more often a broken
     * gradient than a hard landscape*, and `deadParameters` does not catch it because `NaN == 0.0`
     * is false — which is why [SolidGradientCheck.nonFiniteAnalytic] now exists and is asserted over
     * the whole census.
     *
     * **Not re-verified:** the Adam-over-LBFGS advice in `SolidFitOptions.method`,
     * `SolidSLApproximationTest` and `fitToPointCloud` was written from measurements taken *before*
     * this fix. Those problems still converge with the method each of them names, but whether LBFGS
     * would now do better on any of them has not been measured.
     */
    @Test
    fun `lbfgs recovers the plate too, now that the gradient is finite inside it`() {
        val template = plateTemplate()
        val samples = template.withValues(truth).sampleField(count = 2400, seed = 5)
        val fit = template.fitParameters(samples, seed)   // the LBFGS default

        println(
            "LBFGS on a hard boolean: ${fit.run.iterations} iterations (${fit.run.reason}), " +
                "improvement ${"%.4g".format(fit.improvementFactor)}x",
        )

        assertEquals(emptyList(), fit.deadParameters)
        assertTrue(
            fit.run.iterations > 0,
            "LBFGS took no step at all — the `0 iterations (line search)` stall this test exists to " +
                "pin down has come back, which almost certainly means a non-finite gradient somewhere",
        )
        assertTrue(
            fit.improvementFactor > 1e12,
            "LBFGS improved the loss only ${"%.4g".format(fit.improvementFactor)}x",
        )
    }

    /**
     * The same oracle, applied to a fit that is *known* to be wrong — so that the agreement above is
     * evidence rather than a property of the instrument.
     */
    @Test
    fun `Manifold rejects a fit that cannot be right`() {
        if (!ManifoldLibrary.isAvailable) {
            println("SolidSLFitExactOracleTest: skipping — ${ManifoldLibrary.describe()}")
            return
        }
        val template = plateTemplate()
        val target = template.withValues(truth)

        // Not a failed fit — a deliberately wrong answer, so the comparison is about the oracle and
        // not about the optimiser. A 12% thicker plate with 20% wider bores.
        val wrong = template.withValues(doubleArrayOf(3.0, 0.56, 0.42))
        val measured = compare(target, wrong)
        println("a deliberately wrong answer, through the same oracle:")
        println(measured.report())

        assertTrue(
            measured.relativeVolume > 1e-2,
            "the oracle called a 12%-thicker plate with 20%-wider bores a match " +
                "(${"%.3e".format(measured.relativeVolume)} relative volume), so it cannot discriminate " +
                "and the agreement in the other test means nothing",
        )
        // Six orders of magnitude between the pass and the fail. That gap is what a tolerance of
        // 1e-7 is buying, and it is measured rather than assumed.
        println(
            "  oracle margin: the correct fit agrees to ~1e-9, this disagrees at " +
                "${"%.2e".format(measured.relativeVolume)} — a factor of " +
                "${"%.0e".format(measured.relativeVolume / 1e-9)}",
        )
    }

    // ==========================================================================================

    private data class Measured(
        val targetVolume: Double,
        val fittedVolume: Double,
        val targetArea: Double,
        val fittedArea: Double,
        val targetGenus: Int,
        val fittedGenus: Int,
    ) {
        val relativeVolume: Double get() = rel(targetVolume, fittedVolume)
        val relativeArea: Double get() = rel(targetArea, fittedArea)

        private fun rel(a: Double, b: Double): Double {
            val s = max(abs(a), abs(b))
            return if (s == 0.0) 0.0 else abs(a - b) / s
        }

        fun report(): String =
            "  Manifold: volume ${"%.9f".format(targetVolume)} vs ${"%.9f".format(fittedVolume)} " +
                "(rel ${"%.3e".format(relativeVolume)})\n" +
                "            area   ${"%.9f".format(targetArea)} vs ${"%.9f".format(fittedArea)} " +
                "(rel ${"%.3e".format(relativeArea)})\n" +
                "            genus  $targetGenus vs $fittedGenus"
    }

    /** Both shapes through Manifold's exact kernel, and nothing else. */
    private fun compare(target: SolidExpr, fitted: SolidExpr): Measured =
        target.exactSolid(quality).orThrow().use { a ->
            fitted.exactSolid(quality).orThrow().use { b ->
                Measured(a.volume, b.volume, a.surfaceArea, b.surfaceArea, a.genus, b.genus)
            }
        }
}
