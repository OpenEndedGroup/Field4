package afield.geometry.network

import afield.geometry.path.FLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeometryNetworkTest {
    /** A Y: three curves meeting at node 0, plus an X-crossing curve that shares no node. */
    private fun yPlusCrossing(): GeometryNetwork {
        val nodes = GeometryNodes(
            2, intArrayOf(10, 11, 12, 13), doubleArrayOf(0.0, 0.0, -1.0, 1.0, 1.0, 1.0, 0.0, -1.0),
            mapOf("persistence" to Channel.scalar(doubleArrayOf(0.5, 1.0, 2.0, 3.0))),
        )
        val curves = listOf(
            GeometryCurve(0, 2, doubleArrayOf(0.0, 0.0, -0.5, 0.5, -1.0, 1.0), 10, 11,
                samples = mapOf("width" to Channel.scalar(doubleArrayOf(1.0, 2.0, 3.0)))),
            GeometryCurve(1, 2, doubleArrayOf(0.0, 0.0, 1.0, 1.0), 10, 12, attributes = mapOf("persistence" to 0.7)),
            GeometryCurve(2, 2, doubleArrayOf(0.0, 0.0, 0.0, -1.0), 10, 13),
            // passes through (0,0) but names no node there
            GeometryCurve(3, 2, doubleArrayOf(-1.0, 0.0, 1.0, 0.0)),
        )
        return GeometryNetwork(2, nodes, curves, mapOf("source" to "test"))
    }

    @Test
    fun crossingIsNotAJunction() {
        val n = yPlusCrossing()
        assertEquals(3, n.degrees()[10])
        assertEquals(setOf(setOf(0, 1, 2), setOf(3)), n.components().map { it.toSet() }.toSet())
    }

    @Test
    fun fLinesCarryProvenanceAndSamples() {
        val n = yPlusCrossing()
        val lines = n.toFLines()
        assertEquals(4, lines.size)
        assertSame(n, lines[0].properties["geometryNetwork"])
        assertEquals(10, lines[0].properties["startNode"])
        assertEquals(0.7, lines[1].properties["persistence"])
        assertEquals(listOf(1.0, 2.0, 3.0), lines[0].nodes.map { it.properties["width"] })
    }

    @Test
    fun smoothingKeepsEndpoints() {
        val line = yPlusCrossing().toFLines(smooth = true)[0]
        assertTrue(line.nodes.first().to.distance(afield.math.linalg.Vec3(0f, 0f, 0f)) < 1e-9)
        assertTrue(line.nodes.last().to.distance(afield.math.linalg.Vec3(-1f, 1f, 0f)) < 1e-9)
    }

    @Test
    fun fromFLinesInventsNoConnectivity() {
        val a = FLine().moveTo(0, 0).lineTo(1, 0)
        val b = FLine().moveTo(1, 0).lineTo(1, 1).moveTo(5, 5).lineTo(6, 6)
        val n = GeometryNetwork.fromFLines(listOf(a, b))
        assertEquals(3, n.curves.size)
        assertEquals(0, n.nodes.size)
        assertEquals(3, n.components().size)
    }

    @Test
    fun filterDropsOrphanNodesAndKeepsChannels() {
        val f = yPlusCrossing().filterCurves { it.id == 1 }
        assertEquals(listOf(10, 12), f.nodes.ids.toList())
        assertEquals(2.0, f.nodes.channels.getValue("persistence")[1])
    }

    @Test
    fun danglingNodeReferenceIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            GeometryNetwork(2, GeometryNodes.empty(2), listOf(GeometryCurve(0, 2, doubleArrayOf(0.0, 0.0, 1.0, 1.0), 99, null)))
        }
    }

    @Test
    fun unknownModuleIsReportedNotThrown() {
        assertEquals(false, NativeGeometry.available("noSuchModule"))
        assertTrue(NativeGeometry.describe("noSuchModule").contains("not a native geometry module"))
    }
}
