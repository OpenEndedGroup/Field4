package afield.ai.learning.pen

import afield.geometry.path.FLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin forward pass against PyTorch's (`python/fieldpen`), whose own test pins it to the
 * Distill article's JavaScript. Fixtures were written by torch from the same inputs.
 */
class PenModelTest {
    private fun resource(name: String) = File(javaClass.getResource("/afield/pen/$name")!!.toURI())

    private fun checkAgainst(model: PenModel, reference: String, tolerance: Double) {
        val ref = Json.parseToJsonElement(resource(reference).readText()).jsonObject
        val inputs = ref["inputs"]!!.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double } }
        val raw = ref["raw"]!!.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double } }
        val pen = model.pen(0)
        // raw[0] is the head after the start-of-sequence step, raw[k] after the k-th input
        raw.forEachIndexed { k, expected ->
            if (k > 0) inputs[k - 1].let { pen.feed(it[0], it[1], it[2] == 1.0) }
            val actual = pen.rawOutput()
            assertEquals(expected.size, actual.size)
            expected.forEachIndexed { i, e ->
                assertTrue(abs(e - actual[i]) < tolerance, "step $k output $i: torch $e, kotlin ${actual[i]}")
            }
        }
    }

    @Test
    fun distillMatchesTorch() = checkAgainst(PenModel.distill, "distill_reference.json", 2e-4)

    @Test
    fun exportedTwoLayerModelMatchesTorch() =
        checkAgainst(PenModel.load(resource("tiny.fieldpen.json").path), "tiny_reference.json", 1e-5)

    @Test
    fun halfPrecisionExportStaysCloseToTorch() =
        checkAgainst(PenModel.load(resource("tiny.f16.fieldpen.json").path), "tiny_reference.json", 2e-3)

    @Test
    fun distributionMatchesWhatNextSamples() {
        val pen = PenModel.distill.pen(5)
        pen.feed(0.2, 0.1, false)
        val d = pen.distribution(PenControls(temperature = 1.0, noise = 1.0))
        assertEquals(1.0, d.weights.sum(), 1e-9)
        assertTrue(d.liftProbability in 0.0..1.0)
        // the sample mean of many next steps sits near the mixture's mean
        val mx = (0 until d.size).sumOf { d.weights[it] * d.meanX[it] }
        val my = (0 until d.size).sumOf { d.weights[it] * d.meanY[it] }
        var sx = 0.0
        var sy = 0.0
        val n = 4000
        repeat(n) { k ->
            val s = pen.fork(k.toLong()).next(PenControls(temperature = 1.0, noise = 1.0))
            sx += s.dx; sy += s.dy
        }
        assertEquals(mx, sx / n, 0.05)
        assertEquals(my, sy / n, 0.05)
    }

    @Test
    fun penLineRecordsEveryPoint() {
        val writer = PenLine(FLine().moveTo(0, 0).lineTo(20, 0), recordMemory = true)
        writer.grow(50)
        assertEquals(50, writer.history.size)
        assertEquals(writer.tip, writer.history.last().to)
        assertEquals(500, writer.history[0].hidden!!.size)
        writer.history.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from) }
    }

    @Test
    fun continuationStartsAtTheEndAndIsDeterministic() {
        val primer = FLine().moveTo(0, 0).cubicTo(10, -30, 30, -30, 30, 0).lineTo(40, 10)
        val a = primer.continueWith(steps = 120, seed = 7)
        val b = primer.continueWith(steps = 120, seed = 7)
        val c = primer.continueWith(steps = 120, seed = 8)
        val start = a.nodes.first().to
        assertEquals(40f, start.x, 1e-4f)
        assertEquals(10f, start.y, 1e-4f)
        assertEquals(121, a.nodes.size)
        assertEquals(a.nodes.map { it.to }, b.nodes.map { it.to })
        assertTrue(a.nodes.map { it.to } != c.nodes.map { it.to })
        // handwriting-sized steps: a quarter of a model unit is two pixels at scale 8
        val steps = a.nodes.zipWithNext { p, q -> hypot((q.to.x - p.to.x).toDouble(), (q.to.y - p.to.y).toDouble()) }.sorted()
        val median = steps[steps.size / 2]
        assertTrue(median in 0.5..8.0, "median step $median px")
    }

    @Test
    fun futuresLeaveTheLineAlone() {
        val writer = PenLine(FLine().moveTo(0, 0).lineTo(20, 0))
        writer.grow(30)
        val before = writer.line.nodes.map { it.to }
        val futures = writer.futures(count = 4, steps = 20)
        assertEquals(4, futures.size)
        assertEquals(before, writer.line.nodes.map { it.to })
        futures.forEach { assertEquals(before.last(), it.nodes.first().to) }
    }
}
