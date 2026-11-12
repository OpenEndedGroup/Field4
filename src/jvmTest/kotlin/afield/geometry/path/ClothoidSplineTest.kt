package afield.geometry.path

import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `clothoidSplineFrom` is the G2 Euler-spiral spline. These measure the drawn polyline: curvature
 * from circles through consecutive samples, compared just before and just after each knot.
 */
class ClothoidSplineTest {

    private val perSegment = 200

    /** The drawn points: a closed line ends in a close node, which is not a point of its own. */
    private fun points(line: FLine, closed: Boolean): List<Vec3> =
        (if (closed) line.nodes.dropLast(1) else line.nodes).map { Vec3(it.to) }

    private fun curvatures(line: FLine, closed: Boolean = false): DoubleArray {
        val p = points(line, closed)
        // Points are floats; a circle through neighbours 8 samples apart keeps rounding out of the answer.
        val w = 8
        return DoubleArray(p.size) { i ->
            if (i < w || i > p.lastIndex - w) 0.0 else {
                val a = p[i - w]; val b = p[i]; val c = p[i + w]
                val cross = (b.x - a.x).toDouble() * (c.y - b.y) - (b.y - a.y).toDouble() * (c.x - b.x)
                2 * cross / (hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) *
                    hypot((c.x - b.x).toDouble(), (c.y - b.y).toDouble()) *
                    hypot((c.x - a.x).toDouble(), (c.y - a.y).toDouble()))
            }
        }
    }

    private val wave = (0..7).map { i -> vec(40.0 + i * 50, 130 + 70 * sin(i * 0.9), 0.0) }

    @Test
    fun passesThroughEveryKnot() {
        val line = clothoidSplineFrom(wave, samplesPerSegment = perSegment)
        for ((i, k) in wave.withIndex()) {
            val node = line.nodes[i * perSegment].to
            assertTrue(Vec3(node).sub(k).length() < 1e-3f, "knot $i missed: $node vs $k")
        }
    }

    @Test
    fun curvatureIsContinuousAtKnots() {
        val line = clothoidSplineFrom(wave, samplesPerSegment = perSegment)
        val k = curvatures(line)
        val kMax = k.maxOf { abs(it) }
        for (i in 1 until wave.size - 1) {
            val at = i * perSegment
            // curvature is linear inside a piece: extrapolate each side to the knot, then compare
            val left = 2 * k[at - 10] - k[at - 20]
            val right = 2 * k[at + 10] - k[at + 20]
            val jump = abs(left - right)
            assertTrue(jump < 0.01 * kMax, "knot $i curvature jump $jump (max $kMax)")
        }
    }

    @Test
    fun knotsOnACircleGiveTheCircle() {
        val r = 100.0
        val knots = (0 until 7).map { i -> val a = 2 * PI * i / 7 + 0.3 * sin(i * 1.7); vec(r * cos(a), r * sin(a), 0.0) }
        val line = clothoidSplineFrom(knots, closed = true, samplesPerSegment = perSegment)
        for (p in points(line, closed = true)) {
            val d = p.length()
            assertTrue(abs(d - r) < 1e-3 * r, "point $d from centre, expected $r")
        }
        val k = curvatures(line, closed = true).drop(8).dropLast(8)
        val spread = k.maxOf { it } - k.minOf { it }
        assertTrue(spread < 0.01 / r, "curvature varies by $spread on a circle")
    }

    @Test
    fun atMostOneInflectionPerSegment() {
        val shapes = listOf(
            wave to false,
            listOf(vec(0, 0, 0), vec(100, 40, 0), vec(160, 140, 0), vec(60, 200, 0), vec(-40, 120, 0)) to true,
            listOf(vec(0, 0, 0), vec(80, 0, 0), vec(80, 80, 0), vec(160, 80, 0), vec(160, 0, 0)) to false,
        )
        for ((knots, closed) in shapes) {
            val k = curvatures(clothoidSplineFrom(knots, closed = closed, samplesPerSegment = perSegment), closed)
            val segs = if (closed) knots.size else knots.size - 1
            for (s in 0 until segs) {
                val part = k.slice(s * perSegment + 10 until (s + 1) * perSegment - 10)
                // below this the sign is rounding in the measurement, not the curve
                val eps = 1e-3 * k.maxOf { abs(it) }
                val signs = part.filter { abs(it) > eps }.map { it > 0 }
                val changes = signs.zipWithNext().count { (a, b) -> a != b }
                assertTrue(changes <= 1, "segment $s of ${knots.size}-knot shape has $changes inflections")
                assertTrue(part.isNotEmpty())
            }
        }
    }

    @Test
    fun combAndPlotHaveOneToothPerSample() {
        val line = clothoidSplineFrom(wave)
        val comb = line.curvatureComb(samples = 50)
        assertTrue(comb.nodes.size >= 100, "comb has ${comb.nodes.size} nodes")
        val plot = line.curvaturePlot(width = 400.0, height = 100.0, samples = 80)
        assertTrue(plot.nodes.size == 80)
        assertTrue(plot.nodes.all { it.to.y in -0.01f..100.01f })
    }

    @Test
    fun plotOfACircleIsFlat() {
        // The spline carries exact curvature on its nodes, so the plot has no sampling ripple.
        val knots = (0 until 6).map { i -> val a = 2 * PI * i / 6 + 0.2 * sin(i * 2.3); vec(80 * cos(a), 80 * sin(a), 0.0) }
        val ys = clothoidSplineFrom(knots, closed = true).curvaturePlot(height = 100.0, samples = 200, maxCurvature = 0.02)
            .nodes.map { it.to.y.toDouble() }
        val spread = ys.maxOf { it } - ys.minOf { it }
        assertTrue(spread < 0.05, "plot of a circle varies by $spread")
    }

    @Test
    fun cubicCombFallsBackToMeasuredCurvature() {
        val comb = cubicSplineFrom(wave).curvatureComb(samples = 40)
        assertTrue(comb.nodes.size >= 80, "comb has ${comb.nodes.size} nodes")
    }

    /** Direction of travel between two drawn points. */
    private fun heading(a: Vec3, b: Vec3) = kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())

    /**
     * The tangent at the start (or end) of a spline: the first chord's heading is the tangent half a
     * sample in, so take back curvature × half the chord, from the curvature recorded on the node.
     */
    private fun startTangent(line: FLine): Double {
        val a = Vec3(line.nodes[0].to); val b = Vec3(line.nodes[1].to)
        return heading(a, b) - (line.nodes[0].properties["curvature"] as Double) * a.distance(b) / 2
    }
    private fun endTangent(line: FLine): Double {
        val drawn = line.nodes.filter { it !is FLine.Close }
        val a = Vec3(drawn[drawn.lastIndex - 1].to); val b = Vec3(drawn.last().to)
        return heading(a, b) + (drawn.last().properties["curvature"] as Double) * a.distance(b) / 2
    }

    private fun angleBetween(a: Double, b: Double): Double {
        var d = a - b
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        return abs(d)
    }

    @Test
    fun endDirectionsAreMet() {
        val line = clothoidSplineFrom(wave, samplesPerSegment = perSegment, startDirection = vec(0.0, 1.0, 0.0), endDirection = vec(1.0, -1.0, 0.0))
        assertTrue(line.nodes.drop(1).all { it.properties["curvature"] is Double }, "fell back to a cubic")
        val start = startTangent(line)
        val end = endTangent(line)
        assertTrue(angleBetween(start, PI / 2) < 1e-3, "start heading $start")
        assertTrue(angleBetween(end, -PI / 4) < 1e-3, "end heading $end")
        // the interior knots stay curvature-continuous
        val k = curvatures(line)
        val kMax = k.maxOf { abs(it) }
        for (i in 1 until wave.size - 1) {
            val at = i * perSegment
            val jump = abs((2 * k[at - 10] - k[at - 20]) - (2 * k[at + 10] - k[at + 20]))
            assertTrue(jump < 0.01 * kMax, "knot $i curvature jump $jump (max $kMax)")
        }
    }

    @Test
    fun twoKnotsWithBothDirectionsAreOneEulerSpiral() {
        val a = vec(0.0, 0.0, 0.0)
        val b = vec(200.0, 50.0, 0.0)
        val line = clothoidSplineFrom(listOf(a, b), samplesPerSegment = perSegment, startDirection = vec(1.0, 0.0, 0.0), endDirection = vec(0.0, 1.0, 0.0))
        val p = points(line, closed = false)
        assertTrue(p.size == perSegment + 1, "expected one piece, got ${p.size} points")
        assertTrue(angleBetween(startTangent(line), 0.0) < 1e-3, "start ${startTangent(line)}")
        assertTrue(angleBetween(endTangent(line), PI / 2) < 1e-3, "end ${endTangent(line)}")
        // curvature is linear in arc length along a single Euler spiral
        val ks = line.nodes.map { it.properties["curvature"] as Double }
        val mid = ks[perSegment / 2]
        assertTrue(abs(mid - 0.5 * (ks.first() + ks.last())) < 1e-6 * ks.maxOf { abs(it) } + 1e-12, "curvature not linear: $mid")
    }

    @Test
    fun oneFixedEndLeavesTheOtherAnArc() {
        val line = clothoidSplineFrom(listOf(vec(0.0, 0.0, 0.0), vec(100.0, 0.0, 0.0), vec(150.0, 80.0, 0.0)), samplesPerSegment = perSegment, startDirection = vec(0.0, -1.0, 0.0))
        assertTrue(angleBetween(startTangent(line), -PI / 2) < 1e-3, "start ${startTangent(line)}")
        val ks = line.nodes.map { it.properties["curvature"] as Double }
        val last = ks.takeLast(perSegment)
        assertTrue(last.maxOf { it } - last.minOf { it } < 1e-9 + 1e-6 * last.maxOf { abs(it) }, "last piece is not an arc")
    }

    @Test
    fun cornersBreakTheTangentAndAdjacentCornersAreStraight() {
        // a "D": a straight back from (0,0) up to (0,200), a round bowl through three points
        val knots = listOf(vec(0.0, 0.0, 0.0), vec(0.0, 200.0, 0.0), vec(90.0, 190.0, 0.0), vec(140.0, 100.0, 0.0), vec(90.0, 10.0, 0.0))
        val line = clothoidSplineFrom(knots, closed = true, samplesPerSegment = perSegment, corners = listOf(0, 1))
        val p = points(line, closed = true)
        assertTrue(p.first().distance(knots[0]) < 1e-3f, "closed shape with corners starts at its first corner")
        // the back is one straight segment
        assertTrue(p[1].distance(knots[1]) < 1e-3f, "back is not a single straight segment: ${p[1]}")
        // the bowl leaves the top corner turning, not continuing the back's direction
        val back = heading(p[0], p[1])
        val out = heading(p[1], p[2])
        assertTrue(angleBetween(back, out) > 0.3, "no corner at the top: $back vs $out")
        // every knot is on the line, and the bowl is curvature-continuous at its interior knots
        for (k in knots) assertTrue(p.any { it.distance(k) < 1e-3f }, "knot $k missed")
        val ks = line.nodes.filter { it !is FLine.Close }.map { it.properties["curvature"] as Double }
        for (i in listOf(2, 3, 4)) {
            val at = 1 + (i - 1) * perSegment
            assertTrue(abs(ks[at - 1] - ks[at + 1]) < 0.01 * ks.maxOf { abs(it) }, "bowl knot $i curvature jump")
        }
        // the bowl ends meet the corners as arcs
        val bowl = ks.subList(2, 2 + perSegment)
        assertTrue(bowl.maxOf { it } - bowl.minOf { it } < 1e-6 * bowl.maxOf { abs(it) } + 1e-12, "first bowl piece is not an arc")
    }

    @Test
    fun cornerIndicesSurviveRepeatedPoints() {
        val knots = listOf(vec(0.0, 0.0, 0.0), vec(0.0, 0.0, 0.0), vec(100.0, 0.0, 0.0), vec(100.0, 80.0, 0.0), vec(0.0, 120.0, 0.0))
        // index 2 is (100, 0) in the caller's list even though index 1 repeats index 0
        val line = clothoidSplineFrom(knots, samplesPerSegment = 20, corners = listOf(2))
        val p = points(line, closed = false)
        // the stretch before the corner has two knots, so it is one straight segment ending at the corner
        assertTrue(p[1].distance(knots[2]) < 1e-3f, "corner knot is not the second point: ${p[1]}")
        val at = 1
        val before = heading(p[at - 1], p[at])
        val after = heading(p[at], p[at + 1])
        assertTrue(angleBetween(before, after) > 0.3, "corner smoothed away")
    }

    @Test
    fun hundredsOfKnotsSolve() {
        val n = 600
        val knots = (0 until n).map { i ->
            val a = 2 * PI * i / n
            vec(300 * cos(a) + 40 * cos(7 * a), 300 * sin(a) + 40 * sin(5 * a), 0.0)
        }
        clothoidSplineFrom(knots, closed = true, samplesPerSegment = 4)
        val t0 = System.nanoTime()
        val line = clothoidSplineFrom(knots, closed = true, samplesPerSegment = 4)
        val ms = (System.nanoTime() - t0) / 1e6
        println("clothoidSplineFrom: $n knots closed in $ms ms")
        assertTrue(line.nodes.drop(1).filter { it !is FLine.Close }.all { it.properties["curvature"] is Double }, "$n knots fell back to a cubic")
        // generous: a dense solve took seconds here; the banded one takes tens of milliseconds
        assertTrue(ms < 3000, "$n knots took $ms ms")
        val open = clothoidSplineFrom(knots, samplesPerSegment = 4)
        assertTrue(open.nodes.drop(1).all { it.properties["curvature"] is Double }, "open $n knots fell back")
    }

    @Test
    fun manyKnotsOnACircleGiveTheCircle() {
        val r = 100.0
        val knots = (0 until 240).map { i -> val a = 2 * PI * i / 240 + 0.01 * sin(i * 1.7); vec(r * cos(a), r * sin(a), 0.0) }
        val line = clothoidSplineFrom(knots, closed = true, samplesPerSegment = 8)
        for (p in points(line, closed = true)) assertTrue(abs(p.length() - r) < 1e-3 * r, "point ${p.length()} from centre")
    }

    @Test
    fun combFollowsAScaledOrMirroredLine() {
        val built = clothoidSplineFrom(wave)
        val doubled = built * 2.0
        val rebuilt = clothoidSplineFrom(wave.map { vec(it.x * 2.0, it.y * 2.0, 0.0) })
        val a = doubled.curvaturePlot(height = 100.0, samples = 60, maxCurvature = 0.02).nodes.map { it.to.y.toDouble() }
        val b = rebuilt.curvaturePlot(height = 100.0, samples = 60, maxCurvature = 0.02).nodes.map { it.to.y.toDouble() }
        val worst = a.zip(b).maxOf { (x, y) -> abs(x - y) }
        assertTrue(worst < 0.5, "scaled line's curvature plot differs from a rebuilt one by $worst")
        // a mirror flips the sign of the curvature
        val mirrored = built * vec(-1.0, 1.0, 1.0)
        val m = mirrored.curvaturePlot(height = 100.0, samples = 60, maxCurvature = 0.04).nodes.map { 50 - it.to.y.toDouble() }
        val o = built.curvaturePlot(height = 100.0, samples = 60, maxCurvature = 0.04).nodes.map { 50 - it.to.y.toDouble() }
        assertTrue(m.zip(o).maxOf { (x, y) -> abs(x + y) } < 0.5, "mirrored curvature is not the negative")
    }

    /** Curvature of the 3D polyline from circles through points [w] samples apart. */
    private fun curvatures3D(p: List<Vec3>, w: Int = 8): DoubleArray = DoubleArray(p.size) { i ->
        if (i < w || i > p.lastIndex - w) 0.0 else {
            val a = Vec3(p[i - w]); val b = Vec3(p[i]); val c = Vec3(p[i + w])
            val ab = Vec3(b).sub(a); val bc = Vec3(c).sub(b); val ac = Vec3(c).sub(a)
            2.0 * Vec3(ab).cross(bc).length() / (ab.length().toDouble() * bc.length() * ac.length())
        }
    }

    @Test
    fun knotsOnAHelixGiveTheHelix() {
        // two turns, unevenly spaced: plan view a circle, height rising evenly with angle
        val angles = (0 until 13).map { i -> 4 * PI * i / 12 + 0.2 * sin(i * 1.3) }
        val knots = angles.map { a -> vec(100 * cos(a), 100 * sin(a), 15 * a) }
        val line = clothoidSplineFrom(knots, samplesPerSegment = perSegment)
        var turned = 0.0
        var last = 0.0
        for (p in points(line, closed = false)) {
            val r = hypot(p.x.toDouble(), p.y.toDouble())
            assertTrue(abs(r - 100) < 0.1, "point $r from the axis")
            var a = kotlin.math.atan2(p.y.toDouble(), p.x.toDouble())
            while (a < last - PI) a += 2 * PI
            turned = a; last = a
            assertTrue(abs(p.z - 15 * turned) < 0.05, "height ${p.z} at angle $turned, expected ${15 * turned}")
        }
    }

    @Test
    fun heightsKeepCurvatureContinuousInSpace() {
        val knots = wave.mapIndexed { i, k -> vec(k.x, k.y, 40 * cos(i * 1.1)) }
        val line = clothoidSplineFrom(knots, samplesPerSegment = perSegment)
        val p = points(line, closed = false)
        for ((i, k) in knots.withIndex()) assertTrue(p[i * perSegment].distance(k) < 1e-3f, "knot $i missed")
        val k = curvatures3D(p)
        val kMax = k.maxOf { abs(it) }
        for (i in 1 until knots.size - 1) {
            val at = i * perSegment
            // smooth on both sides: extrapolate each side quadratically to the knot
            val left = 3 * k[at - 10] - 3 * k[at - 20] + k[at - 30]
            val right = 3 * k[at + 10] - 3 * k[at + 20] + k[at + 30]
            assertTrue(abs(left - right) < 0.02 * kMax, "knot $i 3D curvature jump ${abs(left - right)} (max $kMax)")
        }
    }

    @Test
    fun aConstantHeightIsKeptExactly() {
        val flat = clothoidSplineFrom(wave.map { vec(it.x, it.y, 5.0) })
        val plan = clothoidSplineFrom(wave.map { afield.math.linalg.vec(it.x, it.y) }, z = 5.0)
        assertTrue(flat.nodes.all { it.to.z == 5f })
        assertTrue(flat.nodes.zip(plan.nodes).all { (a, b) -> Vec3(a.to).distance(b.to) == 0f })
    }

    @Test
    fun aTiltedCircleIsFoundInItsOwnPlane() {
        // seven points on a radius-100 circle, in a plane tilted 50 degrees about X and moved
        val tilt = 50 * PI / 180
        val centre = vec(30.0, -20.0, 70.0)
        val knots = (0 until 7).map { i ->
            val a = 2 * PI * i / 7 + 0.3 * sin(i * 1.7)
            val x = 100 * cos(a); val y = 100 * sin(a)
            vec(centre.x + x, centre.y + y * cos(tilt), centre.z + y * sin(tilt))
        }
        val normal = vec(0.0, -sin(tilt), cos(tilt))
        val line = clothoidSplineFrom(knots, closed = true, samplesPerSegment = 60, planeNormal = null)
        for (p in points(line, closed = true)) {
            val d = Vec3(p).sub(centre)
            assertTrue(abs(d.dot(normal)) < 0.05, "point ${d.dot(normal)} off the plane")
            assertTrue(abs(d.length() - 100) < 0.1, "point ${d.length()} from the centre")
        }
    }

    @Test
    fun aThreeDimensionalStartDirectionSetsTheClimb() {
        val knots = listOf(vec(0.0, 0.0, 0.0), vec(100.0, 40.0, 10.0), vec(200.0, 0.0, 0.0))
        val line = clothoidSplineFrom(knots, samplesPerSegment = perSegment, startDirection = vec(1.0, 0.0, 1.0))
        val p = points(line, closed = false)
        val d = Vec3(p[1]).sub(p[0])
        val climb = d.z / hypot(d.x.toDouble(), d.y.toDouble())
        assertTrue(abs(climb - 1) < 0.02, "climb $climb at the start, expected 1")
        assertTrue(abs(d.y) < 0.02 * d.x, "heading not along X: $d")
    }

    // ---- S9 weights ----

    private fun recorded(line: FLine): List<Double> = line.nodes.filter { it !is FLine.Close }.map { it.properties["curvature"] as Double }

    private val roundish = listOf(vec(0, 0, 0), vec(120, -20, 0), vec(200, 60, 0), vec(170, 170, 0), vec(40, 190, 0), vec(-40, 100, 0))

    @Test
    fun weightOneIsTheEulerSpline() {
        // the weight-1 path is the unchanged Euler code: an explicit weight changes nothing
        val a = clothoidSplineFrom(wave, samplesPerSegment = perSegment)
        val b = clothoidSplineFrom(wave, samplesPerSegment = perSegment, weight = 1.0)
        assertTrue(a.nodes.zip(b.nodes).all { (x, y) -> x.to == y.to })
        // and the general S9 solver agrees with it as the weight approaches 1
        val c = clothoidSplineFrom(wave, samplesPerSegment = perSegment, weight = 0.99999)
        val worst = a.nodes.zip(c.nodes).maxOf { (x, y) -> Vec3(x.to).sub(y.to).length().toDouble() }
        assertTrue(worst < 0.01, "weight 0.99999 strays $worst from weight 1")
    }

    @Test
    fun weightedSplinesAreG2AndPassThroughKnots() {
        val n = 4000
        for (w in listOf(0.8, 0.6, 0.4, 0.25)) {
            for ((knots, closed) in listOf(wave to false, roundish to true)) {
                val line = clothoidSplineFrom(knots, closed = closed, samplesPerSegment = n, weight = w)
                val k = recorded(line)
                val kMax = k.maxOf { abs(it) }
                val segs = if (closed) knots.size else knots.size - 1
                for (i in 1 until segs) {
                    val at = i * n
                    assertTrue(Vec3(line.nodes[at].to).sub(knots[i]).length() < 1e-3f, "w=$w knot $i missed")
                    val jump = abs(k[at] - k[at + 1])
                    assertTrue(jump < 0.01 * kMax, "w=$w knot $i curvature jump $jump (max $kMax)")
                }
                // recorded curvature is the drawn shape's: compare with circles through samples mid-segment
                val coarse = clothoidSplineFrom(knots, closed = closed, samplesPerSegment = perSegment, weight = w)
                val measured = curvatures(coarse, closed)
                val rec = recorded(coarse)
                for (s in 0 until segs) {
                    val mid = s * perSegment + perSegment / 2
                    assertTrue(abs(measured[mid] - rec[mid]) < 0.02 * kMax, "w=$w seg $s: measured ${measured[mid]} recorded ${rec[mid]}")
                }
            }
        }
    }

    @Test
    fun weightedSegmentsHaveAtMostOneInflection() {
        val shapes = listOf(wave to false, roundish to true,
            listOf(vec(0, 0, 0), vec(80, 0, 0), vec(80, 80, 0), vec(160, 80, 0), vec(160, 0, 0)) to false)
        for (w in listOf(0.7, 0.4, 0.2)) for ((knots, closed) in shapes) {
            val k = recorded(clothoidSplineFrom(knots, closed = closed, samplesPerSegment = perSegment, weight = w))
            val segs = if (closed) knots.size else knots.size - 1
            for (s in 0 until segs) {
                val part = k.slice(s * perSegment + 1..(s + 1) * perSegment)
                val eps = 1e-9 * k.maxOf { abs(it) }
                val changes = part.filter { abs(it) > eps }.map { it > 0 }.zipWithNext().count { (a, b) -> a != b }
                assertTrue(changes <= 1, "w=$w segment $s has $changes inflections")
                // curvature is monotone inside a segment, so its extremes are at the knots
                val d = part.zipWithNext { a, b -> b - a }.filter { abs(it) > 1e-12 }
                assertTrue(d.all { it > 0 } || d.all { it < 0 }, "w=$w segment $s curvature not monotone")
            }
        }
    }

    @Test
    fun weightedCircleStaysACircle() {
        val r = 100.0
        val knots = (0 until 7).map { i -> val a = 2 * PI * i / 7 + 0.3 * sin(i * 1.7); vec(r * cos(a), r * sin(a), 0.0) }
        for (w in listOf(0.7, 0.4, 0.2)) {
            val line = clothoidSplineFrom(knots, closed = true, samplesPerSegment = perSegment, weight = w)
            for (p in points(line, closed = true)) assertTrue(abs(p.length() - r) < 1e-3 * r, "w=$w point ${p.length()} from centre")
            val k = recorded(line)
            val spread = k.maxOf { it } - k.minOf { it }
            assertTrue(spread < 1e-4 / r, "w=$w curvature varies by $spread on a circle")
        }
    }

    /** Convex outlines with uneven spacing where weight 1's curvature dips below zero. */
    private val convexEggs = listOf(
        listOf(vec(0, 0, 0), vec(110, -5, 0), vec(190, 35, 0), vec(190, 65, 0), vec(110, 105, 0), vec(0, 100, 0), vec(-30, 50, 0)),
        listOf(vec(0, 0, 0), vec(120, 0, 0), vec(200, 40, 0), vec(200, 60, 0), vec(120, 100, 0), vec(0, 100, 0), vec(-30, 50, 0)),
    )

    @Test
    fun lowWeightsKeepConvexKnotsConvex() {
        // The first weight family (flat middle, both ends steep) passed every per-piece test and
        // still put inflections into these outlines; this is the check that caught it.
        for (knots in convexEggs) {
            val euler = recorded(clothoidSplineFrom(knots, closed = true))
            assertTrue(euler.minOf { it } < 0, "weight 1 no longer dips on this egg: pick a harder one")
            for (w in listOf(0.5, 0.4, 0.3, 0.2)) {
                val k = recorded(clothoidSplineFrom(knots, closed = true, weight = w))
                assertTrue(k.all { it > 0 }, "w=$w: curvature changes sign (min ${k.minOf { it }}) on convex knots")
            }
        }
    }

    @Test
    fun perPieceWeightKeepsConvexKnotsConvexAndIsG2() {
        val n = 2000
        for (knots in convexEggs) {
            val line = clothoidSplineFrom(knots, closed = true, samplesPerSegment = n, weight = null)
            val k = recorded(line)
            assertTrue(k.all { it > 0 }, "weight=null: curvature changes sign (min ${k.minOf { it }})")
            val kMax = k.maxOf { abs(it) }
            for (i in 1 until knots.size) {
                assertTrue(Vec3(line.nodes[i * n].to).sub(knots[i]).length() < 1e-3f, "knot $i missed")
                assertTrue(abs(k[i * n] - k[i * n + 1]) < 0.01 * kMax, "knot $i curvature jump")
            }
        }
        // an S-shaped wave keeps Euler pieces: weight=null draws what weight 1 draws, near enough
        val a = clothoidSplineFrom(wave, samplesPerSegment = perSegment)
        val b = clothoidSplineFrom(wave, samplesPerSegment = perSegment, weight = null)
        val worst = a.nodes.zip(b.nodes).maxOf { (x, y) -> Vec3(x.to).sub(y.to).length().toDouble() }
        assertTrue(worst < 2.0, "weight=null moved the wave by $worst")
    }

    @Test
    fun hobbyPassesThroughKnotsAndRoundsACircle() {
        val r = 100.0
        val knots = (0 until 8).map { i -> val a = 2 * PI * i / 8 + 0.2 * sin(i * 1.7); afield.math.linalg.Vec2((r * cos(a)).toFloat(), (r * sin(a)).toFloat()) }
        val line = hobbySplineFrom(knots, closed = true)
        val ends = line.nodes.filter { it !is FLine.Close }.map { Vec3(it.to) }
        for ((i, k) in knots.withIndex()) assertTrue(hypot((ends[i].x - k.x).toDouble(), (ends[i].y - k.y).toDouble()) < 1e-3, "knot $i missed")
        val c = line.cursor()
        val total = c.lengthD().toDouble()
        for (i in 0 until 200) {
            c.setD((total * i / 200).toFloat())
            val d = c.position().length()
            assertTrue(abs(d - r) < 0.01 * r, "Hobby point $d from centre, expected about $r")
        }
        val open = hobbySplineFrom(wave.map { afield.math.linalg.Vec2(it.x, it.y) })
        assertTrue(open.nodes.size == wave.size)
    }
}
