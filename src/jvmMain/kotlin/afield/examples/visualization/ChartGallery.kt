package afield.examples.visualization

import afield.CHART_BAD
import afield.Chart
import afield.FLineClick
import afield.ScaleKind
import afield.chart
import afield.getFLineClickCallback
import afield.graphics.linesToHTML
import afield.graphics.outputTo
import afield.hexColor
import afield.niceTicks
import afield.signalPalette
import afield.stdlib.Vec4
import afield.stdlib.label
import afield.toIntersection
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
FIELD_TEST
id: visualization.chart-gallery
runner: jvm-main
entrypoint: afield.examples.visualization.ChartGallery
tags: [smoke, visualization, chart, fline, capture, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "chart gallery rendered"
    - "hit record"
*/

/**
 * Headless capture harness for [Chart] — the vehicle that takes the primitive all the way to pixels.
 *
 * A chart that compiles is not a chart that is *right*: ticks can land on ugly numbers, labels can
 * smear or vanish, data can be drawn outside its own frame, and none of that fails a type check. So
 * this renders every mark kind through the real path — `Chart.toFLines()` → Skia's `outputTo` on a
 * **raster** surface → PNG — and then asserts the things a reader would otherwise have to check by
 * eye:
 *
 *  - every figure is inked well above a floor, in the plot *and* in the label gutters, so a chart
 *    that lost its axis text cannot pass;
 *  - a **counterfactual**: the same chart re-rendered with different data must differ by at least
 *    [MIN_COUNTERFACTUAL_PIXELS] pixels, which is what stops blank-versus-blank passing at zero
 *    differences;
 *  - the data→pixel mapping puts the extremes on the plot edges, to a pixel;
 *  - the interactive path is *live*: the emitted markup carries one registered callback per bar, and
 *    invoking one through `FLineClick.toIntersection()` — the same conversion `Server.kt`'s
 *    `fline-click` handler uses — delivers the right datum to `Chart.onPick`. What this does not
 *    cover is Chromium deciding *which* `<g>` a click landed in; `tools/fline_hit_proof.py` covers
 *    that half for `FLineHitInspectorFigure`, and the markup here is built by the same
 *    `buildInteractiveSVG`.
 *
 * Rendering is CPU-only Skia with no AWT surface, no GPU and no Field window, so it runs in a test
 * and in a docs build. Pass an output directory as `args[0]` (or set `CHART_GALLERY_DIR`) to
 * regenerate committed captures; with neither it writes to a temp dir and commits nothing, which is
 * what a test in a shared worktree has to do.
 */
object ChartGallery {

    /** Background of every capture — the dark the rest of the docs figures use. */
    private const val BACKGROUND = 0xFF12151C.toInt()

    /**
     * Inked-pixel floor, as a fraction of the figure. Below this something did not draw.
     *
     * Set just under the *sparsest* figure here — the log-log one — with the rest at 23–36%. A floor
     * of "more than nothing" would have passed a chart that lost every mark but its axes.
     *
     * **Re-cut when the default aspect changed from 640x380 to 900x420, and the arithmetic is the
     * reason to trust the re-cut rather than suspect it.** The sparsest figure went 5.7% -> 4.30%,
     * which fires a 4.5% floor. Nothing stopped drawing: the canvas grew 243,200 -> 378,000 px
     * (x1.554), so that figure's ABSOLUTE ink went UP, ~13,862 -> ~16,254 px. A fraction-of-canvas
     * floor is sensitive to canvas size in a way the property it guards is not; the ratio to the
     * sparsest measurement is held at the original 0.79 (0.045/0.057), giving 0.79 x 4.30% = 3.4%.
     */
    private const val MIN_INK_FRACTION = 0.034

    /**
     * How many pixels a counterfactual must move. Not "differs at all" — a real number.
     *
     * Quartering a sine's amplitude measured 10193 moved pixels of 243200, so this is a floor near
     * the measurement rather than a formality. Two blank images differ in zero pixels and would
     * satisfy any "they are not identical" check.
     */
    private const val MIN_COUNTERFACTUAL_PIXELS = 8_000

    /** Inked pixels required in the strip left of the plot frame, where only y labels live. */
    private const val MIN_LEFT_GUTTER_INK = 400

    /** Inked pixels required below the plot frame: x labels, axis title, footnote. */
    private const val MIN_BOTTOM_GUTTER_INK = 500

    /**
     * How many pixels sharing a grid's y domain must move.
     *
     * Measured 20550 of 256000 over four panels a thousandfold apart, so this is a floor near the
     * measurement. It is high on purpose: a grid that silently stopped sharing would still render
     * four plausible panels, and only the *amount* of difference distinguishes that from working.
     */
    private const val MIN_GRID_SHARING_PIXELS = 15_000

    /** One named figure. */
    private class Figure(val name: String, val build: () -> Chart)

    // ── the figures ───────────────────────────────────────────────────────────────

    private val FIGURES: List<Figure> = listOf(
        Figure("marks-lines-and-points") {
            val xs = (0..120).map { it / 120.0 * 6.28318 }
            val decay = xs.map { exp(-it * 0.25) * sin(it * 2.0) }
            val envelope = xs.map { exp(-it * 0.25) }
            Chart(
                title = "line, area and points share one frame",
                xLabel = "t (radians)",
                yLabel = "amplitude",
            )
                .area("envelope", xs, envelope)
                .line("damped sine", xs, decay)
                .points("samples", xs.filterIndexed { i, _ -> i % 8 == 0 },
                    decay.filterIndexed { i, _ -> i % 8 == 0 })
        },

        Figure("bars-categorical") {
            Chart(title = "bars put their categories on the axis", yLabel = "gates")
                .bars(
                    "cost",
                    listOf("xor", "maj3", "adder", "mux", "parity"),
                    listOf(6, 11, 27, 4, 19),
                )
        },

        Figure("histogram-sturges") {
            // A deterministic, visibly non-uniform sample: two Gaussians via Box-Muller on a fixed
            // LCG, so the shape is a real distribution and the picture is the same every run.
            var seed = 12345L
            fun next(): Double {
                seed = (seed * 6364136223846793005L + 1442695040888963407L)
                return ((seed ushr 11).toDouble() / (1L shl 53).toDouble())
            }
            val values = (0 until 600).map {
                val u1 = next().coerceAtLeast(1e-12)
                val u2 = next()
                val g = sqrt(-2.0 * ln(u1)) * cos(6.28318 * u2)
                if (it % 3 == 0) g * 0.6 + 3.0 else g
            }
            Chart(title = "histogram, bins by Sturges' rule", xLabel = "value", yLabel = "count")
                .histogram("samples", values)
        },

        Figure("log-scale-decades") {
            val n = (1..6).map { 10.0.pow(it) }
            val time = n.map { it * 1.4e-6 + 3e-4 }
            Chart(title = "a log axis, and decade ticks", xLabel = "n", yLabel = "seconds")
                .also { it.xKind = ScaleKind.LOG; it.yKind = ScaleKind.LOG }
                .line("measured", n, time)
                .points("measured", n, time)
        },

        Figure("expect-a-claim-you-can-watch-fail") {
            val iterations = (0..40).map { it }
            // A solver that converges but overshoots twice — the interesting case, because the
            // figure has to show the two failures rather than average them away.
            val residual = iterations.map { i ->
                val base = exp(-i * 0.22) * 1.5e-6
                if (i == 11 || i == 23) base + 2.4e-6 else base
            }
            Chart(
                title = "residual per iteration",
                xLabel = "iteration",
                yLabel = "|r|",
                note = "Adam, lr 0.05",
            )
                .line("residual", iterations, residual)
                .points("residual", iterations, residual)
                .expect(max = 1e-6, because = "converged below 1e-6")
        },

    )

    /** A deterministic Gaussian sample: Box-Muller on a fixed LCG, so the figure never moves. */
    private fun gaussian(n: Int, spread: Double, seed: Long): List<Double> {
        var state = seed
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (state ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        return (0 until n).map {
            val u1 = next().coerceAtLeast(1e-12)
            val u2 = next()
            sqrt(-2.0 * ln(u1)) * cos(6.28318 * u2) * spread
        }
    }

    // ── entry point ───────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val explicit = args.firstOrNull() ?: System.getenv("CHART_GALLERY_DIR")
        val dir = File(explicit ?: File(System.getProperty("java.io.tmpdir"), "chart-gallery").path)
        dir.mkdirs()

        checkTickSelection()

        FIGURES.forEach { figure ->
            val chart = figure.build()
            val raster = render(chart)
            val ink = inkFraction(raster)
            check(ink >= MIN_INK_FRACTION) {
                "${figure.name}: only ${"%.4f".format(ink)} of the figure is inked, floor is $MIN_INK_FRACTION — " +
                    "something did not draw"
            }
            checkGuttersInked(figure.name, chart, raster)
            // The sixth gate, applied to every committed figure too: nothing may leave the frame.
            frameProblems(figure.name, chart).forEach { error(it) }
            val file = File(dir, "chart-${figure.name}.png")
            file.writeBytes(raster.png)
            println(
                "  ${figure.name}: ${chart.marks.size} mark(s), ${chart.marks.sumOf { it.count }} points, " +
                    "ink ${"%.1f%%".format(ink * 100)}, " +
                    "frame overshoot ${"%.2f".format(frameOvershoot(chart))} px " +
                    "-> ${file.absolutePath}"
            )
        }

        checkCounterfactual(dir)
        checkPixelMapping()
        checkInteractivePath()
        checkNothingLeavesTheFrame(dir)

        println("chart gallery rendered ${FIGURES.size + 1} figure(s) into ${dir.absolutePath}")
    }

    // ── assertions ────────────────────────────────────────────────────────────────

    /**
     * The tick values every hand-rolled copy of this algorithm was supposed to produce.
     *
     * Exact equality on purpose: [niceTicks] snaps its output to the step's own precision, so
     * `0.30000000000000004` is a bug this catches rather than a floating-point fact to tolerate.
     */
    private fun checkTickSelection() {
        fun expect(got: List<Double>, want: List<Double>, what: String) {
            check(got == want) { "niceTicks $what: got $got, wanted $want" }
        }
        // Exact `==` on Double, deliberately: these are the literals a label must be able to print
        // short, so `0.6000000000000001` sneaking in here is the bug and not a rounding detail.
        expect(niceTicks(0.0, 1.0, 6), listOf(0.0, 0.2, 0.4, 0.6, 0.8, 1.0), "over 0..1")
        expect(niceTicks(0.0, 47.0, 5), listOf(0.0, 10.0, 20.0, 30.0, 40.0), "over 0..47")
        expect(niceTicks(-3.0, 3.0, 6), listOf(-3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0), "over -3..3")
        expect(niceTicks(0.0, 0.5, 6), listOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5), "over 0..0.5")
        check(niceTicks(5.0, 5.0, 6) == listOf(5.0)) { "a degenerate range must give one tick" }
        check(niceTicks(Double.NaN, 1.0, 6).isEmpty()) { "a NaN range must give no ticks" }
        val decades = afield.logTicks(1.0, 1e6)
        check(decades.size == 7 && decades.first() == 1.0 && decades.last() == 1e6) {
            "log ticks over six decades should be the seven powers of ten, got $decades"
        }
        println("  niceTicks: 7 cases, exact")
    }

    /**
     * A figure whose data changed must look different, by a floored number of pixels.
     *
     * The point of the floor: two blank images differ in zero pixels and would pass any "they are
     * not identical" check that forgot to say by how much.
     */
    private fun checkCounterfactual(dir: File) {
        val a = render(chart("signal" to (0..80).map { sin(it * 0.12) }, title = "counterfactual A"))
        val b = render(chart("signal" to (0..80).map { sin(it * 0.12) * 0.25 }, title = "counterfactual B"))
        check(a.width == b.width && a.height == b.height) { "counterfactual sizes differ" }
        var moved = 0
        for (i in a.pixels.indices) if (a.pixels[i] != b.pixels[i]) moved++
        check(moved >= MIN_COUNTERFACTUAL_PIXELS) {
            "quartering the amplitude moved only $moved pixels, floor is $MIN_COUNTERFACTUAL_PIXELS"
        }
        check(inkFraction(a) >= MIN_INK_FRACTION && inkFraction(b) >= MIN_INK_FRACTION) {
            "a counterfactual is worthless if either side is blank"
        }
        File(dir, "chart-counterfactual-a.png").writeBytes(a.png)
        File(dir, "chart-counterfactual-b.png").writeBytes(b.png)
        println("  counterfactual: $moved pixel(s) moved (floor $MIN_COUNTERFACTUAL_PIXELS)")
    }

    /**
     * The extremes of the data land on the extremes of the plot, to within a pixel.
     *
     * The one arithmetic claim a chart makes about itself. Read off the drawn geometry rather than
     * from a `Scale` built here, so it is the *figure's* mapping being checked and not a copy of it.
     */
    private fun checkPixelMapping() {
        val chart = Chart().also { it.width = 400.0; it.height = 300.0; it.legend = false }
            .line("ramp", (0..10).map { it }, (0..10).map { it })
        chart.yDomain = 0.0 to 10.0
        chart.xDomain = 0.0 to 10.0
        val ramp = chart.toFLines().first { it.label == "ramp" }
        val points = ramp.nodes.mapNotNull { it.toOrNull }
        check(points.size == 11) { "the ramp should have 11 nodes, has ${points.size}" }
        val first = points.first()
        val last = points.last()
        // Left-to-right and bottom-to-top: y grows *down* in the 2D outputs, so the last point of a
        // rising ramp must have the *smaller* y. Getting this backwards is the classic silent flip.
        check(last.x > first.x) { "x must increase left to right, got ${first.x} -> ${last.x}" }
        check(last.y < first.y) { "a rising ramp must go up the screen, got ${first.y} -> ${last.y}" }
        // Uniform data on a linear scale must be uniformly spaced, to a pixel.
        val steps = points.zipWithNext { a, b -> (b.x - a.x).toDouble() }
        val spread = steps.maxOrNull()!! - steps.minOrNull()!!
        check(spread < 1e-3) { "a linear scale must space equal steps equally, spread was $spread" }
        val inside = points.all { it.x >= 0f && it.x <= 400f && it.y >= 0f && it.y <= 300f }
        check(inside) { "every drawn point must be inside the figure" }
        println(
            "  mapping: 11 nodes, x ${"%.1f".format(first.x)}..${"%.1f".format(last.x)}, " +
                "y ${"%.1f".format(first.y)}..${"%.1f".format(last.y)}, spacing spread ${"%.2e".format(spread)}"
        )
    }

    /**
     * Every bar is separately live, and one of them, invoked, reports the right datum.
     *
     * This walks the production consumer: [linesToHTML] is what `Box.lines` calls, and the callbacks
     * it registers are looked up and fired through the same `FLineClick.toIntersection()` the server's
     * `fline-click` route uses.
     */
    private fun checkInteractivePath() {
        val categories = listOf("xor", "maj3", "adder", "mux", "parity")
        val values = listOf(6, 11, 27, 4, 19)
        var picked: String? = null
        val chart = Chart(title = "clickable bars").bars("cost", categories, values)
        chart.onPick = { pick -> picked = "${pick.series}/${pick.category}=${pick.y}@${pick.index}" }

        val html = linesToHTML(chart.toFLines(), chart.width, chart.height)
        val ids = Regex("""data-fline-callback-id="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
        check(ids.size == categories.size) {
            "expected one live callback per bar (${categories.size}), the markup carries ${ids.size}"
        }
        check("__flineHoverIn" in html) { "a hoverable mark must emit the hover hooks" }
        check(categories.all { it in html }) { "every category label must reach the markup" }

        // The third bar. Callback ids are emitted in draw order, so index 2 is "adder"/27.
        val wire = FLineClick(
            callbackId = ids[2],
            closestVertexIndex = 1,
            x = 210.0, y = 120.0,
            kind = "face",
            lineIndex = 2,
            label = "cost: adder → 27",
            nodeIndex = 1,
            subPathIndex = 0,
            elementIndex = 0,
            faceCoordinates = "bilinear",
            u = 0.5, v = 0.5,
        )
        val callback = getFLineClickCallback(ids[2])
        check(callback != null) { "the callback the markup names is not registered" }
        val record = wire.toIntersection()
        callback(record)
        check(picked == "cost/adder=27.0@2") { "a click on the third bar reported `$picked`" }
        println(
            "  hit record: kind=${record.kind} label=\"${record.label}\" line=${record.lineIndex} " +
                "node=${record.nodeIndex} u=${record.u} v=${record.v} faces=${record.faceCoordinates} " +
                "-> onPick saw $picked"
        )
        println("  interactive markup: ${ids.size} live callback(s), ${html.length} bytes")
    }

    // ── the sixth gate: a figure has to fit inside the figure ─────────────────────

    /**
     * How far outside its declared `width` x `height` a figure's geometry may reach, in pixels.
     *
     * Not zero: glyph outlines overshoot their advance box by a fraction of a pixel, a stroked axis
     * is drawn on the frame line itself, and `fast2DBounds` is a bounding box over Bézier *control*
     * points rather than over the curve. Measured over the five committed gallery figures, the worst
     * overshoot is under a pixel, so 4 px is a floor with room and still four hundred times smaller
     * than the failure it is here to catch.
     */
    private const val FRAME_SLOP = 4.0

    /**
     * Five green gates shipped `sharp_edge.png` — an empty olive rectangle with the real plot as an
     * illegible stamp in one corner. This is the sixth, and it is the one that would have caught it.
     *
     * The mechanism, which nothing in a build makes visible: a [Chart] declares a `width` x `height`
     * frame, `Chart.toHTML` hands the lines to `linesToHTML`, and that stamps the geometry's *own*
     * union bounds onto the wrapper as `data-afield-bounds-*`. The box output panel and the docs
     * capture then fit **those** bounds. So any geometry outside the declared frame does not get
     * cropped, it shrinks everything else. Two ways to produce it, both of which shipped:
     *
     *  - data outside an explicit `xDomain`/`yDomain`, which used to be mapped and drawn wherever it
     *    landed — the tetgen corner figure put a cross section 2 x 3 across into a 0.24 x 0.24
     *    window and the outline landed thousands of pixels off-frame;
     *  - a long `note`, which used to be one unwrapped line running off the right edge.
     *
     * Each case is measured twice. Once for the fix — the figure's bounds are inside the frame, and
     * the mark geometry is inside `plotRect` — and once for the **counterfactual**: the same numbers
     * mapped through the figure's own scales *without* the clip must land far outside, and the note
     * unwrapped must be far too wide. Without that second half a future refactor that quietly
     * disabled the clip would leave a test that still passes because the input became harmless.
     */
    private fun checkNothingLeavesTheFrame(dir: File) {
        val problems = mutableListOf<String>()

        // ── (i) data outside an explicit domain ───────────────────────────────────
        // The tetgen corner, reduced to its arithmetic: a closed 2 x 3 outline charted into a
        // 0.24 x 0.24 window on one of its corners.
        val outline = listOf(
            -1.0 to -1.5, 1.0 to -1.5, 1.0 to 1.5, -1.0 to 1.5, -1.0 to -1.5,
        )
        val corner = Chart(
            title = "one corner of a 2 x 3 outline",
            xLabel = "x", yLabel = "y",
        ).line("outline", outline.map { it.first }, outline.map { it.second })
        corner.width = 780.0
        corner.height = 440.0
        corner.xDomain = 0.82 to 1.06
        corner.yDomain = 1.32 to 1.56

        val plot = corner.plotRect()
        // The counterfactual, computed from the figure's own layout so it cannot drift out of step:
        // where the unclipped outline *would* land. If this is not far outside, the case is a
        // tautology and the assertion below proves nothing.
        val sx = afield.Scale(0.82, 1.06, plot.x, plot.x + plot.w)
        val sy = afield.Scale(1.32, 1.56, plot.y + plot.h, plot.y)
        val rawExcursion = outline.maxOf { (x, y) ->
            val px = sx.toPixel(x)
            val py = sy.toPixel(y)
            maxOf(plot.x - px, px - (plot.x + plot.w), plot.y - py, py - (plot.y + plot.h), 0.0)
        }
        if (rawExcursion < 2_000.0) {
            problems += "the corner counterfactual is toothless: unclipped, the outline would only " +
                "reach ${"%.0f".format(rawExcursion)} px outside a ${plot.w.toInt()}x${plot.h.toInt()} " +
                "plot, and the case no longer reproduces the defect it is here for"
        }
        problems += frameProblems("corner-window", corner)
        problems += markProblems("corner-window", corner, "outline")
        println(
            "  corner-window: unclipped the outline would reach ${"%.0f".format(rawExcursion)} px " +
                "outside the plot; drawn, ${extentReport(corner)}"
        )

        // ── (ii) a long note ──────────────────────────────────────────────────────
        // Verbatim the note the tetgen corner figure ships with, because that is the one that is
        // published wrong today.
        val longNote = "a 0.24 x 0.24 window on a body 2 x 3 across. The true corner is at " +
            "(1.0, 1.5); every level set cuts it off, and halving h halves what is left."
        val noted = Chart(title = "a long note", xLabel = "x", yLabel = "y", note = longNote)
            .line("ramp", (0..10).map { it }, (0..10).map { it })
        noted.width = 780.0
        noted.height = 440.0

        val oneLineWidth = afield.chartLabelWidth(longNote)
        val room = noted.width - noted.plotRect().x - 12.0
        if (oneLineWidth < room + 200.0) {
            problems += "the long-note counterfactual is toothless: on one line the note measures " +
                "${"%.0f".format(oneLineWidth)} px against ${"%.0f".format(room)} px of room, which " +
                "is not enough overhang to prove the wrap did anything"
        }
        problems += frameProblems("long-note", noted)
        println(
            "  long-note: ${longNote.length} chars, ${"%.0f".format(oneLineWidth)} px on one line " +
                "against ${"%.0f".format(room)} px of room; drawn, ${extentReport(noted)}"
        )

        // Both figures are captured, because "it compiles" is not verification for anything visual.
        File(dir, "chart-clip-corner-window.png").writeBytes(render(corner).png)
        File(dir, "chart-clip-long-note.png").writeBytes(render(noted).png)

        check(problems.isEmpty()) { "figures left their own frame:\n  - " + problems.joinToString("\n  - ") }
        println(
            "  frame gate: 2 regression figures + ${FIGURES.size} gallery figures inside their own " +
                "frames to within $FRAME_SLOP px"
        )
    }

    /** The union of everything a chart draws, in figure pixels. */
    private fun drawnBounds(chart: Chart): afield.Rect? =
        chart.toFLines().mapNotNull { runCatching { it.fast2DBounds() }.getOrNull() }
            .filter { it.x.isFinite() && it.y.isFinite() && it.w.isFinite() && it.h.isFinite() }
            .reduceOrNull { a, b -> a.union(b) }

    /** How far outside its declared frame a figure's geometry reaches, in pixels; 0 if none does. */
    private fun frameOvershoot(chart: Chart): Double {
        val b = drawnBounds(chart) ?: return 0.0
        return maxOf(-b.x, -b.y, (b.x + b.w) - chart.width, (b.y + b.h) - chart.height, 0.0)
    }

    /** Empty if the whole figure is inside its declared frame, else one line saying by how much. */
    private fun frameProblems(name: String, chart: Chart): List<String> {
        val b = drawnBounds(chart) ?: return listOf("$name: the figure drew nothing at all")
        val over = frameOvershoot(chart)
        if (over <= FRAME_SLOP) return emptyList()
        val shrink = minOf(chart.width / b.w, chart.height / b.h).coerceAtMost(1.0)
        return listOf(
            "$name: geometry reaches ${"%.0f".format(over)} px outside a " +
                "${chart.width.toInt()}x${chart.height.toInt()} frame — bounds " +
                "${"%.0f".format(b.x)},${"%.0f".format(b.y)} " +
                "${"%.0f".format(b.w)}x${"%.0f".format(b.h)}, so a panel fitting those bounds draws " +
                "this figure at ${"%.0f%%".format(shrink * 100)} of its declared size"
        )
    }

    /** `""` if every line of the named series is inside `plotRect`, else one line saying by how much. */
    private fun markProblems(name: String, chart: Chart, series: String): List<String> {
        val plot = chart.plotRect()
        val lines = chart.toFLines().filter { it.label?.startsWith(series) == true }
        if (lines.isEmpty()) return listOf("$name: no line is labelled `$series`; the case did not draw")
        val over = lines.mapNotNull { runCatching { it.fast2DBounds() }.getOrNull() }.maxOf { b ->
            maxOf(plot.x - b.x, plot.y - b.y, (b.x + b.w) - (plot.x + plot.w), (b.y + b.h) - (plot.y + plot.h), 0.0)
        }
        if (over <= FRAME_SLOP) return emptyList()
        return listOf(
            "$name: series `$series` reaches ${"%.0f".format(over)} px outside plotRect " +
                "(${plot.x.toInt()},${plot.y.toInt()} ${plot.w.toInt()}x${plot.h.toInt()}), and " +
                "plotRect's own KDoc says the data is clipped to it"
        )
    }

    private fun extentReport(chart: Chart): String {
        val b = drawnBounds(chart) ?: return "nothing"
        return "bounds ${"%.0f".format(b.x)},${"%.0f".format(b.y)} " +
            "${"%.0f".format(b.w)}x${"%.0f".format(b.h)} in a " +
            "${chart.width.toInt()}x${chart.height.toInt()} frame"
    }

    /**
     * Ink outside the plot frame proves the *labels* drew, not only the data.
     *
     * A chart that silently lost its glyph outlines still passes an overall ink floor, because the
     * data is most of the ink. The gutters are where the tick labels and the title live and nothing
     * else does, so they are checked separately.
     */
    private fun checkGuttersInked(name: String, chart: Chart, raster: Raster) {
        // The real frame, not a guess: the insets come from the widest tick label, so a hard-coded
        // gutter would be measuring the wrong strip on half these figures.
        val plot = chart.plotRect()
        val left = plot.x.toInt()
        val below = (plot.y + plot.h).toInt() + 2
        val leftGutter = countInk(raster, 0, 0, left - 2, raster.height)
        val bottomGutter = countInk(raster, 0, below, raster.width, raster.height - below)
        check(leftGutter >= MIN_LEFT_GUTTER_INK) {
            "$name: the y tick labels did not draw ($leftGutter inked px, floor $MIN_LEFT_GUTTER_INK)"
        }
        check(bottomGutter >= MIN_BOTTOM_GUTTER_INK) {
            "$name: the x tick labels did not draw ($bottomGutter inked px, floor $MIN_BOTTOM_GUTTER_INK)"
        }
        // The title is drawn in the first palette colour, so its absence is detectable.
        if (chart.title != null) {
            val accent = countMatching(raster, hexOf(hexColor(signalPalette[0])), tolerance = 70)
            check(accent >= 30) { "$name: no palette-coloured ink; the title did not draw" }
        }
        // A claim, when one failed, must actually be visible: the band is drawn in CHART_BAD.
        if (chart.deviations.isNotEmpty()) {
            val bad = countMatching(raster, hexOf(CHART_BAD), tolerance = 46)
            check(bad >= 40) { "$name: ${chart.deviations.size} deviations but only $bad px of CHART_BAD" }
        }
        println("    $name: gutters left=$leftGutter bottom=$bottomGutter, plot=${plot.w.toInt()}x${plot.h.toInt()}")
    }

    // ── rasterising ───────────────────────────────────────────────────────────────

    /** One rendered figure: its pixels, and the PNG bytes for the same. */
    private class Raster(val width: Int, val height: Int, val pixels: IntArray, val png: ByteArray)

    /**
     * A chart to real pixels: Skia's own `outputTo`, on a raster surface, antialiased.
     *
     * `outputTo` with `antiAlias = true` because this *is* the final image — the SVG outputs leave
     * antialiasing to the viewer, but a raster capture without it turns every glyph stem and
     * diagonal into a staircase, which is exactly the detail a reader is being asked to judge.
     */
    private fun render(chart: Chart): Raster =
        renderLines(chart.toFLines(), chart.width, chart.height)

    private fun renderLines(lines: List<afield.graphics.FLine>, wd: Double, ht: Double): Raster {
        val w = wd.toInt()
        val h = ht.toInt()
        val surface = Surface.makeRasterN32Premul(w, h)
        val bitmap = Bitmap()
        try {
            val canvas = surface.canvas
            canvas.clear(BACKGROUND)
            lines.forEach { outputTo(it, canvas, antiAlias = true) }
            val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
                ?: error("Skia declined to encode the figure as PNG")
            bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
            check(surface.readPixels(bitmap, 0, 0)) { "Skia declined to read the figure back" }
            val pixels = IntArray(w * h) { bitmap.getColor(it % w, it / w) }
            return Raster(w, h, pixels, png)
        } finally {
            runCatching { bitmap.close() }
            runCatching { surface.close() }
        }
    }

    private fun inkFraction(r: Raster): Double =
        countInk(r, 0, 0, r.width, r.height).toDouble() / (r.width * r.height)

    private fun countInk(r: Raster, x0: Int, y0: Int, w: Int, h: Int): Int {
        var n = 0
        for (y in y0 until minOf(y0 + h, r.height)) {
            for (x in x0 until minOf(x0 + w, r.width)) {
                if (!nearBackground(r.pixels[y * r.width + x])) n++
            }
        }
        return n
    }

    private fun nearBackground(argb: Int): Boolean {
        val dr = abs(((argb shr 16) and 0xFF) - ((BACKGROUND shr 16) and 0xFF))
        val dg = abs(((argb shr 8) and 0xFF) - ((BACKGROUND shr 8) and 0xFF))
        val db = abs((argb and 0xFF) - (BACKGROUND and 0xFF))
        return dr + dg + db <= 12
    }

    private fun hexOf(c: Vec4): Int {
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f).toInt()
        return (ch(c.x) shl 16) or (ch(c.y) shl 8) or ch(c.z)
    }

    private fun countMatching(r: Raster, rgb: Int, tolerance: Int): Int {
        var n = 0
        for (p in r.pixels) {
            val dr = abs(((p shr 16) and 0xFF) - ((rgb shr 16) and 0xFF))
            val dg = abs(((p shr 8) and 0xFF) - ((rgb shr 8) and 0xFF))
            val db = abs((p and 0xFF) - (rgb and 0xFF))
            if (dr + dg + db <= tolerance) n++
        }
        return n
    }
}
