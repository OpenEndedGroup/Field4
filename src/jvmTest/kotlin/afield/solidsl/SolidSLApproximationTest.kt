package afield.solidsl

import afield.stdlib.OptimizeMethod
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The second fitting problem, and the one that is actually **design** rather than recovery: a target
 * the model provably **cannot** represent.
 *
 * `SolidSLGradientTest`'s two fits both recover a shape from samples of *itself*. That is the right
 * first test — there is one correct answer and the loss knows it, so "did the gradient work" has a
 * yes-or-no answer to four decimal places — and it is also the easy case, in a way worth naming: a
 * recovery problem has a global minimum at zero, so a descent that reaches `1e-22` has proved the
 * gradient points at it, and nothing about what happens when it does not exist.
 *
 * Design is the other shape of the problem. Here the target is a **rounded rod** — a capped cylinder
 * grown by a radius — and the model is a chain of three smooth-unioned spheres on the Z axis. Three
 * spheres cannot be a cylinder. There is no setting of the parameters that drives the residual to
 * zero, the optimum is an unknown compromise, and the only honest assertions are:
 *
 *  - it got **substantially** better, with the counterfactual measured in the same run;
 *  - the residual is a stated fraction of the target's own scale, so "close" means something;
 *  - and it did **not** reach zero — which is asserted, because a zero here would mean the model
 *     secretly *could* represent the target and this test had quietly become a second recovery test
 *     wearing an approximation's clothes.
 *
 * That last assertion is the point of the file. A test whose premise has silently stopped holding is
 * worth less than no test, and "the two shapes turn out to be the same family" is exactly the kind of
 * premise that rots when somebody adjusts a constant.
 *
 * # The target's field has to be an exact distance function, and that is not a detail
 *
 * [fitParameters] regresses on the **field**, not on the surface. So a target whose field is only
 * *sign*-correct sets the model an impossible task for a reason that has nothing to do with shape: a
 * non-uniform [SolidExpr.Scale], for instance, multiplies its child's distance by the smallest axis
 * factor, so a perfect geometric match would still leave a residual of `(1 − k)` times the distance
 * everywhere. That was this test's first target and it was quietly measuring the wrong thing.
 *
 * A capped cylinder grown by [SolidExpr.Round] is exact in both — `sdCappedCone` is a true distance
 * and `f − r` preserves that — so `fieldNotes()` on the target comes back **empty**, and the residual
 * below is a fact about shape. That emptiness is asserted first, before anything else runs.
 *
 * # Why LBFGS is not the default here
 *
 * It is the default in [SolidFitOptions] and it is the wrong choice for this problem. A three-sphere
 * chain has a genuinely rough landscape — each sphere's derivative saturates when the blend clamp
 * does, so the loss is piecewise-smooth with the pieces meeting at the clamp boundaries — and a line
 * search across such a boundary keeps failing and reporting convergence. `OptimizeMethod.ADAM` walks
 * through it, which is what `SolidFitOptions.method`'s KDoc says it is for; this test is where that
 * sentence was learned.
 */
class SolidSLApproximationTest {

    @Test
    fun `three spheres approximate a rounded rod they cannot equal`() {
        // A rounded rod. `sdCappedConeZ` is an exact distance and `Round` is `f - r`, so the whole
        // thing is metrically exact — which is what makes the residual below a fact about shape
        // rather than about the target's own field being an under-estimate.
        val target = cylinder(2.2, 0.5).rounded(0.12)
        assertEquals(
            emptyList(), target.fieldNotes(),
            "PREMISE: the target's field was supposed to be an exact distance function, and " +
                "fieldNotes() now says it is not. fitParameters regresses on the FIELD, so an " +
                "inexact target makes the residual below a measurement of the wrong thing.",
        )
        val samples = target.sampleField(count = 3000, seed = 17, margin = 0.3)

        // Three spheres on the axis, blended. `endRadius` drives BOTH outer spheres, so the model is
        // symmetric by construction rather than by luck — and that halves the search space.
        val model = smoothUnion(
            smoothUnion(sphere(0.5).at(0.0, 0.0, 0.7), sphere(0.5), 0.35),
            sphere(0.5).at(0.0, 0.0, -0.7),
            0.35,
        )
        val template = model.parameterize {
            param("endRadius") { it.node == "Sphere" && it.path.contains("Translate") }
            param("middleRadius", "SmoothUnion/a/SmoothUnion/b/Sphere.radius")
            param("topZ", "SmoothUnion/a/SmoothUnion/a/Translate.z")
            param("bottomZ", "SmoothUnion/b/Translate.z")
        }.orThrow()

        println("model: ${template.report()}")

        // PREMISE, asserted before the result is read: the model cannot be the target. Checked
        // structurally rather than by hoping — there is no Scale anywhere in the model, and no
        // parameterisation of spheres and blends produces one.
        assertTrue(
            template.base.numericSlots().none { it.node == "Scale" },
            "the model contains a Scale, so it might be able to reach the target exactly and this " +
                "would be a recovery test rather than an approximation one",
        )

        val fit = template.fitParameters(
            samples,
            options = SolidFitOptions(method = OptimizeMethod.ADAM, iterations = 900, learningRate = 0.02),
        )

        println(fit.report())
        println("  target rms   ${"%.6f".format(samples.rms)}")
        println("  residual     ${"%.6f".format(fit.rmsError)} = ${"%.2f".format(100 * fit.rmsError / samples.rms)}% of it")

        assertTrue(fit.deadParameters.isEmpty(), "parameters could not move: ${fit.deadParameters}")

        // 1. Substantially better, counterfactual in the same object. Measured 12.0x, in 374 of the
        //    900 iterations offered before the gradient tolerance stopped it.
        assertTrue(
            fit.improvementFactor > 8.0,
            "the fit improved the loss only ${"%.3g".format(fit.improvementFactor)}x " +
                "(${fit.initialLoss} -> ${fit.loss})",
        )
        // 2. And "better" means something: the residual is a stated fraction of the target's own
        //    scale. Measured 8.0%; three spheres are simply not a rod, and this says how much not.
        assertTrue(
            fit.rmsError < 0.10 * samples.rms,
            "the residual is ${"%.2f".format(100 * fit.rmsError / samples.rms)}% of the target's rms",
        )
        // 3. And it is NOT zero — the premise, re-asserted against the result. A model that provably
        //    cannot represent its target must leave a residual, so a *small* one here would mean the
        //    two shapes had quietly become the same family and every number above meant something
        //    else. The floor is two orders below the measured 8.0% rather than at zero, because
        //    "the loss is not literally zero" is satisfied by 1e-22 and would not catch that.
        assertTrue(
            fit.rmsError > 0.02 * samples.rms,
            "the approximation closed to ${"%.3f".format(100 * fit.rmsError / samples.rms)}% of the " +
                "target's rms, which for a model that provably cannot represent it means this test " +
                "is no longer measuring what it says it is",
        )

        // The shape it found is symmetric, because the parameterisation could not have made it
        // otherwise for the radii — and it discovered the Z symmetry on its own for the offsets,
        // which is a nice thing to see rather than to assume.
        val top = fit.values[template.names.indexOf("topZ")]
        val bottom = fit.values[template.names.indexOf("bottomZ")]
        println("  found offsets ${"%.5f".format(top)} and ${"%.5f".format(bottom)} — sum ${"%.2e".format(top + bottom)}")
        assertTrue(
            abs(top + bottom) < 0.02,
            "the two ends settled at $top and $bottom, which is not the symmetric answer a symmetric " +
                "target should have produced",
        )

        // And it is still an ordinary SolidExpr, so the rest of the package still works on it.
        val fitted = fit.fitted
        assertTrue(fitted.bounds() != null, "the fitted approximation has no bounds")
        assertTrue(fitted.toGlsl().statementCount > 0, "the fitted approximation does not emit GLSL")
    }
}
