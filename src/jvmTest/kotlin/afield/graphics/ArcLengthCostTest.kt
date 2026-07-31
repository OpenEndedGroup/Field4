package afield.graphics

import field.graphics.FLinesAndJavaShapes
import java.awt.Font
import java.awt.font.FontRenderContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the sharper subdivision criterion costs, measured on a **realistic composite workload** built
 * from real font data rather than on a synthetic polyline.
 *
 * Pass 1 offered "1.23x on 1000 straight cubics", which is a microbenchmark of the worst case for
 * `minSubDiv` and tells a reader nothing about anything they would feel. This runs the ops a
 * document actually performs, on the geometry a document actually holds:
 *
 *   a full line of text as real glyph outlines (`Font.createGlyphVector(...).outline` ->
 *   `FLinesAndJavaShapes.javaShapeToFLine`), then per glyph: dash it, resample it by arc length,
 *   reparameterize it, take its midpoint, and hit-test a point against it.
 *
 * The honest framing, stated up front so the number is not oversold: **the flattener is not in the
 * frame path.** Nothing outside `FLinesAndJavaShapes` constructs a `PathFlattener` (see
 * `RenderPathFlatteningTest` for the citations), so no measurement here can be a frame time. This is
 * the cost of a geometry pass — the thing that happens when a box runs, not every 16ms. That is the
 * ceiling of what this branch can honestly claim to have measured.
 */
class ArcLengthCostTest {

    private val frc = FontRenderContext(null, true, true)

    /** A line of text as real outlines — the heaviest realistic FLine payload I can build here. */
    private fun textAsOutlines(
        text: String = "Handgloves — the quick brown fox jumps over the lazy dog, 0123456789",
        size: Float = 96f
    ): List<FLine> {
        val font = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(size)
        return text.filter { !it.isWhitespace() }.map { ch ->
            FLinesAndJavaShapes.javaShapeToFLine(font.createGlyphVector(frc, ch.toString()).outline)
        }.filter { it.nodes.size > 3 }
    }

    /** Everything a document plausibly does to a piece of geometry, once. */
    private fun geometryPass(glyphs: List<FLine>): Int {
        var work = 0
        for (glyph in glyphs) {
            val length = glyph.cursor().lengthD()
            if (length <= 0f) continue
            work += glyph.byDashing(length / 30f, length / 60f).size
            work += glyph.sampleByDistance(length / 120f).size
            work += glyph.byArcLengthParameterizing(length / 90f).nodes.size
            work += if (glyph.midPoint().x.isFinite()) 1 else 0
            work += if (FLinesAndJavaShapes.closestT(glyph, glyph.midPoint()).isFinite()) 1 else 0
        }
        return work
    }

    @Test
    fun aRealGeometryPassOverALineOfTextIsFastEnoughToNotNotice() {
        val glyphs = textAsOutlines()
        assertTrue(glyphs.size > 30, "only ${glyphs.size} glyph outlines — check the JVM's font setup")

        val nodes = glyphs.sumOf { it.nodes.size }
        val cubics = glyphs.sumOf { g -> g.nodes.count { it is FLine.CubicTo } }
        val leaves = glyphs.sumOf { PathFlattener(it, 0.01f).mappings.size }

        repeat(3) { geometryPass(glyphs) } // warm

        val runs = 10
        val start = System.nanoTime()
        var work = 0
        repeat(runs) { work = geometryPass(glyphs) }
        val millisPerPass = (System.nanoTime() - start) / 1e6 / runs

        println(
            "geometry pass over ${glyphs.size} real glyph outlines: " +
                "$nodes nodes, $cubics cubics, $leaves flattener leaves, $work results, " +
                "${"%.2f".format(millisPerPass)} ms/pass"
        )

        // Not a tight bound — machines differ, and the point is the printed number. But a full
        // geometry pass over a line of text taking more than a fifth of a second would be felt as
        // lag when a box re-runs, and would mean this branch had made something genuinely slower.
        assertTrue(
            millisPerPass < 200.0,
            "a geometry pass over a line of text took ${"%.1f".format(millisPerPass)} ms"
        )
    }

    /**
     * The worst case for `minSubDiv`, kept from pass 1 as the honest ceiling: geometry that is all
     * straight cubics, where the forced floor is pure overhead and buys nothing.
     */
    @Test
    fun theWorstCaseForTheSubdivisionFloorIsStillBounded() {
        val polyline = FLine().moveTo(0f, 0f)
        for (i in 1..1000) {
            val x0 = (i - 1) * 10f
            val x1 = i * 10f
            polyline.cubicTo(
                afield.stdlib.Vec3(x0 + (x1 - x0) / 3f, 0f, 0f),
                afield.stdlib.Vec3(x0 + 2 * (x1 - x0) / 3f, 0f, 0f),
                afield.stdlib.Vec3(x1, 0f, 0f)
            )
        }

        repeat(3) { PathFlattener(polyline, 0.01f) }
        val runs = 20
        val start = System.nanoTime()
        var leaves = 0
        repeat(runs) { leaves = PathFlattener(polyline, 0.01f).mappings.size }
        val microsPerBuild = (System.nanoTime() - start) / 1000.0 / runs

        println("1000 straight cubics: $leaves leaves, ${"%.1f".format(microsPerBuild)} us/build")
        assertTrue(leaves <= 8000, "1000 straight cubics flattened to $leaves leaves")
    }
}
