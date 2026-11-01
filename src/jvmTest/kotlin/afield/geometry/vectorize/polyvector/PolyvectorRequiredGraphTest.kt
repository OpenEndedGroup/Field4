package afield.geometry.vectorize.polyvector

import afield.geometry.network.GeometryCurve
import afield.geometry.network.GeometryNetwork
import afield.geometry.network.GeometryNodes
import afield.media.image.Img
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolyvectorRequiredGraphTest {
    /** A path 0 — 1 — 2 at (10,10), (50,10), (50,50). */
    private val required = GeometryNetwork(
        2,
        GeometryNodes(2, intArrayOf(0, 1, 2), doubleArrayOf(10.0, 10.0, 50.0, 10.0, 50.0, 50.0)),
        listOf(
            GeometryCurve(0, 2, doubleArrayOf(10.0, 10.0, 50.0, 10.0), 0, 1),
            GeometryCurve(1, 2, doubleArrayOf(50.0, 10.0, 50.0, 50.0), 1, 2),
        ),
        mapOf("source" to "test"),
    )

    @Test
    fun endsNearRequiredNodesSnapAndKeepTheConnection() {
        // The first edge traced in two pieces meeting at a free junction, the second off by two pixels.
        val traced = listOf(
            floatArrayOf(11f, 11f, 30f, 10f), floatArrayOf(30f, 10f, 49f, 9f),
            floatArrayOf(52f, 11f, 51f, 48f),
        )
        val (net, missingCurves, missingNodes) = constrainToRequired(traced, required, 3.0)
        assertEquals(emptyList(), missingCurves)
        assertEquals(emptyList(), missingNodes)
        val c = net.curves[0]
        assertEquals(0, c.startNode)
        assertEquals(10.0, c.coordinates[0]); assertEquals(10.0, c.coordinates[1])
        assertEquals(c.endNode, net.curves[1].startNode, "the free junction is a shared node")
        assertTrue(c.endNode!! >= 3, "free nodes are numbered after the required ones")
    }

    @Test
    fun aBrokenOrMisroutedConnectionIsReported() {
        // 1 — 2 traced with a gap, so it is missing; nothing reaches node 2.
        val traced = listOf(floatArrayOf(10f, 10f, 50f, 10f), floatArrayOf(50f, 11f, 50f, 30f))
        val (_, missingCurves, missingNodes) = constrainToRequired(traced, required, 3.0)
        assertEquals(listOf(1), missingCurves)
        assertEquals(listOf(2), missingNodes)
        // A walk through another required node does not count: 0 — 2 only via 1 is not a 0–2 curve.
        // Node 1 has to end a required curve (0 — 1 here) to be required at all; a node no required
        // curve ends at constrains nothing, and a walk through it would count.
        val via = GeometryNetwork(2, required.nodes, listOf(
            GeometryCurve(0, 2, doubleArrayOf(10.0, 10.0, 50.0, 50.0), 0, 2),
            GeometryCurve(1, 2, doubleArrayOf(10.0, 10.0, 50.0, 10.0), 0, 1),
        ))
        val (_, m, _) = constrainToRequired(listOf(floatArrayOf(10f, 10f, 50f, 10f), floatArrayOf(50f, 10f, 50f, 50f)), via, 3.0)
        assertEquals(listOf(0), m)
    }

    @Test
    fun aJunctionThatStoppedShortIsPulledOntoItsNode() {
        // The second edge stops ten pixels short of node 2, at a junction with a spur.
        val traced = listOf(floatArrayOf(10f, 10f, 50f, 10f), floatArrayOf(50f, 10f, 50f, 40f), floatArrayOf(52f, 30f, 50f, 40f))
        assertEquals(listOf(1), constrainToRequired(traced, required, 3.0).second, "without reach the edge is lost")
        val (net, missingCurves, missingNodes) = constrainToRequired(traced, required, 3.0, reach = 12.0)
        assertEquals(emptyList(), missingCurves)
        assertEquals(emptyList(), missingNodes)
        val edge = net.curves[1]
        assertEquals(2, edge.endNode)
        assertEquals(listOf(50.0, 40.0, 50.0, 50.0), edge.coordinates.takeLast(4), "extended to the node, not moved")
        assertEquals(2, net.curves[2].endNode, "the spur ends at the node too")
        // Node 1 was snapped to, so spurs near it are not pulled in.
        val spurNearOne = constrainToRequired(traced + listOf(floatArrayOf(51f, 18f, 60f, 18f)), required, 3.0, reach = 12.0).first
        assertEquals(null, spurNearOne.curves[3].startNode)
    }

    @Test
    fun tracingAnLWithItsRequiredGraphKeepsIt() {
        if (!PolyvectorLibrary.isAvailable) { println("SKIPPED: ${PolyvectorLibrary.describe()}"); return }
        val w = 64; val h = 64
        val ink = FloatArray(w * h) { i ->
            val x = i % w + 0.5; val y = i / w + 0.5
            val d = minOf(
                if (x in 10.0..50.0) hypot(0.0, y - 10) else 99.0,
                if (y in 10.0..50.0) hypot(x - 50, 0.0) else 99.0,
            ) / 1.5
            kotlin.math.exp(-d * d).toFloat()
        }
        val drawing = Img(w, h, 3, FloatArray(w * h * 3) { 1f - ink[it / 3] })
        val traced = Polyvector.vectorize(drawing, required = required)
        assertTrue(traced.satisfied, traced.toString())
    }
}
