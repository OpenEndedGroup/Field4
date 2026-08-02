package afield.stdlib.polytope

import afield.ValueVisualizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolytopeVisualizersTest {
    @Test
    fun summariesExposeTheLiveCodingFactsWithoutRenderingTypes() {
        var source = cube()
        val selected = source.topology.elements(2).take(2)
        selected.forEachIndexed { index, face ->
            source = source.withAttributes(
                face.id,
                mapOf("raise" to true, "height" to listOf(0.22, 0.68)[index]),
            )
        }
        val result = source.kis(
            ElementSelectors.attributeEquals("raise", true),
            ElementParameters.attribute("height"),
        )

        val topology = result.polyhedron.topology.summary()
        assertEquals(mapOf(0 to 10, 1 to 20, 2 to 12, 3 to 1), topology.elementCounts)
        assertEquals(mapOf(3 to 8, 4 to 4), topology.faceDegreeCounts)
        assertEquals(2, topology.eulerCharacteristic)
        assertTrue(topology.valid)
        assertEquals(true, topology.closedManifold)

        val transform = result.summary()
        assertEquals(listOf("kis"), transform.requestedOperations)
        assertEquals(2, transform.deployments.single().selectedCount)
        assertEquals(
            ScalarParameterSummary("heightScale", 2, 0.22, 0.68),
            transform.deployments.single().scalarParameters.single(),
        )
        assertEquals(setOf("height", "raise"), transform.output.attributeKeys)

        val triangles = result.polyhedron.triangulate().summary()
        assertEquals(16, triangles.triangles)
        assertEquals(12, triangles.sourcePolygonFaces)
        assertTrue(triangles.hasFlatNormals)
    }

    @Test
    fun malformedValidationGetsACompactStructuredSummary() {
        val builder = PolytopeTopologyBuilder(3)
        val vertex = builder.addElement(0)
        val edge = builder.addElement(1)
        builder.addElement(2) // deliberately has no ordered face cycle
        builder.addIncidence(vertex, edge)
        val topology = builder.build()
        val summary = topology.validate().summary()

        assertFalse(summary.valid)
        assertTrue(summary.issueCount > 0)
        assertEquals(1, summary.issueTypes["EdgeEndpointCount"])
        assertTrue(summary.messages.any { "expected exactly 2" in it })
        assertFalse(topology.summary().valid, "the topology printer must also survive malformed input")
    }

    @Test
    fun registeredPrintersUseTheDataClassVisualizerForCoreObjects() {
        registerPolytopeVisualizers()
        val result = conway("k4aC")
        val transformHtml = ValueVisualizer.visualize(result)
        val topologyHtml = ValueVisualizer.visualize(result.polyhedron.topology)
        val triangulationHtml = ValueVisualizer.visualize(result.polyhedron.triangulate())

        assertTrue("PolytopeTransformSummary" in transformHtml, transformHtml)
        assertTrue("PolytopeTopologySummary" in topologyHtml, topologyHtml)
        assertTrue("TriangulatedGeometrySummary" in triangulationHtml, triangulationHtml)
        assertFalse("PolytopeTransformResult@" in transformHtml)
    }
}
