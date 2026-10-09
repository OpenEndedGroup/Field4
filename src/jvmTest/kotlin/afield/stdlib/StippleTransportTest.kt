package afield.stdlib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Cross-framework arithmetic oracle from PyTorch, including reverse integration and both times. */
class StippleTransportTest {
    private val fixture = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/transport-flow-parity.json")!!.bufferedReader().use { it.readText() }
    ).jsonObject
    private fun field(): StippleTransport = StippleTransport.fromJson(fixture.getValue("model").toString())

    @Test
    fun staticExportHasNoMovieConditionAndStillMatchesPyTorch() {
        val data = fixture.getValue("static").jsonObject
        val model = StippleTransport.fromJson(data.getValue("model").toString())
        assertEquals(false, model.temporal)
        val points = fixture.getValue("points").jsonArray.map { row ->
            vec(row.jsonArray[0].jsonPrimitive.double, row.jsonArray[1].jsonPrimitive.double, 0.0)
        }
        assertEquals(model.transport(points, .6, 0.0), model.transport(points, .6, 1.0))
        for ((actual, key) in listOf(points.map { model.velocity(it, .6) } to "velocity", model.transport(points, .6) to "transport")) {
            data.getValue(key).jsonArray.forEachIndexed { i, expected ->
                assertEquals(expected.jsonArray[0].jsonPrimitive.double, actual[i].x.toDouble(), 2e-6)
                assertEquals(expected.jsonArray[1].jsonPrimitive.double, actual[i].y.toDouble(), 2e-6)
            }
        }
    }

    @Test
    fun exportedNetworkAndIntegratedGeometryAgreeWithPyTorchAtBothTimes() {
        val model = field()
        val points = fixture.getValue("points").jsonArray.map { row ->
            val xy = row.jsonArray
            vec(xy[0].jsonPrimitive.double, xy[1].jsonPrimitive.double, 7.0)
        }
        for (case in fixture.getValue("cases").jsonArray) {
            val data = case.jsonObject
            val t = data.getValue("transportTime").jsonPrimitive.double
            val tau = data.getValue("movieTime").jsonPrimitive.double
            val from = data.getValue("fromTransportTime").jsonPrimitive.double
            val velocity = points.map { model.velocity(it, t, tau) }
            val transported = model.transport(points, t, tau, fromTransportTime = from)
            for ((actual, key) in listOf(velocity to "velocity", transported to "transport")) {
                data.getValue(key).jsonArray.forEachIndexed { i, expected ->
                    assertEquals(expected.jsonArray[0].jsonPrimitive.double, actual[i].x.toDouble(), 2e-6)
                    assertEquals(expected.jsonArray[1].jsonPrimitive.double, actual[i].y.toDouble(), 2e-6)
                }
            }
            assertTrue(transported.all { it.z == 7f })
            assertTrue(velocity.all { it.z == 0f })
            val traced = model.pathsFrom(points, t, tau, fromTransportTime = from)
            assertEquals(points.size, traced.size)
        }
    }

    @Test
    fun canonicalSourceIsRepeatableAndInvalidRequestsFailBeforeMapping() {
        val model = field()
        val source = model.sourceParticles(100, seed = 3)
        assertEquals(source, model.sourceParticles(100, seed = 3))
        assertTrue(source.all { it.x in -1f..1f && it.y in -1f..1f })
        assertEquals(source, model.transport(source, transportTime = 0.0, movieTime = .8))
        assertEquals(emptyList(), model.transport(emptyList()))
        assertFailsWith<IllegalArgumentException> { model.transport(emptyList(), steps = 0) }
        assertFailsWith<IllegalArgumentException> { model.transport(source, movieTime = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { model.sourceParticles(-1) }
        val same = model.unsafeBlendWeights(model, .25)
        val at = vec(.3, .2, 0)
        assertEquals(model.velocity(at, .5, .7), same.velocity(at, .5, .7))
        assertFailsWith<IllegalArgumentException> {
            StippleTransport.fromJson(fixture.getValue("model").toString().replace("field.transport-flow.v1", "future"))
        }
    }
}
