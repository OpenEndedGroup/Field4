package afield.geometry.mesh

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

    /** Roughing passes on a sphere come out top to bottom, one ring each. */
    @Test
    fun roughingPassesSliceTopDown() {
        val passes = icosphereMesh(3, 1.0).roughingPasses(0.25)
        assertEquals(8, passes.size)
        assertTrue(passes.zipWithNext().all { (a, b) -> a.first > b.first })
        assertTrue(passes.all { it.second.size == 1 })
    }
}
