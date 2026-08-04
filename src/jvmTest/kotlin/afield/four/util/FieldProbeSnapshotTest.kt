package afield.four.util

import afield.stdlib.vec
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldProbeSnapshotTest {
    private fun scalarSnapshot(values: List<Double>) = FieldProbeSnapshot(
        dimensions = 1,
        invocationCount = values.size.toLong(),
        acceptedCount = values.size.toLong(),
        finiteCount = values.size.toLong(),
        sampleCount = values.size,
        nanCount = 0,
        infCount = 0,
        nonZeroCount = values.count { it != 0.0 }.toLong(),
        typeMismatchCount = 0,
        exactMin = doubleArrayOf(values.min()),
        exactMax = doubleArrayOf(values.max()),
        samples = values.map { vec(it, 0, 0, 0) },
    )

    @Test
    fun scalarSummaryPresentationIgnoresGpuInvocationOrder() {
        val ascending = scalarSnapshot(listOf(0.125, 0.5, 1.0, 2.0, 4.0))
        val schedulerOrder = scalarSnapshot(listOf(2.0, 0.125, 4.0, 1.0, 0.5))

        assertEquals(ascending.summaryHtml(), schedulerOrder.summaryHtml())
    }
}
