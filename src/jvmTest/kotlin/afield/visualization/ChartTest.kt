package afield

import afield.graphics.FLine
import afield.stdlib.hoverColor
import afield.stdlib.label
import afield.stdlib.onClick
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Chart] is the one plot renderer, so what matters here is not the geometry but the five things a
 * caller depends on: ticks land on round numbers and *print* as round numbers, the data→pixel
 * mapping is right way up and evenly spaced, a log scale refuses a domain it cannot represent, a
 * named series that does not exist fails loudly instead of silently doing nothing, and a
 * [Chart.expect] claim shows up in all three places it promises to — the colour, the footnote, and
 * [Chart.deviations].
 *
 * The pixels themselves are `ChartGallery`'s job (`visualization.chart-gallery`), which renders every
 * mark kind through Skia and asserts ink floors and a counterfactual. This file is the arithmetic.
 */
class ChartTest {

    // ── ticks ─────────────────────────────────────────────────────────────────────

    @Test
    fun `nice ticks are round numbers, and print as round numbers`() {
        assertEquals(listOf(0.0, 0.2, 0.4, 0.6, 0.8, 1.0), niceTicks(0.0, 1.0, 6))
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0), niceTicks(0.0, 47.0, 5))
        assertEquals(listOf(-3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0), niceTicks(-3.0, 3.0, 6))
        // The one that catches the bug: `k * 0.2` alone gives 0.6000000000000001, and a tick label
        // reading "0.6000000000000001" is a formatting failure forever after.
        assertEquals("0.6", tickLabel(niceTicks(0.0, 1.0, 6)[3], 0.2))
        assertTrue(niceTicks(0.0, 1.0, 6).none { it.toString().length > 3 }, "ticks must be snapped")
    }

    @Test
    fun `a degenerate or infinite range does not throw and does not loop`() {
        assertEquals(listOf(5.0), niceTicks(5.0, 5.0, 6), "constant data still wants one tick")
        assertEquals(emptyList<Double>(), niceTicks(Double.NaN, 1.0, 6))
        assertEquals(emptyList<Double>(), niceTicks(0.0, Double.POSITIVE_INFINITY, 6))
        assertEquals(emptyList<Double>(), logTicks(0.0, 10.0), "a log axis cannot start at zero")
        assertEquals(emptyList<Double>(), logTicks(-1.0, 10.0))
    }

    @Test
    fun `log ticks are decades, with 1-2-5 only while they fit`() {
        assertEquals(listOf(1.0, 10.0, 100.0, 1000.0, 10000.0, 100000.0, 1000000.0), logTicks(1.0, 1e6))
        // Two decades or fewer get the 1/2/5 mantissas, because a plot of one decade with two ticks
        // on it is an axis with no scale.
        assertEquals(listOf(1.0, 2.0, 5.0, 10.0), logTicks(1.0, 10.0))
    }

    @Test
    fun `one axis uses one notation`() {
        // The mixed-notation bug: 100000 beside 1.0e+06 reads as two different quantities.
        val labels = tickLabels(logTicks(10.0, 1e6))
        assertTrue(labels.all { "e+" in it }, "expected one notation for the whole axis, got $labels")
        assertTrue(tickLabels(listOf(0.0, 1.0, 2.0)).none { "e" in it }, "small numbers stay plain")
    }

    // ── scales ────────────────────────────────────────────────────────────────────

    @Test
    fun `a scale maps its ends to its ends, and inverts`() {
        val s = Scale(0.0, 10.0, 100.0, 500.0)
        assertEquals(100.0, s.toPixel(0.0), 1e-9)
        assertEquals(500.0, s.toPixel(10.0), 1e-9)
        assertEquals(300.0, s.toPixel(5.0), 1e-9)
        assertEquals(5.0, s.toData(300.0), 1e-9)
    }

    @Test
    fun `a y scale is built inverted, because y grows down`() {
        // plotBottom -> lo, plotTop -> hi. The classic silent flip is getting this backwards, so it
        // is stated as a test rather than left as a comment.
        val y = Scale(0.0, 1.0, 300.0, 20.0)
        assertTrue(y.toPixel(1.0) < y.toPixel(0.0), "the larger value must be higher on screen")
        assertEquals(160.0, y.toPixel(0.5), 1e-9)
    }

    @Test
    fun `a log scale refuses a domain it cannot represent, and NaNs a point it cannot`() {
        assertFailsWith<IllegalArgumentException> { Scale(0.0, 100.0, 0.0, 1.0, ScaleKind.LOG) }
        assertFailsWith<IllegalArgumentException> { Scale(-1.0, 100.0, 0.0, 1.0, ScaleKind.LOG) }
        val s = Scale(1.0, 100.0, 0.0, 200.0, ScaleKind.LOG)
        assertEquals(0.0, s.toPixel(1.0), 1e-9)
        assertEquals(100.0, s.toPixel(10.0), 1e-9, "a decade is half the span of two decades")
        assertEquals(200.0, s.toPixel(100.0), 1e-9)
        // Clamping a non-positive datum onto a log axis would draw a lie; NaN is dropped instead.
        assertTrue(s.toPixel(0.0).isNaN(), "zero has no place on a log axis")
        assertTrue(s.toPixel(-5.0).isNaN())
    }

    // ── data to pixels ────────────────────────────────────────────────────────────

    @Test
    fun `a rising ramp goes up the screen, evenly, inside the frame`() {
        val chart = Chart().also { it.width = 400.0; it.height = 300.0 }
            .line("ramp", (0..10).map { it }, (0..10).map { it })
        chart.xDomain = 0.0 to 10.0
        chart.yDomain = 0.0 to 10.0

        val ramp = chart.toFLines().first { it.label == "ramp" }
        val points = ramp.nodes.mapNotNull { it.toOrNull }
        assertEquals(11, points.size, "one node per datum")
        assertTrue(points.last().x > points.first().x, "x must grow to the right")
        assertTrue(points.last().y < points.first().y, "a rising ramp must go up, not down")

        val gaps = points.zipWithNext { a, b -> (b.x - a.x).toDouble() }
        assertTrue(
            gaps.maxOrNull()!! - gaps.minOrNull()!! < 1e-3,
            "equal steps must be equally spaced, spread was ${gaps.maxOrNull()!! - gaps.minOrNull()!!}"
        )

        val plot = chart.plotRect()
        assertTrue(
            points.all { it.x >= plot.x - 0.5 && it.x <= plot.x + plot.w + 0.5 },
            "the data must stay inside the plot frame"
        )
        assertEquals(plot.x, points.first().x.toDouble(), 0.5, "the first datum sits on the left edge")
        assertEquals(plot.y, points.last().y.toDouble(), 0.5, "the top datum sits on the top edge")
    }

    @Test
    fun `an artefact is not empty, and it moves when the data does`() {
        // A blank figure passes every "they differ" check that forgot to say by how much, so both
        // halves are asserted: non-trivial size, and a floored difference.
        fun draw(scale: Double): List<FLine> =
            Chart(title = "t").line("s", (0..40).map { it }, (0..40).map { it * scale }).toFLines()

        val a = draw(1.0)
        val b = draw(0.25)
        assertTrue(a.size > 20, "a labelled chart is many lines, got ${a.size}")
        assertTrue(a.sumOf { it.nodes.size } > 200, "a labelled chart is many nodes")

        val ya = a.first { it.label == "s" }.nodes.mapNotNull { it.toOrNull }.map { it.y }
        val yb = b.first { it.label == "s" }.nodes.mapNotNull { it.toOrNull }.map { it.y }
        assertEquals(ya.size, yb.size)
        // Both are autoscaled, so quartering the slope must *not* move the curve: that is the point
        // of autoscaling, and it is what makes the next assertion the interesting one.
        val movedAuto = ya.indices.count { abs(ya[it] - yb[it]) > 0.5 }
        assertEquals(0, movedAuto, "autoscaling should place a scaled ramp identically")

        val fixed = { s: Double ->
            Chart(title = "t").also { it.yDomain = 0.0 to 40.0 }
                .line("s", (0..40).map { it }, (0..40).map { it * s })
                .toFLines().first { it.label == "s" }.nodes.mapNotNull { it.toOrNull }.map { it.y }
        }
        val fa = fixed(1.0)
        val fb = fixed(0.25)
        val moved = fa.indices.count { abs(fa[it] - fb[it]) > 0.5 }
        assertTrue(moved >= 30, "on a fixed domain, 30 of 41 nodes must move; only $moved did")
    }

    // ── staying inside the frame ──────────────────────────────────────────────────
    //
    // `ChartGallery` takes these to rendered pixels and to a panel's fit; this half is the
    // arithmetic, so a failure names a coordinate rather than a percentage of ink. Each one carries
    // its counterfactual, because a clip that stopped working would otherwise leave a test that
    // passes on input that has quietly become harmless.

    /** The union of everything a chart draws, as `(x0, y0, x1, y1)` in figure pixels. */
    private fun drawnBox(chart: Chart): DoubleArray {
        val points = chart.toFLines().flatMap { line -> line.nodes.mapNotNull { it.toOrNull } }
        return doubleArrayOf(
            points.minOf { it.x.toDouble() }, points.minOf { it.y.toDouble() },
            points.maxOf { it.x.toDouble() }, points.maxOf { it.y.toDouble() },
        )
    }

    @Test
    fun `a line outside an explicit domain is clipped to the plot, and would not have been`() {
        // The tetgen corner, reduced: a 2 x 3 outline charted into a 0.24 x 0.24 window on one of
        // its corners. Unclipped, this reached 5137 px outside a 677 x 346 plot.
        fun corner(clip: Boolean): Chart =
            Chart(title = "corner", xLabel = "x", yLabel = "y")
                .line("outline", listOf(-1.0, 1.0, 1.0, -1.0, -1.0), listOf(-1.5, -1.5, 1.5, 1.5, -1.5))
                .also {
                    it.width = 780.0; it.height = 440.0
                    it.xDomain = 0.82 to 1.06
                    it.yDomain = 1.32 to 1.56
                    it.clip = clip
                }

        val clipped = corner(clip = true)
        val plot = clipped.plotRect()
        val drawn = clipped.toFLines().first { it.label == "outline" }.nodes.mapNotNull { it.toOrNull }
        assertTrue(drawn.isNotEmpty(), "the visible part of the outline must still be drawn")
        drawn.forEach {
            assertTrue(
                it.x >= plot.x - 0.5 && it.x <= plot.x + plot.w + 0.5 &&
                    it.y >= plot.y - 0.5 && it.y <= plot.y + plot.h + 0.5,
                "a clipped series must stay inside plotRect; ${it.x},${it.y} is not in $plot"
            )
        }
        // One FLine and not one per surviving run: a clipped series is still one series and one
        // legend entry. The corner clips to an L, so what survives is more than a stub.
        assertEquals(1, clipped.toFLines().count { it.label == "outline" }, "one series, one line")
        assertTrue(drawn.size >= 3, "the corner clips to an L, so at least 3 nodes survive")

        // The counterfactual: the same numbers with the clip off must land far outside, or this test
        // is asserting a property of its input rather than of the code.
        val loose = corner(clip = false).toFLines()
            .first { it.label == "outline" }.nodes.mapNotNull { it.toOrNull }
        val excursion = loose.maxOf { p ->
            val (px, py) = p.x.toDouble() to p.y.toDouble()
            maxOf(plot.x - px, px - (plot.x + plot.w), plot.y - py, py - (plot.y + plot.h), 0.0)
        }
        assertTrue(excursion > 2000.0, "unclipped, this data must reach far outside; it reached $excursion px")
    }

    @Test
    fun `a long note wraps instead of dragging the figure's bounds`() {
        // Verbatim the note the tetgen corner figure shipped with at 9f7ffc4f2, before it was
        // shortened by hand at 281f3948f to work around exactly this.
        val note = "a 0.24 x 0.24 window on a body 2 x 3 across. The true corner is at " +
            "(1.0, 1.5); every level set cuts it off, and halving h halves what is left."
        fun figure(clip: Boolean) =
            Chart(title = "a long note", xLabel = "x", yLabel = "y", note = note)
                .line("ramp", (0..10).map { it }, (0..10).map { it })
                .also { it.width = 780.0; it.height = 440.0; it.clip = clip }

        val wrapped = drawnBox(figure(clip = true))
        assertTrue(
            wrapped[0] >= -4.0 && wrapped[1] >= -4.0 && wrapped[2] <= 784.0 && wrapped[3] <= 444.0,
            "a wrapped figure must fit its own 780x440 frame; it drew ${wrapped.toList()}"
        )
        // The counterfactual, and the number that matters: unwrapped it was 1240 px wide, so a panel
        // fitting those bounds drew a 780 px figure at 63% of its declared size.
        val unwrapped = drawnBox(figure(clip = false))
        val looseWidth = unwrapped[2] - unwrapped[0]
        assertTrue(looseWidth > 1200.0, "unwrapped, this note must overhang badly; it was $looseWidth px wide")
        assertTrue(780.0 / looseWidth < 0.70, "unwrapped, the figure must shrink below 70%; it was ${780.0 / looseWidth}")
    }

    @Test
    fun `a log x axis drops a datum it cannot represent instead of moving the axis`() {
        // `resolvedYDomain` has always dropped a non-positive datum on a log axis; `resolvedXDomain`
        // did not, so one zero sent `padDegenerate` down its `a = b / 100` path and gave 10..1000 —
        // and 0.001 and 0.1 were then drawn off the left of the frame with no explicit domain in
        // sight. Measured before the fix: the smallest datum sat 1645 px left of the axis.
        val chart = Chart(xLabel = "n").line("t", listOf(0.0, 0.001, 0.1, 10.0, 1000.0), listOf(1, 2, 3, 4, 5))
        chart.xKind = ScaleKind.LOG
        val (lo, hi) = chart.resolvedXDomain()
        assertEquals(0.001, lo, 1e-12, "the smallest representable datum must be on the axis")
        assertEquals(1000.0, hi, 1e-9)
        // y already did this, and the symmetry is the whole point of the change.
        val onY = Chart().line("t", listOf(1, 2, 3), listOf(0.0, 0.5, 50.0)).also { it.yKind = ScaleKind.LOG }
        assertEquals(0.5, onY.resolvedYDomain().first, 1e-12, "y has always dropped the zero")
    }

    @Test
    fun `a bar and a rule off the axis are dropped, not flattened onto the frame`() {
        // Both bars run 0..1 and 0..2, entirely below a 10..20 window. Before `clipSpan`, `coerceIn`
        // collapsed each to the frame line and `max(1.0, ...)` drew a one-pixel smear along it.
        val bars = Chart().bars("cost", listOf("a", "b"), listOf(1, 2))
        bars.yDomain = 10.0 to 20.0
        assertEquals(
            0, bars.toFLines().count { it.label?.startsWith("cost") == true },
            "a bar with no part inside the window must not be drawn at all"
        )
        // And the counterfactual: inside the window they draw.
        val inside = Chart().bars("cost", listOf("a", "b"), listOf(1, 2))
        inside.yDomain = 0.0 to 4.0
        assertEquals(2, inside.toFLines().count { it.label?.startsWith("cost") == true }, "both bars draw")

        // A rule off the axis has no honest position, so it is not drawn.
        val boxed = Chart().line("s", listOf(1, 2, 3), listOf(1, 2, 3)).rule(99.0, "way up there")
        boxed.yDomain = 0.0 to 4.0
        assertEquals(0, boxed.toFLines().count { it.label == "way up there" }, "an off-axis rule is dropped")
        // With no explicit domain the same rule is inside the derived domain, and draws.
        val free = Chart().line("s", listOf(1, 2, 3), listOf(1, 2, 3)).rule(99.0, "way up there")
        assertEquals(1, free.toFLines().count { it.label == "way up there" }, "an in-domain rule draws")
    }

    // ── loudness ──────────────────────────────────────────────────────────────────

    @Test
    fun `a series name that does not exist throws, listing the real ones`() {
        val chart = Chart().line("residual", listOf(1, 2, 3))
        val thrown = assertFailsWith<IllegalArgumentException> {
            chart.expect(max = 1.0, series = "resdiual")
        }
        assertTrue("residual" in thrown.message!!, "the message must list the real names")
    }

    @Test
    fun `a second bars mark cannot relabel the x axis`() {
        val chart = Chart().bars("a", listOf("x", "y"), listOf(1, 2))
        assertFailsWith<IllegalArgumentException> { chart.bars("b", listOf("p", "q"), listOf(3, 4)) }
        // The same categories are fine: two series over one set of slots.
        chart.bars("b", listOf("x", "y"), listOf(3, 4))
        assertEquals(2, chart.marks.size)
    }

    @Test
    fun `expect refuses a claim with nothing in it`() {
        assertFailsWith<IllegalArgumentException> { Chart().line("s", listOf(1)).expect() }
        assertFailsWith<IllegalArgumentException> {
            Chart().line("s", listOf(1)).expect(min = 5.0, max = 1.0)
        }
    }

    // ── claims ────────────────────────────────────────────────────────────────────

    @Test
    fun `an expectation colours, counts and reports the same points`() {
        val chart = Chart(note = "Adam")
            .line("r", listOf(0, 1, 2, 3), listOf(0.5, 2.0, 0.25, 3.0))
            .expect(max = 1.0, because = "converged")

        val deviations = chart.deviations
        assertEquals(listOf(1, 3), deviations.map { it.index })
        assertEquals(listOf(2.0, 3.0), deviations.map { it.y })
        assertEquals("converged", deviations.first().because)
        assertTrue("2 points outside expectation" in chart.toText(), chart.toText())
        assertTrue("Adam" in chart.toText(), "the caller's own note must survive")
    }

    @Test
    fun `one datum drawn by two marks is one deviation`() {
        // A line plus points over the same numbers is the ordinary way to draw a clickable trend,
        // and it used to report every failure twice.
        val chart = Chart()
            .line("r", listOf(0, 1), listOf(0.5, 9.0))
            .points("r", listOf(0, 1), listOf(0.5, 9.0))
            .expect(max = 1.0)
        assertEquals(1, chart.deviations.size, "two marks, one datum, one deviation")
    }

    @Test
    fun `a predicate expectation runs on x as well as y`() {
        val chart = Chart()
            .points("p", listOf(0, 1, 2), listOf(5, 5, 5))
            .expect(because = "only the left half") { x, _ -> x < 1.5 }
        assertEquals(listOf(2), chart.deviations.map { it.index })
    }

    @Test
    fun `a drawn claim stays in frame even when every point is inside it`() {
        // The band is part of the figure's argument. A y domain taken only from the data would crop
        // the claim away exactly when it is being satisfied.
        val chart = Chart().line("r", listOf(0, 1), listOf(0.1, 0.2)).expect(max = 5.0)
        val (_, hi) = chart.resolvedYDomain()
        assertTrue(hi >= 5.0, "the expectation's edge must be visible, domain topped out at $hi")
        assertTrue(chart.deviations.isEmpty())
    }

    // ── interaction ───────────────────────────────────────────────────────────────

    @Test
    fun `point and bar marks carry the interaction the SVG renderer looks for`() {
        val chart = Chart().bars("cost", listOf("a", "b", "c"), listOf(1, 2, 3))
        val interactive: List<FLine> = chart.toFLines().filter { it.onClick != null }
        assertEquals(3, interactive.size, "one live bar per category")
        assertTrue(interactive.all { it.hoverColor != null }, "every bar must hover")
        assertTrue(interactive.mapNotNull { it.label }.any { "cost: b" in it }, "labels name the datum")

        // pickable = false is the escape hatch, and it must take the whole figure off the
        // interactive path — one stray callback is enough to switch the renderer.
        val quiet = Chart().also { it.pickable = false }
            .bars("cost", listOf("a", "b", "c"), listOf(1, 2, 3))
        assertTrue(quiet.toFLines().none { it.onClick != null || it.hoverColor != null })
    }

    // ── distributions ─────────────────────────────────────────────────────────────



    // ── small multiples ───────────────────────────────────────────────────────────


    // ── DataFrame ─────────────────────────────────────────────────────────────────

    @Test
    fun `a frame with a categorical column charts as bars over its own labels`() {
        val people = dataFrameOf("name", "age")("Alice", 15, "Bob", 30, "Cara", 45)
        val chart = people.chart()
        assertEquals(listOf("Alice", "Bob", "Cara"), chart.xCategories)
        assertEquals(1, chart.marks.size)
        assertEquals(MarkKind.BARS, chart.marks.first().kind)
        assertEquals("age", chart.marks.first().label)
        assertTrue("3 rows × 2 columns" in chart.toText())
    }

    @Test
    fun `a frame of numbers charts one column against another`() {
        val runs = dataFrameOf("iteration", "residual", "step")(
            0, 1.0, 0.5,
            1, 0.5, 0.4,
            2, 0.1, 0.3,
        )
        val chart = runs.chart()
        assertEquals("iteration", chart.xLabel)
        // Two series, each a line and a pickable points mark.
        assertEquals(setOf("residual", "step"), chart.marks.map { it.label }.toSet())
        assertEquals(4, chart.marks.size)
        assertTrue(chart.marks.any { it.kind == MarkKind.POINTS })
    }

    @Test
    fun `a frame column that is not numeric is refused by name, and missing cells become gaps`() {
        val frame = dataFrameOf("label", "value")("a", 1.0, "b", 2.0)
        assertFailsWith<IllegalArgumentException> { frame.chart(y = listOf("nope")) }

        val holed = dataFrameOf("i", "v")(0, 1.0, 1, null, 2, 3.0)
        val chart = holed.chart()
        // Three data, two drawn nodes: a missing measurement is a gap and never a zero.
        val drawn = chart.toFLines().first { it.label == "v" }.nodes.mapNotNull { it.toOrNull }
        assertEquals(2, drawn.size, "a null cell must not be plotted as 0")
    }
}
