package afield

import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Baking a gesture take into keyframe lanes and rendering lanes back into a take
 * (`afield/math/gestureKeyframes.kt`). The numbers printed here are the ones quoted in
 * `dev_docs/GESTURE_KEYFRAMES.md`.
 */
class GestureKeyframesTest {

    // A two-second figure eight at 120 Hz, as a hand draws it: uneven sample spacing and a little
    // tremor, seeded so the counts below are stable.
    private fun handDrawnEight(): DoubleArray {
        val rnd = Random(7)
        val out = ArrayList<Double>()
        var t = 0.0
        while (t < 2.0) {
            val u = t / 2.0 * 2 * PI
            out += t
            out += (0.7 * sin(u) + rnd.nextDouble(-0.004, 0.004)).coerceIn(-1.0, 1.0)
            out += (0.5 * sin(2 * u) + rnd.nextDouble(-0.004, 0.004)).coerceIn(-1.0, 1.0)
            t += 1.0 / 120 * rnd.nextDouble(0.6, 1.4)
        }
        out += 2.0; out += 0.0; out += 0.0
        return out.toDoubleArray()
    }

    private fun lane(keys: List<Keyframe>) = KeyframeEvaluator(Json.encodeToString(keys))

    @Test
    fun theToleranceHoldsAtEveryMomentAndKeysDropAsItLoosens() {
        val take = handDrawnEight()
        val samples = take.size / 3
        var previousKeys = Int.MAX_VALUE
        for (tolerance in listOf(0.002, 0.005, 0.01, 0.02, 0.05, 0.1)) {
            val lanes = takeToLanes(take, tolerance)
            val fit = measureGestureFit(take, lane(lanes.x), lane(lanes.y), between = 16)
            println("bake tolerance=$tolerance -> $fit")
            assertTrue(fit.maxError <= tolerance + 1e-12, "tolerance $tolerance broken: $fit")
            assertTrue(fit.keyCount <= previousKeys, "keys rose as tolerance loosened: $fit")
            assertTrue(fit.keyCount < samples, "no reduction at $tolerance: $fit")
            previousKeys = fit.keyCount
            // The lanes span the take's loop, and share their key times.
            assertEquals(0.0, lanes.x.first().time)
            assertEquals(2.0, lanes.x.last().time)
            assertEquals(lanes.x.map { it.time }, lanes.y.map { it.time })
        }
        // At the docs' default the eight is a handful of keys, not hundreds.
        val default = takeToLanes(take, 0.02).x.size
        assertTrue(default * 5 < samples, "0.02 kept $default of $samples")
    }

    @Test
    fun aStraightStrokeBakesToItsTwoEnds() {
        val take = DoubleArray(30) { i -> when (i % 3) { 0 -> i / 3 * 0.1; 1 -> i / 3 * 0.05; else -> -i / 3 * 0.02 } }
        val lanes = takeToLanes(take, 1e-6)
        assertEquals(2, lanes.x.size)
    }

    @Test
    fun bakedLanesRenderBackIntoTheTake() {
        val take = handDrawnEight()
        for (tolerance in listOf(0.005, 0.02)) {
            val lanes = takeToLanes(take, tolerance)
            val x = lane(lanes.x)
            val y = lane(lanes.y)
            val back = lanesToTake(x, y, samplesPerSecond = 60.0)
            val flat = back.flatMap { (t, v) -> listOf(t, v.x.toDouble(), v.y.toDouble()) }.toDoubleArray()
            // Back against the original take: the bake's own error plus float storage on the pad.
            val fit = measureGestureFit(flat, x, y, between = 16)
            var worstVsOriginal = 0.0
            var sum = 0.0
            for (i in 0 until take.size / 3) {
                val (bx, by) = takeAt(flat, take[i * 3])
                val e = max(abs(bx - take[i * 3 + 1]), abs(by - take[i * 3 + 2]))
                worstVsOriginal = max(worstVsOriginal, e); sum += e
            }
            println("round trip tolerance=$tolerance: ${back.size} samples, vs lanes max=${"%.2e".format(fit.maxError)}, " +
                "vs original take max=${"%.4f".format(worstVsOriginal)} mean=${"%.4f".format(sum / (take.size / 3))}")
            assertTrue(fit.maxError < 1e-6, "rendering lanes lost them: $fit")
            assertTrue(worstVsOriginal <= tolerance + 1e-6)
            assertEquals(2.0, back.last().first, 1e-9)
        }
    }

    @Test
    fun editedBezierLanesReplayAndRebakeWithinTolerance() {
        // Lanes as someone shapes them by hand in the editor: eased in and out with flat handles a
        // third of the way along each segment, not straight lines.
        fun eased(t: Double, v: Double, span: Double) =
            Keyframe(t, v, "bezierFree", Handles(Handle(-span / 3, 0.0), Handle(span / 3, 0.0)))
        val x = lane(listOf(eased(0.0, -0.6, 1.0), eased(1.0, 0.6, 1.0), eased(2.0, 0.2, 1.0), Keyframe(3.0, -0.6, "linear")))
        val y = lane(listOf(eased(0.0, -0.4, 1.5), eased(1.5, 0.7, 1.5), Keyframe(3.0, -0.4, "linear")))
        // The ease really is there: halfway into x's first segment is not on the chord's line at a quarter.
        assertTrue(abs(x.evaluateValueAt(0.25) - (-0.3)) > 0.05, "x(0.25)=${x.evaluateValueAt(0.25)}")
        val take = lanesToTake(x, y, samplesPerSecond = 60.0)
        val flat = take.flatMap { (t, v) -> listOf(t, v.x.toDouble(), v.y.toDouble()) }.toDoubleArray()
        val replay = measureGestureFit(flat, x, y, between = 16)
        val rebaked = takeToLanes(flat, 0.01)
        val again = measureGestureFit(flat, lane(rebaked.x), lane(rebaked.y), between = 16)
        println("bezier lanes -> take: ${take.size} samples, replay error vs curves max=${"%.4f".format(replay.maxError)} mean=${"%.5f".format(replay.meanError)}; rebake at 0.01 -> $again")
        // 60 samples a second of an ease: the chords sit inside a hundredth, and rebaking keeps far
        // fewer keys than samples but more than the four the curve was drawn with.
        assertTrue(replay.maxError in 1e-5..0.01, "$replay")
        assertTrue(again.maxError <= 0.01 + 1e-9)
        assertTrue(again.keyCount in 5 until take.size / 2, "$again")
    }
}
