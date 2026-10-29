package afield.geometry.spatial

import afield.geometry.mesh.boxMesh
import afield.geometry.mesh.convexHullMesh
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LpPolytopeTest {
    private val cube = LpPolytope.cube(1.0)
    private val c = vec(1.0, 0.6, 0.3)
    private val cubeOptimum = vec(1.0, 1.0, 1.0)

    // A random cloud's hull: 60 points in a squashed ball, seeded.
    private val cloud: List<Vec3> = java.util.Random(7).let { r ->
        List(60) {
            val v = vec(r.nextGaussian(), r.nextGaussian(), r.nextGaussian())
            val len = sqrt(v.x.toDouble() * v.x + v.y.toDouble() * v.y + v.z.toDouble() * v.z)
            val s = 0.4 + 0.6 * r.nextDouble()
            vec(2.0 * s * v.x / len, 1.4 * s * v.y / len, 1.0 * s * v.z / len)
        }
    }
    private val blob = LpPolytope.hull(cloud)

    private fun dist(s: LpSample3, v: Vec3) = sqrt((s.x - v.x) * (s.x - v.x) + (s.y - v.y) * (s.y - v.y) + (s.z - v.z) * (s.z - v.z))

    @Test
    fun cubeHasSixFacesEightVerticesTwelveEdges() {
        assertEquals(6, cube.constraintCount)
        assertEquals(8, cube.vertices.size)
        assertEquals(12, cube.edges.size)
        assertTrue(cube.vertexPlanes.all { it.size == 3 })
        // The hull of a box mesh merges its two triangles per face back into six half-spaces.
        val fromMesh = LpPolytope.of(boxMesh(2.0, 2.0, 2.0))
        assertEquals(6, fromMesh.constraintCount)
        assertEquals(12, fromMesh.edges.size)
        assertEquals(8.0, fromMesh.volume, 1e-4)
    }

    @Test
    fun hullKeepsOnlyExtremePointsAndContainsTheCloud() {
        assertTrue(cloud.all { blob.violation(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) < 1e-5 })
        assertTrue(blob.vertices.size < cloud.size, "interior points dropped")
        // Euler: V − E + F = 2 holds for any simplicial hull with merged faces too.
        val faces = blob.constraintCount
        assertEquals(convexHullMesh(cloud).vertexCount, blob.vertices.size, "one vertex per hull point")
        assertEquals(2, blob.vertices.size - blob.edges.size + faces)
        // An octahedron's vertices plus interior points and repeats: eight faces, six vertices.
        val octa = listOf(vec(1, 0, 0), vec(-1, 0, 0), vec(0, 1, 0), vec(0, -1, 0), vec(0, 0, 1), vec(0, 0, -1))
        val oct = LpPolytope.hull(octa + octa + listOf(vec(0.1, 0.1, 0.1), vec(0, 0, 0)))
        assertEquals(8, oct.constraintCount)
        assertEquals(6, oct.vertices.size)
        assertTrue(oct.vertexPlanes.all { it.size == 4 }, "each octahedron vertex has four tight faces")
        assertEquals(12, oct.edges.size)
    }

    @Test
    fun centralPathIsFeasibleAndEndsAtTheOptimalVertex() {
        for ((poly, label) in listOf(cube to "cube", blob to "hull")) {
            val path = poly.centralPath(c)
            val v = poly.optimalVertex(c)
            assertTrue(path.samples.all { poly.containsStrictly(it.x, it.y, it.z) }, "$label: strictly feasible")
            assertTrue(dist(path.last, v) < 2e-3, "$label: ends ${dist(path.last, v)} from the optimal vertex")
            assertTrue(path.samples.zipWithNext().all { (a, b) -> b.objective >= a.objective - 1e-9 }, "$label: objective rises")
        }
        assertTrue(dist(cube.centralPath(c).samples.first(), vec(0, 0, 0)) < 1e-2, "at μ = 1000 the path starts beside the cube's centre")
    }

    @Test
    fun fanHasOnePathPerSphereDirection() {
        val fan = cube.centralFan(directions = 26, samples = 40)
        assertEquals(26, fan.size)
        assertTrue(fan.all { it.samples.size == 40 })
        val dirs = sphereDirections(26)
        assertTrue(dirs.all { abs(sqrt(it.x.toDouble() * it.x + it.y.toDouble() * it.y + it.z.toDouble() * it.z) - 1.0) < 1e-5 })
        // Ends at the optimum: the objective gap, since a direction nearly along an axis almost ties two vertices.
        fan.forEachIndexed { i, t ->
            val v = cube.optimalVertex(dirs[i])
            val best = (v.x * dirs[i].x + v.y * dirs[i].y + v.z * dirs[i].z).toDouble()
            assertTrue(best - t.last.objective in -1e-6..1e-3, "direction $i: gap ${best - t.last.objective}")
        }
        assertEquals(26, fan.toFLines().size)
    }

    @Test
    fun primalDualAndPdhgConverge() {
        for (poly in listOf(cube, blob)) {
            val v = poly.optimalVertex(c)
            val pd = poly.primalDual(c)
            assertTrue(pd.samples.all { poly.containsStrictly(it.x, it.y, it.z) })
            assertTrue(dist(pd.last, v) < 1e-4, "primal-dual ends ${dist(pd.last, v)} away")
            assertTrue(pd.samples.size < 40)
        }
        val trace = cube.pdhg(c, iterations = 3000, tau = 0.2, sigma = 0.2)
        assertTrue(dist(trace.trajectory.last, cubeOptimum) < 1e-2, "pdhg ends ${dist(trace.trajectory.last, cubeOptimum)} away")
        assertTrue(trace.trajectory.samples.any { it.violation > 0.0 }, "pdhg leaves the polytope on the way")
        assertEquals(trace.trajectory.samples.size, trace.duals.size)
    }

    @Test
    fun ellipsoidsStayPositiveDefiniteContainTheOptimumAndShrinkBy27Over32() {
        for (poly in listOf(cube, blob)) {
            val v = poly.optimalVertex(c)
            val trace = poly.ellipsoid(c, iterations = 100)
            assertTrue(trace.ellipsoids.all { it.isPositiveDefinite })
            assertTrue(trace.ellipsoids.all { it.quadraticForm(v.x.toDouble(), v.y.toDouble(), v.z.toDouble()) <= 1.0 + 1e-3 }, "every ellipsoid contains the optimum")
            for (k in 1 until 20) {
                val ratio = trace.ellipsoids[k].determinant / trace.ellipsoids[k - 1].determinant
                assertEquals(729.0 / 1024.0, ratio, 1e-9, "det ratio (27/32)² at step $k")
            }
            val best = trace.trajectory.samples.filter { it.violation == 0.0 }.maxOf { it.objective }
            val opt = poly.simplexWalk(c).last.objective
            assertTrue(opt - best in -1e-6..1e-2, "best feasible centre within ${opt - best}")
        }
    }

    @Test
    fun simplexWalkFollowsEdgesToTheOptimum() {
        for (poly in listOf(cube, blob)) {
            val v = poly.optimalVertex(c)
            for (start in poly.vertices.indices) {
                val walk = poly.simplexWalk(c, start)
                assertTrue(dist(walk.last, v) < 1e-6)
                assertTrue(walk.samples.zipWithNext().all { (a, b) -> b.objective > a.objective })
            }
        }
        val idx = cube.vertices.indexOfFirst { it.distance(vec(-1, -1, -1)) < 1e-6f }
        assertEquals(4, cube.simplexWalk(c, idx).samples.size, "three pivots across the cube")
    }

    @Test
    fun cuttingPlanesApproachTheOptimum() {
        for (kind in LpCenter.entries) {
            val trace = cube.cuttingPlanes(c, iterations = 18, center = kind)
            val s = trace.trajectory.samples
            assertTrue(s.all { cube.violation(it.x, it.y, it.z) < 1e-9 }, "$kind: centres inside")
            assertTrue(s.zipWithNext().all { (a, b) -> b.scale < a.scale }, "$kind: volume falls")
            assertTrue(dist(s.last(), cubeOptimum) < 0.25, "$kind ends ${dist(s.last(), cubeOptimum)} away")
            assertEquals(s.size, trace.regions.size)
        }
        val cheb = chebyshevCenter3(cube.normalX, cube.normalY, cube.normalZ, cube.bound)
        assertEquals(1.0, cheb[3], 1e-9)
    }

    @Test
    fun volumetricCentreOfAPrismIsItsTriangleCentreInThePlane() {
        // a triangle times [-1, 2]: the 3-D volumetric centre's x, y must be the 2-D one's
        val nx = doubleArrayOf(-1.0, 0.0, 0.6); val ny = doubleArrayOf(0.0, -1.0, 0.8); val b = doubleArrayOf(0.0, 0.0, 2.4)
        val flat = volumetricCenter(nx, ny, b, 0.5, 0.5)
        val prism = volumetricCenter3(
            nx + doubleArrayOf(0.0, 0.0), ny + doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0, 1.0, -1.0), b + doubleArrayOf(2.0, 1.0),
            doubleArrayOf(0.5, 0.5, 0.0),
        )
        assertEquals(flat[0], prism[0], 1e-7)
        assertEquals(flat[1], prism[1], 1e-7)
        assertEquals(0.5, prism[2], 1e-7)
        val centre = volumetricCenter3(cube.normalX, cube.normalY, cube.normalZ, cube.bound, doubleArrayOf(0.3, -0.2, 0.1))
        centre.forEach { assertEquals(0.0, it, 1e-7) }
    }

    @Test
    fun badInputIsRejectedClearly() {
        // Five half-spaces of a cube: the top is missing, so +z is unbounded.
        assertTrue(assertFailsWith<IllegalArgumentException> {
            LpPolytope(listOf(vec(1, 0, 0), vec(-1, 0, 0), vec(0, 1, 0), vec(0, -1, 0), vec(0, 0, -1)), listOf(1.0, 1.0, 1.0, 1.0, 1.0))
        }.message!!.contains("unbounded"))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            LpPolytope.hull(listOf(vec(0, 0, 0), vec(1, 0, 0), vec(0, 1, 0), vec(1, 1, 0), vec(0.5, 0.5, 0.0)))
        }.message!!.contains("no volume"))
        assertFailsWith<IllegalArgumentException> { cube.centralPath(c, start = vec(2, 0, 0)) }
    }
}
