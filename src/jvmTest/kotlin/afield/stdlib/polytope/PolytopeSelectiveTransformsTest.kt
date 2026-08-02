package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** These examples are intentionally small enough to become interactive Field demos verbatim. */
class PolytopeSelectiveTransformsTest {
    @Test
    fun explicitFaceSelectionKisOnlyReplacesChosenFaces() {
        val source = cube()
        val selected = source.topology.elements(2).take(2).map { it.id }
        val result = source.kis(ElementSelectors.ids(selected))
        val output = result.polyhedron

        assertCounts(output.topology, vertices = 10, edges = 20, faces = 12)
        assertEquals(mapOf(3 to 8, 4 to 4), faceSizes(output))
        assertEquals(16, output.triangulate().triangleCount)
        assertOutward(output)
        assertEquals(2, result.selectElements(ElementSelectors.provenanceRole("face-apex"), rank = 0).size)
    }

    @Test
    fun faceAttributesSelectKisTargetsAndDeployDifferentHeights() {
        var source = cube()
        val selectedFaces = source.topology.elements(2).take(2)
        source = source
            .withAttribute(selectedFaces[0].id, "raise", true)
            .withAttribute(selectedFaces[0].id, "height", 0.18)
            .withAttribute(selectedFaces[1].id, "raise", true)
            .withAttribute(selectedFaces[1].id, "height", 0.72)

        val result = source.kis(
            ElementSelectors.attributeEquals("raise", true),
            ElementParameters.attribute("height"),
        )
        val apexRadii = result.selectElements(ElementSelectors.provenanceRole("face-apex"), rank = 0)
            .map { result.polyhedron.coordinate(it.id).length() }
            .sorted()
        assertEquals(2, apexRadii.size)
        assertTrue(apexRadii[1] - apexRadii[0] > 0.8f, "varying heights should visibly separate apex radii: $apexRadii")
        assertTrue(result.polyhedron.topology.elements(2).all { face ->
            val role = (result.lineage(face.id) as ElementLineage.Derived).role
            role == "kis-triangle" || role == "original-face"
        })
        val deployment = result.deployments.single()
        assertEquals(selectedFaces.map { ElementRef(it.id, it.rank) }.toSet(), deployment.selectedSources)
        assertEquals(
            setOf(0.18, 0.72),
            deployment.scalarParameters.values.map { it.getValue("heightScale") }.toSet(),
        )
        assertOutward(result.polyhedron)
    }

    @Test
    fun selectiveTruncatePreservesUnselectedVerticesAndClosesTheSurface() {
        val source = cube()
        val selected = source.topology.elements(0).first().id
        val result = source.truncate(ElementSelectors.id(selected))
        val output = result.polyhedron

        assertCounts(output.topology, vertices = 10, edges = 15, faces = 7)
        assertEquals(mapOf(3 to 1, 4 to 3, 5 to 3), faceSizes(output))
        assertEquals(16, output.triangulate().triangleCount)
        assertEquals(7, result.selectElements(ElementSelectors.provenanceRole("original-vertex"), rank = 0).size)
        assertEquals(3, result.selectElements(ElementSelectors.provenanceRole("vertex-edge-point"), rank = 0).size)
        assertOutward(output)
    }

    @Test
    fun vertexAttributesControlBothSelectionAndTruncationFraction() {
        var source = cube()
        val selected = listOf(source.topology.elements(0)[0], source.topology.elements(0)[6])
        source = source
            .withAttribute(selected[0].id, "cut", true)
            .withAttribute(selected[0].id, "fraction", 0.15)
            .withAttribute(selected[1].id, "cut", true)
            .withAttribute(selected[1].id, "fraction", 0.40)
        val result = source.truncate(
            ElementSelectors.attributeEquals("cut", true),
            ElementParameters.attribute("fraction"),
        )
        val sourceCoordinates = assertNotNull(source.realization)
        val measured = result.selectElements(ElementSelectors.provenanceRole("vertex-edge-point"), rank = 0)
            .groupBy { outputVertex ->
                result.sourceElements(outputVertex.id).single { it.rank == 0 }.id
            }
            .mapValues { (sourceVertex, outputVertices) ->
                val origin = sourceCoordinates[source.realizationIndex(sourceVertex)]
                outputVertices.map { result.polyhedron.coordinate(it.id).distance(origin) }.average()
            }
        val shallow = measured.getValue(selected[0].id)
        val deep = measured.getValue(selected[1].id)
        assertTrue(abs(shallow - 0.30) < 1e-5, measured.toString())
        assertTrue(abs(deep - 0.80) < 1e-5, measured.toString())
        assertOutward(result.polyhedron)
    }

    @Test
    fun lineageAndDegreeSelectorsExpressIndexedConwayOperations() {
        val indexedKis = conway("k4aC")
        assertCounts(indexedKis.polyhedron.topology, vertices = 18, edges = 48, faces = 32)
        assertEquals(ConwayInvocation(ConwayOperator.KIS, 4), parseConwayExpression("k4aC").invocations.last())

        val kisCube = cube().kis()
        val byLineage = kisCube.truncate(ElementSelectors.provenanceRole("face-apex"))
        val byDegree = conway("t4kC")
        assertTrue(byLineage.polyhedron.topology.isCombinatoriallyIsomorphicTo(byDegree.polyhedron.topology))
        assertOutward(byLineage.polyhedron)
        assertOutward(byDegree.polyhedron)
    }

    @Test
    fun gyroCanVaryItsSubdivisionFractionBySourceEdge() {
        var source = cube()
        source.topology.elements(1).forEachIndexed { index, edge ->
            source = source.withAttribute(edge.id, "gyroFraction", if (index % 2 == 0) 0.2 else 0.4)
        }
        val result = source.gyro(ElementParameters.attribute("gyroFraction"))
        assertCounts(result.polyhedron.topology, vertices = 38, edges = 60, faces = 24)
        assertEquals(12, result.deployments.single().selectedSources?.size)
        assertEquals(
            setOf(0.2, 0.4),
            result.deployments.single().scalarParameters.values.map { it.getValue("edgeFraction") }.toSet(),
        )

        val realization = assertNotNull(source.realization)
        for (output in result.selectElements(ElementSelectors.provenanceRole("directed-edge-point"), rank = 0)) {
            val sources = result.sourceElements(output.id)
            val vertex = sources.single { it.rank == 0 }.id
            val edge = sources.single { it.rank == 1 }.id
            val endpoints = source.topology.incidentDown(edge).map(::ElementId)
            val other = endpoints.single { it != vertex }
            val origin = realization[source.realizationIndex(vertex)]
            val edgeLength = origin.distance(realization[source.realizationIndex(other)])
            val actual = result.polyhedron.coordinate(output.id).distance(origin) / edgeLength
            val expected = source.attributes[edge]["gyroFraction"] as Double
            assertTrue(abs(actual - expected) < 1e-5, "edge ${edge.value}: $actual != $expected")
        }
        assertOutward(result.polyhedron)
    }

    @Test
    fun invalidSelectionsParametersAndIndexedSyntaxFailAtTheirSource() {
        val cube = cube()
        val face = cube.topology.elements(2).first().id
        val wrongRank = assertFailsWith<IllegalArgumentException> { cube.topology.truncate(listOf(face)) }
        assertTrue(wrongRank.message.orEmpty().contains("expected rank 0"))
        val badFraction = assertFailsWith<IllegalArgumentException> {
            cube.truncate(ElementSelectors.all, ElementParameters.constant(0.75))
        }
        assertTrue(badFraction.message.orEmpty().contains("fraction"))
        assertFailsWith<IllegalArgumentException> {
            cube.kis(ElementSelectors.all, ElementParameters.attribute("missing"))
        }

        val unattached = assertFailsWith<ConwayParseException> { parseConwayExpression("4kC") }
        assertTrue(unattached.message.orEmpty().contains("not attached"))
        val unsupported = assertFailsWith<ConwayParseException> { parseConwayExpression("a4C") }
        assertTrue(unsupported.message.orEmpty().contains("only kis and truncate"))
        val tooSmall = assertFailsWith<ConwayParseException> { parseConwayExpression("k2C") }
        assertTrue(tooSmall.message.orEmpty().contains("at least 3"))
    }

    private fun PolyhedralObject.coordinate(vertex: ElementId): Vec3 {
        val realization = assertNotNull(realization)
        return realization[realizationIndex(vertex)]
    }

    private fun faceSizes(polyhedron: PolyhedralObject): Map<Int, Int> =
        polyhedron.topology.elements(2).groupingBy { polyhedron.topology.faceCycle(it.id).size }.eachCount()

    private fun assertCounts(topology: PolytopeTopology, vertices: Int, edges: Int, faces: Int) {
        assertEquals(vertices, topology.elements(0).size)
        assertEquals(edges, topology.elements(1).size)
        assertEquals(faces, topology.elements(2).size)
        assertEquals(2, topology.eulerCharacteristic())
        assertTrue(topology.validate(checkManifold = true).isValid, topology.validate(true).issues.toString())
    }

    private fun assertOutward(polyhedron: PolyhedralObject) {
        val realization = assertNotNull(polyhedron.realization)
        val center = Vec3()
        realization.vertexCoordinates.forEach(center::add)
        center.mul(1f / realization.size)
        for (face in polyhedron.topology.elements(2)) {
            val faceCenter = Vec3()
            val cycle = polyhedron.topology.faceCycle(face.id)
            cycle.forEach { faceCenter.add(realization[polyhedron.realizationIndex(ElementId(it))]) }
            faceCenter.mul(1f / cycle.size).sub(center)
            assertTrue(polyhedron.faceNormal(face.id).dot(faceCenter) > 0f, "face ${face.id.value} is inward")
        }
    }
}
