package afield.stdlib.polytope

import afield.stdlib.TriMeshBuilder
import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A face may have holes, and everything that draws one knows it.
 *
 * Two claims are load-bearing here and neither is asserted by a comment. The first is that a hole is
 * really subtracted: no triangle of [cubeWithWindow]'s top face covers the middle of its window, and
 * the filled area is short by exactly the window's area. The second is the counterfactual — that a
 * face *without* holes takes the path it always took — which is measured against
 * [legacyTriangulate], a copy of `PolyhedralObject.triangulate` as it stood at `9052820d8`, before
 * rings existed. Equal vertex for vertex, index for index and id for id, on every seed.
 *
 * `PolytopeFaceHolesRenderProofTest` is the same two claims in pixels.
 */
class PolytopeFaceHolesTest {

    private val window = 0.5f

    // ------------------------------------------------------------------------------- the topology

    @Test
    fun aWindowedCubeIsAValidComplexButNotAClosedManifold() {
        val solid = cubeWithWindow(window)
        val topology = solid.topology

        solid.topology.validate().throwIfInvalid()
        val manifold = topology.validate(checkManifold = true)
        assertFalse(manifold.isValid, "a face with a hole is a surface with boundary, not a closed manifold")
        assertTrue(
            manifold.issues.all { it is NonManifoldEdge },
            "the only complaint should be the window's one-sided edges, got ${manifold.issues.map { it.message }}",
        )
        assertEquals(4, manifold.issues.size, "one per window edge")

        assertEquals(12, topology.elements(0).size)
        assertEquals(16, topology.elements(1).size)
        assertEquals(6, topology.elements(2).size)

        // Counting elements the object is a sphere; counting *cells* it is a sphere with a disk
        // removed, because a face with one hole contributes 1 - 1 = 0 rather than 1.
        assertEquals(2, topology.eulerCharacteristic())
        assertEquals(1, topology.cellularEulerCharacteristic())
        assertEquals(1, topology.totalFaceHoleCount())
    }

    @Test
    fun aFaceBoundaryIsAnOuterRingPlusItsHolesAndHandsOutCopies() {
        val topology = cubeWithWindow(window).topology
        val windowed = topology.elements(2).single { topology.faceHasHoles(it.id) }
        val plain = topology.elements(2).first { !topology.faceHasHoles(it.id) }

        val boundary = topology.faceBoundary(windowed.id)
        assertEquals(4, boundary.outer.size)
        assertEquals(1, boundary.holeCount)
        assertEquals(4, boundary.holes.single().size)
        assertEquals(2, boundary.rings.size)
        assertContentEquals(topology.faceCycle(windowed.id), boundary.outer)
        assertContentEquals(boundary.outer, boundary.rings.first())

        // Nothing handed out is a view onto the stored arrays.
        boundary.outer[0] = -1
        boundary.holes.single()[0] = -1
        topology.faceHoles(windowed.id).single()[1] = -1
        assertContentEquals(topology.faceBoundary(windowed.id).outer, topology.faceCycle(windowed.id))
        assertFalse(topology.faceHoles(windowed.id).single().contains(-1))

        // An ordinary face is untouched by any of this.
        assertFalse(topology.faceHasHoles(plain.id))
        assertEquals(emptyList(), topology.faceHoles(plain.id))
        assertEquals(1, topology.faceRings(plain.id).size)
        assertEquals(0, topology.faceBoundary(plain.id).holeCount)
        assertContentEquals(topology.faceCycle(plain.id), topology.faceRings(plain.id).single())
    }

    @Test
    fun theWindowRingIsWoundAgainstItsOuterRing() {
        val solid = cubeWithWindow(window)
        val topology = solid.topology
        val realization = requireNotNull(solid.realization)
        val windowed = topology.elements(2).single { topology.faceHasHoles(it.id) }
        val index = topology.elements(0).mapIndexed { at, vertex -> vertex.id.value to at }.toMap()

        fun newell(ring: IntArray): Vec3 {
            val normal = Vec3()
            for (i in ring.indices) {
                val p = realization[index.getValue(ring[i])]
                val q = realization[index.getValue(ring[(i + 1) % ring.size])]
                normal.add(Vec3((p.y - q.y) * (p.z + q.z), (p.z - q.z) * (p.x + q.x), (p.x - q.x) * (p.y + q.y)))
            }
            return normal
        }

        val outer = newell(topology.faceCycle(windowed.id))
        val hole = newell(topology.faceHoles(windowed.id).single())
        assertTrue(outer.dot(hole) < 0f, "hole normal $hole should oppose the outer normal $outer")
        // And the face's own normal is read from the outer ring alone, so it still points out of the
        // cube rather than being cancelled down by the hole.
        val normal = solid.faceNormal(windowed.id)
        assertTrue(normal.z > 0.99f, "windowed top face normal should be +z, got $normal")
    }

    // ------------------------------------------------------------------------------- what is caught

    /** The cube's top face, with whatever inner rings a case wants, assembled by hand. */
    private fun windowedCubeBuilder(): Triple<PolytopeTopologyBuilder, ElementId, List<ElementId>> {
        val builder = PolytopeTopologyBuilder(rank = 3)
        val vertices = List(12) { builder.addElement(0) }
        val face = builder.addElement(2)
        val outer = listOf(4, 5, 6, 7)
        val inner = listOf(8, 9, 10, 11)
        for (ring in listOf(outer, inner)) for (i in ring.indices) {
            val edge = builder.addElement(1)
            builder.addIncidence(vertices[ring[i]], edge)
            builder.addIncidence(vertices[ring[(i + 1) % ring.size]], edge)
            builder.addIncidence(edge, face)
        }
        builder.setFaceCycle(face, outer.map { vertices[it] })
        return Triple(builder, face, vertices)
    }

    @Test
    fun aWellFormedHandBuiltHoleValidates() {
        val (builder, face, vertices) = windowedCubeBuilder()
        builder.addFaceHole(face, listOf(11, 10, 9, 8).map { vertices[it] })
        val issues = builder.build().validate().issues
        assertTrue(issues.isEmpty(), "expected a clean face, got ${issues.map { it.message }}")
    }

    @Test
    fun aHoleTouchingItsOuterRingIsReported() {
        val (builder, face, vertices) = windowedCubeBuilder()
        // Ring 8-9-10-4 shares vertex 4 with the outer ring: the face is pinched, not holed.
        builder.addFaceHole(face, listOf(8, 9, 10, 4).map { vertices[it] })
        val issues = builder.build().validate().issues
        assertTrue(
            issues.any { it is FaceRingsShareVertex && it.vertex == vertices[4] },
            "expected FaceRingsShareVertex, got ${issues.map { it.message }}",
        )
    }

    @Test
    fun holesWithNoOuterCycleAreReported() {
        // A face that was given rings to subtract and no outline to subtract them from.
        val orphan = PolytopeTopologyBuilder(rank = 3)
        val orphanFace = orphan.addElement(2)
        val orphanVertices = List(3) { orphan.addElement(0) }
        orphan.addFaceHole(orphanFace, orphanVertices)
        val issues = orphan.build().validate().issues
        assertTrue(
            issues.any { it is FaceHolesWithoutOuterBoundary },
            "expected FaceHolesWithoutOuterBoundary, got ${issues.map { it.message }}",
        )
    }

    @Test
    fun aHoleRingWithNoEdgesOfItsOwnIsReported() {
        val builder = PolytopeTopologyBuilder(rank = 3)
        val vertices = List(8) { builder.addElement(0) }
        val face = builder.addElement(2)
        val outer = listOf(0, 1, 2, 3)
        for (i in outer.indices) {
            val edge = builder.addElement(1)
            builder.addIncidence(vertices[outer[i]], edge)
            builder.addIncidence(vertices[outer[(i + 1) % outer.size]], edge)
            builder.addIncidence(edge, face)
        }
        builder.setFaceCycle(face, outer.map { vertices[it] })
        // Four vertices declared as a hole, with no edges recorded for them at all.
        builder.addFaceHole(face, listOf(4, 5, 6, 7).map { vertices[it] })
        val issues = builder.build().validate().issues
        assertTrue(issues.any { it is FaceCycleEdgeMismatch }, "expected the missing steps to be named")
        assertTrue(
            issues.any { it is DisconnectedFaceBoundary && it.expectedRings == 2 },
            "expected two boundary rings to be demanded, got ${issues.map { it.message }}",
        )
    }

    @Test
    fun aTwoVertexHoleIsTooSmall() {
        val (builder, face, vertices) = windowedCubeBuilder()
        builder.addFaceHole(face, listOf(8, 9).map { vertices[it] })
        val issues = builder.build().validate().issues
        assertTrue(
            issues.any { it is FaceHoleTooSmall && it.hole == 0 && it.vertexCount == 2 },
            "expected FaceHoleTooSmall, got ${issues.map { it.message }}",
        )
    }

    @Test
    fun setFaceHolesReplacesAndClears() {
        val (builder, face, vertices) = windowedCubeBuilder()
        builder.addFaceHole(face, listOf(8, 9, 10, 11).map { vertices[it] })
        assertEquals(1, builder.build().faceHoles(face).size)
        builder.setFaceHoles(face, emptyList())
        assertFalse(builder.build().faceHasHoles(face))
    }

    // ------------------------------------------------------- what refuses a hole, and says so

    @Test
    fun dualizationRefusesAHoledFace() {
        val message = assertFailsWith<IllegalArgumentException> { cubeWithWindow(window).topology.dual() }.message
        assertTrue(message!!.contains("hole"), message)
        assertTrue(message.contains("closed manifold"), message)
    }

    @Test
    fun conwayOperatorsRefuseAHoledFace() {
        for (operator in ConwayOperator.entries.filter { it.primitive }) {
            val message = assertFailsWith<IllegalArgumentException> {
                cubeWithWindow(window).topology.transform(operator)
            }.message
            assertTrue(message!!.contains("hole"), "$operator: $message")
        }
        val chained = assertFailsWith<IllegalArgumentException> { cubeWithWindow(window).bevel() }.message
        assertTrue(chained!!.contains("hole"), chained)
    }

    @Test
    fun offExportRefusesAHoledFace() {
        val message = assertFailsWith<IllegalArgumentException> { cubeWithWindow(window).toOff() }.message
        assertTrue(message!!.contains("OFF cannot express"), message)
    }

    @Test
    fun aWindowedCubeIsNotIsomorphicToACube() {
        val windowed = cubeWithWindow(window).topology
        assertTrue(windowed.isCombinatoriallyIsomorphicTo(cubeWithWindow(0.3f).topology))
        assertFalse(windowed.isCombinatoriallyIsomorphicTo(cube().topology), "element counts alone differ")

        // The sharper case: the identical incidence, element for element, with the hole declaration
        // taken away. Counting cannot tell these apart; the face boundaries can.
        val undeclared = withoutHoleDeclarations(cubeWithWindow(window))
        assertEquals(
            (0..3).map { windowed.elements(it).size },
            (0..3).map { undeclared.elements(it).size },
            "the two should differ only in whether the ring is a hole",
        )
        assertFalse(
            windowed.isCombinatoriallyIsomorphicTo(undeclared),
            "a holed face and a solid one over the same incidence must not compare equal",
        )
    }

    // ------------------------------------------------------------------------------ triangulation

    @Test
    fun theWindowIsSubtractedFromTheFaceItIsIn() {
        val solid = cubeWithWindow(window)
        val topology = solid.topology
        val geometry = solid.triangulate()
        val windowed = topology.elements(2).single { topology.faceHasHoles(it.id) }

        val topTriangles = (0 until geometry.triangleCount).filter { geometry.sourceFace(it) == windowed.id }
        // A square annulus tessellates into two triangles per side.
        assertEquals(8, topTriangles.size, "expected eight triangles around the window")

        val mesh = geometry.mesh
        fun corner(triangle: Int, at: Int): Vec3 {
            val vertex = mesh.indices[triangle * 3 + at]
            return Vec3(mesh.x(vertex), mesh.y(vertex), mesh.z(vertex))
        }

        // Every triangle is in the face's plane, and none of them covers the middle of the window.
        var area = 0.0
        for (triangle in topTriangles) {
            val a = corner(triangle, 0)
            val b = corner(triangle, 1)
            val c = corner(triangle, 2)
            for (point in listOf(a, b, c)) assertEquals(1f, point.z, 1e-6f, "triangle $triangle left the top plane")
            area += abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2.0
            assertFalse(
                containsXY(a, b, c, 0f, 0f),
                "triangle $triangle covers the middle of the window",
            )
        }
        // 2x2 face, 1x1 window: the filled area is short by exactly the window.
        assertEquals(4.0 - 4.0 * window * window, area, 1e-4, "filled area should be the face minus the window")

        // And the same face with the hole dropped fills the whole square — the counterfactual, live.
        // Selected by its plane rather than its id, because filling a hole in also removes the four
        // edges the ring owned and so renumbers everything after them.
        val filled = fillEveryHole(solid).triangulate()
        val filledMesh = filled.mesh
        var filledArea = 0.0
        for (triangle in 0 until filled.triangleCount) {
            val a = filledMesh.indices[triangle * 3]
            val b = filledMesh.indices[triangle * 3 + 1]
            val c = filledMesh.indices[triangle * 3 + 2]
            if (listOf(a, b, c).any { abs(filledMesh.z(it) - 1f) > 1e-6f }) continue
            filledArea += abs(
                (filledMesh.x(b) - filledMesh.x(a)) * (filledMesh.y(c) - filledMesh.y(a)) -
                    (filledMesh.x(c) - filledMesh.x(a)) * (filledMesh.y(b) - filledMesh.y(a)),
            ) / 2.0
        }
        assertEquals(4.0, filledArea, 1e-4, "with the ring dropped the same face fills solid")
    }

    @Test
    fun everyWindowVertexReachesTheMeshWithTheFaceNormal() {
        val solid = cubeWithWindow(window)
        val geometry = solid.triangulate()
        val topology = solid.topology
        val windowed = topology.elements(2).single { topology.faceHasHoles(it.id) }
        val holeVertices = topology.faceHoles(windowed.id).single().toSet()

        val reached = (0 until geometry.mesh.vertexCount)
            .map { geometry.sourceVertex(it).value }
            .toSet()
        assertTrue(reached.containsAll(holeVertices), "hole vertices never reached the render buffer")

        val normals = requireNotNull(geometry.mesh.normals)
        for (renderVertex in 0 until geometry.mesh.vertexCount) {
            if (geometry.sourceVertex(renderVertex).value !in holeVertices) continue
            assertEquals(1f, normals[renderVertex * 3 + 2], 1e-6f, "a window corner got the wrong flat normal")
        }
    }

    @Test
    fun aTriangulatorThatCannotDoHolesRefusesRatherThanFillingThemIn() {
        val message = assertFailsWith<IllegalArgumentException> {
            cubeWithWindow(window).triangulate(FanFaceTriangulator)
        }.message
        assertTrue(message!!.contains("FanFaceTriangulator"), message)
        assertTrue(message.contains("hole"), message)
        // …and it is fine on everything that has no holes.
        assertEquals(12, cube().triangulate(FanFaceTriangulator).triangleCount)
    }

    // -------------------------------------------------------------------- the unchanged counterfactual

    @Test
    fun aFaceWithNoHolesTakesExactlyThePathItAlwaysTook() {
        val seeds = mapOf(
            "tetrahedron" to tetrahedron(),
            "cube" to cube(),
            "octahedron" to octahedron(),
            "icosahedron" to icosahedron(),
            "dodecahedron" to dodecahedron(),
            "bC" to conway("bC").polyhedron,
            "windowed cube with its ring dropped" to fillEveryHole(cubeWithWindow(window)),
        )
        for ((name, solid) in seeds) {
            val now = solid.triangulate()
            val before = solid.legacyTriangulate()
            assertContentEquals(before.mesh.positions, now.mesh.positions, "$name positions")
            assertContentEquals(before.mesh.indices, now.mesh.indices, "$name indices")
            assertContentEquals(before.mesh.normals, now.mesh.normals, "$name normals")
            assertContentEquals(before.triangleFaceIds, now.triangleFaceIds, "$name triangle provenance")
            assertContentEquals(before.vertexElementIds, now.vertexElementIds, "$name vertex provenance")
            assertEquals(before.triangleAttributes, now.triangleAttributes, "$name triangle attributes")
            assertEquals(before.vertexAttributes, now.vertexAttributes, "$name vertex attributes")
        }
    }

    /**
     * `PolyhedralObject.triangulate` exactly as it stood at `9052820d8`, before a face could have
     * rings. Kept here so "the hole-free path is unchanged" is a measurement rather than a claim: if
     * the two ever disagree on a hole-free solid, the change was not additive after all.
     */
    private fun PolyhedralObject.legacyTriangulate(): TriangulatedGeometry {
        val realization = realization ?: throw IllegalStateException("triangulation requires a 3D realization")
        topology.validate().throwIfInvalid()
        val mesh = TriMeshBuilder()
        val triangleFaces = mutableListOf<Int>()
        val renderVertices = mutableListOf<Int>()
        val triangleAttributes = mutableListOf<Map<String, Any?>>()
        val vertexAttributes = mutableListOf<Map<String, Any?>>()
        val realizationIndex = topology.elements(0).mapIndexed { index, vertex -> vertex.id.value to index }.toMap()

        for (face in topology.elements(2)) {
            val elementCycle = topology.faceCycle(face.id)
            val cycle = elementCycle.map { realizationIndex.getValue(it) }.toIntArray()
            val normal = faceNormal(face.id)
            val localRenderIndex = mutableMapOf<Int, Int>()
            for (i in cycle.indices) {
                val coordinateIndex = cycle[i]
                val elementId = elementCycle[i]
                val position = realization[coordinateIndex]
                localRenderIndex[coordinateIndex] = mesh.vertex(position.x, position.y, position.z, normal = normal)
                renderVertices += elementId
                vertexAttributes += attributes[ElementId(elementId)]
            }

            val indices = EarcutFaceTriangulator.triangulate(cycle.copyOf(), realization)
            for (i in indices.indices step 3) {
                mesh.triangle(
                    localRenderIndex.getValue(indices[i]),
                    localRenderIndex.getValue(indices[i + 1]),
                    localRenderIndex.getValue(indices[i + 2]),
                )
                triangleFaces += face.id.value
                triangleAttributes += attributes[face.id]
            }
        }
        return TriangulatedGeometry(
            mesh.build(),
            triangleFaces.toIntArray(),
            renderVertices.toIntArray(),
            triangleAttributes,
            vertexAttributes,
        )
    }

    // ------------------------------------------------------------------------------------- FLines

    @Test
    fun aHoledFaceBecomesOneLineWithTwoContours() {
        val solid = cubeWithWindow(window)
        val topology = solid.topology
        val windowed = topology.elements(2).single { topology.faceHasHoles(it.id) }
        val lines = solid.toFaceFLines()
        assertEquals(6, lines.size, "one line per face, and a hole is not a face")

        val line = lines.single { it.properties[POLYTOPE_FACE_ID_PROPERTY] == windowed.id.value }
        // Four corners and a close, twice over.
        assertEquals(10, line.nodes.size)

        // Field's own fill path subtracts the second contour rather than drawing it as a second face.
        var area = 0.0
        line.tessellate { positions, indices ->
            for (i in indices.indices step 3) {
                val a = indices[i] * 3
                val b = indices[i + 1] * 3
                val c = indices[i + 2] * 3
                area += abs(
                    (positions[b] - positions[a]) * (positions[c + 1] - positions[a + 1]) -
                        (positions[c] - positions[a]) * (positions[b + 1] - positions[a + 1]),
                ) / 2.0
            }
        }
        assertEquals(4.0 - 4.0 * window * window, area, 1e-3, "the FLine fill should have a hole in it")
    }

    // ---------------------------------------------------------------------------------- machinery

    /**
     * The same object with every hole filled in: the inner rings dropped, and the edges that only
     * those rings traversed dropped with them, so the result is a valid closed complex again. This is
     * the pre-holes behaviour computed rather than described — the counterfactual half of the area,
     * pixel and unchanged-path tests.
     */
    private fun fillEveryHole(solid: PolyhedralObject): PolyhedralObject {
        val source = solid.topology
        val holeEdges = mutableSetOf<ElementId>()
        for (face in source.elements(2)) {
            val faceEdges = source.incidentDown(face.id).map(::ElementId)
            for (ring in source.faceHoles(face.id)) for (i in ring.indices) {
                val ends = setOf(ring[i], ring[(i + 1) % ring.size])
                holeEdges += faceEdges.filter { source.incidentDown(it).toSet() == ends }
            }
        }
        return rebuild(solid) { edge -> edge !in holeEdges }
    }

    /**
     * The same incidence, element for element, with the hole *declarations* removed and nothing else —
     * an invalid complex on purpose, and the only way to ask whether the isomorphism check can see a
     * hole at all rather than merely counting elements.
     */
    private fun withoutHoleDeclarations(solid: PolyhedralObject): PolytopeTopology =
        rebuild(solid) { true }.topology

    private fun rebuild(solid: PolyhedralObject, keepEdge: (ElementId) -> Boolean): PolyhedralObject {
        val source = solid.topology
        val builder = PolytopeTopologyBuilder(rank = source.rank)
        val mapped = mutableMapOf<ElementId, ElementId>()
        for (rank in 0..source.rank) for (element in source.elements(rank)) {
            if (rank == 1 && !keepEdge(element.id)) continue
            mapped[element.id] = builder.addElement(rank)
        }
        for (rank in 0 until source.rank) for (element in source.elements(rank)) {
            val lower = mapped[element.id] ?: continue
            for (higher in source.incidentUp(element.id)) {
                val upper = mapped[ElementId(higher)] ?: continue
                builder.addIncidence(lower, upper)
            }
        }
        for (face in source.elements(2)) {
            builder.setFaceCycle(
                mapped.getValue(face.id),
                source.faceCycle(face.id).map { mapped.getValue(ElementId(it)).value }.toIntArray(),
            )
        }
        return PolyhedralObject(builder.build(), solid.realization, solid.attributes)
    }

    private fun containsXY(a: Vec3, b: Vec3, c: Vec3, x: Float, y: Float): Boolean {
        fun side(p: Vec3, q: Vec3) = (q.x - p.x) * (y - p.y) - (q.y - p.y) * (x - p.x)
        val ab = side(a, b)
        val bc = side(b, c)
        val ca = side(c, a)
        return (ab >= 0f && bc >= 0f && ca >= 0f) || (ab <= 0f && bc <= 0f && ca <= 0f)
    }
}
