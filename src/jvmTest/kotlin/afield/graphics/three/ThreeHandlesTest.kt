package afield.graphics.three

import afield.math.linalg.vec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * `three { handle(...) }`: the constraint is written twice, in `ThreeHandles.kt` (which the box's
 * callback sees) and in the viewer's `handle-constraint` region (which moves the marker under the
 * hand). If the two disagreed, the marker would sit somewhere other than where the box was told it is.
 */
class ThreeHandlesTest {

    private val free = ThreeHandle("p", listOf(1.0, 2.0, 3.0))
    private val alongX = free.copy(axis = listOf(2.0, 0.0, 0.0))
    private val diagonal = free.copy(axis = listOf(1.0, 1.0, 0.0))
    private val floor = free.copy(normal = listOf(0.0, 0.0, 5.0))
    private val tilted = free.copy(normal = listOf(1.0, 0.0, 1.0))
    private val degenerate = free.copy(axis = listOf(0.0, 0.0, 0.0))
    private val handles = listOf(free, alongX, diagonal, floor, tilted, degenerate)
    private val targets = listOf(listOf(4.0, -7.0, 9.5), listOf(1.0, 2.0, 3.0), listOf(-3.25, 0.5, -11.0))

    private fun json(h: ThreeHandle) = buildString {
        append("{\"position\":${h.position}")
        h.axis?.let { append(",\"axis\":$it") }
        h.normal?.let { append(",\"normal\":$it") }
        append('}')
    }

    @Test
    fun constraintsHoldToTheirLineOrPlane() {
        // Along x through (1, 2, 3): only x is free.
        assertEquals(listOf(4.0, 2.0, 3.0), constrainHandle(alongX, listOf(4.0, -7.0, 9.5)))
        // The plane z = 3, whatever the normal's length.
        assertEquals(listOf(4.0, -7.0, 3.0), constrainHandle(floor, listOf(4.0, -7.0, 9.5)))
        // No constraint, and a zero axis, leave the point alone.
        assertEquals(listOf(4.0, -7.0, 9.5), constrainHandle(free, listOf(4.0, -7.0, 9.5)))
        assertEquals(listOf(4.0, -7.0, 9.5), constrainHandle(degenerate, listOf(4.0, -7.0, 9.5)))
    }

    @Test
    fun theViewerConstrainsExactlyAsFieldDoes() {
        ViewerJavaScript.Session.open(
            regionName = "handle-constraint",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("handle-constraint"),
        ).use { js ->
            for (h in handles) for (p in targets) {
                val expected = constrainHandle(h, p)
                val got = js.call("handleConstrain", js.fromJson(json(h)), js.fromJson(p.toString()))
                val actual = List(3) { got.getArrayElement(it.toLong()).asDouble() }
                for (i in 0 until 3) assertEquals(expected[i], actual[i], 1e-12, "handle $h, target $p, axis $i")
            }
        }
    }

    @Test
    fun aDragReachesTheCallbackConstrainedAndAnUnknownHandleIsIgnored() {
        val uid = "test_${System.nanoTime()}"
        val seen = java.util.concurrent.LinkedBlockingQueue<ThreeHandleDrag>()
        registerThreeHandles(uid, mapOf("p" to ThreeHandleBinding(alongX) { seen.put(it) }))
        assertEquals(listOf(4.0, 2.0, 3.0), deliverThreeHandle(uid, null, "p", listOf(4.0, -7.0, 9.5), "end"))
        val drag = seen.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals(ThreeHandleDrag.Phase.END, drag.phase)
        assertEquals(vec(4.0, 2.0, 3.0), drag.position)
        assertNull(deliverThreeHandle(uid, null, "missing", listOf(0.0, 0.0, 0.0), "move"))
        assertNull(deliverThreeHandle(uid, null, "p", listOf(Double.NaN, 0.0, 0.0), "move"))
        registerThreeHandles(uid, emptyMap())
        assertNull(deliverThreeHandle(uid, null, "p", listOf(0.0, 0.0, 0.0), "move"))
    }

    @Test
    fun theBuilderPutsHandlesOnTheWireAndRefusesDuplicates() {
        val builder = ThreeSceneBuilder(48)
        builder.handle("tip", at = vec(1, 2, 3), label = "tip", axis = vec(0, 1, 0)) { }
        val payload = builder.payload("uid")
        assertEquals(listOf(ThreeHandle("tip", listOf(1.0, 2.0, 3.0), "tip", null, listOf(0.0, 1.0, 0.0), null)), payload.handles)
        assertFailsWith<IllegalArgumentException> { builder.handle("tip", at = vec(0, 0, 0)) { } }
    }
}
