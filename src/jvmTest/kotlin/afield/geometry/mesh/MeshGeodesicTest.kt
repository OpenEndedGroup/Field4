package afield.geometry.mesh

import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Heat-method geodesics ([HeatGeodesics]) against distances known in closed form, and the toolpaths built on them. */
class MeshGeodesicTest {

    /** Great-circle distance on a unit icosphere: mean and max error against `acos(p·s)`, by subdivision level. */
    @Test
    fun heatGeodesicsMatchGreatCircleDistanceOnASphere() {
        var previousMean = Double.POSITIVE_INFINITY
        for (level in 3..5) {
            val sphere = icosphereMesh(level, 1.0)
            val source = sphere.nearestVertex(0, 1, 0)
            val d = sphere.geodesicDistance(source)
            val sx = sphere.x(source).toDouble(); val sy = sphere.y(source).toDouble(); val sz = sphere.z(source).toDouble()
            var sum = 0.0; var worst = 0.0
            for (i in 0 until sphere.vertexCount) {
                val px = sphere.x(i).toDouble(); val py = sphere.y(i).toDouble(); val pz = sphere.z(i).toDouble()
                val l = sqrt(px * px + py * py + pz * pz)
                val exact = acos(((px * sx + py * sy + pz * sz) / l).coerceIn(-1.0, 1.0))
                val err = abs(d[i] - exact)
                sum += err; worst = maxOf(worst, err)
            }
            val mean = sum / sphere.vertexCount
            println("icosphere($level) verts=${sphere.vertexCount} mean|err|=%.5f (%.3f%% of pi) max|err|=%.5f".format(mean, 100 * mean / PI, worst))
            assertTrue(mean < 0.02 * PI, "level $level: mean error $mean is over 2% of the half-circumference")
            assertTrue(mean < previousMean, "refining should reduce the error: $mean after $previousMean")
            previousMean = mean
        }
    }

    /** One factorisation, several source sets: the multi-source field is the pointwise minimum of the singles, to within the method's error. */
    @Test
    fun multipleSourcesGiveTheNearestSourceDistance() {
        val sphere = icosphereMesh(4, 1.0)
        val heat = HeatGeodesics(sphere)
        val a = sphere.nearestVertex(0, 1, 0); val b = sphere.nearestVertex(0, -1, 0)
        val da = heat.distance(a); val db = heat.distance(b); val both = heat.distance(a, b)
        var worst = 0.0
        for (i in 0 until sphere.vertexCount) worst = maxOf(worst, abs(both[i] - minOf(da[i], db[i])))
        assertTrue(worst < 0.08, "two-source distance strays $worst from min of the singles")
        assertEquals(0.0, both[a]); assertEquals(0.0, both[b])
        // The equator is pi/2 from both poles.
        val e = sphere.nearestVertex(1, 0, 0)
        assertTrue(abs(both[e] - PI / 2) < 0.05, "equator at ${both[e]}, expected ${PI / 2}")
    }

    /** On a flat square, distance from the boundary is the distance to the nearest side. */
    @Test
    fun distanceFromBoundaryOnAFlatSquare() {
        val square = heightfieldMesh(40, 40, 2.0, 2.0) { _, _ -> 0f }
        val d = square.geodesicDistanceFromBoundary()
        val centre = square.nearestVertex(0, 0, 0)
        assertTrue(abs(d[centre] - 1.0) < 0.05, "centre of a 2x2 square at ${d[centre]}, expected 1")
        val off = square.nearestVertex(0.5, 0, 0)
        assertTrue(abs(d[off] - 0.5) < 0.05, "x=0.5 at ${d[off]}, expected 0.5")
    }

    /** Isolines of latitude on a sphere are single closed rings of the right circumference; the spiral through them is continuous. */
    @Test
    fun isolinesAreClosedRingsAndTheSpiralIsContinuous() {
        val sphere = icosphereMesh(4, 1.0)
        val d = sphere.geodesicDistance(sphere.nearestVertex(0, 1, 0))
        val ring = sphere.isolines(d, PI / 2)
        assertEquals(1, ring.size, "the equator should be one ring")
        val r = ring.single()
        assertEquals(r.first(), r.last(), "a closed ring repeats its first point")
        var len = 0.0
        for (i in 1 until r.size) len += r[i].distance(r[i - 1]).toDouble()
        assertTrue(abs(len - 2 * PI) < 0.1, "equator length $len, expected ${2 * PI}")

        val levels = sphere.evenIsolines(d, 0.25)
        assertTrue(levels.all { it.size == 1 }, "one ring a level on a sphere")
        val spiral = spiralFromRings(levels.map { it.single() })
        var maxStep = 0.0
        for (i in 1 until spiral.size) maxStep = maxOf(maxStep, spiral[i].distance(spiral[i - 1]).toDouble())
        assertTrue(maxStep < 0.2, "spiral jumps $maxStep somewhere")
    }

    /**
     * The double spiral on a flat square: continuous, starts and ends at the outside side by side,
     * turns round near the centre, and the way out keeps clear of the way in (it runs between the
     * inward laps, not on top of them).
     */
    @Test
    fun doubleSpiralRunsOutBetweenTheWayIn() {
        val disc = heightfieldMesh(120, 120, 2.0, 2.0) { _, _ -> 0f }
        val fromCentre = disc.geodesicDistance(disc.nearestVertex(0, 0, 0))
        val spacing = 0.08
        val rings = disc.evenIsolines(fromCentre, spacing).map { it.maxBy { r -> r.size } }
            .filter { r -> r.all { p -> abs(p.x) < 0.98f && abs(p.z) < 0.98f } }
            .reversed()
        val path = doubleSpiralFromRings(rings)
        var maxStep = 0.0
        for (i in 1 until path.size) maxStep = maxOf(maxStep, path[i].distance(path[i - 1]).toDouble())
        assertTrue(maxStep < 0.1, "double spiral jumps $maxStep somewhere")

        fun radius(p: Vec3) = kotlin.math.hypot(p.x.toDouble(), p.z.toDouble())
        val outer = radius(rings.first().first())
        assertTrue(abs(radius(path.first()) - outer) < spacing, "starts on the outer ring")
        assertTrue(abs(radius(path.last()) - outer) < 2 * spacing, "ends at the outside")
        assertTrue(path.first().distance(path.last()) < 3 * spacing, "both ends side by side")
        // Away from the U-bend, the way out stays about one spacing from the way in: between its laps.
        val bend = path.indices.minBy { radius(path[it]) }
        assertTrue(radius(path[bend]) < 3 * spacing, "turns round in the middle")
        val wayIn = path.subList(0, (bend - 60).coerceAtLeast(1))
        val wayOut = path.subList((bend + 60).coerceAtMost(path.size - 1), path.size)
        var closest = Double.MAX_VALUE
        for (p in wayOut) for (q in wayIn) closest = minOf(closest, p.distance(q).toDouble())
        println("double spiral: ${rings.size} rings, ${path.size} points, max step $maxStep, closest approach out→in $closest (spacing $spacing)")
        assertTrue(closest > 0.9 * spacing, "way out comes within $closest of the way in")
    }

    /**
     * An L-shaped plan filled by [outlineMesh]: the mesh covers the plan's area, its edge lies on the
     * walls, and the double spiral through its rings is continuous with both ends at the walls.
     */
    @Test
    fun outlineMeshFillsAnLShapedRoom() {
        val plan = listOf(Vec2(-1f, -0.7f), Vec2(1f, -0.7f), Vec2(1f, 0.1f), Vec2(0.1f, 0.1f), Vec2(0.1f, 0.7f), Vec2(-1f, 0.7f))
        val floor = outlineMesh(plan, 0.02)
        var area = 0.0
        val p = floor.positions
        for (t in 0 until floor.indices.size / 3) {
            val a = floor.indices[3 * t]; val b = floor.indices[3 * t + 1]; val c = floor.indices[3 * t + 2]
            area += abs((p[3 * b] - p[3 * a]) * (p[3 * c + 2] - p[3 * a + 2]) - (p[3 * c] - p[3 * a]) * (p[3 * b + 2] - p[3 * a + 2])) / 2.0
        }
        val expected = 2.0 * 1.4 - 0.9 * 0.6
        assertTrue(abs(area - expected) < 0.01, "floor area $area, plan area $expected")

        val fromWalls = floor.geodesicDistanceFromBoundary()
        val rings = floor.evenIsolines(fromWalls, 0.08).map { it.maxBy { r -> r.size } }
        val path = doubleSpiralFromRings(rings)
        var maxStep = 0.0
        for (i in 1 until path.size) maxStep = maxOf(maxStep, path[i].distance(path[i - 1]).toDouble())
        assertTrue(maxStep < 0.1, "double spiral jumps $maxStep somewhere")
        assertTrue(path.first().distance(path.last()) < 3 * 0.08, "both ends side by side at the wall")
    }

    /**
     * The field double spiral in an L-shaped room with a bay: continuous, and the way out keeps
     * about a spacing from the way in everywhere away from the bend, including round the inside
     * corner where blending between rings folds.
     */
    @Test
    fun fieldDoubleSpiralNeverCrossesInAnLShapedRoom() {
        val bay = (0..16).map { i ->
            val a = PI * i / 16
            Vec2((-1.0 - 0.3 * kotlin.math.sin(a)).toFloat(), (0.25 + 0.25 * kotlin.math.cos(a)).toFloat())
        }
        val plan = listOf(Vec2(-1f, -0.7f), Vec2(1f, -0.7f), Vec2(1f, 0.1f), Vec2(0.1f, 0.1f), Vec2(0.1f, 0.7f), Vec2(-1f, 0.7f)) + bay
        val floor = outlineMesh(plan, 0.0125)
        val spacing = 0.07
        val (supply, back) = floor.windingAngle(floor.geodesicDistanceFromBoundary()).fieldDoubleSpiral(spacing)
        fun maxStep(path: List<Vec3>): Double { var m = 0.0; for (i in 1 until path.size) m = maxOf(m, path[i].distance(path[i - 1]).toDouble()); return m }
        val bendAt = supply.last()
        fun awayFromBend(path: List<Vec3>) = path.filter { it.distance(bendAt) > 3 * spacing }
        var closest = Double.MAX_VALUE
        for (p in awayFromBend(supply)) for (q in awayFromBend(back)) closest = minOf(closest, p.distance(q).toDouble())
        println("field double spiral: supply ${supply.size}, return ${back.size}, steps ${maxStep(supply)} / ${maxStep(back)}, closest ${closest} (spacing $spacing)")
        assertTrue(maxStep(supply) < 0.1 && maxStep(back) < 0.1, "a jump in the path")
        assertTrue(closest > 0.5 * spacing, "return comes within $closest of supply")
    }

    /** Roughing passes on a sphere come out top to bottom, one ring each. */
    @Test
    fun roughingPassesSliceTopDown() {
        val passes = icosphereMesh(3, 1.0).roughingPasses(0.25)
        assertEquals(8, passes.size)
        assertTrue(passes.zipWithNext().all { (a, b) -> a.first > b.first })
        assertTrue(passes.all { it.second.size == 1 })
    }
}
