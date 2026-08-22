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

    @Test
    fun `box stats use type-7 quartiles, and Tukey fences hold the whisker in`() {
        // 1..9 has exact type-7 quartiles, so this pins the interpolation convention rather than
        // accepting whatever it happens to be: 3 versus 3.5 is a box a quarter the wrong height.
        val s = boxStats((1..9).toList())
        assertEquals(3.0, s.q1)
        assertEquals(5.0, s.median)
        assertEquals(7.0, s.q3)
        assertEquals(4.0, s.iqr)
        assertEquals(1.0, s.low)
        assertEquals(9.0, s.high)
        assertTrue(s.outliers.isEmpty())
        assertEquals(9, s.count)

        // The whole reason for Tukey's convention: one far value becomes a point and pulls the
        // whisker IN, instead of stretching it and making the sample look uniformly wide.
        val tailed = boxStats((1..9).toList() + listOf(60))
        assertEquals(listOf(60.0), tailed.outliers)
        assertTrue(tailed.high < 60.0, "the whisker must stop short of the outlier")

        // A single value is a degenerate box, not a crash; an empty sample is an error, not zeroes.
        val one = boxStats(listOf(4.0))
        assertEquals(4.0, one.median)
        assertEquals(0.0, one.iqr)
        assertFailsWith<IllegalArgumentException> { boxStats(emptyList()) }
    }

    @Test
    fun `a boxes mark reaches its own whiskers and outliers`() {
        // The mark's `y` is only the medians, so a domain taken from `y` alone would crop the tails —
        // which are the reason to draw a box plot at all.
        val chart = Chart().boxes(
            "s", listOf("a", "b"),
            listOf(listOf(1, 2, 3, 4, 5), listOf(10, 11, 12, 13, 99)),
        )
        val (lo, hi) = chart.resolvedYDomain()
        assertTrue(lo <= 1.0, "the low whisker must be in frame, domain started at $lo")
        assertTrue(hi >= 99.0, "the outlier must be in frame, domain ended at $hi")
        assertEquals(listOf("a", "b"), chart.xCategories)
        assertEquals(listOf(99.0), chart.marks.first().boxes!![1].outliers)
    }

    // ── small multiples ───────────────────────────────────────────────────────────

    @Test
    fun `a grid shares its y domain, and that is what changes the picture`() {
        fun panels() = listOf(1.0, 0.001).map { scale ->
            Chart(title = "x $scale").also { it.width = 320.0; it.height = 200.0 }
                .line("v", (0..20).map { it }, (0..20).map { it * scale })
        }

        val shared = ChartGrid(panels(), columns = 2, shareY = true)
        assertEquals(640.0, shared.width)
        assertEquals(200.0, shared.height)
        assertEquals(1, shared.rows)

        // Shared: the small panel's curve is flat near the bottom. Free: it fills its own frame.
        // Compare the drawn y extents rather than the domains, so this is about the picture.
        fun spanOf(grid: ChartGrid, panel: Int): Double {
            grid.toFLines()
            val ys = grid.charts[panel].toFLines().first { it.label == "v" }
                .nodes.mapNotNull { it.toOrNull }.map { it.y.toDouble() }
            return ys.maxOrNull()!! - ys.minOrNull()!!
        }

        val sharedSmall = spanOf(shared, 1)
        val freeSmall = spanOf(ChartGrid(panels(), columns = 2, shareY = false), 1)
        assertTrue(
            freeSmall > sharedSmall * 100,
            "unshared, the thousandfold-smaller panel should fill its frame: $freeSmall vs $sharedSmall"
        )
        assertTrue(sharedSmall < 2.0, "shared, it should be nearly flat, spanned $sharedSmall px")

        assertFailsWith<IllegalArgumentException> { ChartGrid(emptyList()) }
    }

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
