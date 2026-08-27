package afield.solidsl

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recovering a parametric solid from a **point cloud** — and mapping where that stops working.
 *
 * `SolidSLGradientTest` fits against a [SolidSamples]: points *with a signed distance at each*, taken
 * from `toScalarField`. That is the right test of the gradient and it is a generous target — the
 * oracle already knows the answer in the same representation the model speaks. The problem anybody
 * actually has is a cloud of unoriented boundary points with no sign, no normals, no correspondence,
 * some noise, and usually only the side that faced the sensor.
 *
 * So this file runs the same machinery against a cloud built by a path that shares **no code** with
 * the differentiable reading — [SolidExpr.samplePointCloud] goes marching cubes → triangle areas →
 * barycentric samples, and never touches an [afield.stdlib.AD] — under four conditions, and reports
 * what survives each:
 *
 * | condition | what it tests |
 * |---|---|
 * | clean, all round | can a surface-only loss recover parameters at all |
 * | Gaussian noise | how the recovery degrades, and whether it degrades *gracefully* |
 * | a one-sided view | the failure mode, which is the point of the file |
 * | a sparse cloud | whether 300 points is enough |
 *
 * # The one-sided view is the result worth having
 *
 * It is not a solver failure and no amount of restarting or reseeding fixes it. The loss is
 * `Σ f(pᵢ)²` and it contains no term that wants the interior filled, so a parameter controlling a
 * feature that is *not in the cloud* has a gradient of zero: the objective is indifferent to it, the
 * optimiser correctly reports convergence, and the answer is confidently wrong in exactly one
 * coordinate. Tuning until a seed happens to start near the truth would hide that, which is why the
 * test asserts the parameter **stays at its seed** rather than asserting it recovers.
 *
 * [SolidCloudFit.unconstrainedParameters] is the first-order detector for it, and this file is where
 * that field earns its place: it names the parameter before the descent runs.
 */
class SolidSLPointCloudTest {

    /**
     * Three lobes in a row on the X axis, with independent radii.
     *
     * Chosen so that a one-sided crop can remove one lobe *entirely*. A rotationally symmetric shape
     * would have been the wrong subject: crop a ball-and-rod from any direction and the whole profile
     * is still visible, so it would have made a partial view look harmless when it is not.
     */
    private fun lobesTemplate(): SolidTemplate = smoothUnion(
        smoothUnion(sphere(0.50), sphere(0.35).at(-0.80, 0.0, 0.0), 0.25),
        sphere(0.42).at(0.80, 0.0, 0.0),
        0.25,
    ).parameterize {
        param("middle", "SmoothUnion/a/SmoothUnion/a/Sphere.radius")
        param("left", "SmoothUnion/a/SmoothUnion/b/Translate/Sphere.radius")
        param("right", "SmoothUnion/b/Translate/Sphere.radius")
    }.orThrow()

    private val truth = doubleArrayOf(0.50, 0.35, 0.42)
    private val seed = doubleArrayOf(0.30, 0.56, 0.20)

    @Test
    fun `a clean cloud all round the surface recovers every parameter`() {
        val template = lobesTemplate()
        val cloud = template.withValues(truth).samplePointCloud(count = 3000, seed = 4)

        // PREMISE, before anything is read from the result: the cloud is a *surface* sample and
        // carries no sign. If it ever stops being one this test is measuring something else.
        assertTrue(cloud.size == 3000, "the cloud lost points: ${cloud.size}")
        val field = template.withValues(truth).toField3()
        val worstOffSurface = (0 until cloud.size).maxOf { abs(field.at(cloud.xs[it], cloud.ys[it], cloud.zs[it])) }
        println("cloud: $cloud, worst point off the true surface ${"%.4e".format(worstOffSurface)}")
        assertTrue(
            worstOffSurface < 0.01 * cloud.scale,
            "the sampled points are not on the surface they claim to be on (worst $worstOffSurface); " +
                "the march resolution or the sampler is wrong, and every recovery below would be " +
                "measuring that instead",
        )

        val fit = template.fitToPointCloud(cloud, seed)
        println(fit.report())
        report(template, fit, truth)

        assertEquals(emptyList<String>(), fit.unconstrainedParameters)
        // Measured 1.0e6x. The floor is two orders below that and still forty thousand times more
        // than "it got better".
        assertTrue(fit.improvementFactor > 1e4, "only ${"%.3g".format(fit.improvementFactor)}x better")
        truth.indices.forEach { i ->
            // Measured 2.3e-4 to 3.9e-4. The floor sits just above the discretisation error the
            // cloud carries, which is where a surface fit of a marched shape *should* stop.
            assertTrue(
                abs(fit.values[i] - truth[i]) < 1e-3,
                "${template.names[i]} recovered as ${fit.values[i]}, wanted ${truth[i]}",
            )
            assertTrue(
                abs(seed[i] - truth[i]) > 0.15,
                "${template.names[i]} started only ${abs(seed[i] - truth[i])} away, so this is not a recovery",
            )
        }
        // The residual is the marching/sampling floor, not zero — the cloud sits on a *marched*
        // surface, which is the true one plus O(h²).
        assertTrue(
            fit.rmsDistance > 1e-6,
            "the fit reached ${fit.rmsDistance}, which is below the discretisation floor the cloud " +
                "itself carries — that would mean the cloud is not what this test thinks it is",
        )
    }

    @Test
    fun `Gaussian noise degrades the recovery gracefully rather than breaking it`() {
        val template = lobesTemplate()
        val clean = template.withValues(truth).samplePointCloud(count = 3000, seed = 4)
        val sigma = 0.01 * clean.scale

        val noisy = template.withValues(truth).samplePointCloud(count = 3000, seed = 4, noise = sigma)
        val fit = template.fitToPointCloud(noisy, seed)
        println("noise sigma ${"%.4f".format(sigma)} (1% of the cloud's ${"%.3f".format(clean.scale)} scale)")
        println(fit.report())
        report(template, fit, truth)

        val worst = truth.indices.maxOf { abs(fit.values[it] - truth[it]) }
        println("  worst parameter error ${"%.5f".format(worst)} = ${"%.2f".format(worst / sigma)} sigma")

        assertEquals(emptyList<String>(), fit.unconstrainedParameters)
        // Graceful: the parameter error stays inside the noise, not merely finite. A surface-fitting
        // estimator that recovers a parameter to well under one sigma is averaging the noise away,
        // which is the whole reason 3000 points beats 30.
        // Measured 0.09 sigma: 3000 points average the jitter down by about elevenfold. The floor
        // is at a quarter of sigma, which still fails an estimator that merely passes the noise
        // through, let alone one that amplifies it.
        assertTrue(
            worst < 0.25 * sigma,
            "noise of $sigma pushed a parameter $worst away (${"%.2f".format(worst / sigma)} sigma) — " +
                "the estimator is not averaging it away",
        )
        // And the residual has risen to about the noise level, which is what it should be: the model
        // cannot fit jitter and should not try.
        assertTrue(
            fit.rmsDistance > 0.3 * sigma,
            "the residual ${fit.rmsDistance} is far below the noise $sigma, which would mean the model " +
                "is fitting the jitter",
        )
    }

    /**
     * The failure. A one-sided view leaves a parameter with no gradient, and the optimiser correctly
     * converges without ever moving it.
     */
    @Test
    fun `a one-sided view leaves the unseen lobe unconstrained, and says so`() {
        val template = lobesTemplate()
        val full = template.withValues(truth).samplePointCloud(count = 4000, seed = 4)
        // Everything the sensor could see from -X. The right lobe spans x in [0.38, 1.22] and is
        // removed entirely; the middle and left lobes are still substantially present.
        val cropped = full.filter { x, _, _ -> x < 0.10 }
        println("cropped ${full.size} points to ${cropped.size}; extent ${cropped.extent().joinToString(", ") { "%.2f".format(it) }}")
        assertTrue(
            cropped.size in 1000..(full.size - 500),
            "the crop kept ${cropped.size} of ${full.size} — not a partial view of the kind this test means",
        )
        // PREMISE: the right lobe really is absent from this cloud. Checked geometrically, before
        // any conclusion is drawn from the fit.
        assertTrue(
            cropped.xs.max() < 0.38,
            "the crop still contains points on the right lobe (max x ${cropped.xs.max()}), so the " +
                "parameter below is not actually unobserved and this test proves nothing",
        )

        val fit = template.fitToPointCloud(cropped, seed)
        println(fit.report())
        report(template, fit, truth)

        val iRight = template.names.indexOf("right")
        // The detector names it before the descent — that is the whole reason the field exists.
        assertEquals(
            listOf("right"), fit.unconstrainedParameters,
            "a one-sided view was expected to leave exactly the unseen lobe's radius unconstrained",
        )
        // And it is *confidently wrong*: it sits exactly where it started, and the optimiser reports
        // convergence. This is asserted rather than fixed, because it cannot be fixed by the solver —
        // the objective genuinely does not depend on this number.
        assertTrue(
            abs(fit.values[iRight] - seed[iRight]) < 1e-9,
            "the unseen radius moved to ${fit.values[iRight]} from a seed of ${seed[iRight]}, which " +
                "would mean the cloud constrains it after all",
        )
        assertTrue(
            abs(fit.values[iRight] - truth[iRight]) > 0.15,
            "the unseen radius happens to be near the truth, which makes this test vacuous — " +
                "choose a seed further away",
        )
        // The two lobes that ARE in the cloud still come back, so the failure is localised rather
        // than a general collapse. That distinction is the useful half of the result.
        listOf("middle", "left").forEach { name ->
            val i = template.names.indexOf(name)
            assertTrue(
                abs(fit.values[i] - truth[i]) < 0.02,
                "$name is visible in the cloud and should still recover; got ${fit.values[i]} for ${truth[i]}",
            )
        }
    }

    /**
     * Three hundred points recover the shape as well as three thousand, and the reason is the
     * interesting part: on a **clean** cloud the limiting error is not sampling, it is the
     * discretisation of the marched surface the points were taken from.
     *
     * Ten times fewer points and the same three parameters to four decimal places. That is not a
     * statement about how easy the problem is — the noisy case above shows point count buying real
     * accuracy the moment there is noise to average — it is a statement about *which* error
     * dominates when there is none. Worth knowing before anybody pays for a denser scan to fix a
     * residual that a finer march would fix instead.
     */
    @Test
    fun `three hundred points are as good as three thousand on a clean cloud`() {
        val template = lobesTemplate()
        val dense = template.withValues(truth).samplePointCloud(count = 3000, seed = 9)
        val sparse = template.withValues(truth).samplePointCloud(count = 300, seed = 9)
        val denseFit = template.fitToPointCloud(dense, seed)
        val sparseFit = template.fitToPointCloud(sparse, seed)
        println("sparse: $sparse")
        println(sparseFit.report())
        report(template, sparseFit, truth)

        val denseWorst = truth.indices.maxOf { abs(denseFit.values[it] - truth[it]) }
        val sparseWorst = truth.indices.maxOf { abs(sparseFit.values[it] - truth[it]) }
        println(
            "  worst error: 3000 points ${"%.3e".format(denseWorst)}, 300 points ${"%.3e".format(sparseWorst)} " +
                "— ratio ${"%.2f".format(sparseWorst / denseWorst)}x for 10x fewer points",
        )

        assertEquals(emptyList<String>(), sparseFit.unconstrainedParameters)
        truth.indices.forEach { i ->
            assertTrue(
                abs(sparseFit.values[i] - truth[i]) < 1e-3,
                "${template.names[i]} recovered as ${sparseFit.values[i]} from 300 points, wanted ${truth[i]}",
            )
        }
        // The claim, asserted: ten times fewer points costs less than a factor of two. If this ever
        // fires, sampling has become the dominant error and the paragraph above is wrong.
        assertTrue(
            sparseWorst < 2.0 * denseWorst,
            "300 points were ${"%.2f".format(sparseWorst / denseWorst)}x worse than 3000, so sampling " +
                "IS the dominant error here and this test's stated reasoning no longer holds",
        )
    }

    private fun report(template: SolidTemplate, fit: SolidCloudFit, truth: DoubleArray) {
        truth.indices.forEach { i ->
            println(
                "  %-8s truth %.4f  seed %.4f  ->  fitted %.6f   error %.2e".format(
                    template.names[i], truth[i], fit.start[i], fit.values[i], abs(fit.values[i] - truth[i]),
                ),
            )
        }
    }
}
