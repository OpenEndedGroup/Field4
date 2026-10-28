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
}
