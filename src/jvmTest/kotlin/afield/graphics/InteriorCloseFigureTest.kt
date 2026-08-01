package afield.graphics

import afield.Rect
import afield.stdlib.Vec3
import afield.stdlib.Vec4
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.fitInto
import afield.stdlib.planeFigureHtml
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pictures. This is drawing code, so "the test is green" is not the whole story — these are the
 * three changes whose *output* is different rather than merely non-throwing, rendered through the
 * same path a Field box uses (`planeFigureHtml` -> `drawToSVG` -> Skia's SVG canvas,
 * spatial_field.kt:341-370, which is what `Box.showPlaneGrid` at :463 calls).
 *
 * Every assertion here runs on every build. The *writing* is gated on `AFIELD_FIGURES_OUT=<dir>`,
 * so a normal test run touches no files: the committed figures under
 * `dev_docs/assets/interior_close/` were produced by running this twice, once with the graphics
 * sources checked out at 7abe8736 (the "before" half) and once at HEAD, and nothing about them is
 * synthesized.
 */
class InteriorCloseFigureTest {

    /**
     * A two-contour outline that leans on `close()` for its final segment — no explicit `lineTo`
     * back to the start. That is what makes a dropped `Close` visible rather than a no-op: the
     * contour is left with a hole in it where the closing run should be.
     */
    private fun ringGlyph(): FLine = FLine()
        .moveTo(0f, 0f)
        .cubicTo(Vec3(-40f, 150f, 0f), Vec3(60f, 260f, 0f), Vec3(160f, 250f, 0f))
        .cubicTo(Vec3(260f, 240f, 0f), Vec3(300f, 120f, 0f), Vec3(250f, 20f, 0f))
        .close() // ~250 units back to the origin: a real segment, not a formality
        .moveTo(80f, 70f)
        .cubicTo(Vec3(60f, 140f, 0f), Vec3(110f, 190f, 0f), Vec3(170f, 180f, 0f))
        .cubicTo(Vec3(220f, 172f, 0f), Vec3(230f, 110f, 0f), Vec3(200f, 62f, 0f))
        .close()

    private fun closedSquare(): FLine = FLine()
        .moveTo(0f, 0f)
        .lineTo(200f, 0f)
        .lineTo(200f, 200f)
        .lineTo(0f, 200f)
        .close()

    /** the `twoSubpaths` fixture from ArcLengthSpacingTest, which is what 1428% was measured on. */
    private fun twoSubpaths(): FLine = FLine().moveTo(0f, 0f)
        .cubicTo(Vec3(150f, 0f, 0f), Vec3(280f, 0f, 0f), Vec3(300f, 0f, 0f))
        .moveTo(0f, 200f)
        .cubicTo(Vec3(20f, 200f, 0f), Vec3(150f, 200f, 0f), Vec3(300f, 200f, 0f))

    private fun styled(l: FLine, colour: Vec4, thickness: Double = 3.0): FLine = l.copy {
        this.color = Vec4(colour)
        this.thickness = thickness
        this.stroked = true
        this.filled = false
    }

    private val ghost = vec(0.45, 0.5, 0.58, 0.6)
    private val subject = vec(0.35, 0.85, 1.0, 0.95)

    /** the three panels, as (name, lines). Rendered identically before and after. */
    private fun panels(): List<Pair<String, List<FLine>>> {
        val square = closedSquare()
        val glyph = ringGlyph()
        val two = twoSubpaths()
        return listOf(
            "reversing-a-closed-square" to listOf(
                styled(square, ghost, 8.0),
                styled(square.byReversing(), subject)
            ),
            "subdividing-a-two-contour-glyph" to listOf(
                styled(glyph, ghost, 8.0),
                styled(glyph.bySubdividing(2), subject)
            ),
            "dashing-across-two-subpaths" to listOf(
                styled(two, ghost, 8.0),
                *two.byDashing(25f, 12f).map { styled(it, subject, 4.0) }.toTypedArray()
            )
        )
    }

    /**
     * Renders every panel and checks the SVG is a real drawing rather than an empty frame — the
     * failure mode a figure-generating test has to guard against is producing something blank and
     * calling it a picture.
     */
    @Test
    fun everyPanelRendersRealGeometry() {
        for ((name, lines) in panels()) {
            val svg = svgFor(lines)
            // Skia's SVG canvas leads with an XML prolog, so this is `contains`, not `startsWith`
            assertTrue(svg.contains("<svg"), "$name did not render an svg: ${svg.take(80)}")
            val paths = Regex("<path").findAll(svg).count()
            assertTrue(paths >= 2, "$name rendered only $paths paths, so one of the two lines is missing")
            // real coordinates, not a degenerate frame collapsed onto the origin
            val coords = Regex("[ML](-?\\d+(?:\\.\\d+)?) (-?\\d+(?:\\.\\d+)?)").findAll(svg)
                .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
            assertTrue(coords.size >= 6, "$name has only ${coords.size} path points")
            val spanX = coords.maxOf { it.first } - coords.minOf { it.first }
            val spanY = coords.maxOf { it.second } - coords.minOf { it.second }
            assertTrue(spanX > 100 && spanY > 50, "$name collapsed to ${spanX}x${spanY}")
            println("$name: $paths paths, ${coords.size} points, ${"%.0f".format(spanX)}x${"%.0f".format(spanY)}")
        }
    }

    /**
     * The claims each panel is meant to *show*, asserted so the picture cannot quietly stop being
     * the picture. Only meaningful at HEAD; at 7abe8736 these are the red tests.
     */
    @Test
    fun eachPanelShowsWhatItSaysItShows() {
        // 1. the reversal of a closed square is a closed square, not a triangle
        val square = closedSquare()
        val reversed = square.byReversing()
        assertTrue(reversed.nodes.last() is FLine.Close, "the reversed square is not closed")
        assertTrue(
            reversed.nodes.count { it is FLine.LineTo } == 3,
            "the reversed square has ${reversed.nodes.count { it is FLine.LineTo }} sides, not 3"
        )

        // 2. subdividing a two-contour glyph keeps both contours closed
        val glyph = ringGlyph()
        val subdivided = glyph.bySubdividing(2)
        assertTrue(subdivided.nodes.count { it is FLine.Close } == 2, "a contour lost its Close")
        assertTrue(subdivided.nodes.count { it is FLine.CubicTo } == 16, "2 levels of 4 cubics is 16")

        // 3. no dash bridges the 360-unit gap between the two subpaths
        val two = twoSubpaths()
        val dashes = two.byDashing(25f, 12f)
        val longest = dashes.maxOf { d ->
            (1 until d.nodes.size).sumOf { d.nodes[it].to.distance(d.nodes[it - 1].to).toDouble() }
        }
        assertTrue(longest < 25.0 * 1.1, "a dash is $longest long against a 25-unit request")
    }

    /** `AFIELD_FIGURES_OUT=<dir>` writes the figures; otherwise this does nothing. */
    @Test
    fun writeFiguresWhenAsked() {
        val out = System.getenv("AFIELD_FIGURES_OUT") ?: return
        val suffix = System.getenv("AFIELD_FIGURES_SUFFIX") ?: "after"
        val dir = File(out).also { it.mkdirs() }

        val grid = StringBuilder("<div style='display:grid;grid-template-columns:max-content;gap:10px'>")
        for ((name, lines) in panels()) {
            File(dir, "$name-$suffix.svg").writeText(svgFor(lines))
            grid.append(planeFigureHtml(lines, "$name ($suffix)", null, 560, 400, 14.0))
        }
        grid.append("</div>")
        File(dir, "grid-$suffix.html").writeText(grid.toString())
        println("wrote ${panels().size} svg panels + grid-$suffix.html to $dir")
    }

    /**
     * Exactly what `planeFigureHtml` renders with (spatial_field.kt:351, :370): fit the lines into
     * the frame, then hand them to Skia's SVG canvas. Standalone, so the result can be dropped into
     * a markdown page.
     */
    private fun svgFor(lines: List<FLine>, w: Int = 560, h: Int = 400): String =
        drawToSVG(lines.fitInto(Rect(0.0, 0.0, w.toDouble(), h.toDouble()), padding = 18.0), w.toDouble(), h.toDouble())
}
