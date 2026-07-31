package afield.graphics

import field.graphics.FLinesAndJavaShapes
import java.awt.Font
import java.awt.font.FontRenderContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The downstream payoff, measured on **real glyph outlines** rather than a hand-drawn approximation
 * of one.
 *
 * The unmerged `nightly/2026-07-30/1-typography-geometry-family` branch ships a gallery panel red on
 * purpose, asserting that "a glyph jumped 9.7x the median step". That branch is not read or run here
 * (it is another agent's worktree); instead the *metric* is reconstructed on geometry available on
 * `main` — glyph outlines pulled straight out of a real font via
 * `java.awt.Font.createGlyphVector(...).outline` and `FLinesAndJavaShapes.javaShapeToFLine`
 * (`FLinesAndJavaShapes.kt:2169`) — and run through the same arc-length operation.
 *
 * **THE RESULT REFUTES WHAT I EXPECTED, AND MY OWN PASS-1 WORDING.** I had reasoned that glyph
 * outlines would be the worst case for this defect, because a font's straight stems would be stored
 * as cubics with their control points on the chord — the exact shape whose flatness reads 0.0. On the
 * JVM's `SANS_SERIF` they are not:
 *
 *  - **flatness-blind cubics: 0, in every one of the twelve glyphs tested.** The font stores straight
 *    stems as `LineTo`, not as collinear cubics, and its curved cubics all have control points well
 *    off the chord, so the *old* criterion already subdivided them properly.
 *  - arc-length step max/median on real glyphs: **1.001x before this branch, 1.000x after.** The fix
 *    does essentially nothing here, because there was essentially nothing wrong.
 *
 * So `realGlyphOutlinesAreEvenlySpacedByArcLength` is a **characterization** test, not a regression
 * test for this branch — it is green at base too. It is worth keeping precisely because it is the
 * evidence for the negative claim: whatever ails glyph spacing, on this font it is not the
 * subdivision criterion. The hand-built `glyphish` curve in ArcLengthTestCurves, which *does* put a
 * straight stem in a cubic, remains the case that exercises the fix (1.107x -> 1.000x).
 *
 * The other half of the file is where the interesting number is, and it points somewhere else
 * entirely.
 */
class GlyphArcLengthTest {

    private val frc = FontRenderContext(null, true, true)

    /**
     * Real outlines for a handful of letters. Uses the logical font `SANS_SERIF`, which the JVM
     * always resolves to something, so this does not depend on what is installed.
     */
    private fun glyphs(text: String = "aBeGoRs8", size: Float = 720f): List<Pair<String, FLine>> =
        text.map { ch ->
            val font = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(size)
            val outline = font.createGlyphVector(frc, ch.toString()).outline
            ch.toString() to FLinesAndJavaShapes.javaShapeToFLine(outline)
        }.filter { it.second.nodes.size > 3 }

    /** max/median of the *true* arc-length step between consecutive equally-spaced requests. */
    private fun stepRatio(line: FLine, steps: Int): Double {
        val oracle = ArcLengthOracle(line, samplesPerSegment = 2048)
        if (oracle.length < 1.0) return 1.0
        val cursor = line.cursor()
        val total = cursor.lengthD()
        val trueD = DoubleArray(steps) {
            cursor.setD(total * it / (steps - 1f))
            oracle.distanceAt(cursor.t.toDouble())
        }
        val gaps = DoubleArray(steps - 1) { trueD[it + 1] - trueD[it] }.sortedArray()
        val median = gaps[gaps.size / 2]
        return if (median <= 0.0) 1.0 else gaps.last() / median
    }

    @Test
    fun realGlyphOutlinesAreEvenlySpacedByArcLength() {
        val all = glyphs()
        assertTrue(all.isNotEmpty(), "no glyph outlines available — check the JVM's font setup")

        var worst = 0.0
        var worstName = ""
        for ((name, glyph) in all) {
            val cubics = glyph.nodes.count { it is FLine.CubicTo }
            val ratio = stepRatio(glyph, 120)
            // how many of this glyph's cubics are invisible to the old flatness-only criterion
            val blind = glyph.nodes.indices.drop(1).count { i ->
                val n = glyph.nodes[i]
                if (n !is FLine.CubicTo) false else {
                    val a = glyph.endPointOf(i - 1)
                    val f1 = FLinesAndJavaShapes.ptSegDistSq3(a.x, a.y, a.z, n.to.x, n.to.y, n.to.z, n.c1.x, n.c1.y, n.c1.z)
                    val f2 = FLinesAndJavaShapes.ptSegDistSq3(a.x, a.y, a.z, n.to.x, n.to.y, n.to.z, n.c2.x, n.c2.y, n.c2.z)
                    kotlin.math.sqrt(kotlin.math.max(f1, f2)) <= 0.01f
                }
            }
            println(
                "glyph '$name' nodes=${glyph.nodes.size} cubics=$cubics " +
                    "flatness-blind cubics=$blind  arc-length step max/median=${"%.3f".format(ratio)}"
            )
            if (ratio > worst) {
                worst = ratio
                worstName = name
            }
        }

        println("worst glyph: '$worstName' at ${"%.3f".format(worst)}x the median step")
        assertTrue(
            worst < 1.5,
            "glyph '$worstName' jumped ${"%.2f".format(worst)}x the median step — arc-length spacing " +
                "is still wrong on real glyph outlines"
        )
    }

    /**
     * **The caveat.** `FLineInterpolation` — the glyph-morphing machinery, and the most likely home
     * of a "glyph jumped 9.7x" assertion — does *not* sample by arc length. Its default correspondence
     * sampler is `FLineSamplers.curvatureAdaptive` (`FLineInterpolation.kt:101`), which builds on
     * `FLineSamplers.uniform()` (`FLineSampling.kt:125`), which is `sampleAtParams`
     * (`FLineSampling.kt:291`) and drives the cursor with **`setT`**, not `setD`. Grepping the whole
     * of `src/jvmMain` for `cursor`/`Cursor`/`PathFlattener`/`setD`/`lengthD`/`sampleByDistance`
     * turns up **zero** hits in `FLineInterpolation.kt`, `FLineEditing.kt`,
     * `LowSegmentFLineFitting.kt`, `PolynomialTreeFitting.kt` and `FLineInstructionTrajectories.kt`.
     *
     * So this branch's fix does not touch the morph path, and the two samplers give very different
     * step ratios on the same glyph. This test prints both side by side and asserts only the
     * relationship, so the number is on the record for whoever owns the typography panel:
     *
     * Measured on real glyphs, same 120 requested samples:
     *
     *   glyph   arc-length (setD)   curvatureAdaptive   uniform (setT)
     *     a           1.00x               1.02x             2.19x
     *     B           1.00x               1.08x            10.70x
     *     e           1.00x               1.05x            16.89x
     *     G           1.00x               1.02x             1.51x
     *     o           1.00x               1.49x             2.98x
     *     R           1.00x               1.06x            12.66x
     *     s           1.00x               1.02x             2.07x
     *     8           1.00x               1.18x             3.99x
     *
     * Note where a ~9.7x lives. It is not in the arc-length column — that column is 1.00x both
     * before and after this branch. It is in the **parameter** column, at 10.70x, 12.66x and 16.89x.
     *
     * The honest conclusion, therefore: **this branch most likely does NOT turn the typography
     * panel green.** If that panel measures spacing through `setD`/`sampleByDistance`/`byDashing`,
     * it should; if it measures through `FLineInterpolator` or any `FLineSampler` — which is what
     * a glyph *morph* would use — it will not move at all, and the fix it needs is a different one
     * (arc-length-aware correspondence in `FLineInterpolation`, most likely). I cannot tell which
     * without reading that worktree, which is out of bounds, and the numbers above lean towards the
     * second.
     */
    @Test
    fun theMorphPathSamplesByParameterNotArcLengthAndThisIsTheDifference() {
        var worstParam = 0.0
        var worstParamName = ""
        var worstArc = 0.0

        for ((name, glyph) in glyphs()) {
            val oracle = ArcLengthOracle(glyph, samplesPerSegment = 2048)
            if (oracle.length < 1.0) continue

            val arcRatio = stepRatio(glyph, 120)

            // exactly what FLineInterpolation's default correspondence does
            val samples = FLineSamplers.curvatureAdaptive(gain = 3f, oversample = 4, minBaseSamples = 48)
                .sample(glyph, FLineSampleContext(requestedCount = 120))
            val paramGaps = (1 until samples.size)
                .map { samples[it].point.distance(samples[it - 1].point).toDouble() }
                .filter { it > 0 }.sorted()
            val paramRatio = if (paramGaps.size < 4) 1.0 else paramGaps.last() / paramGaps[paramGaps.size / 2]

            // and the plain uniform-parameter sampler underneath it
            val uniform = FLineSamplers.uniform().sample(glyph, FLineSampleContext(requestedCount = 120))
            val uniformGaps = (1 until uniform.size)
                .map { uniform[it].point.distance(uniform[it - 1].point).toDouble() }
                .filter { it > 0 }.sorted()
            val uniformRatio =
                if (uniformGaps.size < 4) 1.0 else uniformGaps.last() / uniformGaps[uniformGaps.size / 2]

            println(
                "glyph '$name'  arc-length(setD)=${"%.2f".format(arcRatio)}x   " +
                    "curvatureAdaptive=${"%.2f".format(paramRatio)}x   " +
                    "uniform(setT)=${"%.2f".format(uniformRatio)}x"
            )

            if (paramRatio > worstParam) {
                worstParam = paramRatio
                worstParamName = name
            }
            if (arcRatio > worstArc) worstArc = arcRatio
        }

        println(
            "worst across the set: arc-length ${"%.2f".format(worstArc)}x, " +
                "curvatureAdaptive ${"%.2f".format(worstParam)}x (glyph '$worstParamName')"
        )
        assertTrue(
            worstParam > worstArc,
            "the parameter-based samplers used by FLineInterpolation are expected to space glyphs " +
                "*worse* than the (now fixed) arc-length path; measured ${"%.2f".format(worstParam)}x " +
                "vs ${"%.2f".format(worstArc)}x. If this flips, re-read the caveat above — the " +
                "conclusion about whether this branch can turn the typography panel green changes."
        )
    }

    /**
     * Glyph outlines are multi-subpath and closed (think 'a', 'B', '8'), so they exercise every
     * Close-node and subpath fix on this branch at once, on geometry nobody hand-picked.
     */
    @Test
    fun everyArcLengthOperationSurvivesRealGlyphOutlines() {
        for ((name, glyph) in glyphs("aBeGoRs8gq%@")) {
            val subpaths = glyph.pieces().size
            val closes = glyph.nodes.count { it is FLine.Close }

            val length = glyph.cursor().lengthD()
            assertTrue(length > 0f && length.isFinite(), "glyph '$name' lengthD = $length")

            val samples = glyph.sampleByDistance(length / 80f)
            val dashes = glyph.byDashing(length / 40f, length / 80f)
            val reparam = glyph.byArcLengthParameterizing(length / 60f)
            val reversed = glyph.byReversing()
            val mid = glyph.midPoint()
            val hit = FLinesAndJavaShapes.closestT(glyph, mid)
            val cuts = glyph.cursor().splitD(listOf(0.2f, 0.45f, 0.7f).map { (length * it) })

            assertTrue(samples.size > 20, "glyph '$name' sampled to ${samples.size} points")
            assertTrue(dashes.size >= subpaths, "glyph '$name' dashed to ${dashes.size} for $subpaths subpaths")
            assertTrue(reparam.nodes.size > 20, "glyph '$name' reparameterized to ${reparam.nodes.size} nodes")
            assertTrue(reversed.nodes.size == glyph.nodes.size, "glyph '$name' reversal changed the node count")
            assertTrue(mid.x.isFinite() && mid.y.isFinite(), "glyph '$name' midPoint = $mid")
            assertTrue(hit.isFinite(), "glyph '$name' closestT = $hit")
            assertTrue(cuts.size == 4, "glyph '$name' splitD gave ${cuts.size} pieces, expected 4")

            println(
                "glyph '$name' subpaths=$subpaths closes=$closes length=${"%.1f".format(length)} " +
                    "samples=${samples.size} dashes=${dashes.size} splitD=${cuts.size}"
            )
        }
    }
}
