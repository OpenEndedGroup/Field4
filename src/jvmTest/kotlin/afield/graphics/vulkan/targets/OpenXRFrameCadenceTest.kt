package afield.graphics.vulkan.targets

import kotlin.test.*

class OpenXRFrameCadenceTest {
    @Test
    fun separatesHalfRateSubmissionFromRuntimeRefreshPeriod() {
        val cadence = OpenXRFrameCadence()
        repeat(10) { i ->
            cadence.record(9_000_000_000L + i * 22_222_222L, 11_111_111L, 1_000_000_000L + i * 22_222_222L, true)
        }
        val snapshot = cadence.snapshot()
        assertEquals(9, snapshot.samples)
        assertEquals(mapOf(22L to 9), snapshot.submissionMs)
        assertEquals(mapOf(22L to 9), snapshot.predictionMs)
        assertEquals(mapOf(11L to 9), snapshot.periodMs)
    }

    @Test
    fun retainsMixedCadenceAndEvictsOldSamples() {
        val cadence = OpenXRFrameCadence(capacity = 3)
        var now = 1_000_000_000L
        cadence.record(now, 11_111_111L, now, true)
        for (step in listOf(11_111_111L, 11_111_111L, 22_222_222L, 33_333_333L)) {
            now += step
            cadence.record(now, 11_111_111L, now, true)
        }
        assertEquals(3, cadence.snapshot().samples)
        assertEquals(mapOf(11L to 1, 22L to 1, 33L to 1), cadence.snapshot().predictionMs)
    }

    @Test
    fun emptyFramesAndLongPausesDoNotManufactureMissedRefreshSamples() {
        val cadence = OpenXRFrameCadence()
        cadence.record(1_000_000_000L, 11_111_111L, 1_000_000_000L, true)
        cadence.record(1_011_111_111L, 11_111_111L, 1_011_111_111L, false)
        cadence.record(1_022_222_222L, 11_111_111L, 1_022_222_222L, true)
        cadence.record(5_000_000_000L, 11_111_111L, 5_000_000_000L, true)
        assertEquals(0, cadence.snapshot().samples)
        cadence.record(5_011_111_111L, 11_111_111L, 5_011_111_111L, true)
        assertEquals(mapOf(11L to 1), cadence.snapshot().submissionMs)
    }

    @Test
    fun reportingIsThrottled() {
        val cadence = OpenXRFrameCadence()
        assertNull(cadence.reportIfDue(1_000_000_000L))
        cadence.record(1_000_000_000L, 11_111_111L, 1_000_000_000L, true)
        cadence.record(1_011_111_111L, 11_111_111L, 1_011_111_111L, true)
        assertNull(cadence.reportIfDue(5_999_999_999L))
        assertEquals(1, cadence.reportIfDue(6_000_000_000L)?.samples)
        assertNull(cadence.reportIfDue(6_000_000_001L))
    }
}
