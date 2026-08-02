package afield.stdlib.polytope

import afield.graphics.FLine
import afield.stdlib.Vec3
import afield.stdlib.filled
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolytopeCoreTest {
    private data class SolidCase(
        val name: String,
        val object3: PolyhedralObject,
        val vertices: Int,
        val edges: Int,
        val faces: Int,
        val triangles: Int,
    )

    private fun solids() = listOf(
        SolidCase("tetrahedron", tetrahedron(), 4, 6, 4, 4),
        SolidCase("cube", cube(), 8, 12, 6, 12),
        SolidCase("octahedron", octahedron(), 6, 12, 8, 8),
    )

    @Test
    fun constructorsHaveExpectedCountsAndValidTopology() {
        for (case in solids()) with(case.object3.topology) {
            assertEquals(case.vertices, elements(0).size, "${case.name} vertices")
            assertEquals(case.edges, elements(1).size, "${case.name} edges")
            assertEquals(case.faces, elements(2).size, "${case.name} faces")
            assertEquals(1, elements(3).size, "${case.name} whole element")
            assertTrue(validate(checkManifold = true).isValid, "${case.name}: ${validate(true).issues}")
            assertEquals(2, eulerCharacteristic(), "${case.name} Euler characteristic")
            elements(1).forEach { edge ->
                assertEquals(2, incidentDown(edge.id).distinct().size, "${case.name} edge ${edge.id.value}")
                assertEquals(2, incidentUp(edge.id).distinct().size, "${case.name} edge ${edge.id.value} faces")
            }
        }
    }

    @Test
    fun faceCyclesAreOrderedCompleteBoundaries() {
        for (case in solids()) {
            val topology = case.object3.topology
            for (face in topology.elements(2)) {
                val cycle = topology.faceCycle(face.id)
                assertTrue(cycle.size >= 3, "${case.name} face ${face.id.value}")
                assertEquals(cycle.size, cycle.distinct().size, "cycle vertices are unique")
                assertEquals(cycle.size, topology.incidentDown(face.id).distinct().size, "one edge per boundary step")
                assertEquals(cycle.toList(), topology.verticesOfFace(face.id).map { it.id.value })
            }
        }
    }

    @Test
    fun builtInFaceWindingIsOutward() {
        for (case in solids()) {
            val polyhedron = case.object3
            val coordinates = assertNotNull(polyhedron.realization).vertexCoordinates
            val center = Vec3()
            coordinates.forEach { center.add(it) }
            center.mul(1f / coordinates.size)
            val vertexIndex = polyhedron.topology.elements(0).mapIndexed { i, element -> element.id to i }.toMap()
            for (face in polyhedron.topology.elements(2)) {
                val faceCenter = Vec3()
                val cycle = polyhedron.topology.faceCycle(face.id)
                cycle.forEach { faceCenter.add(coordinates[vertexIndex.getValue(ElementId(it))]) }
                faceCenter.mul(1f / cycle.size).sub(center)
                assertTrue(
                    polyhedron.faceNormal(face.id).dot(faceCenter) > 0f,
                    "${case.name} face ${face.id.value} should point outward",
                )
            }
        }
    }

    @Test
    fun realizationAndTopologyArrayReadsAreDefensiveCopies() {
        val cube = cube()
        val realization = assertNotNull(cube.realization)
        val before = realization[0]
        realization.vertexCoordinates[0].add(100f, 100f, 100f)
        val after = realization[0]
        assertEquals(before, after)

        val vertex = cube.topology.elements(0).first().id
        val first = cube.topology.incidentUp(vertex)
        val expected = first.copyOf()
        first.fill(-1)
        assertContentEquals(expected, cube.topology.incidentUp(vertex))
    }

    @Test
    fun rankTwoPolygonUsesTheSameGeneralIncidenceCoreAndCanBeDualized() {
        val builder = PolytopeTopologyBuilder(rank = 2)
        val vertices = List(3) { builder.addElement(0) }
        val edges = List(3) { builder.addElement(1) }
        builder.addIncidence(vertices[0], edges[0]).addIncidence(vertices[1], edges[0])
        builder.addIncidence(vertices[1], edges[1]).addIncidence(vertices[2], edges[1])
        builder.addIncidence(vertices[2], edges[2]).addIncidence(vertices[0], edges[2])
        val face = builder.addElement(2)
        edges.forEach { builder.addIncidence(it, face) }
        builder.setFaceCycle(face, vertices)

        val triangle = builder.buildValidated()
        assertEquals(listOf(3, 3, 1), (0..2).map { triangle.elements(it).size })
        val dual = triangle.dual()
        assertTrue(dual.validate().isValid, dual.validate().issues.toString())
        assertEquals(listOf(3, 3, 1), (0..2).map { dual.elements(it).size })
        assertEquals(3, dual.faceCycle(dual.elements(2).single().id).size)
    }

    @Test
    fun abstractDualsHaveExpectedCombinatorics() {
        val tetrahedron = tetrahedron().topology
        val cube = cube().topology
        val octahedron = octahedron().topology

        val tetraDual = tetrahedron.dual()
        val cubeDual = cube.dual()
        val octaDual = octahedron.dual()
        assertTrue(tetraDual.validate(checkManifold = true).isValid, tetraDual.validate(true).issues.toString())
        assertTrue(cubeDual.validate(checkManifold = true).isValid, cubeDual.validate(true).issues.toString())
        assertTrue(octaDual.validate(checkManifold = true).isValid, octaDual.validate(true).issues.toString())
        assertTrue(tetraDual.isCombinatoriallyIsomorphicTo(tetrahedron))
        assertTrue(cubeDual.isCombinatoriallyIsomorphicTo(octahedron))
        assertTrue(octaDual.isCombinatoriallyIsomorphicTo(cube))

        for (source in listOf(tetrahedron, cube, octahedron)) {
            assertTrue(source.dual().dual().isCombinatoriallyIsomorphicTo(source))
        }
    }

    @Test
    fun dualCountsReverseProperRanksAndDoNotInventCoordinates() {
        val source = cube()
        val dual = source.topology.dual()
        assertEquals(source.topology.elements(2).size, dual.elements(0).size)
        assertEquals(source.topology.elements(1).size, dual.elements(1).size)
        assertEquals(source.topology.elements(0).size, dual.elements(2).size)
        assertEquals(1, dual.elements(3).size)
        assertEquals(null, PolyhedralObject(dual).realization)
    }

    @Test
    fun flineAdapterProducesOneClosedProvenancedFaceLine() {
        for (case in solids()) {
            val lines = case.object3.toFaceFLines { _, line -> line.filled = true }
            assertEquals(case.faces, lines.size, "${case.name} face lines")
            lines.forEachIndexed { i, line ->
                assertIs<FLine.Close>(line.nodes.last())
                val face = case.object3.topology.elements(2)[i]
                assertEquals(face.id.value, line.properties[POLYTOPE_FACE_ID_PROPERTY])
                assertEquals(face.rank, line.properties[POLYTOPE_FACE_RANK_PROPERTY])
                assertEquals(case.object3.topology.faceCycle(face.id).size + 1, line.nodes.size)
                assertTrue(line.filled)
            }
        }
    }

    @Test
    fun triangulationHasExpectedCountsWindingNormalsAndProvenance() {
        for (case in solids()) {
            val geometry = case.object3.triangulate()
            val mesh = geometry.mesh
            assertEquals(case.triangles, mesh.triangleCount, "${case.name} triangle count")
            assertEquals(mesh.triangleCount, geometry.triangleFaceIds.size)
            assertEquals(mesh.vertexCount, geometry.vertexElementIds.size)
            assertTrue(mesh.hasNormals)

            val trianglesPerFace = geometry.triangleFaceIds.toList().groupingBy { it }.eachCount()
            for (face in case.object3.topology.elements(2)) {
                assertEquals(
                    case.object3.topology.faceCycle(face.id).size - 2,
                    trianglesPerFace[face.id.value],
                    "${case.name} source face ${face.id.value}",
                )
            }
            for (triangle in 0 until mesh.triangleCount) {
                val expected = case.object3.faceNormal(geometry.sourceFace(triangle))
                assertTrue(mesh.faceNormal(triangle).dot(expected) > 0.9999f, "${case.name} triangle $triangle winding")
                for (vertex in intArrayOf(mesh.ia(triangle), mesh.ib(triangle), mesh.ic(triangle))) {
                    val normal = assertNotNull(mesh.normal(vertex))
                    assertTrue(normal.dot(expected) > 0.9999f, "${case.name} triangle $triangle flat normal")
                    assertTrue(normal.distance(expected) < 1e-5f)
                }
            }
        }
    }

    @Test
    fun fanStrategyIsExplicitlyReplaceableAndRejectsForeignVertices() {
        val badStrategy = FaceTriangulator { cycle, _ -> intArrayOf(cycle[0], cycle[1], Int.MAX_VALUE) }
        val error = assertFailsWith<IllegalArgumentException> { tetrahedron().triangulate(badStrategy) }
        assertTrue(error.message.orEmpty().contains("outside face"))
    }

    @Test
    fun malformedBuilderInputsProduceStructuredUsefulIssues() {
        val builder = PolytopeTopologyBuilder(rank = 3)
        val v0 = builder.addElement(0)
        val v1 = builder.addElement(0)
        val v2 = builder.addElement(0)
        val v3 = builder.addElement(0)
        val e01 = builder.addElement(1)
        val e12 = builder.addElement(1)
        val e20 = builder.addElement(1)
        val face = builder.addElement(2)
        val whole = builder.addElement(3)
        builder.addIncidence(v0, e01).addIncidence(v1, e01)
        builder.addIncidence(v1, e12).addIncidence(v2, e12)
        builder.addIncidence(v2, e20) // deliberately missing its other endpoint
        builder.addIncidence(e01, face).addIncidence(e01, face) // duplicate
        builder.addIncidence(e12, face).addIncidence(e20, face)
        builder.addIncidence(face, whole)
        builder.addIncidence(v0, face) // skips rank 1
        builder.addIncidence(ElementId(999), face)
        builder.setFaceCycle(face, listOf(v0, v1, v3)) // v3 is not incident; v2 is omitted
        builder.setFaceCycle(e01, listOf(v0, v1, v2)) // cycle owner has the wrong rank

        val result = builder.build().validate()
        assertTrue(!result.isValid)
        assertTrue(result.issues.any { it is EdgeEndpointCount })
        assertTrue(result.issues.any { it is DuplicateIncidence })
        assertTrue(result.issues.any { it is IncorrectRankTransition })
        assertTrue(result.issues.any { it is InvalidElementReference })
        assertTrue(result.issues.any { it is FaceCycleOwnerWrongRank })
        assertTrue(result.issues.any { it is FaceCycleVertexNotIncident })
        assertTrue(result.issues.any { it is FaceCycleEdgeMismatch })
        val thrown = assertFailsWith<TopologyValidationException> { result.throwIfInvalid() }
        assertTrue(thrown.message.orEmpty().contains("edge"))
        assertTrue(thrown.message.orEmpty().contains("face"))
    }

    @Test
    fun degenerateRealizationReportsTheFaceInsteadOfReturningNan() {
        val builder = PolytopeTopologyBuilder(rank = 3)
        val vertices = List(3) { builder.addElement(0) }
        val edges = List(3) { builder.addElement(1) }
        builder.addIncidence(vertices[0], edges[0]).addIncidence(vertices[1], edges[0])
        builder.addIncidence(vertices[1], edges[1]).addIncidence(vertices[2], edges[1])
        builder.addIncidence(vertices[2], edges[2]).addIncidence(vertices[0], edges[2])
        val face = builder.addElement(2)
        edges.forEach { builder.addIncidence(it, face) }
        builder.setFaceCycle(face, vertices)
        val whole = builder.addElement(3)
        builder.addIncidence(face, whole)
        val topology = builder.buildValidated()
        assertTrue(topology.validate().isValid, "basic incidence validation permits an open surface")
        assertTrue(topology.validate(checkManifold = true).issues.any { it is NonManifoldEdge })
        val object3 = PolyhedralObject(
            topology,
            Realization3(listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f))),
        )
        val error = assertFailsWith<DegenerateFaceGeometryException> { object3.faceNormal(face) }
        assertEquals(face, error.face)
    }

    @Test
    fun offExportIsPolygonalLocaleIndependentAndNewlineTerminated() {
        val off = cube().toOff()
        val lines = off.lines()
        assertEquals("OFF", lines[0])
        assertEquals("8 6 12", lines[1])
        assertTrue(lines.drop(2).take(8).all { it.split(' ').size == 3 })
        assertTrue(lines.drop(10).take(6).all { it.startsWith("4 ") })
        assertTrue(off.endsWith('\n'))
        assertTrue(',' !in off)
        assertFailsWith<IllegalStateException> { PolyhedralObject(cube().topology).toOff() }
    }
}
