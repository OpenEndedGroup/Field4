package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolytopeTransformsTest {
    private data class Expected(
        val vertices: Int,
        val edges: Int,
        val faces: Int,
        val triangles: Int,
        val faceSizes: Map<Int, Int>,
    )

    private val cubeExpected = mapOf(
        ConwayOperator.DUAL to Expected(6, 12, 8, 8, mapOf(3 to 8)),
        ConwayOperator.AMBO to Expected(12, 24, 14, 20, mapOf(3 to 8, 4 to 6)),
        ConwayOperator.KIS to Expected(14, 36, 24, 24, mapOf(3 to 24)),
        ConwayOperator.TRUNCATE to Expected(24, 36, 14, 44, mapOf(3 to 8, 8 to 6)),
        ConwayOperator.GYRO to Expected(38, 60, 24, 72, mapOf(5 to 24)),
    )

    @Test
    fun abstractOperatorsProduceExpectedCubeCombinatorics() {
        for ((operation, expected) in cubeExpected) {
            val transformed = cube().topology.transform(operation)
            val topology = transformed.topology
            assertEquals(expected.vertices, topology.elements(0).size, "$operation vertices")
            assertEquals(expected.edges, topology.elements(1).size, "$operation edges")
            assertEquals(expected.faces, topology.elements(2).size, "$operation faces")
            assertEquals(1, topology.elements(3).size, "$operation whole")
            assertEquals(2, topology.eulerCharacteristic(), "$operation Euler characteristic")
            assertTrue(topology.validate(checkManifold = true).isValid, "$operation: ${topology.validate(true).issues}")
            assertEquals(
                expected.faceSizes,
                topology.elements(2).groupingBy { topology.faceCycle(it.id).size }.eachCount(),
                "$operation face sizes",
            )
            topology.elementsByRank.flatten().forEach { transformed.provenance(it.id) }
        }
    }

    @Test
    fun realizedOperatorsAreOutwardAndTriangulateWithExpectedCounts() {
        for ((operation, expected) in cubeExpected) {
            val result = cube().transform(operation)
            val polyhedron = result.polyhedron
            assertNotNull(polyhedron.realization, "$operation should use its default realization policy")
            assertOutward(polyhedron, operation.toString())
            val triangles = polyhedron.triangulate()
            assertEquals(expected.triangles, triangles.triangleCount, "$operation triangles")
            for (triangle in 0 until triangles.mesh.triangleCount) {
                val sourceFace = triangles.sourceFace(triangle)
                assertTrue(
                    triangles.mesh.faceNormal(triangle).dot(polyhedron.faceNormal(sourceFace)) > 0.9999f,
                    "$operation triangle $triangle winding",
                )
            }
        }
    }

    @Test
    fun everyOperatorWorksAcrossAllInitialSeeds() {
        val seeds = listOf(tetrahedron(), cube(), octahedron())
        for (seed in seeds) for (operation in ConwayOperator.entries) {
            val transformed = seed.transform(operation).polyhedron
            assertTrue(
                transformed.topology.validate(checkManifold = true).isValid,
                "$operation: ${transformed.topology.validate(true).issues}",
            )
            assertEquals(2, transformed.topology.eulerCharacteristic())
            assertOutward(transformed, "${seed.topology.elements(0).size}-vertex seed $operation")
            assertTrue(transformed.triangulate().triangleCount > 0)
        }
    }

    @Test
    fun dualTransformMatchesTheExistingAbstractDualAndDoubleDualReturnsTheSeedType() {
        val cubeTopology = cube().topology
        val transformed = cubeTopology.transform(ConwayOperator.DUAL).topology
        assertTrue(transformed.isCombinatoriallyIsomorphicTo(cubeTopology.dual()))
        assertTrue(transformed.isCombinatoriallyIsomorphicTo(octahedron().topology))

        val twice = cube().dual().dual()
        assertEquals(listOf(ConwayOperator.DUAL, ConwayOperator.DUAL), twice.history)
        assertTrue(twice.polyhedron.topology.isCombinatoriallyIsomorphicTo(cubeTopology))
        twice.polyhedron.topology.elementsByRank.flatten().forEach { output ->
            assertTrue(twice.sourceElements(output.id).isNotEmpty())
        }
    }

    @Test
    fun realizationPoliciesAreReplaceableWithoutChangingTopology() {
        val abstractOnly = RealizationPolicy3 { _, _ -> null }
        val noGeometry = cube().transform(ConwayOperator.AMBO, abstractOnly)
        assertNull(noGeometry.polyhedron.realization)
        assertTrue(noGeometry.polyhedron.topology.isCombinatoriallyIsomorphicTo(cube().ambo().polyhedron.topology))

        val shallow = cube().kis(KisFaceNormalRealization(heightScale = 0.2)).polyhedron
        val tall = cube().kis(KisFaceNormalRealization(heightScale = 0.8)).polyhedron
        assertTrue(shallow.topology.isCombinatoriallyIsomorphicTo(tall.topology))
        val shallowCoordinates = assertNotNull(shallow.realization).vertexCoordinates
        val tallCoordinates = assertNotNull(tall.realization).vertexCoordinates
        assertTrue(shallowCoordinates.zip(tallCoordinates).any { (a, b) -> a.distance(b) > 0.1f })

        assertFailsWith<IllegalArgumentException> { KisFaceNormalRealization(0.0) }
        assertFailsWith<IllegalArgumentException> { TruncateAlongEdgesRealization(0.5) }
    }

    @Test
    fun transformsWithoutSourceCoordinatesRemainAbstract() {
        val abstractCube = PolyhedralObject(cube().topology)
        for (operation in ConwayOperator.entries) {
            val result = abstractCube.transform(operation)
            assertNull(result.polyhedron.realization)
            assertTrue(result.polyhedron.topology.validate(checkManifold = true).isValid)
        }
    }

    @Test
    fun composedLineageReachesBackToSeedElements() {
        val result = cube().ambo().kis().dual()
        assertEquals(
            listOf(ConwayOperator.AMBO, ConwayOperator.KIS, ConwayOperator.DUAL),
            result.history,
        )
        for (element in result.polyhedron.topology.elementsByRank.flatten()) {
            val lineage = assertIs<ElementLineage.Derived>(result.lineage(element.id))
            assertEquals(ConwayOperator.DUAL, lineage.operation)
            val seedElements = result.sourceElements(element.id)
            assertTrue(seedElements.isNotEmpty(), "output ${element.id.value} should reach the seed")
            assertTrue(seedElements.all { it.rank in 0..3 })
        }
    }

    @Test
    fun directFaceAndVertexAttributesReachFieldAdapters() {
        var attributed = cube()
        for (face in attributed.topology.elements(2)) {
            attributed = attributed.withAttribute(face.id, "family", "cube-face")
        }
        for (vertex in attributed.topology.elements(0)) {
            attributed = attributed.withAttribute(vertex.id, "corner", vertex.id.value)
        }

        attributed.toFaceFLines().forEach { line ->
            assertEquals("cube-face", line.properties["family"])
        }
        val triangles = attributed.triangulate()
        assertTrue(triangles.triangleAttributes.all { it["family"] == "cube-face" })
        triangles.vertexAttributes.forEachIndexed { renderVertex, attributes ->
            assertEquals(triangles.sourceVertex(renderVertex).value, attributes["corner"])
        }
    }

    @Test
    fun transformAttributePolicyUsesSemanticParents() {
        var attributed = cube()
        attributed.topology.elements(2).forEach { face ->
            attributed = attributed.withAttribute(face.id, "surface", "original-face")
        }
        attributed.topology.elements(0).forEach { vertex ->
            attributed = attributed.withAttribute(vertex.id, "corner", "original-vertex")
        }

        val kis = attributed.kis().polyhedron
        assertTrue(kis.topology.elements(2).all { kis.attributes[it.id]["surface"] == "original-face" })
        assertTrue(kis.triangulate().triangleAttributes.all { it["surface"] == "original-face" })

        val truncated = attributed.truncate().polyhedron
        val vertexFaces = truncated.topology.elements(2).filter { face ->
            truncated.attributes[face.id]["corner"] == "original-vertex"
        }
        assertEquals(8, vertexFaces.size)
        val originalFaces = truncated.topology.elements(2).filter { face ->
            truncated.attributes[face.id]["surface"] == "original-face"
        }
        assertEquals(6, originalFaces.size)
    }

    @Test
    fun conwayParserUsesPrefixOrderAndEvaluatesSupportedSeeds() {
        val parsed = parseConwayExpression(" d a C ")
        assertEquals(ConwaySeed.CUBE, parsed.seed)
        assertEquals(listOf(ConwayOperator.AMBO, ConwayOperator.DUAL), parsed.operators)
        val result = parsed.evaluate()
        assertEquals(listOf(ConwayOperator.AMBO, ConwayOperator.DUAL), result.history)
        assertCounts(result.polyhedron.topology, 14, 24, 12)

        assertCounts(conway("T").polyhedron.topology, 4, 6, 4)
        assertCounts(conway("dC").polyhedron.topology, 6, 12, 8)
        assertCounts(conway("aO").polyhedron.topology, 12, 24, 14)
        assertCounts(conway("tC").polyhedron.topology, 24, 36, 14)
        assertCounts(conway("kC").polyhedron.topology, 14, 36, 24)
        assertCounts(conway("gC").polyhedron.topology, 38, 60, 24)
        assertCounts(conway("I").polyhedron.topology, 12, 30, 20)
        assertCounts(conway("D").polyhedron.topology, 20, 30, 12)
    }

    @Test
    fun namedConwayOperatorsMatchTheirPrimitiveDefinitions() {
        val definitions = mapOf(
            "jC" to "daC",
            "nC" to "kdC",
            "zC" to "dkC",
            "oC" to "jjC",
            "eC" to "aaC",
            "sC" to "dgdC",
            "mC" to "kjC",
            "bC" to "taC",
        )
        for ((named, expanded) in definitions) {
            val namedResult = conway(named)
            val expandedResult = conway(expanded)
            assertTrue(
                namedResult.polyhedron.topology.isCombinatoriallyIsomorphicTo(expandedResult.polyhedron.topology),
                "$named should be combinatorially equivalent to $expanded",
            )
            assertEquals(expandedResult.history, namedResult.history, "$named primitive history")
            assertEquals(listOf(parseConwayExpression(named).operators.single()), namedResult.notationHistory)
        }
    }

    @Test
    fun conwayParserReportsUsefulErrors() {
        val empty = assertFailsWith<ConwayParseException> { parseConwayExpression("   ") }
        assertTrue(empty.message.orEmpty().contains("expected an expression"))
        val seed = assertFailsWith<ConwayParseException> { parseConwayExpression("dx") }
        assertTrue(seed.message.orEmpty().contains("final seed"))
        val operator = assertFailsWith<ConwayParseException> { parseConwayExpression("qC") }
        assertEquals(0, operator.offset)
        assertTrue(operator.message.orEmpty().contains("unknown operator"))
        val spaced = assertFailsWith<ConwayParseException> { parseConwayExpression(" d q C ") }
        assertEquals(3, spaced.offset)
    }

    private fun assertCounts(topology: PolytopeTopology, vertices: Int, edges: Int, faces: Int) {
        assertEquals(vertices, topology.elements(0).size)
        assertEquals(edges, topology.elements(1).size)
        assertEquals(faces, topology.elements(2).size)
        assertEquals(2, topology.eulerCharacteristic())
        assertTrue(topology.validate(checkManifold = true).isValid, topology.validate(true).issues.toString())
    }

    private fun assertOutward(polyhedron: PolyhedralObject, label: String) {
        val realization = assertNotNull(polyhedron.realization)
        val center = Vec3()
        realization.vertexCoordinates.forEach { center.add(it) }
        center.mul(1f / realization.size)
        val vertexIndex = polyhedron.topology.elements(0).mapIndexed { i, vertex -> vertex.id to i }.toMap()
        for (face in polyhedron.topology.elements(2)) {
            val faceCenter = Vec3()
            val cycle = polyhedron.topology.faceCycle(face.id)
            cycle.forEach { vertexValue -> faceCenter.add(realization[vertexIndex.getValue(ElementId(vertexValue))]) }
            faceCenter.mul(1f / cycle.size).sub(center)
            assertTrue(
                polyhedron.faceNormal(face.id).dot(faceCenter) > 0f,
                "$label face ${face.id.value} is not outward",
            )
        }
    }
}
