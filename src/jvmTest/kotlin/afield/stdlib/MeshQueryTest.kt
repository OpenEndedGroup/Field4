package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariants of the query layer (`mesh_measure.kt`): curvature, the [MeshBvh] and the convex hull.
 *
 * The rays, the closest points and the inside tests are all checked against **analytic** answers on
 * primitives whose geometry is known in closed form — a ray at a sphere of radius `r` from distance
 * `d` hits at `d - r`, the closest point on a plane is the foot of the perpendicular, a point at
 * `0.9r` is inside and one at `1.1r` is not. The curvatures are checked against `1/r²` and `1/r`, and
 * against Gauss-Bonnet: `Σ K_v A_v = 2π χ`, which is a global identity no local bug survives.
 */
class MeshQueryTest {

    // ==========================================================================================
    // Curvature
    // ==========================================================================================

    @Test
    fun sphereCurvaturesMatchOneOverRSquaredAndOneOverR() {
        for (radius in listOf(0.5, 1.0, 3.0)) {
            val ball = icosphereMesh(4, radius)
            val gauss = ball.gaussianCurvature()
            val mean = ball.meanCurvature()
            val expectedK = 1.0 / (radius * radius)
            val expectedH = 1.0 / radius
            val meanK = gauss.average()
            val meanH = mean.average()
            assertEquals(expectedK, meanK, expectedK * 0.02, "Gaussian curvature of a sphere of radius $radius")
            assertEquals(expectedH, meanH, expectedH * 0.02, "mean curvature of a sphere of radius $radius")
            assertTrue(mean.all { it > 0.0 }, "an outward-wound convex surface has positive mean curvature")
        }
    }

    @Test
    fun gaussBonnetHoldsForClosedSurfaces() {
        for ((mesh, label) in listOf(
            icosphereMesh(3) to "icosphere",
            uvSphereMesh(40, 20) to "uv sphere",
            torusMesh(48, 24) to "torus",
            boxMesh() to "cube",
        )) {
            val topo = mesh.topology()
            val gauss = mesh.gaussianCurvature()
            val areas = mesh.mixedVertexAreas()
            var total = 0.0
            for (v in 0 until mesh.vertexCount) total += gauss[v] * areas[v]
            assertEquals(
                2.0 * PI * topo.eulerCharacteristic, total, 1e-4,
                "Gauss-Bonnet for $label (chi = ${topo.eulerCharacteristic})",
            )
        }
    }

    @Test
    fun aPlaneHasNoCurvatureInItsInterior() {
        val plane = planeMesh(8, 8)
        val boundary = plane.topology().boundaryVertices
        val gauss = plane.gaussianCurvature()
        val mean = plane.meanCurvature()
        for (v in 0 until plane.vertexCount) {
            if (v in boundary) continue
            assertEquals(0.0, gauss[v], 1e-5, "interior vertex $v Gaussian curvature")
            assertEquals(0.0, mean[v], 1e-5, "interior vertex $v mean curvature")
        }
    }

    @Test
    fun aCubesCurvatureIsConcentratedAtItsCorners() {
        val cube = boxMesh()
        val gauss = cube.gaussianCurvature()
        val areas = cube.mixedVertexAreas()
        // Every corner carries the same angle deficit; on a triangulated cube some corners see three
        // 90-degree face angles and some see extra 45-degree ones from the diagonals, so compare the
        // integrated quantity, which must be 4 pi over the whole solid and positive at every corner.
        var total = 0.0
        for (v in 0 until cube.vertexCount) {
            total += gauss[v] * areas[v]
            assertTrue(gauss[v] > 0.0, "corner $v should be positively curved, got ${gauss[v]}")
        }
        assertEquals(4.0 * PI, total, 1e-5, "a convex solid integrates to 4 pi")
    }

    // ==========================================================================================
    // Rays
    // ==========================================================================================

    @Test
    fun raysHitASphereWhereTheAnalyticAnswerSaysTheyShould() {
        val ball = icosphereMesh(5, 1.0) // fine enough that the polyhedron is within 1e-3 of the sphere
        val tree = ball.bvh()
        for (direction in listOf(
            Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f), Vec3(-1f, 0f, 0f),
            Vec3(0.577f, 0.577f, 0.577f),
        )) {
            val unit = Vec3(direction).normalize()
            val origin = Vec3(unit).mul(-5f)
            val hit = assertNotNull(tree.raycast(origin, unit), "ray along $unit should hit")
            assertEquals(4.0, hit.distance, 2e-3, "a ray from 5 units out hits a unit sphere at 4")
            assertEquals(1.0f, hit.point.length(), 2e-3f, "and the hit point is on the sphere")
            assertTrue(hit.normal.dot(unit) < 0f, "the front face's normal opposes the ray")
        }
        // A ray that misses.
        assertNull(tree.raycast(Vec3(0f, 3f, -5f), Vec3(0f, 0f, 1f)), "a ray 3 units above should miss")
    }

    @Test
    fun raycastAllFindsBothWallsOfAHollowShell() {
        val shell = icosphereMesh(4, 1.0) + icosphereMesh(4, 0.5).flipped()
        val tree = shell.bvh()
        // Deliberately off-axis: a ray straight down x would pass exactly through an icosphere vertex
        // (the midpoint of one icosahedron edge projects to (1,0,0)), and "through a vertex" is the one
        // case where a hit count is genuinely ambiguous.
        val oy = 0.13f
        val oz = 0.07f
        val hits = tree.raycastAll(Vec3(-5f, oy, oz), Vec3(1f, 0f, 0f))
        assertEquals(4, hits.size, "outer in, inner in, inner out, outer out; got ${hits.map { it.distance }}")
        val perpendicular2 = (oy * oy + oz * oz).toDouble()
        fun chord(radius: Double) = kotlin.math.sqrt(radius * radius - perpendicular2)
        val expected = listOf(5.0 - chord(1.0), 5.0 - chord(0.5), 5.0 + chord(0.5), 5.0 + chord(1.0))
        for (i in 0..3) {
            assertEquals(expected[i], hits[i].distance, 5e-3, "hit $i of ${hits.map { it.distance }}")
        }
    }

    @Test
    fun backFaceCullingHidesTheFarWall() {
        val ball = icosphereMesh(4, 1.0)
        val tree = ball.bvh()
        // Starting inside, the only triangle ahead is a back face from the ray's point of view.
        val through = tree.raycast(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), cullBackFaces = false)
        assertNotNull(through)
        assertEquals(1.0, through.distance, 2e-3)
        assertNull(
            tree.raycast(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), cullBackFaces = true),
            "from inside, every triangle is back-facing",
        )
    }

    @Test
    fun raysAgainstAnAxisAlignedBoxAreExact() {
        val cube = boxMesh(2, 2, 2).bvh()
        val hit = assertNotNull(cube.raycast(Vec3(0f, 0f, -10f), Vec3(0f, 0f, 1f)))
        assertEquals(9.0, hit.distance, 1e-5, "the -z face is at z = -1")
        assertEquals(0.0, angleBetweenDegrees(hit.normal, Vec3(0f, 0f, -1f)), 1e-2)
        // A grazing ray straight down a face plane still resolves to one side or the other, never NaN.
        val grazing = cube.raycast(Vec3(-10f, 1f, 0f), Vec3(1f, 0f, 0f))
        if (grazing != null) assertTrue(grazing.distance.isFinite())
    }

    // ==========================================================================================
    // Closest point, inside test, signed distance
    // ==========================================================================================

    @Test
    fun closestPointOnAPlaneIsTheFootOfThePerpendicular() {
        val plane = planeMesh(10, 10, 4.0, 4.0) // xy plane at z = 0, spanning -2..2
        val tree = plane.bvh()
        for (q in listOf(Vec3(0.3f, -0.7f, 2f), Vec3(-1.5f, 1.1f, -3f), Vec3(0f, 0f, 0.25f))) {
            val near = assertNotNull(tree.closestPoint(q))
            assertEquals(abs(q.z).toDouble(), near.distance, 1e-4, "distance to the plane is |z|")
            assertEquals(q.x, near.point.x, 1e-4f)
            assertEquals(q.y, near.point.y, 1e-4f)
            assertEquals(0.0f, near.point.z, 1e-4f)
        }
        // Off the edge of the patch, the nearest point is on the border, not the projection.
        val beyond = assertNotNull(tree.closestPoint(Vec3(5f, 0f, 0f)))
        assertEquals(3.0, beyond.distance, 1e-4)
        assertEquals(2.0f, beyond.point.x, 1e-4f)
    }

    @Test
    fun closestPointAndSignedDistanceOnASphereMatchTheAnalyticField() {
        val ball = icosphereMesh(5, 1.0)
        val tree = ball.bvh()
        val exact = sdfSphere(1.0)
        val random = Random(11)
        var worst = 0.0
        repeat(60) {
            val p = Vec3(
                (random.nextFloat() - 0.5f) * 6f,
                (random.nextFloat() - 0.5f) * 6f,
                (random.nextFloat() - 0.5f) * 6f,
            )
            val got = tree.signedDistance(p)
            val want = exact(p).toDouble()
            worst = kotlin.math.max(worst, abs(got - want))
            assertEquals((want < 0.0), (got < 0.0), "inside/outside at $p: got $got want $want")
        }
        assertTrue(worst < 3e-3, "worst signed-distance error against the analytic sphere was $worst")
    }

    @Test
    fun insideTestWorksOnAConcaveSolid() {
        // A cube with a cube bitten out of one corner: concave, so a naive normal-facing test fails.
        val solid = marchingCubes(
            sdfSubtract(sdfBox(Vec3(2f, 2f, 2f)), sdfBox(Vec3(1.2f, 1.2f, 1.2f), Vec3(1f, 1f, 1f))),
            boundsAround(radius = 1.6), 72,
        )
        assertTrue(solid.isWatertight(), solid.summary())
        val tree = solid.bvh()
        assertTrue(tree.contains(Vec3(-0.5f, -0.5f, -0.5f)), "deep inside the remaining material")
        assertTrue(!tree.contains(Vec3(0.9f, 0.9f, 0.9f)), "inside the bite, so outside the solid")
        assertTrue(!tree.contains(Vec3(3f, 0f, 0f)), "outside altogether")
        assertTrue(tree.contains(Vec3(0f, 0f, 0f)), "the centre survives the bite")
    }

    @Test
    fun sampledSurfacePointsReallyLieOnTheSurface() {
        val mesh = torusMesh(32, 16)
        val tree = mesh.bvh()
        val points = mesh.samplePoints(400, seed = 3)
        assertEquals(400, points.size)
        for (p in points) {
            val near = assertNotNull(tree.closestPoint(p))
            assertEquals(0.0, near.distance, 1e-4, "sampled point $p is off the surface")
        }
        // Area weighting means the sample density should be roughly uniform, so the sampled centroid
        // should land near the mesh's own area-weighted centroid.
        val mean = Vec3(0f, 0f, 0f)
        for (p in points) mean.add(p)
        mean.mul(1f / points.size)
        assertTrue(mean.distance(mesh.centroid()) < 0.08f, "sample centroid $mean vs ${mesh.centroid()}")
    }

    @Test
    fun theBvhAgreesWithBruteForceOnARandomMesh() {
        // The BVH's whole job is to give the same answer faster, so check it against the O(n) truth.
        val mesh = icosphereMesh(2, 1.0).transformed { p ->
            Vec3(p.x * 1.7f, p.y * 0.6f, p.z * 1.1f)
        }
        val tree = mesh.bvh()
        // One single-triangle BVH per triangle is the O(n) truth to compare against. Built once.
        val singles = (0 until mesh.triangleCount).map { t -> MeshBvh(mesh.filterTriangles { it == t }) }
        val random = Random(5)
        repeat(25) {
            val q = Vec3(
                (random.nextFloat() - 0.5f) * 5f,
                (random.nextFloat() - 0.5f) * 5f,
                (random.nextFloat() - 0.5f) * 5f,
            )
            val fromTree = assertNotNull(tree.closestPoint(q))
            var brute = Double.MAX_VALUE
            for (single in singles) brute = kotlin.math.min(brute, single.closestPoint(q)!!.distance)
            assertEquals(brute, fromTree.distance, 1e-4, "BVH disagreed with brute force at $q")
        }
    }

    // ==========================================================================================
    // Convex hull
    // ==========================================================================================

    @Test
    fun hullOfPointsOnASphereIsAClosedSolidContainingThemAll() {
        val random = Random(19)
        val points = List(300) {
            Vec3(
                random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f,
            ).also { if (it.length() > 1e-6f) it.normalize() }
        }
        val hull = convexHullMesh(points)
        val topo = hull.topology()
        assertTrue(topo.isWatertight, "a hull must be closed; $topo")
        assertEquals(2, topo.eulerCharacteristic, "a hull is genus 0; $topo")
        assertTrue(hull.signedVolume() > 0.0, "and wound outward")
        // 300 points on a unit sphere give a hull close to the ball itself.
        assertEquals(4.0 / 3.0 * PI, hull.signedVolume(), 0.35, hull.summary())
        val tree = hull.bvh()
        for (p in points) {
            val d = tree.closestPoint(p)!!.distance
            assertTrue(tree.contains(p) || d < 1e-3, "hull vertex $p was left outside by $d")
        }
    }

    @Test
    fun hullOfPointsInsideAShapeIsTheShapesHull() {
        val random = Random(23)
        // Points filling a ball: the hull must be near the ball, and interior points must not appear
        // as hull vertices.
        val points = List(500) {
            var p: Vec3
            do {
                p = Vec3(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1)
            } while (p.length() > 1f)
            p
        }
        val hull = convexHullMesh(points)
        assertTrue(hull.isWatertight(), hull.summary())
        assertTrue(hull.vertexCount < points.size / 2, "most points are interior; got ${hull.vertexCount}")
        assertTrue(hull.signedVolume() < 4.0 / 3.0 * PI + 1e-6, "a hull of interior points cannot exceed the ball")
        assertTrue(hull.signedVolume() > 3.0, "and with 500 points it should be close: ${hull.signedVolume()}")
    }

    @Test
    fun hullOfAConvexMeshRecoversThatMesh() {
        val ball = icosphereMesh(2, 1.0)
        val hull = ball.convexHull()
        assertTrue(hull.isWatertight(), hull.summary())
        assertEquals(ball.signedVolume(), hull.signedVolume(), ball.signedVolume() * 0.02, hull.summary())
        assertEquals(ball.vertexCount, hull.vertexCount, "every icosphere vertex is on its own hull")
    }

    @Test
    fun degenerateInputToTheHullReturnsAnEmptyMeshRatherThanThrowing() {
        assertTrue(convexHullMesh(listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f))).isEmpty, "too few points")
        val coplanar = List(20) { i -> Vec3(i.toFloat(), (i * i % 7).toFloat(), 0f) }
        assertTrue(convexHullMesh(coplanar).isEmpty, "a flat point set has no hull volume")
        val collinear = List(10) { i -> Vec3(i.toFloat(), 0f, 0f) }
        assertTrue(convexHullMesh(collinear).isEmpty, "a line has no hull volume")
    }

    @Test
    fun deviationMeasuresWhatItSaysItMeasures() {
        val a = icosphereMesh(4, 1.0)
        val b = icosphereMesh(4, 1.1)
        // Every vertex of b is 0.1 further out than a's surface.
        assertEquals(0.1, a.deviationFrom(b), 0.01)
        assertEquals(0.0, a.deviationFrom(a), 1e-5, "a mesh does not deviate from itself")
        assertTrue(maxDeviation(a, b) >= a.deviationFrom(b))
    }
}
