package afield.geometry.euclid

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElementsTest {
    private fun p(x: Number, y: Number) = point2(x, y)
    private fun d(a: Point2, b: Point2) = (b - a).length
    private fun angle(v: Point2, a: Point2, b: Point2): Double {
        val u = (a - v).normalized(); val w = (b - v).normalized()
        return acos((u dot w).coerceIn(-1.0, 1.0))
    }
    private val tol = 1e-9

    private fun <T> drawsSomething(c: Construction<T>) {
        assertTrue(c.steps.isNotEmpty() && c.figures.isNotEmpty())
        val bare = ByrneStyle(constructionLines = Pen(0.6), cited = Pen(0.5), labels = false, paper = null)
        val everything = c.toFLines(style = bare)
        val drawable = c.steps.sumOf { s -> s.figures.count { s.cited == null || it.shape !is Point2 } }
        assertEquals(drawable, everything.size)
        assertTrue(c.toFLines(upTo = 1, style = bare).size < everything.size)
        assertTrue(c.toFLines(style = bare.copy(cited = null)).size <= everything.size) // cited steps hidden
        assertTrue(c.markdown().startsWith("**${c.proposition}.**"))
    }

    @Test fun i1EquilateralTriangle() {
        val c = Elements.prop1(Segment2(p(0, 0), p(10, 0)))
        val t = c.result
        assertEquals(10.0, d(t.a, t.c), tol); assertEquals(10.0, d(t.b, t.c), tol)
        assertTrue(t.c.y > 0)
        assertTrue(Elements.prop1(Segment2(p(0, 0), p(10, 0)), side = -1).result.c.y < 0)
        drawsSomething(c)
    }

    @Test fun i2PlacesAnEqualLine() {
        val c = Elements.prop2(p(3, 7), Segment2(p(10, 0), p(14, 3)))
        assertEquals(p(3, 7), c.result.a)
        assertEquals(5.0, c.result.length, tol)
        assertTrue(c.steps.any { it.text.startsWith("By I.1:") })
        drawsSomething(c)
    }

    @Test fun i3CutsOffTheLess() {
        val ab = Segment2(p(0, 0), p(20, 0))
        val r = Elements.prop3(ab, Segment2(p(5, 5), p(8, 9))).result
        assertEquals(5.0, r.length, tol)
        assertEquals(0.0, ab.distanceTo(r.b), tol)
    }

    @Test fun i9BisectsAnAngle() {
        val a = p(1, 1); val b = p(9, 2); val c = p(3, 8)
        val ray = Elements.prop9(a, b, c).result
        val f = ray.at(1.0)
        assertEquals(angle(a, b, f), angle(a, f, c), tol)
    }

    @Test fun i10BisectsALine() {
        val ab = Segment2(p(2, 3), p(11, -4))
        val m = Elements.prop10(ab).result
        assertEquals(0.0, d(m, ab.midpoint), tol)
    }

    @Test fun i11AndI12Perpendiculars() {
        val ab = Segment2(p(0, 0), p(8, 4))
        val up = Elements.prop11(ab, p(4, 2)).result
        assertEquals(0.0, up.direction dot ab.direction, tol)
        assertTrue(ab.line.side(up.at(1.0)) > 0)
        val ch = Elements.prop12(Line2(p(0, 0), p(1, 0)), p(3, 5)).result
        assertEquals(0.0, d(ch.b, p(3, 0)), tol)
    }

    @Test fun i23CopiesAnAngle() {
        val v = p(0, 0); val dd = p(5, 0); val e = p(2, 4)
        val ray = Elements.prop23(Ray2(p(10, 10), p(0, 1)), v, dd, e).result
        assertEquals(angle(v, dd, e), angle(ray.origin, p(10, 11), ray.at(1.0)), 1e-9)
    }

    @Test fun i31DrawsAParallel() {
        val bc = Segment2(p(0, 0), p(10, 2))
        val l = Elements.prop31(p(3, 6), bc).result
        assertEquals(0.0, l.direction.normalized() cross bc.direction.normalized(), tol)
        assertEquals(p(3, 6), l.point)
    }

    @Test fun i46DescribesASquare() {
        val q = Elements.prop46(Segment2(p(1, 1), p(4, 5))).result
        q.sides.forEach { assertEquals(5.0, it.length, 1e-9) }
        assertEquals(5.0 * sqrt(2.0), d(q.a, q.c), 1e-9)
    }

    @Test fun iii1FindsTheCentre() {
        val f = Elements.book3prop1(Circle2(p(7, -3), 4.0)).result
        assertEquals(0.0, d(f, p(7, -3)), 1e-9)
    }

    @Test fun ivInscribeAndCircumscribe() {
        val t = Triangle2(p(0, 0), p(3, 0), p(0, 4))
        val inc = Elements.book4prop4(t).result
        assertEquals(0.0, d(inc.center, p(1, 1)), 1e-9); assertEquals(1.0, inc.radius, 1e-9)
        val circ = Elements.book4prop5(t).result
        assertEquals(0.0, d(circ.center, p(1.5, 2)), 1e-9); assertEquals(2.5, circ.radius, 1e-9)
    }

    @Test fun vi13MeanProportional() {
        val bd = Elements.book6prop13(Segment2(p(0, 0), p(13, 0)), p(4, 0)).result
        assertEquals(6.0, bd.length, 1e-9) // sqrt(4 * 9)
        assertTrue(abs(bd.b.x - 4) < 1e-9)
    }

    @Test fun describesItself() {
        val text = Elements.prop1(Segment2(p(0, 0), p(1, 0))).toString()
        assertTrue(text.startsWith("I.1. On a given finite straight line"))
    }

    private val phi = (1 + sqrt(5.0)) / 2

    @Test fun i47Pythagoras() {
        val c = Elements.prop47(Triangle2(p(0, 0), p(4, 0), p(0, 3)))
        val (bc, ba, ac) = c.result
        assertEquals(25.0, bc.polygon.area, 1e-9); assertEquals(16.0, ba.polygon.area, 1e-9); assertEquals(9.0, ac.polygon.area, 1e-9)
        // each square lies outside the triangle
        assertTrue(p(4.0 / 3, 1.0) !in bc.polygon && p(4.0 / 3, 1.0) !in ba.polygon && p(4.0 / 3, 1.0) !in ac.polygon)
        drawsSomething(c)
    }

    @Test fun iiGnomonsEqualTheirRectangles() {
        val five = Elements.book2prop5(Segment2(p(0, 0), p(10, 0)))
        assertEquals(7.0 * 3.0, five.result.area, 1e-9) // AD·DB with D at 7
        val six = Elements.book2prop6(Segment2(p(0, 0), p(10, 0)))
        assertEquals(13.5 * 3.5, six.result.area, 1e-9) // AD·DB with D at 13.5
        drawsSomething(five); drawsSomething(six)
    }

    @Test fun ii11GoldenSection() {
        val h = Elements.book2prop11(Segment2(p(0, 0), p(10, 0))).result
        assertEquals(10.0 / phi, h.x, 1e-9); assertEquals(0.0, h.y, 1e-9)
        assertEquals(h.x * h.x, 10.0 * (10.0 - h.x), 1e-9)
    }

    @Test fun ii14SquaresARectangle() {
        val sq = Elements.book2prop14(Quadrilateral2(p(0, 0), p(9, 0), p(9, 4), p(0, 4))).result
        assertEquals(36.0, sq.polygon.area, 1e-9)
        sq.sides.forEach { assertEquals(6.0, it.length, 1e-9) }
    }

    @Test fun iii17Tangent() {
        val circle = Circle2(p(0, 0), 3.0)
        val ab = Elements.book3prop17(circle, p(5, 0)).result
        assertEquals(3.0, ab.b.length, 1e-9)
        assertEquals(0.0, (ab.b - circle.center) dot (ab.a - ab.b), 1e-9)
    }

    @Test fun ivGoldenTriangleAndPolygons() {
        val t = Elements.book4prop10(Segment2(p(0, 0), p(0, 10))).result
        assertEquals(10.0, d(t.a, t.c), 1e-9)
        assertEquals(2 * angle(t.a, t.b, t.c), angle(t.b, t.a, t.c), 1e-9)
        for ((n, c) in listOf(5 to Elements.book4prop11(Circle2(p(3, 4), 2.0)), 6 to Elements.book4prop15(Circle2(p(3, 4), 2.0)), 15 to Elements.book4prop16(Circle2(p(3, 4), 2.0)))) {
            val poly = c.result
            assertEquals(n, poly.points.size)
            val side = 2 * 2.0 * kotlin.math.sin(kotlin.math.PI / n)
            poly.edges.forEach { assertEquals(side, it.length, 1e-7) }
            poly.points.forEach { assertEquals(2.0, d(it, p(3, 4)), 1e-9) }
        }
    }

    @Test fun viProportions() {
        assertEquals(4.0, Elements.book6prop9(Segment2(p(0, 0), p(12, 0))).result.length, 1e-9)
        val cut = Elements.book6prop10(Segment2(p(0, 0), p(12, 0)), Segment2(p(0, 0), p(3, -6)), listOf(p(1, -2), p(2.5, -5))).result
        assertEquals(4.0, cut[0].x, 1e-9); assertEquals(10.0, cut[1].x, 1e-9)
        val ce = Elements.book6prop11(Segment2(p(0, 0), p(4, 0)), Segment2(p(0, 0), p(0, -6))).result
        assertEquals(9.0, ce.length, 1e-9) // 4 : 6 = 6 : 9
        val hl = Elements.book6prop12(Segment2(p(0, 50), p(2, 50)), Segment2(p(0, 60), p(5, 60)), Segment2(p(0, 70), p(3, 70))).result
        assertEquals(7.5, hl.length, 1e-9) // 2 : 5 = 3 : 7.5
        val e = Elements.book6prop30(Segment2(p(0, 0), p(10, 0))).result
        assertEquals(10.0 / phi, e.x, 1e-9)
    }

    @Test fun polygonsMeetLikeTheirEdges() {
        val square = Polygon2(p(0, 0), p(4, 0), p(4, 4), p(0, 4))
        assertEquals(2, square.intersect(Line2(p(-1, 2), p(1, 0))).points.size)
        assertEquals(1.0, square.distanceTo(Circle2(p(7, 2), 2.0)), 1e-9)
        assertTrue(p(2, 2) in square && p(5, 2) !in square)
        assertEquals(16.0, square.area, 1e-12)
    }

    @Test fun solutionsCountTheRealAlternatives() {
        val ab = Segment2(p(0, 0), p(160, 0))
        val one = Elements.solutions { prop1(ab) }
        assertEquals(2, one.size)
        assertEquals(Elements.prop1(ab).result, one.first().result) // the plain call is the first solution
        assertTrue(one.map { it.result.c.y }.let { ys -> ys.any { it > 0 } && ys.any { it < 0 } })

        // I.2: Proclus' four cases, every one a line from A as long as BC
        val bc = Segment2(p(90, 40), p(150, 100))
        val two = Elements.solutions { prop2(p(0, 0), bc) }
        assertEquals(4, two.size)
        two.forEach { assertEquals(p(0, 0), it.result.a); assertEquals(bc.length, it.result.length, 1e-9) }

        assertEquals(2, Elements.solutions { prop11(Segment2(p(0, 0), p(200, 0)), p(80, 0)) }.size)
        assertEquals(1, Elements.solutions { prop31(p(60, -90), Segment2(p(0, 0), p(200, 0))) }.size) // only one copy is parallel
        assertEquals(2, Elements.solutions { prop46(Segment2(p(0, 0), p(120, 0))) }.size)
        assertEquals(1, Elements.solutions { prop47(Triangle2(p(0, 0), p(120, 0), p(0, -90))) }.size) // the squares must stand outside
        assertEquals(2, Elements.solutions { book3prop17(Circle2(p(0, 0), 60.0), p(170, 40)) }.size) // two tangents
        assertEquals(2, Elements.solutions { book4prop10(Segment2(p(0, -150), p(0, 0))) }.size)
        assertEquals(1, Elements.solutions { book4prop16(Circle2(p(0, 0), 100.0)) }.size)
    }

    @Test fun chooseASolutionByWhereItLands() {
        val all = Elements.solutions { prop46(Segment2(p(0, 0), p(120, 0))) }
        assertTrue(all.nearest(p(60, 500)).location.y > 0)
        assertTrue(all.farthest(p(60, 500)).location.y < 0)
        val rays = Elements.solutions { prop11(Segment2(p(0, 0), p(200, 0)), p(80, 0)) }
        assertTrue(rays[0].location != rays[1].location)
    }
}
