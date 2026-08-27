package afield.solidsl

import afield.stdlib.OptimizeMethod
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The fit where **half the model can be right and half cannot**, in one descent.
 *
 * `SolidSLGradientTest`'s recovery fits a shape from samples of itself and reaches `1e-22`.
 * `SolidSLApproximationTest`'s design fit cannot reach zero anywhere. Both are honest and both are
 * one-sided: the first says only "the gradient points at a minimum that exists", the second only
 * "it got better". Neither answers the question a reader of `solid_notation.md` actually has, which
 * is *how would I know which of my parameters to believe?*
 *
 * So: target `sphere + cylinder`, model `cuboid + cylinder`. The cylinder is the **same primitive on
 * both sides**, so its two parameters have a correct answer and the fit can be scored against it.
 * The cube is asked to be a sphere, which no side length achieves — its optimum is a compromise
 * whose value nothing in the source predicts. One number in the report is a recovery and one is a
 * negotiation, and the report does not say which.
 *
 * The bound that makes the cube's answer meaningful rather than merely plausible: a cube that fits a
 * sphere of radius `R` at all must have a side somewhere between the inscribed cube's `2R/sqrt(3)`
 * and the circumscribed cube's `2R`. Outside that interval it is not a compromise, it is a failure,
 * so the interval is asserted rather than the fitted value — which would be a hand-copied constant
 * of exactly the kind this repo keeps finding rotten.
 *
 * Every number this test prints, it also floors.
 */
class SolidSLPartialRecoveryTest {

    private val sphereRadius = 0.62
    private val barHeight = 2.20
    private val barRadius = 0.28

    @Test
    fun `the cylinder recovers and the cube negotiates, in one fit`() {
        val truth = sphere(sphereRadius) + cylinder(barHeight, barRadius)

        // Premise first, so an environment change fails loudly instead of turning this into a
        // tautology: `fitParameters` regresses on the FIELD, so a target whose field is not a true
        // distance would set the model an impossible task for reasons that are not about shape.
        val notes = truth.fieldNotes()
        assertTrue(notes.isEmpty(), "the target's field is not exact, so the residual below would not be about shape: $notes")

        val target = truth.sampleField(count = 3000, seed = 17)

        val model = (cuboid(1.0) + cylinder(1.0, 1.0).turned(y = 1.0)).parameterize {
            // One parameter drives all three of the cuboid's extents, so the box stays a cube and
            // cannot cheat by growing into a sphere-ish slab.
            param("cubeSize") { it.node == "Cuboid" }
            param("barHeight") { it.node == "Cylinder" && it.field == "height" }
            param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
            // The fourth, and the only one that is an ANGLE. The target's cylinder is axis-aligned,
            // so the truth here is 0 degrees -- but the loss cannot tell 0 from 180, because a
            // cylinder is symmetric under a half turn. That is a property of the shape, not a defect
            // in the fit, and it is why the assertion below is modulo 180.
            param("barTilt") { it.node == "Rotate" && it.field == "y" }
        }.orThrow()

        // The seed matters more here than in a recovery fit, and not for convergence-speed
        // reasons. `Union` is `min`, so a child that is never the nearest surface at any sample
        // receives **exactly** zero gradient — and a cube small enough to sit wholly inside the bar
        // is exactly that. Seeded at 0.40 against a bar of radius 0.55 this fit ran 372 iterations
        // and reported `cubeSize 0.400000 -> 0.400000`: not slow, not stuck, simply invisible to
        // the loss. So the cube starts *outside* its own admissible interval and has to come down
        // into it, which also means the descent has to move it to succeed.
        val seed = doubleArrayOf(1.35, 1.60, 0.42, 25.0)
        val fit = model.fitParameters(
            target,
            seed,
            // Hard edges meeting a union make a piecewise-smooth landscape; the line search stalls
            // on it and reports convergence. Same lesson as SolidSLApproximationTest.
            SolidFitOptions(method = OptimizeMethod.ADAM, iterations = 6000, learningRate = 0.015),
        )

        println(fit.report())
        println("  dead at the start: ${if (fit.deadParameters.isEmpty()) "none" else fit.deadParameters.joinToString(", ")}")
        val v = fit.values
        val named = model.names.zip(v.toList()).toMap()
        val gotCube = named.getValue("cubeSize")
        val gotHeight = named.getValue("barHeight")
        val gotRadius = named.getValue("barRadius")
        val gotTilt = named.getValue("barTilt")
        // A cylinder is symmetric under a half turn, so 0 and 180 are the same shape.
        val tiltError = kotlin.math.abs(((gotTilt % 180.0) + 180.0) % 180.0).let { minOf(it, 180.0 - it) }

        val inscribed = 2.0 * sphereRadius / sqrt(3.0)
        val circumscribed = 2.0 * sphereRadius
        println(
            "  cylinder truth   height %.6f radius %.6f".format(barHeight, barRadius) +
                "\n  cylinder fitted  height %.6f radius %.6f".format(gotHeight, gotRadius) +
                "\n  tilt truth 0 deg, fitted %.6f deg (error %.6f deg, mod 180)".format(gotTilt, tiltError) +
                "\n  cube             fitted %.6f, admissible [%.6f, %.6f]".format(gotCube, inscribed, circumscribed) +
                "\n  residual rms     %.6e  (%.3f%% of the target's longest extent)"
                    .format(fit.rmsError, 100.0 * fit.rmsError / barHeight)
        )

        // 1. It did NOT reach zero. If it ever does, the two shapes have become the same family and
        //    this file has quietly turned into a second recovery test.
        assertTrue(fit.loss > 1e-8, "the model reached loss ${fit.loss} — a cube cannot be a sphere, so this premise has broken")

        // 2. The cube MOVED. Without this the frozen-cube case above passes every other assertion
        //    in this file, because a cube that never enters the union leaves the cylinder to fit the
        //    whole target and the residual still comes out finite and improved.
        assertTrue(
            kotlin.math.abs(gotCube - seed[0]) > 0.05,
            "cubeSize never left its seed (${seed[0]} -> $gotCube): it is probably inside the bar and invisible to the loss",
        )

        // 3. The cube's answer is a compromise inside the only interval a cube-for-a-sphere can
        //    occupy, not a fitted constant copied out of a previous run.
        assertTrue(
            gotCube > inscribed && gotCube < circumscribed,
            "cubeSize $gotCube is outside [$inscribed, $circumscribed], so it is a failure rather than a compromise",
        )

        // 4. And the cylinder — the half that CAN be right — is right, to a tolerance far tighter
        //    than the cube's own distance from any of its candidate answers.
        assertTrue(
            kotlin.math.abs(gotHeight - barHeight) < 0.05,
            "barHeight recovered as $gotHeight against a true $barHeight",
        )
        assertTrue(
            kotlin.math.abs(gotRadius - barRadius) < 0.02,
            "barRadius recovered as $gotRadius against a true $barRadius",
        )
        assertTrue(
            tiltError < 3.0,
            "barTilt recovered as $gotTilt deg, ${tiltError} deg from axis-aligned (mod 180)",
        )

        // 5. The counterfactual, measured in this run. Floored at 12x against a measured 18.6x:
        //    near the number, with room for the descent to wobble. Note how far this is from the
        //    recovery fit's 4.6e+19x — that gap IS the difference between the two problems.
        assertTrue(fit.improvementFactor > 12.0, "improvement was only ${fit.improvementFactor}x")

        // 6. And the residual is a stated fraction of the target's own scale, so that "a good
        //    approximation" is a measurement rather than an impression.
        val fraction = fit.rmsError / barHeight
        assertTrue(fraction < 0.05, "residual rms is ${100.0 * fraction}% of the target's longest extent")
    }
}
