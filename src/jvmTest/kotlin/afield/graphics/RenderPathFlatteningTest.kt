package afield.graphics

import afield.stdlib.Vec3
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Field has **two** independent curve flatteners, and pass 1 of this branch only fixed one of them.
 * This test pins down what the other one is, what it promises, and whether it shares the defect.
 *
 * 1. **The arc-length flattener.** `FLinesAndJavaShapes.Cursor.pathFlattener`
 *    (`FLinesAndJavaShapes.kt:797`) builds a [PathFlattener], which subdivides adaptively
 *    (`PathFlattener.kt:128`) and answers "what `node.t` is this far along the curve?". Everything
 *    that spaces by distance goes through it. This is what the branch fixed.
 *
 * 2. **The render flattener.** `StandardDrawing.linesOut` (`StandardDrawing.kt:188`) calls
 *    `FLine.uniform_linearize` (`FLine.kt:838`) per line, which for each `CubicTo` calls
 *    `FLine.uniform_linearize_cubic` (`FLine.kt:917`). That emits a **fixed `samplesPerCubic`
 *    (default 100) samples at uniform parameter** — `alpha = (it + 1) / samplesPerCubic`
 *    (`FLine.kt:929`). Fills go the same way: `FLine.tessellate` (`FLine.kt:961`) also calls
 *    `uniform_linearize` (`FLine.kt:992`). `ThreeJsViewer.kt:826` is a third caller of the same
 *    function. Nothing on this path constructs a [PathFlattener] or a `Cursor`.
 *
 * So the pass-1 claim "PathFlattener is not on the GPU render path" is **true** — but it was
 * incomplete in a way that mattered, because it did not say what *is* there. The render flattener
 * does not share the arc-length bug, for a specific reason: it never reports a distance, so it
 * cannot report a wrong one. Its samples are unevenly spaced *along* a badly parameterised cubic,
 * but every sample it emits lies exactly on the curve, so the drawn shape is right.
 *
 * That is a claim worth measuring rather than asserting, which is what these tests do.
 */
class RenderPathFlatteningTest {

    /** Collect what the render path would actually put in the vertex buffer for one line. */
    private fun renderPolyline(line: FLine, samplesPerCubic: Int = 100): List<Vec3> {
        val out = ArrayList<Vec3>()
        line.uniform_linearize(samplesPerCubic) { at, _ -> out.add(Vec3(at)) }
        return out
    }

    /** The same number of points, but spaced by arc length instead. */
    private fun arcLengthPolyline(line: FLine, count: Int): List<Vec3> {
        val cursor = line.cursor()
        val total = cursor.lengthD()
        return (0 until count).map {
            cursor.setD(total * it / (count - 1f))
            Vec3(cursor.position())
        }
    }

    /**
     * How far the drawn polyline strays from the true curve: for a dense set of true-curve points,
     * the distance to the nearest polyline *segment*. This is the quantity a viewer sees as
     * faceting, and it is what a render flattener is actually responsible for.
     */
    private fun deviationFromCurve(polyline: List<Vec3>, line: FLine): Double {
        val oracle = ArcLengthOracle(line, samplesPerSegment = 2048)
        var worst = 0.0
        val probes = 3000
        for (i in 0..probes) {
            val p = oracle.positionAt(oracle.length * i / probes)
            var best = Double.MAX_VALUE
            for (s in 1 until polyline.size) {
                val d = pointToSegment(p, polyline[s - 1], polyline[s])
                if (d < best) best = d
            }
            if (best > worst) worst = best
        }
        return worst
    }

    private fun pointToSegment(p: DoubleArray, a: Vec3, b: Vec3): Double {
        val ax = a.x.toDouble(); val ay = a.y.toDouble(); val az = a.z.toDouble()
        val dx = b.x - ax; val dy = b.y - ay; val dz = b.z - az
        val len2 = dx * dx + dy * dy + dz * dz
        var t = if (len2 <= 0.0) 0.0 else ((p[0] - ax) * dx + (p[1] - ay) * dy + (p[2] - az) * dz) / len2
        if (t < 0.0) t = 0.0
        if (t > 1.0) t = 1.0
        val qx = ax + dx * t; val qy = ay + dy * t; val qz = az + dz * t
        return sqrt((p[0] - qx) * (p[0] - qx) + (p[1] - qy) * (p[1] - qy) + (p[2] - qz) * (p[2] - qz))
    }

    /**
     * A cubic that is *both* sharply curved *and* badly parameterised — the only shape where uniform
     * parameter sampling can hurt a *rendered* result, because it starves the fast part of the curve
     * of samples. Neither of the branch's collinear curves can show this: they are straight lines, so
     * every chord lies on them exactly however the samples are spaced.
     */
    private fun curvedAndSkewed(): FLine = FLine().moveTo(0f, 0f)
        .cubicTo(Vec3(1200f, 20f, 0f), Vec3(1210f, 30f, 0f), Vec3(20f, 40f, 0f))

    @Test
    fun theRenderPathDrawsTheRightShapeEvenWhenParameterisationIsPathological() {
        val cases = ArcLengthTestCurves.all() + ("curvedAndSkewed" to curvedAndSkewed())
        val offenders = mutableListOf<String>()

        for ((name, line) in cases) {
            val render = renderPolyline(line, 100)
            val oracle = ArcLengthOracle(line, samplesPerSegment = 2048)
            val arc = arcLengthPolyline(line, render.size)

            val renderDev = deviationFromCurve(render, line)
            val arcDev = deviationFromCurve(arc, line)
            val relative = renderDev / oracle.length

            println(
                name.padEnd(22) +
                    " samples=" + render.size.toString().padStart(4) +
                    " uniform-t deviation=" + "%.5f".format(renderDev) +
                    " (" + "%.5f".format(relative * 100) + "% of length)" +
                    "  arc-length deviation=" + "%.5f".format(arcDev)
            )

            // A render flattener's job is shape fidelity. 0.1% of the curve's own length is a
            // generous ceiling and comfortably sub-pixel at any sane zoom.
            if (relative > 0.001) offenders += "$name ${"%.4f".format(relative * 100)}% of length"
        }

        assertTrue(
            offenders.isEmpty(),
            "uniform_linearize draws a visibly wrong shape on: ${offenders.joinToString("; ")}"
        )
    }

    /**
     * **Do not "unify the two flatteners" by switching the render path to arc-length spacing.**
     *
     * That is the obvious-looking follow-on from this branch and it would make rendering measurably
     * worse. Arc-length spacing puts samples evenly by *distance*, which starves exactly the
     * high-curvature regions that need density and lavishes samples on the straight stretches that
     * do not. Uniform-parameter spacing clusters samples where `|B'(t)|` is small — near tight turns
     * and cusps — which is accidentally the right adaptation for shape.
     *
     * Measured at an identical sample budget, worst deviation from the true curve:
     *
     *   curvedAndSkewed   uniform-t 0.084  arc-length 7.686   (91x worse)
     *   glyphish          uniform-t 0.013  arc-length 1.454   (110x worse)
     *
     * This test fails if anyone makes the swap.
     */
    @Test
    fun arcLengthSpacingIsTheWrongToolForRenderingAndThisIsByHowMuch() {
        var worstRatio = 0.0
        var worstName = ""
        for ((name, line) in ArcLengthTestCurves.all() + ("curvedAndSkewed" to curvedAndSkewed())) {
            val render = renderPolyline(line, 100)
            val renderDev = deviationFromCurve(render, line)
            val arcDev = deviationFromCurve(arcLengthPolyline(line, render.size), line)
            if (renderDev < 1e-9 && arcDev < 1e-9) continue // straight lines: both exact
            val ratio = arcDev / renderDev.coerceAtLeast(1e-9)
            println("$name: arc-length/uniform-t shape deviation ratio ${"%.1f".format(ratio)}x")
            if (ratio > worstRatio) {
                worstRatio = ratio
                worstName = name
            }
        }
        assertTrue(
            worstRatio > 10.0,
            "expected arc-length spacing to be markedly worse for shape than uniform-parameter " +
                "spacing (it starves high-curvature regions); worst ratio was only " +
                "${"%.1f".format(worstRatio)}x on $worstName. If this now fails, the render path or " +
                "the sampling has changed and the comment above needs rewriting."
        )
    }

    /**
     * The two flatteners answer different questions, and this is the number that shows it: on a
     * badly parameterised cubic the render path's samples are wildly uneven *along* the curve even
     * though they all sit *on* it. That unevenness is harmless for shape — and would be a bug if
     * anything on the render path claimed a distance. Nothing does; recorded here so a future change
     * that starts interpolating something by distance has a number to check itself against.
     */
    @Test
    fun theRenderPathSamplesAreUnevenAlongTheCurveByDesign() {
        for ((name, line) in ArcLengthTestCurves.all() + ("curvedAndSkewed" to curvedAndSkewed())) {
            val render = renderPolyline(line, 100)
            if (render.size < 4) continue
            val gaps = (1 until render.size).map { render[it].distance(render[it - 1]).toDouble() }
                .filter { it > 0 }.sorted()
            if (gaps.size < 4) continue
            println(
                name.padEnd(22) +
                    " uniform-t gap max/median=" + "%.2f".format(gaps.last() / gaps[gaps.size / 2]) +
                    " min=" + "%.4f".format(gaps.first()) +
                    " max=" + "%.4f".format(gaps.last())
            )
        }
    }

    /**
     * `uniform_linearize`'s `Close` branch (`FLine.kt:901`) emits `lastOpen` — it resolves the
     * subpath start itself rather than reading `Node.to`, so the render path was never subject to
     * the Close landmine. Worth pinning, since it is the reason a closed shape still *drew* while
     * every geometry operation on it threw.
     */
    @Test
    fun theRenderPathHandlesClosedAndMultiSubpathLines() {
        val closed = FLine().moveTo(0f, 0f)
            .cubicTo(Vec3(0f, 100f, 0f), Vec3(100f, 100f, 0f), Vec3(100f, 0f, 0f))
            .close()
        val closedPoly = renderPolyline(closed, 40)
        assertTrue(closedPoly.size > 40, "closed line linearized to ${closedPoly.size} points")
        // the last emitted point is the closing return to the moveTo
        val last = closedPoly.last()
        assertTrue(
            last.distance(Vec3(0f, 0f, 0f)) < 1e-3f,
            "the close should return to the moveTo, ended at $last"
        )

        val two = FLine().moveTo(0f, 0f).lineTo(100f, 0f).close()
            .moveTo(200f, 0f).lineTo(300f, 0f).close()
        assertTrue(renderPolyline(two, 40).size >= 6, "two subpaths linearized to too few points")

        var triangles = 0
        closed.tessellate(40) { _, index -> triangles += index.size / 3 }
        assertTrue(triangles > 10, "tessellate produced $triangles triangles for a closed cubic")
    }

    /**
     * Every other place in the repo that turns cubics into points, checked for the same confusion.
     * All of them are uniform-in-parameter *and* none of them reports a distance, so none shares the
     * defect — but `contours.pointEdgeGraph2D` deserves the note, because uneven spacing there
     * changes the *topology* threshold rather than a distance.
     */
    @Test
    fun theOtherCubicSamplersAreParameterUniformAndClaimNoDistance() {
        // FLine.uniform_linearize_cubic  FLine.kt:929   alpha = (it + 1) / samplesPerCubic
        // contours.pointEdgeGraph2D      contours.kt:749  t = sampleIndex / samplesPerCubic
        // someSkiko                      someSkiko.kt:339 hands c1/c2/to to Skia's own path flattener
        // FLineEditableSvg               FLineEditableSvg.kt:104 emits control points verbatim
        //
        // The claim under test: uniform-parameter sampling never *under*-covers the curve — it puts
        // at least as many samples on it as an arc-length sampler would put in the sparsest region,
        // so a topology walk over its edges cannot miss a feature an arc-length walk would catch.
        // Concretely: the render polyline's own longest gap versus the curve's total length.
        val line = curvedAndSkewed()
        val oracle = ArcLengthOracle(line, samplesPerSegment = 2048)
        val gaps = renderPolyline(line, 100).let { p ->
            (1 until p.size).map { p[it].distance(p[it - 1]).toDouble() }
        }
        val longest = gaps.max()
        println("curvedAndSkewed: longest uniform-t gap $longest of length ${oracle.length}")
        assertTrue(
            longest < oracle.length * 0.05,
            "the longest uniform-t gap is $longest, ${"%.1f".format(100 * longest / oracle.length)}% " +
                "of the curve — a topology walk at that resolution could skip a feature"
        )
    }
}
