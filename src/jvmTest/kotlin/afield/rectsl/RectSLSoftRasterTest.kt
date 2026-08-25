package afield.rectsl

import afield.Color4
import afield.stdlib.Functions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The soft rasterizer, held to an oracle it cannot have been written against, and its bias measured
 * rather than asserted.
 *
 * Four kinds of check live here, and they are deliberately different kinds:
 *
 * 1. **A differential against data, not against code.** `track_characterization.txt` is a 37,057-line
 *    dump of every gallery rect's sampled channels, generated from code that no longer exists. The
 *    `AD` signed-distance field is rebuilt from the lowered `Fn` graphs and compared against exact box
 *    distances computed from those golden numbers, so the oracle shares no code path with the thing
 *    under test — not even [SceneProgram.sample].
 * 2. **A cross-check of the exact reference itself.** [CoverageRect.exactCoverage] (convex clip plus
 *    shoelace) against JTS ([exactUnionCoverage]) on the case where both apply. A bias measured
 *    against an unchecked reference is not a measurement.
 * 3. **The bias, with two-sided assertions.** Every number the docs page quotes has a floor *and* a
 *    ceiling here, because a bias that silently improves is as much a change of behaviour as one that
 *    degrades, and one of the numbers is predicted analytically rather than observed — so a ceiling
 *    on it is a real test of the code.
 * 4. **A gradient against central differences**, and a fit with its counterfactual.
 */
class RectSLSoftRasterTest {

    private val galleryDir: Path = repoRoot().resolve("public/docs/assets/rectsl_gallery")

    // ---------------------------------------------------------------------------------------
    // 1. The differential against the pre-refactor golden
    // ---------------------------------------------------------------------------------------

    /**
     * The `AD` field must reproduce distances computed from the golden channel dump.
     *
     * Coverage is reported and asserted alongside the disagreement for the reason
     * `RectSLFnBackendTest` gives: a differential test that quietly refused every rect would
     * otherwise read as a pass at zero disagreements.
     */
    @Test
    fun `the AD signed distance field agrees with the pre-refactor golden dump`() {
        val golden = readGolden()
        assertTrue(golden.isNotEmpty(), "the golden dump is empty; the oracle is missing")

        var comparisons = 0
        var rectsCompared = 0
        var rectsInGolden = 0
        var programsCompared = 0
        var worst = 0.0
        var worstWhere = ""
        val refusals = mutableListOf<String>()

        for ((name, source) in gallerySources()) {
            val program = RectAnimationOracle.compile(source).programOrNull ?: continue
            val goldenTimes = golden.keys.filter { it.first == name }.map { it.second }.distinct().sorted()
            if (goldenTimes.isEmpty()) continue
            // Lower over the golden's own span, which runs to 1.25x the duration, so every golden
            // instant is inside the window the lowering was declared valid on. This refuses more
            // tracks than a [0, duration] window would; that shows up as coverage, not as silence.
            val window = Functions.Domain(0.0, maxOf(goldenTimes.max(), 1e-9))
            val field = runCatching { program.toAdField(window) }.getOrNull() ?: run {
                refusals += "$name: no rect lowered"
                continue
            }
            programsCompared++
            val lowered = field.shapes.map { it.id }.toSet()
            for (time in goldenTimes) {
                val rows = golden[name to time] ?: continue
                rectsInGolden += rows.size
                val visible = rows.filter { it.id in lowered && it.alpha > 0.0 }
                rectsCompared += visible.size
                if (visible.isEmpty()) continue
                val frame = field.frameAt(time)
                for (p in probePoints(program.wall, visible)) {
                    val expected = visible.minOf { it.signedDistance(p.first, p.second) }
                    val actual = frame.distanceAt(p.first, p.second).value
                    val delta = abs(expected - actual)
                    if (delta > worst) {
                        worst = delta
                        worstWhere = "$name t=$time p=(${p.first}, ${p.second})"
                    }
                    comparisons++
                }
            }
        }

        println(
            "AD SDF vs golden: worst |delta| = $worst at $worstWhere over $comparisons comparisons, " +
                "$rectsCompared / $rectsInGolden golden rects in $programsCompared programs" +
                if (refusals.isEmpty()) "" else "; refused: ${refusals.take(3)}"
        )

        // The population floors first: a shrinking corpus must fail here rather than flattering the
        // disagreement below it.
        assertTrue(programsCompared >= 25, "only $programsCompared gallery programs lowered at all")
        assertTrue(comparisons >= 200_000, "only $comparisons distance comparisons; the sweep shrank")
        assertTrue(
            rectsCompared.toDouble() / rectsInGolden >= 0.80,
            "only $rectsCompared of $rectsInGolden golden rects lowered " +
                "(${"%.1f".format(100.0 * rectsCompared / rectsInGolden)}%)"
        )
        assertTrue(
            worst <= 1e-12,
            "the AD field disagrees with the golden dump by $worst at $worstWhere"
        )
    }

    /**
     * The tracked and untracked spellings of the box field must be *bit* identical.
     *
     * [CoverageRect.signedDistance] exists because a tape per pixel is unaffordable in the bias
     * sweep, which makes it a duplicated formula — the classic place for two implementations to drift.
     * Pinning them at zero difference rather than at a tolerance is what makes the duplication safe.
     */
    @Test
    fun `the untracked box field is bit-identical to the tracked one`() {
        val program = spinScene()
        val field = program.toAdField()
        var compared = 0
        for (time in listOf(0.0, 0.17, 0.41, 0.63, 0.88, 1.0)) {
            val frame = field.frameAt(time)
            val rects = frame.visible.map { it.toCoverageRect() }
            assertTrue(rects.isNotEmpty(), "no visible rect at t=$time; nothing was compared")
            for (i in 0..40) {
                for (j in 0..40) {
                    val x = -8.0 + 16.0 * i / 40.0
                    val y = -8.0 + 16.0 * j / 40.0
                    frame.visible.forEachIndexed { index, shape ->
                        assertEquals(
                            shape.distanceAt(x, y).value,
                            rects[index].signedDistance(x, y),
                            0.0,
                            "tracked and untracked box fields differ at ($x, $y) t=$time"
                        )
                        compared++
                    }
                }
            }
        }
        assertTrue(compared >= 5000, "only $compared points compared")
    }

    // ---------------------------------------------------------------------------------------
    // 2. The exact reference, cross-checked
    // ---------------------------------------------------------------------------------------

    /**
     * Convex clipping and JTS must agree on the quantity the whole bias measurement rests on.
     *
     * The sampler is built to *produce* partial pixels, not to avoid them: the pixel is always placed
     * so that at least a few percent of the cases straddle an edge or a corner, and the count of
     * strictly-partial cases is asserted. A cross-check that only ever compared 0 against 0 and 1
     * against 1 would agree perfectly and prove nothing.
     */
    @Test
    fun `convex-clip coverage agrees with JTS, including on partial pixels`() {
        val random = java.util.Random(20260825L)
        var worst = 0.0
        var partial = 0
        var cases = 0
        for (case in 0 until 4000) {
            val rect = CoverageRect(
                centreX = (random.nextDouble() - 0.5) * 2.0,
                centreY = (random.nextDouble() - 0.5) * 2.0,
                halfX = 0.05 + random.nextDouble() * 1.2,
                halfY = 0.05 + random.nextDouble() * 1.2,
                rotation = random.nextDouble() * 6.283185307179586
            )
            // Half the boxes are placed at a corner of the rect and half at a random point, so the
            // straddling cases are generated on purpose rather than hoped for.
            val size = 0.05 + random.nextDouble() * 0.5
            val (bx, by) = if (case % 2 == 0) {
                val corners = rect.corners()
                val k = random.nextInt(4)
                corners[k * 2] - size * random.nextDouble() to corners[k * 2 + 1] - size * random.nextDouble()
            } else {
                (random.nextDouble() - 0.5) * 3.0 to (random.nextDouble() - 0.5) * 3.0
            }
            val box = doubleArrayOf(bx, by, bx + size, by + size)
            val clip = rect.exactCoverage(box)
            val jts = exactUnionCoverage(listOf(rect, rect), box)
            worst = maxOf(worst, abs(clip - jts))
            if (clip > 1e-9 && clip < 1.0 - 1e-9) partial++
            cases++
        }
        println("convex clip vs JTS: worst |delta| = $worst over $cases cases, $partial strictly partial")
        assertTrue(partial >= 500, "only $partial of $cases cases were strictly partial")
        assertTrue(worst <= 1e-12, "convex clipping and JTS disagree by $worst")
    }

    /** A kernel's closed-form slope must match a central difference of its own coverage. */
    @Test
    fun `each kernel's slope matches a central difference of its coverage`() {
        val beta = 0.125
        var worst = 0.0
        var nonZero = 0
        for (kernel in CoverageKernel.entries) {
            for (step in -60..60) {
                // Avoid the two band edges, where LINEAR is genuinely discontinuous in slope and a
                // central difference straddles the kink. The kink is the truth about a clamp, not a
                // disagreement, and asserting across it would be asserting the wrong thing.
                val d = step * beta / 40.0
                if (kernel != CoverageKernel.SIGMOID && abs(abs(d) - 0.5 * beta) < beta / 20.0) continue
                val h = beta * 1e-5
                val fd = (kernel.coverage(d + h, beta) - kernel.coverage(d - h, beta)) / (2.0 * h)
                val exact = kernel.slope(d, beta)
                if (abs(exact) > 1e-9) nonZero++
                worst = maxOf(worst, abs(exact - fd) / (1.0 + abs(fd)))
            }
        }
        println("kernel slope vs central difference: worst relative delta = $worst, $nonZero non-zero slopes")
        assertTrue(nonZero >= 100, "only $nonZero non-zero slopes were exercised")
        assertTrue(worst <= 1e-7, "a kernel's slope disagrees with its coverage by $worst")
    }

    // ---------------------------------------------------------------------------------------
    // 3. The bias
    // ---------------------------------------------------------------------------------------

    /**
     * The premise of the whole measurement: at one pixel of band, the clamped ramp is *exactly* the
     * area of a pixel on one side of an axis-aligned line.
     *
     * If this is not at machine precision then either the kernel or the exact reference is wrong, and
     * every other number in this file is meaningless — so it is asserted first, and asserted with a
     * counterfactual: the sigmoid at the *same* band width, on the *same* geometry, must be biased by
     * a floored amount. Blank-versus-blank cannot pass here because the partial-pixel count is
     * asserted too.
     */
    @Test
    fun `the linear ramp at one pixel is exact for an axis-aligned edge`() {
        val program = squareScene(rotation = 0.0, size = 8.0)
        val field = program.toAdField()
        val linear = measureCoverageBias(field, spec(program, betaPixels = 1.0, CoverageKernel.LINEAR), 0.0)
        val sigmoid = measureCoverageBias(field, spec(program, betaPixels = 1.0, CoverageKernel.SIGMOID), 0.0)
        val smooth = measureCoverageBias(field, spec(program, betaPixels = 1.0, CoverageKernel.SMOOTHSTEP), 0.0)
        println("axis-aligned edge, beta = 1px:")
        println("  $linear")
        println("  $smooth")
        println("  $sigmoid")

        assertTrue(linear.edgePixels >= 400, "only ${linear.edgePixels} edge pixels; nothing was measured")
        // 1.1e-13, not 1e-16: the exact reference goes through a shoelace of a sliver whose area is
        // ~1e-2 against coordinates of ~8, so double rounding lands here. That is a rounding floor,
        // not an approximation — the ceiling is set an order above the observed value and nothing
        // about the geometry can push it up without a real change of behaviour.
        assertTrue(
            linear.worstEdgeBias <= 2e-12,
            "the clamped ramp should be exact to rounding on an axis-aligned edge, worst was " +
                "${linear.worstEdgeBias}"
        )
        // The counterfactual, floored at a number rather than at "differs".
        assertTrue(
            sigmoid.worstEdgeBias >= 0.02,
            "the sigmoid should be visibly biased on the same geometry, worst was ${sigmoid.worstEdgeBias}"
        )
        assertTrue(
            smooth.worstEdgeBias >= 0.05,
            "smoothstep should be visibly biased on the same geometry, worst was ${smooth.worstEdgeBias}"
        )
        // A finding, asserted so it cannot quietly reverse: on an axis-aligned edge smoothstep is
        // *worse* than the sigmoid, and at 30 degrees of obliquity it is better (see the reach test).
        // The ordering of the two curved kernels is geometry-dependent; only the ramp's primacy is not.
        assertTrue(
            smooth.worstEdgeBias > sigmoid.worstEdgeBias,
            "on an axis-aligned edge smoothstep (${smooth.worstEdgeBias}) should be worse than the " +
                "sigmoid (${sigmoid.worstEdgeBias}); the docs page's crossover claim depends on it"
        )
    }

    /**
     * Obliquity bias, swept over angle, against the closed-form bound.
     *
     * For a unit pixel and a straight edge at 45°, exact coverage as a function of the centre's signed
     * distance `d` is `½(1 - √2·d)²` on `0 ≤ d ≤ √2/2`, so the clamped ramp's worst departure is at
     * `d = ½`, where the ramp has reached zero and the true coverage has not:
     * `½(1 - √2/2)² = 0.0428932…`. That is a prediction, not an observation, and the assertion below
     * brackets the measurement around it.
     */
    @Test
    fun `obliquity bias peaks at 45 degrees near the closed-form bound`() {
        val bound = 0.5 * (1.0 - sqrt(2.0) / 2.0) * (1.0 - sqrt(2.0) / 2.0)
        val rows = listOf(0.0, 7.5, 15.0, 22.5, 30.0, 37.5, 45.0).map { degrees ->
            val program = squareScene(rotation = Math.toRadians(degrees), size = 8.0)
            val field = program.toAdField()
            degrees to measureCoverageBias(field, spec(program, betaPixels = 1.0, CoverageKernel.LINEAR), 0.0)
        }
        println("obliquity sweep (LINEAR, beta = 1px), closed-form 45 degree bound = $bound:")
        rows.forEach { (degrees, row) -> println("  %5.1f deg  %s".format(degrees, row)) }

        rows.forEach { (degrees, row) ->
            assertTrue(row.edgePixels >= 200, "only ${row.edgePixels} edge pixels at $degrees deg")
        }
        val worst = rows.maxOf { it.second.worstEdgeBias }
        val at45 = rows.last().second.worstEdgeBias
        assertEquals(45.0, rows.last().first)
        assertTrue(
            at45 <= bound + 1e-6,
            "the 45 degree edge bias $at45 exceeds the closed-form bound $bound, so the kernel, the " +
                "reference or the derivation is wrong"
        )
        assertTrue(at45 >= 0.9 * bound, "the 45 degree edge bias $at45 is far below the bound $bound")
        assertTrue(
            worst <= bound + 1e-6,
            "some angle biases worse ($worst) than the 45 degree bound $bound"
        )
        // Monotone in obliquity, every step of the way — the qualitative claim the docs page makes,
        // asserted as the whole ordering rather than as its endpoints.
        val edgeBiases = rows.map { it.second.worstEdgeBias }
        assertEquals(
            edgeBiases.sorted(), edgeBiases,
            "edge bias is not monotone in obliquity: $edgeBiases"
        )
        // And the finding that was not predicted: a *corner* is worse than any edge, because two
        // edges meet inside one pixel and no function of one distance can be right there.
        val worstCorner = rows.maxOf { it.second.worstCornerBias }
        rows.forEach { (degrees, row) ->
            if (degrees > 0.0) {
                assertTrue(row.cornerPixels >= 4, "only ${row.cornerPixels} corner pixels at $degrees deg")
            }
        }
        assertTrue(
            worstCorner > 2.0 * bound,
            "corner bias $worstCorner should dominate the $bound edge bound; if it no longer does, the " +
                "docs page's taxonomy is wrong"
        )
        assertTrue(worstCorner <= 0.5, "corner bias $worstCorner exceeds any possible coverage error")
    }

    /**
     * Feature-scale bias: a bar thinner than the band cannot be rasterized softly at all.
     *
     * The claim under test is not "bias grows" but *how far* it grows: at a quarter-pixel bar the
     * error is a large fraction of the feature's own coverage, which is the statement that a soft
     * raster is unusable there. Both ends are floored.
     */
    @Test
    fun `bias grows without bound as a feature thins below the band`() {
        val widths = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0)
        val rows = widths.map { pixels ->
            val program = barScene(widthInPixels = pixels)
            val field = program.toAdField()
            pixels to measureCoverageBias(field, spec(program, betaPixels = 1.0, CoverageKernel.LINEAR), 0.0)
        }
        println("feature-scale sweep (LINEAR, beta = 1px):")
        rows.forEach { (w, row) -> println("  width %5.2f px  %s".format(w, row)) }

        rows.forEach { (w, row) ->
            assertTrue(row.partialPixels >= 50, "only ${row.partialPixels} partial pixels at width $w px")
            assertTrue(row.edgePixels >= 50, "only ${row.edgePixels} edge pixels at width $w px")
        }
        // Read on the *edge* column, not the partial column. A bar has four corners and corner bias is
        // a different failure that does not depend on the width at all — folding the two together
        // would have reported a flat 1.57e-1 for every width and called it a feature-scale result.
        val thin = rows.first().second.worstEdgeBias
        val half = rows[1].second.worstEdgeBias
        val thick = rows.last().second.worstEdgeBias
        assertTrue(thin >= 0.20, "a quarter-pixel bar should be badly biased, worst edge bias was $thin")
        assertTrue(thin <= 0.50, "a quarter-pixel bar's bias $thin exceeds any coverage error possible")
        assertTrue(half in 0.08..0.20, "a half-pixel bar's edge bias $half is off the measured curve")
        assertTrue(
            thick <= 1e-12,
            "an axis-aligned bar eight pixels wide should be exact to rounding, worst edge bias $thick"
        )
        // Monotone across the sub-band widths, then at rounding for every width at or above the band.
        // The tail is deliberately not part of the ordering: those values are 4e-15, 4e-15, 1e-14,
        // 2e-14, which is shoelace rounding rising with the bar's area, and demanding an order there
        // would be asserting the direction of a rounding error.
        val edges = rows.map { it.second.worstEdgeBias }
        val subBand = edges.take(2)
        assertEquals(subBand.sortedDescending(), subBand, "sub-band edge bias is not monotone: $subBand")
        edges.drop(2).forEach {
            assertTrue(it <= 1e-12, "an at-or-above-band axis-aligned bar should be exact, was $it")
        }
        assertTrue(subBand.min() > 1e6 * edges.drop(2).max(), "the sub-band jump vanished: $edges")
        // The corner bias is *width-independent*, which is what makes it a separate source. Pinned so
        // that a change collapsing the two shows up here. The one-pixel bar is excluded: at that width
        // a corner is still inside the *opposite* edge's band, so the two sources overlap there and the
        // measured 9.1e-2 is a mixture rather than a corner number.
        val corners = rows.drop(3).map { it.second.worstCornerBias }
        assertTrue(
            corners.max() - corners.min() < 1e-9,
            "corner bias should not depend on the bar's width once it exceeds the band: $corners"
        )
        assertTrue(corners.first() in 0.10..0.25, "a right-angle corner's bias moved: ${corners.first()}")
    }

    /**
     * The union seam: `min` of two signed distances is not the union's interior distance, so the
     * coverage-of-union composite collapses across a seam that is entirely covered.
     *
     * This is the bias that would break a real image fit, and the over-composite is measurably —
     * not merely arguably — better at it. Both numbers are floored, and their *ratio* is too, so the
     * finding cannot survive one of them changing quietly.
     */
    @Test
    fun `a union seam biases the union-SDF composite worse than the over composite`() {
        val program = seamScene()
        val field = program.toAdField()
        val union = measureCoverageBias(field, spec(program, 1.0, CoverageKernel.LINEAR, CoverageComposite.UNION_SDF), 0.0)
        val over = measureCoverageBias(field, spec(program, 1.0, CoverageKernel.LINEAR, CoverageComposite.OVER), 0.0)
        println("union seam (LINEAR, beta = 1px):")
        println("  $union")
        println("  $over")

        assertTrue(union.pixels >= 4096, "the seam raster is too small to mean anything")
        assertTrue(union.partialPixels >= 100, "only ${union.partialPixels} partial pixels at the seam")
        assertTrue(
            union.worstBias in 0.33..0.45,
            "the union-SDF composite should collapse at a seam, worst was ${union.worstBias}"
        )
        assertTrue(
            over.worstBias in 0.18..0.28,
            "the over composite should be biased at a seam but less so, worst was ${over.worstBias}"
        )
        assertTrue(
            union.worstBias > 1.5 * over.worstBias,
            "the two composites did not separate at a seam: ${union.worstBias} vs ${over.worstBias}"
        )
        assertTrue(
            union.meanPartialBias > 1.4 * over.meanPartialBias,
            "the separation is only at one pixel: means ${union.meanPartialBias} vs ${over.meanPartialBias}"
        )
    }

    /**
     * The trade-off that makes the bias table a table and not a ranking: less bias, less reach.
     *
     * Asserted as a strict ordering with numeric floors on both ends, because "the sigmoid reaches
     * further" is the sentence that licenses using the *most* biased kernel in a fit, and a sentence
     * like that should not be able to become false silently.
     */
    @Test
    fun `the least biased kernel has the least gradient reach`() {
        val program = squareScene(rotation = Math.toRadians(30.0), size = 4.0)
        val field = program.toAdField()
        val rows = CoverageKernel.entries.associateWith {
            measureCoverageBias(field, spec(program, betaPixels = 1.0, it), 0.0)
        }
        println("bias against reach (beta = 1px, 30 degree square):")
        rows.forEach { (_, row) -> println("  $row") }

        val linear = rows.getValue(CoverageKernel.LINEAR)
        val smooth = rows.getValue(CoverageKernel.SMOOTHSTEP)
        val sigmoid = rows.getValue(CoverageKernel.SIGMOID)
        // At 30 degrees the ordering is linear < smoothstep < sigmoid. It is *not* that ordering on an
        // axis-aligned edge, where smoothstep is the worst of the three — see the exactness test. Both
        // halves of that crossover are asserted, in the two places they hold, so neither can drift.
        assertTrue(
            linear.worstEdgeBias < smooth.worstEdgeBias && smooth.worstEdgeBias < sigmoid.worstEdgeBias,
            "kernel bias ordering at 30 degrees changed: ${linear.worstEdgeBias}, " +
                "${smooth.worstEdgeBias}, ${sigmoid.worstEdgeBias}"
        )
        assertTrue(linear.edgePixels >= 100, "only ${linear.edgePixels} edge pixels were measured")
        // Reach in *pixels* is the scene-independent statement, and the honest one: the sigmoid does
        // not reach "everywhere", it reaches about eight times further than the ramp before its slope
        // falls under 1e-6. Both ends floored, plus the ratio.
        assertTrue(
            linear.reachInPixels in 0.49..0.51,
            "the ramp's reach should be half a pixel at beta = 1px, was ${linear.reachInPixels}"
        )
        assertTrue(
            sigmoid.reachInPixels >= 4.0,
            "the sigmoid should reach several pixels, was ${sigmoid.reachInPixels}"
        )
        assertTrue(
            sigmoid.reachInPixels > 6.0 * linear.reachInPixels,
            "the reach gap closed: ${sigmoid.reachInPixels}px vs ${linear.reachInPixels}px"
        )
        assertTrue(linear.gradientReach <= 0.02, "the ramp reaches too much of the image: ${linear.gradientReach}")
        assertTrue(
            sigmoid.gradientReach >= 4.0 * linear.gradientReach,
            "the reach gap closed on this image: ${sigmoid.gradientReach} vs ${linear.gradientReach}"
        )
    }

    // ---------------------------------------------------------------------------------------
    // 4. The gradient, and a fit
    // ---------------------------------------------------------------------------------------

    /**
     * `d(image loss)/dθ` from one backward sweep, against a central difference of the same loss.
     *
     * The check that the whole file exists to license. Every θ slot is probed, not a sample of them,
     * and the count of slots with a non-negligible gradient is asserted — a gradient that is zero
     * everywhere agrees with a central difference perfectly.
     */
    @Test
    fun `the raster loss gradient matches central differences`() {
        val program = fitScene(offsetX = 0.0, offsetY = 0.0, width = 3.0)
        val field = program.toAdField()
        val spec = spec(program, betaPixels = 1.5, CoverageKernel.SIGMOID)
        val target = softRaster(field, spec, 0.0, field.withFreeParameters(mapOf("bar-x" to 1.4, "bar-w" to 4.0)))
        val at = field.theta.copyOf()
        val result = softRasterLossAndGradient(field, spec, 0.0, target, at)

        var worst = 0.0
        var probed = 0
        var live = 0
        for (k in at.indices) {
            val h = 1e-5 * (1.0 + abs(at[k]))
            val plus = at.copyOf().also { it[k] += h }
            val minus = at.copyOf().also { it[k] -= h }
            val fd = (
                softRasterLossAndGradient(field, spec, 0.0, target, plus).loss -
                    softRasterLossAndGradient(field, spec, 0.0, target, minus).loss
                ) / (2.0 * h)
            val delta = abs(result.gradient[k] - fd) / (1.0 + abs(fd))
            if (abs(result.gradient[k]) > 1e-8) live++
            if (delta > worst) worst = delta
            probed++
        }
        println(
            "raster gradient vs central difference: worst relative delta = $worst over $probed theta " +
                "slots, $live of them with a live derivative; loss = ${result.loss}"
        )
        // Eight, exactly: a rect folds to one leaf per channel (x, y, w, h, rotation, scaleX, scaleY,
        // alpha). Pinned rather than floored, so a change in how a channel folds shows up here and has
        // to be re-derived rather than absorbed.
        assertEquals(8, probed, "the scene's theta is no longer 8 leaves; re-derive the channel count")
        assertTrue(live >= 3, "only $live theta slots had a live derivative")
        assertTrue(worst <= 1e-5, "the exact raster gradient disagrees with a central difference by $worst")
    }

    /**
     * The thing the 2026-08-06 abandonment said could not be done from a per-track lowering: solve a
     * scene's free parameters against a **target image**.
     *
     * Reported with its counterfactual — the seed's loss — and with a floor on how much of it was
     * removed, so a fit that merely wobbled cannot pass.
     */
    @Test
    fun `a scene's free parameters solve against a target raster`() {
        val program = fitScene(offsetX = 0.0, offsetY = 0.0, width = 3.0)
        val field = program.toAdField()
        val spec = spec(program, betaPixels = 2.0, CoverageKernel.SIGMOID)
        val truth = mapOf("bar-x" to 1.6, "bar-y" to -1.1, "bar-w" to 4.2)
        val target = softRaster(field, spec, 0.0, field.withFreeParameters(truth))
        assertTrue(target.any { it > 0.05 } && target.any { it < 0.95 }, "the target raster is blank")

        val names = field.freeParameterNames
        assertEquals(truth.keys, names.toSet(), "the scene's free parameters are not the ones being solved")

        var theta = field.theta.copyOf()
        val seedLoss = softRasterLossAndGradient(field, spec, 0.0, target, theta).loss
        val m = DoubleArray(names.size)
        val v = DoubleArray(names.size)
        var loss = seedLoss
        for (iteration in 1..320) {
            val step = softRasterLossAndGradient(field, spec, 0.0, target, theta)
            loss = step.loss
            for (n in names.indices) {
                val slots = field.freeParameterSlots.getValue(names[n])
                val g = slots.sumOf { step.gradient[it] }
                if (!g.isFinite()) continue
                m[n] = 0.9 * m[n] + 0.1 * g
                v[n] = 0.999 * v[n] + 0.001 * g * g
                val mHat = m[n] / (1.0 - Math.pow(0.9, iteration.toDouble()))
                val vHat = v[n] / (1.0 - Math.pow(0.999, iteration.toDouble()))
                val update = 0.05 * mHat / (sqrt(vHat) + 1e-8)
                if (update.isFinite()) for (slot in slots) theta[slot] -= update
            }
        }
        val solved = field.freeParameters(theta)
        val worstParameter = truth.maxOf { (name, want) -> abs(solved.getValue(name) - want) }
        println(
            "image fit: loss $seedLoss -> $loss; solved $solved against $truth, " +
                "worst parameter miss $worstParameter"
        )
        assertTrue(seedLoss > 1e-4, "the seed already matched the target; the fit proves nothing")
        assertTrue(loss < seedLoss / 50.0, "the fit removed too little: $seedLoss -> $loss")
        assertTrue(worstParameter < 0.05, "the fit did not recover the parameters, worst miss $worstParameter")
    }

    /**
     * The lowering's own ledger has to be consistent in **both** directions: every θ slot a name
     * claims is inside θ, every name is reachable, and the count of slots matches the count of leaves.
     *
     * A one-directional check ("every name has a slot") would pass a ledger that had quietly grown a
     * slot pointing at another channel's leaf, which is the failure that makes a fit solve for the
     * wrong number while reporting convergence.
     */
    @Test
    fun `the scene theta ledger is consistent in both directions`() {
        val program = fitScene(offsetX = 0.3, offsetY = -0.2, width = 3.0)
        val field = program.toAdField()
        assertTrue(field.theta.isNotEmpty(), "the scene lowered to an empty theta")
        assertEquals(
            setOf("bar-x", "bar-y", "bar-w"), field.freeParameterSlots.keys,
            "free-parameter names drifted"
        )
        for ((name, slots) in field.freeParameterSlots) {
            assertTrue(slots.isNotEmpty(), "$name claims no slot")
            slots.forEach { assertTrue(it in field.theta.indices, "$name claims slot $it, out of range") }
        }
        // Writing a name must move exactly its own slots and nothing else.
        for (name in field.freeParameterNames) {
            val moved = field.withFreeParameters(mapOf(name to 12.5))
            val changed = field.theta.indices.filter { moved[it] != field.theta[it] }.toSet()
            assertEquals(
                field.freeParameterSlots.getValue(name).toSet(), changed,
                "writing $name moved the wrong slots"
            )
        }
        // And every rect accounted for: lowered plus skipped is the whole scene.
        val rects = program.nodes.filterIsInstance<RectNode>().map { it.id }.toSet()
        assertEquals(
            rects, field.shapes.map { it.id }.toSet() + field.skipped.keys,
            "the lowering lost or invented a rect"
        )
    }

    /**
     * A scene with no differentiable rect must refuse loudly rather than return a zero gradient.
     *
     * The refusing track is a keyed track with two keys at the same instant, which
     * `lowerKeyed` refuses as "a step rather than a span". That refusal is asserted **first**, as this
     * test's own premise: if the lowering ever learns to handle it, this fails on the premise line
     * with a message saying so, rather than quietly becoming a test of nothing.
     */
    @Test
    fun `a scene that cannot be lowered refuses instead of returning a flat field`() {
        val stepped = LinearScalarTrack(listOf(TcbKey(0.5, 0.0), TcbKey(0.5, 1.0), TcbKey(1.0, 2.0)))
        val refusal = stepped.toFn(Functions.Domain(0.0, 1.0)).refusalOrNull()
        assertTrue(
            refusal != null,
            "premise gone: a keyed track with two keys at one time now lowers, so this test must be " +
                "rewritten around a track that still refuses"
        )
        val program = SceneProgram(
            wall = WallSpec(16.0, 16.0, UnitKind.Meters),
            duration = 1.0,
            fps = 30.0,
            nodes = listOf(bar("only", 0.0, 0.0, 2.0, 2.0, x = stepped))
        )
        val thrown = runCatching { program.toAdField() }.exceptionOrNull()
        assertTrue(thrown is RectLangException, "the lowering did not refuse; it returned ${thrown ?: "a field"}")
        assertTrue(
            thrown.message!!.contains("raster gradient is zero everywhere"),
            "the refusal should say why a flat field is dangerous, said: ${thrown.message}"
        )
        assertTrue(
            thrown.message!!.contains("share a time"),
            "the refusal should carry the track's own reason, said: ${thrown.message}"
        )
    }


    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    /**
     * The pixel pitch every fixture here is built around: a 16-unit wall at 128 pixels.
     *
     * Named, because the *first* version of this file placed every rect on a whole number of wall
     * units and every fixture therefore had its edges exactly on pixel boundaries — so no pixel was
     * ever partially covered and every bias in the table came out at exactly zero. That is precisely
     * the measurement that could not have failed, and the only reason it was caught is that the
     * partial-pixel counts are asserted. Every fixture now carries [JITTER_X] / [JITTER_Y].
     */
    private val PIXEL = 16.0 / 128.0

    /** A deliberately non-dyadic sub-pixel offset, so an edge lands *inside* a pixel. */
    private val JITTER_X = 0.37 * PIXEL

    /** As [JITTER_X], and different from it, so a corner is not symmetric about a pixel centre. */
    private val JITTER_Y = 0.21 * PIXEL

    private fun spec(
        program: SceneProgram,
        betaPixels: Double,
        kernel: CoverageKernel,
        composite: CoverageComposite = CoverageComposite.UNION_SDF,
        size: Int = 128
    ): SoftRasterSpec = SoftRasterSpec(
        wall = program.wall,
        width = size,
        height = size,
        beta = betaPixels * program.wall.width / size,
        kernel = kernel,
        composite = composite
    )

    private fun bar(
        id: String,
        cx: Double,
        cy: Double,
        w: Double,
        h: Double,
        rotation: Track<Double> = constantTrack(0.0),
        x: Track<Double> = constantTrack(cx),
        y: Track<Double> = constantTrack(cy),
        width: Track<Double> = constantTrack(w)
    ): RectNode = RectNode(
        id = id,
        x = x,
        y = y,
        w = width,
        h = constantTrack(h),
        rotation = rotation,
        scaleX = constantTrack(1.0),
        scaleY = constantTrack(1.0),
        alpha = constantTrack(1.0),
        z = constantTrack(0.0),
        fill = constantPaintTrack(PaintSpec.Solid(Color4(1.0, 1.0, 1.0))),
        material = MaterialBinding(0),
        tags = emptySet()
    )

    private fun wallScene(vararg rects: RectNode): SceneProgram = SceneProgram(
        wall = WallSpec(16.0, 16.0, UnitKind.Meters),
        duration = 1.0,
        fps = 30.0,
        nodes = rects.toList()
    )

    private fun squareScene(rotation: Double, size: Double): SceneProgram =
        wallScene(bar("square", JITTER_X, JITTER_Y, size, size, rotation = constantTrack(rotation)))

    private fun barScene(widthInPixels: Double): SceneProgram =
        wallScene(bar("bar", JITTER_X, JITTER_Y, widthInPixels * PIXEL, 10.0 + JITTER_Y))

    private fun seamScene(): SceneProgram = wallScene(
        bar("left", -2.0 + JITTER_X, JITTER_Y, 4.0, 8.0 + JITTER_Y),
        bar("right", 2.0 + JITTER_X, JITTER_Y, 4.0, 8.0 + JITTER_Y)
    )

    private fun fitScene(offsetX: Double, offsetY: Double, width: Double): SceneProgram = wallScene(
        bar(
            "solved", 0.0, 0.0, width, 5.0 + JITTER_Y,
            x = FreeParamTrack("bar-x", offsetX + JITTER_X),
            y = FreeParamTrack("bar-y", offsetY + JITTER_Y),
            width = FreeParamTrack("bar-w", width)
        )
    )

    /** A scene whose rect actually moves, so a frame is not the same frame at every time. */
    private fun spinScene(): SceneProgram = wallScene(
        bar(
            "spinner", 0.0, JITTER_Y, 5.0, 3.0,
            rotation = LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(1.0, 1.2))),
            x = LinearScalarTrack(listOf(TcbKey(0.0, -3.0), TcbKey(1.0, 3.0)))
        ),
        bar("still", 2.0 + JITTER_X, -2.0, 2.0, 6.0)
    )

    // ---------------------------------------------------------------------------------------
    // The golden dump, as data
    // ---------------------------------------------------------------------------------------

    /** One golden row: a sampled rect, with the box field it implies. */
    private class GoldenRect(
        val id: String,
        val centreX: Double,
        val centreY: Double,
        val halfX: Double,
        val halfY: Double,
        val rotation: Double,
        val alpha: Double
    ) {
        /** The exact box distance, computed from the golden numbers and nothing else. */
        fun signedDistance(x: Double, y: Double): Double {
            val qx = x - centreX
            val qy = y - centreY
            val c = kotlin.math.cos(rotation)
            val s = kotlin.math.sin(rotation)
            val lx = c * qx + s * qy
            val ly = -s * qx + c * qy
            val dx = abs(lx) - halfX
            val dy = abs(ly) - halfY
            return hypot(maxOf(dx, 0.0), maxOf(dy, 0.0)) + minOf(maxOf(dx, dy), 0.0)
        }

        /** The rect's four corners, for placing probes where a sign error actually shows. */
        fun corners(): List<Pair<Double, Double>> {
            val c = kotlin.math.cos(rotation)
            val s = kotlin.math.sin(rotation)
            return listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
                val lx = sx * halfX
                val ly = sy * halfY
                centreX + c * lx - s * ly to centreY + s * lx + c * ly
            }
        }
    }

    private fun readGolden(): Map<Pair<String, Double>, List<GoldenRect>> {
        val text = javaClass.getResource("/rectsl/track_characterization.txt")?.readText()
            ?: repoRoot().resolve("src/jvmTest/resources/rectsl/track_characterization.txt").readText()
        val out = LinkedHashMap<Pair<String, Double>, MutableList<GoldenRect>>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('\t')
            if (f.size < 12) continue
            val scaleX = f[8].toDouble()
            val scaleY = f[9].toDouble()
            out.getOrPut(f[0] to f[1].toDouble()) { mutableListOf() } += GoldenRect(
                id = f[2],
                centreX = f[3].toDouble(),
                centreY = f[4].toDouble(),
                halfX = abs(0.5 * f[5].toDouble() * scaleX),
                halfY = abs(0.5 * f[6].toDouble() * scaleY),
                rotation = f[7].toDouble(),
                alpha = f[10].toDouble()
            )
        }
        return out
    }

    /**
     * Probe points a sign or transform error can actually show up at: a lattice over the wall, every
     * rect's centre, and points just inside and just outside every corner of the first few rects.
     *
     * The corner offsets are the reason this can fail. A lattice alone lands almost nowhere near an
     * edge on a 183-rect scene, which is exactly the sampler that reports "0 disagreements" and means
     * nothing by it.
     */
    private fun probePoints(wall: WallSpec, rects: List<GoldenRect>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(256)
        for (i in 0..8) {
            for (j in 0..8) {
                out += (i / 8.0 - 0.5) * wall.width to (j / 8.0 - 0.5) * wall.height
            }
        }
        for (rect in rects.take(12)) {
            out += rect.centreX to rect.centreY
            for ((cx, cy) in rect.corners()) {
                val dx = cx - rect.centreX
                val dy = cy - rect.centreY
                out += cx to cy
                out += cx + 0.01 * dx to cy + 0.01 * dy
                out += cx - 0.01 * dx to cy - 0.01 * dy
            }
        }
        return out
    }

    private fun gallerySources(): List<Pair<String, String>> =
        Files.list(galleryDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".rectsl") }
                .map { it.fileName.toString().removeSuffix(".rectsl").removeSuffix("_rectsl_") to it.readText() }
                .toList()
        }.sortedBy { it.first }

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent ?: error("could not locate repo root")
        }
        return dir
    }
}
