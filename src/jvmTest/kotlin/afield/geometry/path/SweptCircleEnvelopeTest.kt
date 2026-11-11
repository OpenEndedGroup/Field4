package afield.geometry.path

import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The swept-circle envelope in `FLineThickening.kt` against cases with a closed-form answer: a
 * constant radius is the ordinary offset, a linear radius on a straight line is the tangent-line
 * "trapezoid pill", and a radius that grows faster than the centre moves has no envelope.
 */
class SweptCircleEnvelopeTest {

    private fun FLine.points(): List<Vec3> = nodes.map { Vec3(it.to) }

    /** Union of many discs along the stroke: the region the envelope should bound. */
    private fun reference(knots: List<Vec3>, perSegment: Int = 400, corners: List<Int> = emptyList()): FLine {
        val st = sweptCircleBeziers(knots, corners)
        val discs = ArrayList<Vec3>()
        for (c in st) for (j in 0..perSegment) {
            val t = j.toDouble() / perSegment
            val u = 1 - t
            fun b(k: (Vec3) -> Float) = u * u * u * k(c[0]) + 3 * u * u * t * k(c[1]) + 3 * u * t * t * k(c[2]) + t * t * t * k(c[3])
            discs += vec(b { it.x }.toFloat(), b { it.y }.toFloat(), b { it.z }.toFloat())
        }
        val gf = JtsCurveSplitter.GF
        val g = org.locationtech.jts.operation.union.UnaryUnionOp.union(discs.map {
            gf.createPoint(org.locationtech.jts.geom.Coordinate(it.x.toDouble(), it.y.toDouble())).buffer(it.z.toDouble(), 64)
        })
        return JtsCurveSplitter().geometryToFLine(g)
    }

    @Test
    fun constantRadiusIsTheOrdinaryOffset() {
        val line = FLine().moveTo(0f, 0f).cubicTo(60f, 120f, 180f, -80f, 240f, 40f)
        val env = line.bySweptCircleEnveloping(ConstantParameter(8f), samples = 300)
        val (l, r) = line.byBidirectionalOffsetting(8f, samples = 300)
        val a = env.left.points()
        val b = l.points()
        assertEquals(b.size, a.size)
        val worst = a.indices.maxOf { maxOf(a[it].distance(b[it]).toDouble(), env.right.points()[it].distance(r.points()[it]).toDouble()) }
        assertTrue(worst < 1e-3, "constant radius envelope differs from offset by $worst")
    }

    @Test
    fun linearRadiusOnALineIsTheTrapezoidPill() {
        val r0 = 10.0
        val r1 = 30.0
        val len = 100.0
        val env = sweptCircleStroke(listOf(vec(0f, 0f, r0.toFloat()), vec(len.toFloat(), 0f, r1.toFloat())), samplesPerSegment = 50, flatness = 0.01)
        // external tangent lines touch each circle at angle phi from +x, cos(phi) = -(r1 - r0)/len
        val s = (r1 - r0) / len
        val c = sqrt(1 - s * s)
        // every left-edge point lies on the line through (-r0 s, r0 c) with direction (len - (r1-r0) s, (r1-r0) c)
        val px = -r0 * s
        val py = r0 * c
        val dx = len - (r1 - r0) * s
        val dy = (r1 - r0) * c
        val dl = hypot(dx, dy)
        val off = env.left.points().maxOf { p -> abs((p.x - px) * dy - (p.y - py) * dx) / dl }
        assertTrue(off < 1e-3, "left edge strays $off from the tangent line")
        // area: the convex hull of the two end circles, drawn finely
        val gf = JtsCurveSplitter.GF
        val hull = org.locationtech.jts.operation.union.UnaryUnionOp.union(listOf(
            gf.createPoint(org.locationtech.jts.geom.Coordinate(0.0, 0.0)).buffer(r0, 256),
            gf.createPoint(org.locationtech.jts.geom.Coordinate(len, 0.0)).buffer(r1, 256),
        )).convexHull().area
        val got = abs(env.shell.area2D().toDouble())
        assertTrue(abs(got - hull) / hull < 2e-3, "pill area $got vs exact $hull")
        assertTrue(sweptCircleDegenerateSpans(listOf(vec(0f, 0f, 10f), vec(100f, 0f, 30f))).isEmpty())
    }

    @Test
    fun radiusOutrunningTheCentreIsDetected() {
        // centre moves 10, radius grows 40: |r'| > |P'| along the whole span
        val knots = listOf(vec(0f, 0f, 5f), vec(10f, 0f, 45f))
        val spans = sweptCircleDegenerateSpans(knots)
        assertEquals(1, spans.size)
        assertEquals(0.0, spans[0].start, 1e-9)
        assertEquals(1.0, spans[0].endInclusive, 1e-9)
        val env = sweptCircleStroke(knots, flatness = 0.02)
        assertTrue(env.left.nodes.isEmpty(), "no envelope where the circle swallows itself")
        // the shell is the big end disc, which contains all the others
        val got = abs(env.shell.area2D().toDouble())
        assertTrue(abs(got - PI * 45 * 45) / (PI * 45 * 45) < 0.01, "degenerate shell area $got")
    }

    @Test
    fun degenerateSpanInsideAStrokeHasExactEnds() {
        // a pen that nearly stops while pressing: the middle knot is almost on top of its neighbours
        val knots = listOf(vec(0f, 0f, 4f), vec(40f, 0f, 6f), vec(41f, 0f, 20f), vec(42f, 0f, 6f), vec(80f, 0f, 4f))
        val spans = sweptCircleDegenerateSpans(knots)
        assertTrue(spans.isNotEmpty())
        val segs = sweptCircleBeziers(knots)
        for (sp in spans) for (t in listOf(sp.start, sp.endInclusive)) {
            val i = t.toInt().coerceAtMost(segs.lastIndex)
            val u = t - i
            val c = segs[i]
            fun d(k: (Vec3) -> Float): Double {
                val w = 1 - u
                return 3 * (w * w * (k(c[1]) - k(c[0])) + 2 * w * u * (k(c[2]) - k(c[1])) + u * u * (k(c[3]) - k(c[2])))
            }
            val g = d { it.x } * d { it.x } + d { it.y } * d { it.y } - d { it.z } * d { it.z }
            assertTrue(abs(g) < 1e-4, "|P'|^2 - r'^2 = $g at span end $t")
        }
        val env = sweptCircleStroke(knots, flatness = 0.02)
        val ref = reference(knots)
        val err = env.shell.outlineDistanceTo(ref)
        assertTrue(err < 0.3, "outline error against union of discs $err")
    }

    @Test
    fun envelopeBeatsTheDisplacedRibbonOnASwellingStroke() {
        val line = FLine().moveTo(0f, 0f).cubicTo(80f, 90f, 160f, -60f, 240f, 40f)
        val radius = parameterFromLambda { t, _ -> 2f + 26f * t * t * (1f - t) * 4f }
        val env = line.bySweptCircleEnveloping(radius, samples = 200, flatness = 0.02)
        val old = line.byVariableRibboning(parameterFromLambda { t, p -> 2f * radius.evaluate(t, p) }, samples = 200)
        val gf = JtsCurveSplitter.GF
        val ref = JtsCurveSplitter().geometryToFLine(org.locationtech.jts.operation.union.UnaryUnionOp.union(
            line.sampleSeries(1500).map {
                gf.createPoint(org.locationtech.jts.geom.Coordinate(it.point.x.toDouble(), it.point.y.toDouble()))
                    .buffer(radius.evaluate(it.t, it.point).toDouble(), 64)
            }))
        val eNew = env.shell.outlineDistanceTo(ref)
        val eOld = old.outlineDistanceTo(ref)
        println("swept envelope error $eNew, byVariableRibboning error $eOld")
        assertTrue(eNew < 0.5 && eNew * 5 < eOld, "new $eNew old $eOld")
    }

    /** A tablet-like stroke: a point every 0.6 units on a slow wave, pressure with sensor wobble. */
    private fun denseNoisyStroke(n: Int = 1000, noise: Double = 1.0): List<Vec3> {
        val rnd = java.util.Random(1)
        return (0 until n).map { i ->
            val s = i.toDouble() / (n - 1)
            val p = (0.15 + 0.85 * sin(PI * s).pow(0.7)) + noise * 0.03 * rnd.nextGaussian()
            vec(600 * s, 60 * sin(9 * s) + noise * 0.1 * rnd.nextGaussian(), 12 * p.coerceIn(0.0, 1.0).pow(1.75))
        }
    }

    @Test
    fun thinningKeepsTheStrokeAndDropsTheWobble() {
        val raw = denseNoisyStroke()
        val thin = thinPenSamples(raw, tolerance = 0.5)
        assertTrue(raw.first().x == thin.first().x && raw.last().x == thin.last().x)
        assertTrue(thin.size * 5 < raw.size, "thinned to ${thin.size} of ${raw.size}")
        val rawSpans = sweptCircleDegenerateSpans(raw).size
        val thinSpans = sweptCircleDegenerateSpans(thin).size
        assertTrue(rawSpans > 100 && thinSpans * 20 < rawSpans, "degenerate spans raw $rawSpans thinned $thinSpans")
        // against the stroke without the sensor noise, the thinned one is as close as the raw one
        val clean = sweptCircleStroke(denseNoisyStroke(noise = 0.0), samplesPerSegment = 4).shell
        val t0 = System.nanoTime()
        val a = sweptCircleStroke(raw, samplesPerSegment = 4).shell
        val t1 = System.nanoTime()
        val b = sweptCircleStroke(thin).shell
        val t2 = System.nanoTime()
        val eRaw = a.outlineDistanceTo(clean)
        val eThin = b.outlineDistanceTo(clean)
        println("thinPenSamples: ${raw.size} -> ${thin.size} knots, raw ${(t1 - t0) / 1e6} ms, thinned ${(t2 - t1) / 1e6} ms; from the noise-free stroke raw $eRaw thinned $eThin")
        assertTrue(eThin < 1.0 && eThin < eRaw, "thinned $eThin raw $eRaw")
        // one filled outline, no sliver rings
        assertEquals(1, b.pieces().size)
        // every dropped knot is within tolerance of the polyline that replaced it
        assertTrue(thinPenSamples(listOf(vec(0f, 0f, 1f), vec(5f, 0.4f, 1f), vec(10f, 0f, 1f)), 0.5).size == 2)
        assertTrue(thinPenSamples(listOf(vec(0f, 0f, 1f), vec(5f, 0f, 1.6f), vec(10f, 0f, 1f)), 0.5, widthSmoothing = 0.0).size == 3)
        // the radius is averaged along the path; a constant radius stays put
        assertTrue(thinPenSamples(raw.map { vec(it.x, it.y, 5f) }).all { abs(it.z - 5f) < 1e-4 })
    }

    @Test
    fun cornersMakeARoundJoin() {
        // a "v" at constant radius: with the corner kept, the stroke is two straight pills joined by
        // the corner's circle, which is the round-joined buffer of the polyline
        val knots = listOf(vec(0f, 0f, 4f), vec(40f, 80f, 4f), vec(80f, 0f, 4f))
        assertEquals(listOf(1), penCorners(knots))
        assertTrue(penCorners(knots, degrees = 150.0).isEmpty())
        val poly = FLine().moveTo(0f, 0f).lineTo(40f, 80f).lineTo(80f, 0f)
        val exact = poly.byJtsBuffering(4.0, quadrantSegments = 64)
        val sharp = sweptCircleStroke(knots, corners = listOf(1), flatness = 0.02)
        val smooth = sweptCircleStroke(knots, flatness = 0.02)
        val eSharp = sharp.shell.outlineDistanceTo(exact)
        val eSmooth = smooth.shell.outlineDistanceTo(exact)
        println("corner: kept $eSharp, smoothed $eSmooth")
        assertTrue(eSharp < 0.1 && eSmooth > 3, "kept $eSharp smoothed $eSmooth")
        // the flanks break at the corner, so each flank is two pieces
        assertEquals(2, sharp.left.pieces().size)
        // degenerate spans and Béziers agree with the stroke about the corner
        assertTrue(sweptCircleDegenerateSpans(knots, listOf(1)).isEmpty())
        val b = sweptCircleBeziers(knots, listOf(1))
        assertTrue(b[0][2].distance(vec(40f - 40f / 3, 80f - 80f / 3, 4f)) < 1e-4, "${b[0][2]}")
    }

    @Test
    fun tightTurnWithAFoldedInnerEdgeIsStillTheUnionOfDiscs() {
        // radius 15 on a path that turns at radius 10: the inner flank folds back on itself
        val knots = (0..24).map { i ->
            val a = PI * i / 16
            vec((10 * cos(a)).toFloat(), (10 * sin(a)).toFloat(), 15f)
        }
        val env = sweptCircleStroke(knots, flatness = 0.02)
        val err = env.shell.outlineDistanceTo(reference(knots))
        assertTrue(err < 0.3, "folded turn outline error $err")
    }

    @Test
    fun shellTessellatesWithItsHole() {
        // the Vulkan path fills an FLine by FLine.tessellate; a closed ring stroke must come out as
        // an annulus, hole and all
        val knots = (0..48).map { i ->
            val a = 2 * PI * i / 48
            vec((60 * cos(a)).toFloat(), (60 * sin(a)).toFloat(), 8f)
        }
        val shell = sweptCircleStroke(knots, flatness = 0.05).shell
        var area = 0.0
        var centreCovered = false
        shell.tessellate { v, idx ->
            for (k in idx.indices step 3) {
                val (a, b, c) = Triple(idx[k], idx[k + 1], idx[k + 2])
                val ax = v[3 * a].toDouble(); val ay = v[3 * a + 1].toDouble()
                val bx = v[3 * b].toDouble(); val by = v[3 * b + 1].toDouble()
                val cx = v[3 * c].toDouble(); val cy = v[3 * c + 1].toDouble()
                area += abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) / 2
                val d1 = (bx - ax) * (0 - ay) - (by - ay) * (0 - ax)
                val d2 = (cx - bx) * (0 - by) - (cy - by) * (0 - bx)
                val d3 = (ax - cx) * (0 - cy) - (ay - cy) * (0 - cx)
                if ((d1 > 0 && d2 > 0 && d3 > 0) || (d1 < 0 && d2 < 0 && d3 < 0)) centreCovered = true
            }
        }
        val annulus = PI * (68.0 * 68.0 - 52.0 * 52.0)
        assertTrue(abs(area - annulus) / annulus < 0.01, "tessellated area $area vs annulus $annulus")
        assertTrue(!centreCovered, "the hole was filled")
    }
}
