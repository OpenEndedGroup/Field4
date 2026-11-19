package afield.geometry.topology.tracking

import afield.geometry.topology.morsesmale.CriticalType
import afield.geometry.topology.morsesmale.MorseSmale
import afield.geometry.topology.morsesmale.MorseSmaleLibrary
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CerfDiagramTest {
    private fun available(): Boolean {
        if (!MorseSmaleLibrary.isAvailable) println("SKIPPED Cerf diagram tests: ${MorseSmaleLibrary.describe()}")
        return MorseSmaleLibrary.isAvailable
    }

    /**
     * A broad hill with a small one on its flank whose height rises from 0 and falls back. While it
     * is small the flank only has a shoulder; once it is tall enough a maximum and a saddle appear
     * together, and they cancel together on the way down.
     */
    private fun field(a: Double, sign: Double, w: Int = 90, h: Int = 70) = FloatArray(w * h) { i ->
        val x = (i % w).toDouble(); val y = (i / w).toDouble()
        fun g(cx: Double, cy: Double, s: Double) = exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (2 * s * s))
        (sign * (g(35.0, 35.0, 10.0) + a * g(55.0, 35.0, 5.0)) + 0.001 * (x * 0.37 + y * 0.11)).toFloat()
    }

    private fun run(sign: Double): CerfDiagram {
        val cerf = CerfDiagram()
        for (frame in 0..40) {
            val a = 0.8 * sin(PI * frame / 40.0)
            cerf.add(frame.toDouble(), MorseSmale.compute(field(a, sign), 90, 70), minPersistence = 1e-4)
        }
        return cerf
    }

    /** Where the flank hill is tall enough to have its own peak, from a 1-D scan along y = 35. */
    private fun hasPeak(a: Double): Boolean {
        val row = (0 until 90).map { x -> field(a, 1.0)[35 * 90 + x] }
        return (46 until 88).any { x -> row[x] > row[x - 1] && row[x] > row[x + 1] }
    }

    @Test
    fun aRisingFlankHillIsBornAsASaddleMaximumPairAndCancels() {
        if (!available()) return
        val cerf = run(1.0)
        val births = cerf.events(CerfEventType.BIRTH)
        val deaths = cerf.events(CerfEventType.DEATH)
        assertEquals(1, births.size, "one pair born: $cerf ${cerf.events}")
        assertEquals(1, deaths.size, "one pair cancels: ${cerf.events}")
        assertEquals(1, births[0].pairIndex, "a saddle with a maximum")
        assertEquals(setOf(CriticalType.SADDLE, CriticalType.MAXIMUM), births[0].tracks.map { it.type }.toSet())
        assertEquals(births[0].tracks.toSet(), deaths[0].tracks.toSet(), "the same two tracks die")
        // the birth falls at the first frame whose 1-D profile has a second peak
        val first = (0..40).first { hasPeak(0.8 * sin(PI * it / 40.0)) }
        val last = (0..40).last { hasPeak(0.8 * sin(PI * it / 40.0)) }
        assertTrue(kotlin.math.abs(births[0].time - first) <= 1, "born at ${births[0].time}, profile peak from $first")
        assertTrue(kotlin.math.abs(deaths[0].time - last) <= 1, "dies at ${deaths[0].time}, profile peak until $last")
        assertTrue(births[0].x in 45.0..60.0, "on the flank: ${births[0]}")
        // the global maximum is followed through every frame
        assertTrue(cerf.tracker.tracks.any { it.type == CriticalType.MAXIMUM && it.samples.size == 41 })
    }

    @Test
    fun theNegatedFieldGivesAMinimumSaddlePair() {
        if (!available()) return
        val cerf = run(-1.0)
        val births = cerf.events(CerfEventType.BIRTH)
        assertEquals(1, births.size, "${cerf.events}")
        assertEquals(0, births[0].pairIndex)
        assertEquals(1, cerf.events(CerfEventType.DEATH).size)
    }

    @Test
    fun crossingValuesAreSwaps() {
        val cerf = CerfDiagram()
        fun max(x: Double, v: Double, id: Int) = Feature(CriticalType.MAXIMUM, x, 10.0, v, 1.0, id)
        cerf.add(0.0, listOf(max(10.0, 1.0, 1), max(40.0, 0.5, 2)))
        cerf.add(1.0, listOf(max(10.0, 0.8, 1), max(40.0, 0.6, 2)))
        val swaps = cerf.add(2.0, listOf(max(10.0, 0.6, 1), max(40.0, 0.8, 2)))
        assertEquals(1, swaps.size)
        assertEquals(CerfEventType.SWAP, swaps[0].type)
        assertEquals(1.5, swaps[0].time, 1e-9)
        assertEquals(0.7, swaps[0].value, 1e-9)
        // unpaired arrivals are APPEAR, not BIRTH
        val appear = cerf.add(3.0, listOf(max(10.0, 0.6, 1), max(40.0, 0.8, 2), max(80.0, 0.3, 3)))
        assertEquals(listOf(CerfEventType.APPEAR), appear.map { it.type })
        val lines = cerf.toFLines(cursor = 1.0)
        assertEquals(3 + 1 + 1, lines.size, "three tracks, one swap layer, a cursor")
        assertEquals(3, cerf.fieldMarkers(3.0)[0].nodes.size)
        assertEquals(2.0, cerf.frameAt(2.5)!!.time)
    }
}
