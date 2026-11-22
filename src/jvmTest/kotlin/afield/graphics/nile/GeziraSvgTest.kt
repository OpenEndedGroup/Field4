package afield.graphics.nile

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SVG reader under Gezira: path commands with exact control points, arcs, transform lists,
 * style inheritance and fill rules, then the tiger through the kernels against Skia's SVGDOM.
 */
class GeziraSvgTest {

    private fun pts(b: Bezier) = b.p.toList()

    private fun near(expected: List<Double>, actual: List<Double>, eps: Double = 1e-9) {
        assertEquals(expected.size, actual.size, "point count: $expected vs $actual")
        for (i in expected.indices) assertTrue(abs(expected[i] - actual[i]) <= eps, "component $i: ${expected[i]} vs ${actual[i]} in $actual")
    }

    @Test
    fun lineCommandsAbsoluteRelativeAndImplicit() {
        val sp = GeziraSvg.pathData("M10,20 30 40 h5 v-5 H0 V1 l1-2z m1 1 L2 2")
        assertEquals(2, sp.size)
        assertTrue(sp[0].closed)
        assertEquals(false, sp[1].closed)
        assertEquals(
            listOf(
                listOf(10.0, 20.0, 30.0, 40.0), listOf(30.0, 40.0, 35.0, 40.0), listOf(35.0, 40.0, 35.0, 35.0),
                listOf(35.0, 35.0, 0.0, 35.0), listOf(0.0, 35.0, 0.0, 1.0), listOf(0.0, 1.0, 1.0, -1.0),
                listOf(1.0, -1.0, 10.0, 20.0),
            ),
            sp[0].beziers.map(::pts),
        )
        // After z the current point is the subpath start, so m1 1 lands on (11, 21).
        assertEquals(listOf(listOf(11.0, 21.0, 2.0, 2.0)), sp[1].beziers.map(::pts))
    }

    @Test
    fun packedNumbersAndExponents() {
        val sp = GeziraSvg.pathData("M.5.5l1e1-2E-1-.25.75")
        assertEquals(listOf(listOf(0.5, 0.5, 10.5, 0.3), listOf(10.5, 0.3, 10.25, 1.05)), sp[0].beziers.map(::pts).map { l -> l.map { Math.round(it * 1e9) / 1e9 } })
    }

    @Test
    fun curvesAndTheirSmoothReflections() {
        val sp = GeziraSvg.pathData("M0 0 C1 2 3 4 5 6 S9 10 11 12 Q13 14 15 16 T19 20 t2 2")
        assertEquals(
            listOf(
                listOf(0.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                listOf(5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0),
                listOf(11.0, 12.0, 13.0, 14.0, 15.0, 16.0),
                listOf(15.0, 16.0, 17.0, 18.0, 19.0, 20.0),
                listOf(19.0, 20.0, 21.0, 22.0, 21.0, 22.0),
            ),
            sp[0].beziers.map(::pts),
        )
        // S after a non-curve uses the current point as its first handle.
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 2.0, 2.0), pts(GeziraSvg.pathData("M0 0 S1 1 2 2")[0].beziers[0]))
    }

    @Test
    fun quarterArcIsTheKappaCubic() {
        val k = 4.0 / 3 * (Math.sqrt(2.0) - 1) * 10
        val b = GeziraSvg.pathData("M10 0 A10 10 0 0 1 0 10")[0].beziers
        assertEquals(1, b.size)
        near(listOf(10.0, 0.0, 10.0, k, k, 10.0, 0.0, 10.0), pts(b[0]))
    }

    @Test
    fun arcFlagsChooseTheFourArcs() {
        // From (0,0) to (10,0) on radius-10 circles: small/large × sweep 0/1, with packed flags.
        for ((flags, quarterTurns, belowCentreLine) in listOf(Triple("00", 1, true), Triple("01", 1, false), Triple("10", 4, false), Triple("11", 4, true))) {
            val b = GeziraSvg.pathData("M0 0a10 10 0 ${flags[0]}${flags[1]}10 0")[0].beziers
            assertEquals(quarterTurns, b.size, "flags $flags")
            near(listOf(10.0, 0.0), pts(b.last()).takeLast(2))
            // Every endpoint lies on the circle through the two points, centre (5, ±8.66).
            val cy = if (belowCentreLine) -8.660254037844386 else 8.660254037844386
            for (s in b) assertEquals(10.0, hypot(s.p[s.p.size - 2] - 5.0, s.p[s.p.size - 1] - cy), 1e-9, "flags $flags")
        }
        // Radii too small are scaled up: a half circle of radius 5.
        val half = GeziraSvg.pathData("M0 0 A1 1 0 0 1 10 0")[0].beziers
        assertEquals(2, half.size)
        near(listOf(5.0, -5.0), pts(half[0]).takeLast(2))
    }

    @Test
    fun arcAgreesWithSkiasPathDataReader() {
        val d = "M5 5 A20 8 30 1 0 40 22 a6 6 0 0 1 -3 9z"
        val ours = GeziraSvg.pathData(d).flatMap { it.closedBeziers() }
        val skia = org.jetbrains.skia.Path.makeFromSVGString(d)
        val a = Gezira.skiaPath(ours).bounds
        val s = skia.computeTightBounds()
        val ourTight = Gezira.skiaPath(ours).computeTightBounds()
        assertTrue(abs(ourTight.left - s.left) < 1e-3 && abs(ourTight.right - s.right) < 1e-3, "x bounds $ourTight vs $s ($a)")
        assertTrue(abs(ourTight.top - s.top) < 1e-3 && abs(ourTight.bottom - s.bottom) < 1e-3, "y bounds $ourTight vs $s")
    }

    @Test
    fun transformListsComposeLeftToRight() {
        val t = SvgTransform.parse("translate(10,20) scale(2) rotate(90)")
        assertEquals(10.0, t.x(1.0, 0.0), 1e-12)
        assertEquals(22.0, t.y(1.0, 0.0), 1e-12)
        val r = SvgTransform.parse("rotate(180 5 5)")
        assertEquals(10.0, r.x(0.0, 0.0), 1e-12)
        assertEquals(10.0, r.y(0.0, 0.0), 1e-12)
        val m = SvgTransform.parse("matrix(1 2 3 4 5 6)")
        assertEquals(1.0 + 3.0 + 5.0, m.x(1.0, 1.0))
        assertEquals(2.0 + 4.0 + 6.0, m.y(1.0, 1.0))
        assertEquals(1.0, SvgTransform.parse("skewX(45)").x(0.0, 1.0), 1e-12)
        assertEquals(1.0, SvgTransform.parse("skewY(45)").y(1.0, 0.0), 1e-12)
        assertEquals(2.0, SvgTransform.parse("scale(2, 2)").scale, 1e-12)
    }

    private val styled = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50" width="200" height="100">
          <title>x</title>
          <g fill="#f00" stroke="blue" stroke-width="2" transform="translate(10 0)" opacity="0.5">
            <g transform="scale(2)" style="fill-rule:evenodd; stroke: none">
              <path id="a" d="M0 0h10v10z"/>
              <rect id="b" x="1" y="2" width="3" height="4" fill="rgb(0, 255, 0)" fill-opacity="0.5"/>
            </g>
            <circle id="c" cx="5" cy="5" r="5" fill="none"/>
            <polygon id="d" points="0,0 4,0 4,4" fill="#0F0"/>
            <linearGradient/>
          </g>
          <line id="e" x1="0" y1="0" x2="1" y2="1" stroke="black"/>
        </svg>
    """.trimIndent()

    @Test
    fun styleIsInheritedAndOverridden() {
        val doc = GeziraSvg.parse(styled)
        assertContentEquals(doubleArrayOf(0.0, 0.0, 100.0, 50.0), doc.viewBox)
        assertEquals(listOf("a", "b", "c", "d", "e"), doc.shapes.map { it.id })
        assertEquals(mapOf("linearGradient" to 1), doc.skipped)
        val (a, b, c, d, e) = doc.shapes
        assertEquals(Rgba.of(1.0, 0.0, 0.0, 0.5), a.fill)
        assertEquals(FillRule.EVEN_ODD, a.fillRule)
        assertNull(a.stroke, "style stroke:none overrides the inherited stroke")
        // translate(10 0) scale(2): (10, 10) -> (30, 20).
        assertEquals(listOf(30.0, 0.0, 30.0, 20.0), pts(a.subpaths[0].beziers[1]))
        assertEquals(Rgba.of(0.0, 1.0, 0.0, 0.25), b.fill)
        assertEquals(listOf(12.0, 4.0, 18.0, 4.0), pts(b.subpaths[0].beziers[0]))
        assertNull(c.fill)
        assertEquals(Rgba.of(0.0, 0.0, 1.0, 0.5), c.stroke)
        assertEquals(2.0, c.strokeWidth)
        assertEquals(4, c.subpaths[0].beziers.size)
        assertEquals(FillRule.NON_ZERO, c.fillRule)
        assertEquals(3, d.subpaths[0].beziers.size, "a polygon closes itself")
        assertTrue(d.subpaths[0].closed)
        assertEquals(Rgba.of(0.0, 0.0, 0.0, 1.0), e.fill, "fill defaults to black")
        assertEquals(1.0, e.strokeWidth)
        // The view box fits 200 x 100 at scale 2.
        assertEquals(20.0, doc.fit(200, 100).x(10.0, 0.0))
    }

    @Test
    fun fillRulesReachTheRasteriser() {
        // A square with a same-direction square inside: even-odd leaves a hole, non-zero does not.
        val svg = { rule: String ->
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path fill-rule="$rule" d="M2 2H18V18H2Z M6 6H14V14H6Z"/></svg>"""
        }
        val evenOdd = GeziraSvg.render(GeziraSvg.parse(svg("evenodd")), 20, 20).canvas
        val nonZero = GeziraSvg.render(GeziraSvg.parse(svg("nonzero")), 20, 20).canvas
        assertEquals(0f, evenOdd.alpha(10, 10))
        assertEquals(1f, evenOdd.alpha(4, 4), 1e-6f)
        assertEquals(1f, nonZero.alpha(10, 10), 1e-6f)
        val skia = GeziraSvg.skia(svg("evenodd"), 20, 20)
        assertEquals(0f, skia[4 * (10 * 20 + 10) + 3])
    }

    @Test
    fun tigerAgainstSkia() {
        val text = GeziraSvg.tiger()
        val doc = GeziraSvg.parse(text)
        println(doc)
        // 240 paths, one of them "M-65.4,9z", which paints nothing.
        assertEquals(239, doc.shapes.size)
        assertEquals(2184, doc.segments)
        assertTrue(doc.skipped.isEmpty(), "skipped ${doc.skipped}")
        val n = 200
        val r = GeziraSvg.render(doc, n, n)
        println(r)
        val skia = GeziraSvg.skia(text, n, n)
        val (worst, mean) = r.canvas.error(skia)
        println("tiger %d px: max %.4f mean %.5f".format(n, worst, mean))
        assertTrue(mean < 0.02, "mean channel difference $mean")
        var painted = 0
        for (i in 0 until n * n) if (r.canvas.rgba[4 * i + 3] > 0.5f) painted++
        assertTrue(painted > n * n / 4, "the tiger covers a good part of the canvas: $painted")
    }
}
