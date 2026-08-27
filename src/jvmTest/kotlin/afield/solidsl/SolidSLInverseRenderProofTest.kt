package afield.solidsl

import afield.proofDir
import kotlin.math.abs
import afield.stdlib.MeshView
import afield.stdlib.boundsArray
import afield.stdlib.renderPixels
import afield.stdlib.renderPng
import afield.stdlib.signedVolume
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The inverse fit, **in pixels** — target, seed and fitted, rendered side by side and compared as
 * silhouettes.
 *
 * `SolidSLGradientTest` proves the loss fell by nineteen orders of magnitude. That is a number about
 * a number. It is consistent with fitting the wrong shape very precisely, with fitting the right
 * numbers into a mirrored lowering, and with the target sampler and the model sharing a bug that
 * cancels. None of those survive looking at the picture, which is why `SolidSLRenderProofTest` exists
 * for the other two lowerings and why this exists for the third.
 *
 * The measurement is a **silhouette exclusive-or**: the fraction of the frame where one render has
 * something and the other does not. Three claims come out of it, and the middle one is the
 * counterfactual the whole exercise turns on:
 *
 *  1. all three renders are real solids — covered, lit, and not inside-out,
 *  2. **the seed is visibly the wrong shape**, so the fit had somewhere to travel from,
 *  3. the fitted render and the target render are the same silhouette to a fraction of a percent,
 *     and by a large multiple better than the seed was.
 *
 * Claim 2 is asserted rather than assumed, because a "before" that already looked like the answer
 * would make claim 3 free.
 *
 * # Two scale-reference cubes, and why they are in the frame
 *
 * [MeshView.distance] is "in multiples of the mesh's bounding-sphere radius, so it is scale-free" —
 * which is exactly the wrong property here. Auto-framing would render a shape and a 1.5x copy of it
 * at the same apparent size, and a silhouette comparison across three differently-sized shapes would
 * be comparing three different cameras. So every subject is unioned with the same two small cubes at
 * fixed opposite corners: they pin the bounding sphere, so all three frames share one camera exactly,
 * and they are visible in the output as an honest scale reference rather than hidden.
 *
 * The PNGs go to [proofDir], a throwaway temp directory unless `AFIELD_PROOF_DIR` is set — so the
 * assertions run everywhere and nothing is left in the repository:
 *
 * ```
 * AFIELD_PROOF_DIR=/tmp/solidsl ./gradlew jvmTest --tests 'afield.solidsl.SolidSLInverseRenderProofTest'
 * ```
 *
 * Manifold is not consulted. Marching cubes and the software rasteriser are both pure Kotlin, so this
 * test runs everywhere rather than skipping on a machine without the native library.
 */
class SolidSLInverseRenderProofTest {

    private val view = MeshView(yawDegrees = 32.0, pitchDegrees = 22.0, width = 640, height = 480)

    /**
     * The two corner cubes that pin the camera. Identical in every subject, by construction.
     *
     * They sit exactly on the **target's own [SolidExpr.bounds] corners** and no further out. That is
     * the minimum that does the job — they have to dominate the box or they pin nothing — and every
     * unit beyond it is frame area taken away from the thing being looked at, which is why they are
     * anisotropic (the subject is long in Z) and why the seed is chosen to fit *inside* the target
     * rather than outside it.
     *
     * "The target's bounds" means the *symbolic* ones, and those are bigger than the shape:
     * [SolidExpr.SmoothUnion]'s bound grows the union by `k`, conservatively, because the fillet
     * bulges outward. So the cubes sit at ±(0.63, 0.63, 1.13) where the surface only reaches
     * ±(0.45, 0.45, 0.95), and the subject pays a little more frame area than it looks like it should.
     * Matching the conservative number is the point: it is the number the assertion below can compute
     * without marching anything.
     */
    private val scaleReference = unionOf(
        listOf(
            cuboid(0.09, 0.09, 0.09).at(-0.585, -0.585, -1.085),
            cuboid(0.09, 0.09, 0.09).at(0.585, 0.585, 1.085),
        ),
    )

    @Test
    fun `the fitted solid looks like the target and the seed does not`() {
        val dir = proofDir("solidsl-inverse")

        val template = barAndBallTemplate()
        val truthValues = BAR_AND_BALL_TRUTH
        val seedValues = BAR_AND_BALL_SEED

        val target = template.withValues(truthValues)
        val seed = template.withValues(seedValues)
        val fit = template.fitParameters(target.sampleField(count = 2400, seed = 91), seedValues)

        println(fit.report())

        // The seed's floor is lower than the other two, and deliberately: the seed is a *smaller*
        // shape, and its being visibly smaller is the counterfactual this whole test rests on. The
        // floor's remaining job is to fail an empty mesh, a mesh behind the camera and a mesh scaled
        // to a speck, and 2% of 640x480 is still 6,100 pixels.
        val frames = listOf(
            Frame("target", target, "the shape the samples came from", floor = 0.06),
            Frame(
                "seed", seed,
                "%.2f, %.2f, %.2f".format(seedValues[0], seedValues[1], seedValues[2]),
                floor = 0.02,
            ),
            Frame(
                "fitted", fit.fitted,
                "%.6f, %.6f, %.6f".format(fit.values[0], fit.values[1], fit.values[2]),
                floor = 0.06,
            ),
        )

        val masks = mutableMapOf<String, BooleanArray>()
        println("%-8s %9s %9s %9s %10s   %s".format("frame", "covered", "meanLuma", "bright", "volume", "values"))
        val coverage = mutableMapOf<String, Double>()
        val bounds = mutableMapOf<String, DoubleArray>()
        frames.forEach { (name, expr, title, floor) ->
            // `margin` is not decoration. The reference cubes sit exactly on the symbolic bounding
            // box, and marching cubes at the window edge produces an open surface or nothing at all —
            // which is how the first version of this test rendered three subjects with no reference
            // in them and an auto-framing camera it believed it had pinned. The assertion below now
            // checks the *marched* bounds, not the symbolic ones, for exactly that reason.
            val mesh = (expr + scaleReference).march(resolution = 128, margin = 0.08)
            // No `title =`: `renderPng` is the AWT-free path and its own KDoc says text is burned in
            // only by the AWT one, so a title argument here would be a caption nobody ever sees. The
            // filename and the printed table carry the labels instead.
            java.io.File(dir, "inverse_$name.png").writeBytes(mesh.renderPng(view))

            bounds[name] = mesh.boundsArray()

            val raster = mesh.renderPixels(view)
            val background = view.background
            val mask = BooleanArray(raster.pixels.size) { raster.pixels[it] != background }
            masks[name] = mask

            var lumaSum = 0.0
            var bright = 0
            var covered = 0
            raster.pixels.forEach { p ->
                if (p == background) return@forEach
                covered++
                val luma = (0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) +
                    0.0722 * (p and 0xFF)) / 255.0
                lumaSum += luma
                if (luma > 0.35) bright++
            }
            val n = raster.pixels.size.toDouble()
            val coveredFraction = covered / n
            coverage[name] = coveredFraction
            val meanLuma = if (covered == 0) 0.0 else lumaSum / covered
            println(
                "%-8s %8.2f%% %9.3f %8.2f%% %10.5f   %s".format(
                    name, 100 * coveredFraction, meanLuma, 100 * bright / n, mesh.signedVolume(), title,
                ),
            )

            // Claim 1. A tenth of the frame is a low bar that an empty mesh, a mesh behind the camera
            // and a mesh scaled to a speck all fail; the luma floor is what an all-black silhouette
            // from inward normals fails, and it passes a coverage check on its own.
            // Lower than the 10% `SolidSLRenderProofTest` uses, because the reference cubes push the
            // camera back on purpose and the subject pays for it in frame area.
            assertTrue(coveredFraction > floor, "$name: the render covers only ${100 * coveredFraction}% of the frame")
            assertTrue(meanLuma > 0.08, "$name: the render is covered but nearly black (luma $meanLuma)")
            assertTrue(bright / n > 0.02, "$name: the render has almost no lit pixels")
            assertTrue(mesh.signedVolume() > 0.0, "$name: the solid is inside-out")
        }

        // The camera really is pinned, checked where it is decided rather than inferred from the
        // pixels: `MeshView.distance` is in bounding-sphere radii, so three subjects share one camera
        // exactly when they share one bounding box. Each subject's symbolic bounds must therefore be
        // the *reference's* bounds — which is the precise statement of "the cubes dominate", and it
        // fails the moment a future seed or target grows past a corner.
        val reference = bounds.getValue("target")
        bounds.forEach { (name, b) ->
            assertTrue(
                (0 until 6).all { abs(b[it] - reference[it]) < 0.02 },
                "$name's marched bounds are ${b.toList()} against the target's ${reference.toList()}. " +
                    "The camera's eye distance is in bounding-sphere radii, so three subjects share one " +
                    "camera only when they share one bounding box — a difference here means the " +
                    "reference cubes did not survive the march and the silhouette comparison below is " +
                    "between two different cameras.",
            )
        }
        assertTrue(
            abs(coverage.getValue("fitted") - coverage.getValue("target")) < 0.003,
            "the fitted render covers ${coverage["fitted"]} against the target's ${coverage["target"]}",
        )

        // Normalised by the target's own footprint rather than left as a fraction of the frame,
        // because a fraction of the frame is a fact about the camera: the reference cubes push the
        // subject back, every number shrinks, and a threshold written against the old framing quietly
        // starts passing things it should not. "A third of the target's silhouette is in the wrong
        // place" survives a change of lens; "2.5% of the frame" does not.
        val subject = masks.getValue("target").count { it }.toDouble()
        val seedError = disagreement(masks.getValue("target"), masks.getValue("seed")) *
            masks.getValue("target").size / subject
        val fitError = disagreement(masks.getValue("target"), masks.getValue("fitted")) *
            masks.getValue("target").size / subject
        println(
            "silhouette error against the target, as a fraction of its own footprint: " +
                "seed %.1f%%, fitted %.3f%% (%s)".format(
                    100 * seedError, 100 * fitError,
                    if (fitError > 0.0) "%.0fx better".format(seedError / fitError) else "pixel-identical",
                ),
        )

        // Claim 2, the counterfactual, measured in this same run: the seed is a visibly different
        // object. Without this the next assertion is free.
        assertTrue(
            seedError > 0.20,
            "the seed silhouette misplaces only ${100 * seedError}% of the target's footprint, so it " +
                "was never visibly the wrong shape and the improvement below proves nothing",
        )
        // Claim 3. The parameters agree to ~5e-11, so the two marches should differ only where the
        // isosurface crosses a cell boundary differently — which is a handful of pixels, not none.
        assertTrue(
            fitError < 0.01,
            "the fitted silhouette misplaces ${100 * fitError}% of the target's footprint",
        )
        // Measured today: the fitted and target rasters are *pixel-identical*, so this ratio is
        // infinite. The floor is written against the ratio rather than against zero anyway, because
        // a march is a discretisation and a future resolution or camera could legitimately put one
        // pixel between them without anything being wrong.
        assertTrue(
            seedError / maxOf(fitError, 1.0 / subject) > 20.0,
            "the fit closed the silhouette gap by less than 20x (seed $seedError, fitted $fitError)",
        )
        println("wrote 3 PNGs to $dir")
    }

    /** One subject in the contact sheet. */
    private data class Frame(val name: String, val expr: SolidExpr, val title: String, val floor: Double)

    /** The fraction of the frame where exactly one of the two renders has something. */
    private fun disagreement(a: BooleanArray, b: BooleanArray): Double {
        require(a.size == b.size) { "two rasters of different sizes: ${a.size} and ${b.size}" }
        return a.indices.count { a[it] != b[it] }.toDouble() / a.size
    }
}
