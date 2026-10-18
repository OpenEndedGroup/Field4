package afield.geometry.topology.tracking

import afield.geometry.topology.morsesmale.CriticalType
import afield.geometry.topology.morsesmale.MorseSmale
import afield.geometry.topology.morsesmale.MorseSmaleLibrary
import afield.media.image.Img
import java.util.Random
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTrackingTest {
    private fun max(x: Double, y: Double, p: Double = 1.0, v: Double = 1.0) = Feature(CriticalType.MAXIMUM, x, y, v, p)
    private fun min(x: Double, y: Double, p: Double = 1.0) = Feature(CriticalType.MINIMUM, x, y, 0.0, p)

    @Test
    fun diagramDistanceIsAMetricOnSmallCases() {
        val a = listOf(0.0 to 1.0, 0.2 to 0.5)
        val b = listOf(0.0 to 1.1)
        assertEquals(0.0, persistenceDiagramDistance(a, a), 1e-12)
        val d = persistenceDiagramDistance(a, b)
        // (0,1)->(0,1.1) costs 0.01; (0.2,0.5) to the diagonal costs 2*(0.15^2) = 0.045
        assertEquals(kotlin.math.sqrt(0.055), d, 1e-9)
        assertEquals(d, persistenceDiagramDistance(b, a), 1e-12)
        // the entropic solver approaches the exact distance as epsilon shrinks; a large epsilon overestimates
        val r = Random(2)
        val x = List(40) { val u = r.nextDouble(); u to u + r.nextDouble() * 0.5 }
        val y = List(35) { val u = r.nextDouble(); u to u + r.nextDouble() * 0.5 }
        val exact = persistenceDiagramDistance(x, y)
        val coarse = persistenceDiagramDistance(x, y, epsilon = 1e-2)
        val fine = persistenceDiagramDistance(x, y, epsilon = 1e-4)
        assertTrue(coarse > fine, "exact $exact, fine $fine, coarse $coarse")
        assertTrue(kotlin.math.abs(fine - exact) / exact < 0.01, "fine is within 1%: $fine vs $exact")
    }

    @Test
    fun movedFeaturesMatchAndNewOnesAreBorn() {
        val before = [max(10.0, 10.0), max(50.0, 50.0), min(30.0, 30.0)]
        val after = [max(52.0, 49.0), max(11.0, 12.0), min(29.0, 31.0), max(90.0, 90.0, p = 0.5)]
        val pairs = FeatureMatching.match(before, after).toSet()
        assertTrue((0 to 1) in pairs && (1 to 0) in pairs && (2 to 2) in pairs, "pairs $pairs")
        assertTrue((null to 3) in pairs, "the new maximum is born")
    }

    @Test
    fun typesNeverMatchAndTheGateHolds() {
        val pairs = FeatureMatching.match([max(10.0, 10.0)], [min(10.0, 10.0)]).toSet()
        assertEquals(setOf(0 to null, null to 0), pairs)
        val gated = FeatureMatching.match([max(0.0, 0.0)], [max(30.0, 0.0)], MatchingWeights(maxDistance = 20.0)).toSet()
        assertEquals(setOf(0 to null, null to 0), gated)
    }

    @Test
    fun weakFeaturesDieRatherThanJump() {
        // A faint peak vanishes while a strong one appears far away: cheaper as a death and a birth.
        val pairs = FeatureMatching.match([max(0.0, 0.0, p = 0.05)], [max(100.0, 0.0, p = 0.05)]).toSet()
        assertEquals(setOf(0 to null, null to 0), pairs)
    }

    @Test
    fun trackerKeepsIdentitiesAndRecordsEvents() {
        val t = FeatureTracker(MatchingWeights(maxDistance = 10.0))
        t.add(0.0, [max(10.0, 10.0), max(50.0, 10.0)])
        t.add(1.0, [max(52.0, 11.0), max(12.0, 10.0)])
        assertEquals(listOf(1, 0), t.alive.map { it.id }, "identities follow the features, not their order")
        t.add(2.0, [max(13.0, 10.0)])
        assertEquals(listOf(1), t.died.map { it.id })
        assertEquals(2.0, t.tracks[1].died)
        t.add(3.0, [max(14.0, 10.0), max(80.0, 80.0)])
        assertEquals(listOf(2), t.born.map { it.id })
        assertEquals(4, t.tracks[0].samples.size)
        assertEquals(1, t.network().curves.count { it.id == 0 })
    }

    @Test
    fun motionPredictionBridgesLargeJumps() {
        // Two features jump 40 px a frame, further than the gate; with the motion known they keep their tracks.
        val plain = FeatureTracker(MatchingWeights(maxDistance = 20.0))
        val predicted = FeatureTracker(MatchingWeights(maxDistance = 20.0))
        for (k in 0 until 5) {
            val f = [max(10.0 + 40 * k, 10.0), max(10.0 + 40 * k, 60.0)]
            plain.add(k.toDouble(), f)
            predicted.add(k.toDouble(), f) { _, _ -> 40.0 to 0.0 }
        }
        assertEquals(10, plain.tracks.size, "without prediction every jump is a death and a birth")
        assertEquals(2, predicted.tracks.size, "with it, two tracks of five samples")
        assertTrue(predicted.tracks.all { it.samples.size == 5 })
    }

    private fun available(): Boolean {
        if (!MorseSmaleLibrary.isAvailable) println("SKIPPED tracking-with-Morse–Smale tests: ${MorseSmaleLibrary.describe()}")
        return MorseSmaleLibrary.isAvailable
    }

    private fun hills(w: Int, h: Int, vararg hill: DoubleArray) = Img(w, h, 1, FloatArray(w * h) { i ->
        val x = i % w; val y = i / w
        hill.sumOf { (cx, cy, s, a) -> a * exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (2 * s * s)) }.toFloat()
    })

    private operator fun DoubleArray.component4() = this[3]

    @Test
    fun aDriftingHillKeepsOneTrack() {
        if (!available()) return
        val t = FeatureTracker(MatchingWeights(maxDistance = 8.0))
        for (frame in 0 until 30) {
            val r = MorseSmale.compute(hills(120, 80, doubleArrayOf(20.0 + 2.5 * frame, 40.0, 8.0, 1.0)), 0.05)
            t.add(frame.toDouble(), r.features(0.05, setOf(CriticalType.MAXIMUM)))
        }
        val long = t.tracks.filter { it.samples.size == 30 }
        assertEquals(1, long.size, "one maximum, followed through all 30 frames: ${t.tracks}")
        assertTrue(kotlin.math.abs(long[0].samples.last().second.x - 92.5) < 2)
    }

    @Test
    fun twoHillsThatMergeLeaveOneSurvivor() {
        if (!available()) return
        val t = FeatureTracker(MatchingWeights(maxDistance = 8.0))
        for (frame in 0 until 25) {
            val d = 30.0 - frame * 1.2
            val r = MorseSmale.compute(hills(140, 80, doubleArrayOf(70.0 - d, 40.0, 7.0, 1.0), doubleArrayOf(70.0 + d, 40.0, 7.0, 0.8)), 0.05)
            t.add(frame.toDouble(), r.features(0.05, setOf(CriticalType.MAXIMUM)))
        }
        assertEquals(1, t.alive.size, "after merging, one peak")
        assertEquals(1, t.tracks.count { it.died != null }, "the weaker one died: ${t.tracks}")
        assertTrue(t.tracks.first { it.died != null }.samples.first().second.value < 0.9, "and it was the weaker one")
    }

    @Test
    fun scaleSpaceKeepsTheLargeBlobLonger() {
        if (!available()) return
        val img = hills(160, 100, doubleArrayOf(50.0, 50.0, 20.0, 1.0), doubleArrayOf(120.0, 50.0, 3.0, 1.0))
        val s = ScaleSpace.track(img, listOf(0.0, 2.0, 4.0, 8.0, 16.0), persistence = 0.02, types = setOf(CriticalType.MAXIMUM))
        val big = s.tracker.tracks.first { it.samples.first().second.x < 80 }
        val small = s.tracker.tracks.first { it.samples.first().second.x > 80 }
        assertTrue(s.scaleOf(big) > s.scaleOf(small), "big reaches σ ${s.scaleOf(big)}, small ${s.scaleOf(small)}")
    }
}
