package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Invariants of the mesh type, its topology layer and its generators (`mesh.kt`,
 * `mesh_topology.kt`, `mesh_generate.kt`).
 *
 * The load-bearing assertion here is the **Euler characteristic**. Every closed genus-0 primitive
 * must report `χ = 2` and the torus `χ = 0`, which is a single integer that catches a duplicated
 * seam, a missing cap, an unwelded weld and an off-by-one in a ring index — all of which still look
 * plausible in a render. Alongside it: analytic volumes and areas (a unit cube is exactly 1 and 6),
 * outward winding (positive signed volume) for every generator, and the repairs actually repairing.
 */
class MeshCoreTest {

    private fun assertClosedGenus(mesh: TriMesh, expectedGenus: Int, label: String) {
        val topo = mesh.topology()
        assertTrue(topo.isManifold, "$label should be manifold; $topo")
        assertTrue(topo.isWatertight, "$label should be watertight; $topo")
        assertTrue(topo.isConsistentlyOriented, "$label should be consistently oriented; $topo")
        assertEquals(2 - 2 * expectedGenus, topo.eulerCharacteristic, "$label Euler characteristic; $topo")
        assertEquals(expectedGenus, topo.genus, "$label genus; $topo")
        assertTrue(mesh.signedVolume() > 0.0, "$label should be wound outward, volume was ${mesh.signedVolume()}")
        assertTrue(mesh.isFinite(), "$label has non-finite floats")
    }

    // ==========================================================================================
    // The type
    // ==========================================================================================

    @Test
    fun builderRejectsInconsistentAttributes() {
        assertFailsWith<IllegalArgumentException> {
            triMesh {
                vertex(0, 0, 0, normal = Vec3(0f, 1f, 0f))
                vertex(1, 0, 0)
            }
        }
    }

    @Test
    fun constructorRejectsOutOfRangeIndices() {
        assertFailsWith<IllegalArgumentException> {
            TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 7))
        }
    }

    @Test
    fun accessorsReturnFreshVectorsSoTheMeshCannotBeMutatedThroughThem() {
        val mesh = boxMesh()
        val p = mesh.position(0)
        p.add(100f, 100f, 100f)
        assertEquals(-0.5f, mesh.x(0), "mutating a returned Vec3 must not touch the mesh")
    }

    @Test
    fun concatenationOffsetsIndicesAndAddsVolumes() {
        val a = boxMesh(1, 1, 1).translated(-2, 0, 0)
        val b = boxMesh(1, 1, 1).translated(2, 0, 0)
        val both = a + b
        assertEquals(16, both.vertexCount)
        assertEquals(24, both.triangleCount)
        assertEquals(2.0, both.signedVolume(), 1e-5)
        assertEquals(2, both.topology().componentCount)
        assertEquals(4, both.topology().eulerCharacteristic, "two closed components give chi = 4")
    }

    @Test
    fun flippedReversesTheSignOfTheVolume() {
        val cube = boxMesh()
        assertEquals(-cube.signedVolume(), cube.flipped().signedVolume(), 1e-6)
    }

    @Test
    fun filterTrianglesDropsTheVerticesItOrphans() {
        val cube = boxMesh()
        // Triangles 0 and 1 are the +z face, so only its four corners should survive.
        val face = cube.filterTriangles { it < 2 }
        assertEquals(2, face.triangleCount)
        assertEquals(4, face.vertexCount, "the other four corners should have been dropped")
        assertTrue(face.topology().vertexTriangles.all { it.isNotEmpty() })
        assertEquals(1.0, face.surfaceArea(), 1e-6, "one unit face of a unit cube")
    }

    // ==========================================================================================
    // Generators: topology
    // ==========================================================================================

    @Test
    fun boxIsTheMinimalWatertightCube() {
        val cube = boxMesh(2, 2, 2)
        assertEquals(8, cube.vertexCount)
        assertEquals(12, cube.triangleCount)
        assertEquals(18, cube.topology().edgeCount)
        assertClosedGenus(cube, 0, "boxMesh")
        assertEquals(8.0, cube.signedVolume(), 1e-5)
        assertEquals(24.0, cube.surfaceArea(), 1e-5)
    }

    @Test
    fun platonicSolidsAreAllClosedGenusZero() {
        assertClosedGenus(tetrahedronMesh(), 0, "tetrahedron")
        assertClosedGenus(octahedronMesh(), 0, "octahedron")
        assertClosedGenus(icosahedronMesh(), 0, "icosahedron")
        assertClosedGenus(dodecahedronMesh(), 0, "dodecahedron")
        assertEquals(4, tetrahedronMesh().triangleCount)
        assertEquals(8, octahedronMesh().triangleCount)
        assertEquals(20, icosahedronMesh().triangleCount)
        assertEquals(60, dodecahedronMesh().triangleCount, "12 pentagons fanned from their centres")
    }

    @Test
    fun spheresAreClosedGenusZeroWithNoDegenerateTriangles() {
        val uv = uvSphereMesh(32, 16)
        assertClosedGenus(uv, 0, "uvSphereMesh")
        assertEquals(0, uv.quality().degenerateCount, "the poles should be fans, not collapsed quads")
        val ico = icosphereMesh(3)
        assertClosedGenus(ico, 0, "icosphereMesh")
        assertEquals(20 * 64, ico.triangleCount)
    }

    @Test
    fun torusIsGenusOne() {
        val t = torusMesh(48, 24)
        assertClosedGenus(t, 1, "torusMesh")
        assertEquals(0, t.topology().eulerCharacteristic)
    }

    @Test
    fun cappedRadialSolidsAreClosedAndUncappedOnesHaveTheRightNumberOfHoles() {
        assertClosedGenus(cylinderMesh(24, caps = true), 0, "capped cylinder")
        assertClosedGenus(coneMesh(24, cap = true), 0, "capped cone")
        assertClosedGenus(capsuleMesh(24, 6), 0, "capsule")
        val tube = cylinderMesh(24, caps = false)
        assertEquals(2, tube.topology().boundaryLoops.size, "an open tube has two rims")
        assertEquals(0, tube.topology().eulerCharacteristic, "an annulus has chi = 0")
        assertTrue(tube.topology().boundaryLoops.all { it.size == 24 })
    }

    @Test
    fun planeAndPolygonAreDiscs() {
        val plane = planeMesh(4, 3)
        assertEquals(1, plane.topology().eulerCharacteristic, "a disc has chi = 1")
        assertEquals(1, plane.topology().boundaryLoops.size)
        // A 4x3 quad grid is 5x4 vertices, of which 2*5 + 2*4 - 4 = 14 sit on the border.
        assertEquals(14, plane.topology().boundaryLoops[0].size)
        assertEquals(1.0, plane.surfaceArea(), 1e-5)
        val ring = listOf(
            Vec2(0f, 0f), Vec2(2f, 0f), Vec2(2f, 1f), Vec2(1f, 1f), Vec2(1f, 2f), Vec2(0f, 2f),
        )
        val poly = polygonMesh(ring)
        assertEquals(1, poly.topology().eulerCharacteristic)
        assertEquals(abs(polygonSignedArea(ring)), poly.surfaceArea(), 1e-5, "an L should tile to its own area")
    }

    @Test
    fun extrusionOfAConcavePolygonIsClosedAndHasTheRightVolume() {
        // An L-shape: area 3, extruded by 2 -> volume 6. Concave, so the fan triangulation would fail.
        val l = listOf(
            Vec2(0f, 0f), Vec2(2f, 0f), Vec2(2f, 1f), Vec2(1f, 1f), Vec2(1f, 2f), Vec2(0f, 2f),
        )
        val prism = extrudeMesh(l, 2.0)
        assertClosedGenus(prism, 0, "extruded L")
        assertEquals(6.0, prism.signedVolume(), 1e-4)
        // The input's winding must not matter.
        val reversedInput = extrudeMesh(l.reversed(), 2.0)
        assertEquals(6.0, reversedInput.signedVolume(), 1e-4)
    }

    @Test
    fun revolveClosesWhenTheProfileTouchesTheAxisAtBothEnds() {
        // A cone profile: radius 1 at y=0 down to 0 at y=2, plus a flat base back to the axis.
        val profile = listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(0f, 2f))
        val lathe = revolveMesh(profile, 64)
        assertClosedGenus(lathe, 0, "revolved cone")
        // (1/3) pi r^2 h = (1/3) pi * 1 * 2 = 2.0944
        assertEquals(2.0 * PI / 3.0, lathe.signedVolume(), 0.01)
    }

    @Test
    fun tubeAlongAClosedPathIsAGenusOneSurface() {
        val circle = (0 until 40).map { i ->
            val a = 2.0 * PI * i / 40
            Vec3((kotlin.math.cos(a)).toFloat(), 0f, (kotlin.math.sin(a)).toFloat())
        }
        val ring = tubeMesh(circle, radius = 0.15, sides = 24, closed = true)
        assertClosedGenus(ring, 1, "closed tube")
        // The analytic torus of major radius 1, minor 0.15 has volume 2 pi^2 R r^2 = 0.44413. A tube
        // of 24-gon cross sections is inscribed in it, so it must be a little smaller and never larger.
        val exact = 2.0 * PI * PI * 1.0 * 0.15 * 0.15
        assertTrue(
            ring.signedVolume() in (exact * 0.95)..exact,
            "closed tube volume ${ring.signedVolume()} should be just under $exact",
        )
        val open = tubeMesh(circle.take(20), radius = 0.15, sides = 12, closed = false, caps = true)
        assertClosedGenus(open, 0, "capped open tube")
    }

    @Test
    fun tubeFramesDoNotTwistThroughAVerticalTangent() {
        // A path that swings its tangent through +y, where an up-vector frame degenerates.
        val path = listOf(
            Vec3(-1f, 0f, 0f), Vec3(-0.5f, 0.5f, 0f), Vec3(0f, 1f, 0f), Vec3(0.5f, 0.5f, 0f), Vec3(1f, 0f, 0f),
        )
        val tube = tubeMesh(path, radius = 0.1, sides = 8, closed = false, caps = true)
        assertTrue(tube.isFinite(), "a vertical tangent must not produce NaN frames")
        assertClosedGenus(tube, 0, "tube over a vertical tangent")
        // Every side vertex must sit exactly `radius` from its own ring centre.
        val ringVertices = 5 * 8
        for (v in 0 until ringVertices) {
            val centre = path[v / 8]
            assertEquals(0.1, tube.position(v).distance(centre).toDouble(), 1e-4, "ring vertex $v radius")
        }
    }

    @Test
    fun heightfieldNormalsPointUpAndAreaExceedsTheFlatArea() {
        val flat = heightfieldMesh(16, 16, 2.0, 2.0) { _, _ -> 0f }
        assertEquals(4.0, flat.surfaceArea(), 1e-4)
        for (v in 0 until flat.vertexCount) {
            val n = assertNotNull(flat.normal(v))
            assertEquals(1f, n.y, 1e-5f, "a flat heightfield's normals must be +y")
        }
        val bumpy = heightfieldMesh(48, 48, 2.0, 2.0) { x, z ->
            (0.3 * kotlin.math.sin(x * 3.0) * kotlin.math.cos(z * 3.0)).toFloat()
        }
        assertTrue(bumpy.surfaceArea() > 4.2, "a corrugated surface has more area than its footprint")
        assertEquals(1, bumpy.topology().eulerCharacteristic)
    }

    /**
     * [reliefMesh] closes what [heightfieldMesh] leaves open, and the counts it closes it with are
     * exact rather than approximately right.
     *
     * The oracle is set equality on the counts, not "χ came out even": `P = 2nx + 2nz` perimeter
     * vertices, `(nx+1)(nz+1) + P + 1` vertices and `2·nx·nz + 3·P` triangles, checked at three
     * aspect ratios so a formula that only works on a square grid fails here.
     */
    @Test
    fun reliefMeshClosesAHeightfieldWithExactCounts() {
        for ((nx, nz) in listOf(1 to 1, 8 to 8, 13 to 5)) {
            val plaque = reliefMesh(nx, nz, 2.0, 2.0, baseY = -0.4) { x, z ->
                (0.15 * kotlin.math.sin(x * 3.0) * kotlin.math.cos(z * 3.0)).toFloat()
            }
            val perimeter = 2 * nx + 2 * nz
            assertEquals((nx + 1) * (nz + 1) + perimeter + 1, plaque.vertexCount, "${nx}x$nz vertices")
            assertEquals(2 * nx * nz + 3 * perimeter, plaque.triangleCount, "${nx}x$nz triangles")
            assertClosedGenus(plaque, 0, "reliefMesh(${nx}x$nz)")
            assertEquals(0, plaque.topology().boundaryLoops.size, "${nx}x$nz has no rim left")
        }
    }

    /**
     * The volume is the integral of the height over the footprint plus the base slab — so a *flat*
     * relief is an exactly-known cuboid, and a rigid lift of any surface adds exactly `footprint*dy`.
     * Both are stronger than "the volume is positive and looks about right".
     */
    @Test
    fun aFlatReliefIsExactlyItsCuboid() {
        val slab = reliefMesh(7, 11, 2.0, 3.0, baseY = -0.25) { _, _ -> 0.5f }
        assertEquals(2.0 * 3.0 * 0.75, slab.volume(), 1e-5, "2 x 3 footprint, 0.75 tall")
        val ripple = { x: Float, z: Float -> (0.1 * kotlin.math.sin(x * 5.0) * kotlin.math.cos(z * 4.0)).toFloat() }
        val low = reliefMesh(32, 48, 2.0, 3.0, baseY = -0.25) { x, z -> 0.5f + ripple(x, z) }
        val high = reliefMesh(32, 48, 2.0, 3.0, baseY = -0.25) { x, z -> 0.9f + ripple(x, z) }
        assertEquals(2.0 * 3.0 * 0.4, high.volume() - low.volume(), 1e-5, "a rigid lift adds footprint*dy")
    }

    @Test
    fun reliefMeshRefusesABaseAboveItsOwnSurface() {
        assertFailsWith<IllegalArgumentException> {
            reliefMesh(4, 4, 2.0, 2.0, baseY = 0.5) { _, _ -> 0.2f }
        }
    }

    /**
     * [Img.toReliefMesh] samples the same grid as [Img.toHeightfieldMesh] — checked vertex for
     * vertex over the shared grid, because "both look like the picture" is not an assertion.
     */
    @Test
    fun imgToReliefAgreesWithImgToHeightfieldOnTheSharedGrid() {
        val img = Img(9, 6, 1, FloatArray(54) { (it % 7) / 7f })
        val open = img.toHeightfieldMesh(scale = 0.6, width = 2.0)
        val closed = img.toReliefMesh(scale = 0.6, width = 2.0, baseThickness = 0.2)
        assertEquals(1, open.topology().eulerCharacteristic, "the heightfield is still a disc")
        assertClosedGenus(closed, 0, "Img.toReliefMesh")
        assertTrue(closed.vertexCount > open.vertexCount, "the plaque adds a rim and a floor")
        for (v in 0 until open.vertexCount) {
            assertEquals(open.position(v).x, closed.position(v).x, 1e-6f, "vertex $v x")
            assertEquals(open.position(v).y, closed.position(v).y, 1e-6f, "vertex $v y")
            assertEquals(open.position(v).z, closed.position(v).z, 1e-6f, "vertex $v z")
        }
    }

    // ==========================================================================================
    // Generators: measurement against analytic answers
    // ==========================================================================================

    @Test
    fun sphereVolumeAndAreaConvergeOnTheAnalyticValuesAsTheMeshRefines() {
        val exactVolume = 4.0 / 3.0 * PI
        val exactArea = 4.0 * PI
        var previousVolumeError = Double.MAX_VALUE
        var previousAreaError = Double.MAX_VALUE
        val errors = mutableListOf<Pair<Double, Double>>()
        for (subdivisions in 1..4) {
            val ball = icosphereMesh(subdivisions, 1.0)
            val ve = abs(ball.signedVolume() - exactVolume) / exactVolume
            val ae = abs(ball.surfaceArea() - exactArea) / exactArea
            errors.add(ve to ae)
            assertTrue(ve < previousVolumeError, "volume error must fall with refinement: $errors")
            assertTrue(ae < previousAreaError, "area error must fall with refinement: $errors")
            previousVolumeError = ve
            previousAreaError = ae
        }
        assertTrue(errors.last().first < 3e-3, "icosphere(4) volume error was ${errors.last().first}")
        assertTrue(errors.last().second < 3e-3, "icosphere(4) area error was ${errors.last().second}")
        // An inscribed polyhedron is always smaller than the ball it approximates.
        assertTrue(icosphereMesh(4).signedVolume() < exactVolume)
    }

    @Test
    fun centroidsSitWhereSymmetryPutsThem() {
        val offset = icosphereMesh(3, 1.0).translated(3, -2, 1)
        val surface = offset.centroid()
        val solid = offset.solidCentroid()
        for ((got, want) in listOf(surface.x to 3f, surface.y to -2f, surface.z to 1f)) {
            assertEquals(want, got, 1e-3f, "surface centroid")
        }
        for ((got, want) in listOf(solid.x to 3f, solid.y to -2f, solid.z to 1f)) {
            assertEquals(want, got, 1e-3f, "solid centroid")
        }
        // A cone's solid centroid is a quarter of the way up from its base, its surface centroid is not.
        val cone = coneMesh(64, radius = 1.0, height = 4.0)
        assertEquals(-2f + 1f, cone.solidCentroid().y, 0.02f, "cone solid centroid at base + h/4")
        assertTrue(abs(cone.centroid().y - cone.solidCentroid().y) > 0.05f, "the two centroids differ")
    }

    @Test
    fun mixedVertexAreasSumToTheSurfaceArea() {
        for (mesh in listOf(icosphereMesh(3), torusMesh(24, 12), planeMesh(6, 6), boxMesh())) {
            assertEquals(mesh.surfaceArea(), mesh.mixedVertexAreas().sum(), 1e-4, "${mesh.summary()}")
        }
    }

    // ==========================================================================================
    // Repairs
    // ==========================================================================================

    @Test
    fun weldTurnsATriangleSoupBackIntoAClosedMesh() {
        val soup = icosphereMesh(2).flatShaded()
        assertEquals(soup.triangleCount * 3, soup.vertexCount, "flatShaded shares nothing")
        assertTrue(!soup.isWatertight(), "an unwelded soup is not watertight")
        val welded = soup.weld(1e-5)
        assertClosedGenus(welded, 0, "welded soup")
        assertEquals(icosphereMesh(2).vertexCount, welded.vertexCount)
        assertEquals(icosphereMesh(2).signedVolume(), welded.signedVolume(), 1e-5)
    }

    @Test
    fun weldWithZeroToleranceStillMergesExactDuplicates() {
        val soup = boxMesh().flatShaded()
        val welded = soup.weld(0.0)
        assertEquals(8, welded.vertexCount)
        assertTrue(welded.isWatertight())
    }

    @Test
    fun fixWindingRepairsBothAScrambledAndAnInvertedMesh() {
        val good = icosphereMesh(2)
        // Scramble: flip half the triangles' winding, which makes neighbours disagree.
        val scrambledIndices = good.indices.copyOf()
        for (t in 0 until good.triangleCount step 2) {
            val tmp = scrambledIndices[t * 3 + 1]
            scrambledIndices[t * 3 + 1] = scrambledIndices[t * 3 + 2]
            scrambledIndices[t * 3 + 2] = tmp
        }
        val scrambled = TriMesh(good.positions, scrambledIndices)
        assertTrue(!scrambled.topology().isConsistentlyOriented, "the scramble should be inconsistent")
        val fixed = scrambled.fixWinding()
        assertTrue(fixed.topology().isConsistentlyOriented, "fixWinding should make it consistent")
        assertEquals(good.signedVolume(), fixed.signedVolume(), 1e-5, "and outward")

        val inverted = good.flipped()
        assertTrue(inverted.topology().isConsistentlyOriented, "an inside-out mesh is still consistent")
        assertTrue(inverted.signedVolume() < 0.0)
        assertEquals(good.signedVolume(), inverted.fixWinding().signedVolume(), 1e-5)
    }

    @Test
    fun removeDegenerateTrianglesDropsSlivers() {
        // Three collinear points make a zero-area triangle; the mesh around it is fine.
        val mesh = triMesh {
            val a = vertex(0, 0, 0)
            val b = vertex(1, 0, 0)
            val c = vertex(2, 0, 0)
            val d = vertex(0, 1, 0)
            triangle(a, b, c)   // degenerate
            triangle(a, b, d)   // real
        }
        assertEquals(2, mesh.triangleCount)
        val cleaned = mesh.removeDegenerateTriangles()
        assertEquals(1, cleaned.triangleCount)
        assertEquals(3, cleaned.vertexCount, "the orphaned collinear vertex should go too")
    }

    @Test
    fun largestComponentKeepsTheBigShapeAndDropsTheSpecks() {
        val main = icosphereMesh(3)
        val speck = tetrahedronMesh(0.05).translated(5, 0, 0)
        val both = main + speck
        assertEquals(2, both.topology().componentCount)
        val kept = both.largestComponent()
        assertEquals(1, kept.topology().componentCount)
        assertEquals(main.triangleCount, kept.triangleCount)
    }

    @Test
    fun nonManifoldDefectsAreDetectedRatherThanIgnored() {
        // Three triangles sharing one edge: a manifold-edge violation.
        val fin = triMesh {
            val a = vertex(0, 0, 0)
            val b = vertex(1, 0, 0)
            triangle(a, b, vertex(0, 1, 0))
            triangle(a, b, vertex(0, 0, 1))
            triangle(a, b, vertex(0, -1, 0))
        }
        val topo = fin.topology()
        assertEquals(1, topo.nonManifoldEdges.size, "the shared edge should be flagged")
        assertTrue(!topo.isManifold)
        assertEquals(null, fin.genus(), "genus is undefined for a non-manifold mesh")

        // Two tetrahedra meeting at one vertex: a manifold-*vertex* violation with no bad edge. Built
        // by reflecting a tetrahedron through its own corner 0, which is the one point the two share.
        val tetra = tetrahedronMesh(1.0)
        val pivot = tetra.position(0)
        val mirrored = tetra.transformed { p -> Vec3(2f * pivot.x - p.x, 2f * pivot.y - p.y, 2f * pivot.z - p.z) }
        val bowtie = (tetra + mirrored).weld(1e-4)
        assertEquals(7, bowtie.vertexCount, "exactly one vertex should have merged")
        val bowtieTopo = bowtie.topology()
        assertTrue(bowtieTopo.nonManifoldEdges.isEmpty(), "no edge has three faces")
        assertEquals(1, bowtieTopo.nonManifoldVertices.size, "the pinch vertex should be flagged")
    }

    @Test
    fun repairedIsTheOneCallThatFixesAFileYouDidNotWrite() {
        val broken = icosphereMesh(2).flatShaded().flipped()
        val fixed = broken.repaired(1e-5)
        assertClosedGenus(fixed, 0, "repaired mesh")
        assertEquals(icosphereMesh(2).signedVolume(), fixed.signedVolume(), 1e-5)
    }

    // ==========================================================================================
    // Triangulation
    // ==========================================================================================

    @Test
    fun earClippingHandlesConcaveAndReversedPolygons() {
        val star = (0 until 10).map { i ->
            val a = 2.0 * PI * i / 10
            val r = if (i % 2 == 0) 1.0 else 0.45
            Vec2((r * kotlin.math.cos(a)).toFloat(), (r * kotlin.math.sin(a)).toFloat())
        }
        val expectedArea = abs(polygonSignedArea(star))
        for (input in listOf(star, star.reversed())) {
            val tri = triangulatePolygon(input)
            assertEquals((input.size - 2) * 3, tri.size, "n-gon must give n-2 triangles")
            var area = 0.0
            for (k in tri.indices step 3) {
                val a = input[tri[k]]; val b = input[tri[k + 1]]; val c = input[tri[k + 2]]
                // Signed, and every triangle must come out counter-clockwise.
                val signed = ((b.x - a.x).toDouble() * (c.y - a.y) - (b.y - a.y).toDouble() * (c.x - a.x)) / 2.0
                assertTrue(signed > 0.0, "triangle at $k was wound clockwise")
                area += signed
            }
            assertEquals(expectedArea, area, 1e-4, "the pieces must tile the polygon exactly")
        }
    }

    @Test
    fun boundaryLoopsAreFoundOnASurfaceWithTwoHoles() {
        // A plane grid with the middle quad removed: outer border plus one inner hole.
        val plane = planeMesh(5, 5)
        val punched = plane.filterTriangles { t ->
            val c = plane.centroidOfTriangle(t)
            !(abs(c.x) < 0.11f && abs(c.y) < 0.11f)
        }
        val loops = punched.topology().boundaryLoops
        assertEquals(2, loops.size, "outer border and one hole; got ${loops.map { it.size }}")
        assertEquals(0, punched.topology().eulerCharacteristic, "an annulus has chi = 0")
    }

    private fun TriMesh.centroidOfTriangle(t: Int): Vec3 {
        val (a, b, c) = triangle(t)
        return Vec3((a.x + b.x + c.x) / 3f, (a.y + b.y + c.y) / 3f, (a.z + b.z + c.z) / 3f)
    }
}
