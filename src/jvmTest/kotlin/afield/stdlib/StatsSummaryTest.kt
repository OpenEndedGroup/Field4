package afield.stdlib

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Known-answer coverage for the descriptive half of [stats.kt]: [buildSummaryStats],
 * [buildHistogram] and [SummaryStatsBuffer].
 *
 * These had no tests at all, and `public/docs/statistics_examples.md` now makes published claims
 * about which convention each field follows. The reference values in
 * [summary stats match SciPy on a tie-heavy sample] were produced by SciPy 1.15.2 / NumPy 1.26.4
 * on the same ten points and are pasted here verbatim, so this is a genuine external oracle rather
 * than the implementation compared against itself:
 *
 * ```
 * x = [1,2,2,4,5,6,6,6,9,10]; y = [3,1,4,1,5,9,2,6,5,3]
 * numpy.percentile(y, [25,50,75])  -> 2.25, 3.5, 5.0
 * numpy.median(abs(y - median(y))) -> 1.5
 * numpy.std(y, ddof=1)             -> 2.4698178070
 * scipy.stats.skew(y)              -> 0.6800647393     (default bias=True, i.e. population)
 * scipy.stats.kurtosis(y)          -> -0.1642529388    (default bias=True, excess)
 * ```
 */
class StatsSummaryTest {

    private val tieHeavy = listOf(3.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0, 6.0, 5.0, 3.0)

    // -------------------------
    // buildSummaryStats against SciPy / NumPy
    // -------------------------

    @Test
    fun `summary stats match SciPy on a tie-heavy sample`() {
        val s = buildSummaryStats(tieHeavy)
        assertEquals(10, s.count)
        assertEquals(0, s.droppedCount)
        assertEquals(1.0, s.min, 0.0)
        assertEquals(9.0, s.max, 0.0)
        assertEquals(39.0, s.sum, 1e-12)
        assertEquals(3.9, s.mean, 1e-12)

        // numpy.percentile default = linear interpolation between order statistics ("type 7")
        assertEquals(2.25, s.q1, 1e-12)
        assertEquals(3.5, s.median, 1e-12)
        assertEquals(5.0, s.q3, 1e-12)
        assertEquals(2.75, s.iqr, 1e-12)

        // median absolute deviation from the median, UNSCALED (no 1.4826 factor)
        assertEquals(1.5, s.mad, 1e-12)

        // stddev/variance are the SAMPLE (n-1) forms...
        assertEquals(2.4698178070, s.sampleStdDev, 1e-9)
        assertEquals(2.4698178070 * 2.4698178070, s.sampleVariance, 1e-9)
        // ...while skew/kurtosis are the POPULATION (n) forms, matching scipy's bias=True default.
        assertEquals(0.6800647393, s.skewness, 1e-9)
        assertEquals(-0.1642529388, s.kurtosisExcess, 1e-9)
        // and populationStdDev is the n form of the same quantity: sqrt((n-1)/n) times the sample one
        assertEquals(s.sampleStdDev * kotlin.math.sqrt(9.0 / 10.0), s.populationStdDev, 1e-12)

        assertEquals(8.0, s.range, 1e-12)
        assertEquals(s.sampleStdDev / s.mean, s.coeffOfVariation, 1e-12)
    }

    @Test
    fun `non-finite values are dropped and counted rather than poisoning the result`() {
        val clean = buildSummaryStats(tieHeavy)
        val dirty = buildSummaryStats(tieHeavy + listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
        assertEquals(clean.count, dirty.count)
        assertEquals(3, dirty.droppedCount)
        assertEquals(clean.mean, dirty.mean, 1e-12)
        assertEquals(clean.median, dirty.median, 1e-12)
        assertEquals(clean.kurtosisExcess, dirty.kurtosisExcess, 1e-12)

        var reported = -1
        buildSummaryStats(tieHeavy + Double.NaN) { reported = it }
        assertEquals(1, reported, "onDrop should be invoked with the total dropped count")
    }

    @Test
    fun `the documented small-sample guards return zero rather than NaN`() {
        // stats.kt:884 -- n == 1 gives sampleVariance 0.0, not NaN
        val one = buildSummaryStats(listOf(4.0))
        assertEquals(0.0, one.sampleVariance, 0.0)
        assertEquals(0.0, one.sampleStdDev, 0.0)
        assertEquals(4.0, one.median, 0.0)
        assertEquals(0.0, one.mad, 0.0)

        // stats.kt:894 -- skewness needs n > 2
        val two = buildSummaryStats(listOf(1.0, 3.0))
        assertEquals(0.0, two.skewness, 0.0)
        assertEquals(0.0, two.kurtosisExcess, 0.0)
        assertEquals(1.0, two.populationVariance, 1e-12)
        assertEquals(2.0, two.sampleVariance, 1e-12)

        // stats.kt:899 -- kurtosis needs n > 3; skewness is live by n == 3
        val three = buildSummaryStats(listOf(1.0, 3.0, 10.0))
        assertTrue(three.skewness > 0.5, "n=3 should produce a real skewness: ${three.skewness}")
        assertEquals(0.0, three.kurtosisExcess, 0.0)
    }

    @Test
    fun `an empty sample is empty rather than an exception`() {
        val e = buildSummaryStats(emptyList())
        assertTrue(e.isEmpty)
        assertEquals(0, e.count)
        assertEquals(0.0, e.sum, 0.0)
        assertTrue(e.mean.isNaN())
        assertTrue(e.range.isNaN())
        assertTrue(e.coeffOfVariation.isNaN())
    }

    // -------------------------
    // buildHistogram
    // -------------------------

    /**
     * Re-derives the bin-width rule the way `statistics_examples.md` does and checks the prediction.
     *
     * This shares `quantileSorted` with the implementation, so it pins the *rule selection and the
     * rounding* rather than the quantiles — those are covered by the SciPy comparison above.
     */
    @Test
    fun `the bin width is the smallest of Freedman-Diaconis, Scott and Sturges`() {
        val rng = java.util.Random(11)
        val skewed = (0 until 500).map { exp(0.6 * rng.nextGaussian()) }
        val s = buildSummaryStats(skewed)
        val range = s.max - s.min
        val cubeRoot = s.count.toDouble().pow(-1.0 / 3.0)
        val hFd = 2.0 * s.iqr * cubeRoot
        val hScott = 3.5 * s.sampleStdDev * cubeRoot
        val kSturges = kotlin.math.ceil(ln(s.count.toDouble()) / ln(2.0) + 1.0).toInt()
        val hSturges = range / kSturges

        // On this sample Freedman-Diaconis is the finest of the three, so it decides.
        assertTrue(hFd < hScott && hFd < hSturges, "expected FD to win: fd=$hFd scott=$hScott sturges=$hSturges")
        val predictedBins = Math.round(range / hFd).toInt()

        val h = buildHistogram(skewed)
        assertEquals(predictedBins, h.bins.size)
        assertEquals(range / predictedBins, h.binWidth, 1e-12)
        assertEquals(500, h.count, "every finite sample must land in exactly one bin")
        assertEquals(s.min, h.min, 0.0)
        assertEquals(s.max, h.max, 0.0)
        assertEquals(s.median, h.median, 1e-12)

        // Bins tile [min, max] with no gap and no overlap, and the last one ends exactly at max.
        for (i in 1 until h.bins.size) {
            assertEquals(h.bins[i - 1].end, h.bins[i].start, 1e-9, "bin $i should start where ${i - 1} ended")
        }
        assertEquals(h.min, h.bins.first().start, 1e-12)
        assertEquals(h.max, h.bins.last().end, 1e-12)
    }

    @Test
    fun `a bimodal sample is under-resolved because both rules read the between-mode spread`() {
        // The caveat statistics_examples.md publishes: same n, far fewer bins.
        val rng = java.util.Random(23)
        val bimodal = (0 until 500).map {
            if (it % 2 == 0) -2.0 + 0.4 * rng.nextGaussian() else 2.0 + 0.4 * rng.nextGaussian()
        }
        val s = buildSummaryStats(bimodal)
        assertTrue(s.iqr > 3.0, "the IQR should straddle both modes: ${s.iqr}")
        val bins = buildHistogram(bimodal).bins.size
        assertTrue(bins < 15, "expected a coarse histogram for a bimodal sample, got $bins bins")
        assertTrue(
            buildHistogram((0 until 500).map { exp(0.6 * java.util.Random(11).nextGaussian()) }).bins.size >= 1,
        )
    }

    @Test
    fun `a constant sample gets one synthetic bin rather than a zero-width divide`() {
        val h = buildHistogram(listOf(5.0, 5.0, 5.0))
        assertEquals(1, h.bins.size)
        assertEquals(3, h.count)
        assertEquals(5.0, h.min, 0.0)
        assertEquals(5.0, h.max, 0.0)
        assertTrue(h.binWidth > 0.0, "the synthetic bin must have positive width: ${h.binWidth}")
        assertTrue(h.bins[0].start < 5.0 && h.bins[0].end > 5.0)
    }

    @Test
    fun `an empty sample gives an empty histogram`() {
        val h = buildHistogram(emptyList())
        assertEquals(0, h.bins.size)
        assertEquals(0, h.count)
        assertNull(h.modeBin)
        assertTrue(h.min.isNaN())
    }

    @Test
    fun `modeBin is the fullest bin`() {
        val h = buildHistogram((0 until 400).map { it / 400.0 } + List(200) { 0.5001 })
        val mode = h.modeBin!!
        assertEquals(h.bins.maxOf { it.count }, mode.count)
        assertTrue(0.5001 >= mode.start && 0.5001 <= mode.end, "the spike should land in the mode bin: $mode")
    }

    // -------------------------
    // SummaryStatsBuffer
    // -------------------------

    @Test
    fun `the buffer counts refusals and evictions separately`() {
        val buf = SummaryStatsBuffer(maxSize = 64)
        val rng = java.util.Random(4)
        repeat(200) { buf.add(rng.nextGaussian()) }
        buf.add(Double.NaN)
        buf.add(null)

        assertEquals(64, buf.size)
        assertEquals(202L, buf.samplesSeen)
        assertEquals(2L, buf.droppedNonFinite, "NaN and null are refusals, not evictions")
        assertEquals(136L, buf.droppedByCapacity, "200 finite adds into a 64 window evicts 136")

        val box = buf.summaryBox()
        assertEquals(64, box.stats.count)
        assertEquals(64, box.windowSize)
        assertEquals(2, box.stats.droppedCount, "the snapshot carries the non-finite count forward")
        assertEquals(64, box.values.size)
        assertTrue(box.values.all { it.isFinite() })
    }

    @Test
    fun `the buffer window keeps the newest samples`() {
        val buf = SummaryStatsBuffer(maxSize = 5)
        (1..10).forEach { buf.add(it.toDouble()) }
        assertEquals(listOf(6.0, 7.0, 8.0, 9.0, 10.0), buf.values())
        assertEquals(8.0, buf.snapshot().median, 1e-12)

        buf.setWindow(3)
        assertEquals(listOf(8.0, 9.0, 10.0), buf.values(), "shrinking the window trims immediately")

        buf.clear()
        assertEquals(0, buf.size)
        assertTrue(buf.samplesSeen > 0, "clear() keeps the counters by default")
        buf.clear(resetCounters = true)
        assertEquals(0L, buf.samplesSeen)
        assertEquals(0L, buf.droppedByCapacity)
    }

    @Test
    fun `maxSize is floored at one`() {
        val buf = SummaryStatsBuffer(maxSize = 0)
        assertEquals(1, buf.maxSize)
        buf.add(1.0)
        buf.add(2.0)
        assertEquals(listOf(2.0), buf.values())
    }

    @Test
    fun `the rendered card is self-contained SVG and HTML`() {
        val buf = SummaryStatsBuffer(maxSize = 128)
        val rng = java.util.Random(6)
        repeat(128) { buf.add(rng.nextGaussian()) }
        val html = buf.summaryHtml(widthPx = 480)
        assertTrue(html.contains("<svg"), "the card embeds SVG rather than referencing an image")
        assertTrue(html.contains("Summary stats"))
        assertTrue(html.contains("window=128"))
        // No external references: no <img>, no <script>, nothing to fetch.
        assertTrue(!html.contains("<img"), "the card must not reference an external image")
        assertTrue(!html.contains("<script"), "the card must not need scripting")
    }

    // -------------------------
    // the vector variants
    // -------------------------

    @Test
    fun `vector buffers report exact component correlations`() {
        val v3 = Vec3SummaryStatsBuffer(maxSize = 100)
        val rng = java.util.Random(8)
        repeat(100) {
            val g = rng.nextGaussian()
            v3.add(vec(g, 2.0 * g, rng.nextGaussian()))   // y is exactly 2x; z is independent
        }
        val s = v3.snapshot()
        assertEquals(100, s.count)
        assertEquals(1.0, s.correlationXY, 1e-12, "y = 2x must correlate exactly 1")
        assertTrue(kotlin.math.abs(s.correlationXZ) < 0.35, "z is independent: ${s.correlationXZ}")
        assertEquals(s.x.mean, s.mean.x.toDouble(), 1e-6)

        val v2 = Vec2SummaryStatsBuffer(maxSize = 8)
        v2.add(vec(Double.NaN, 1.0))
        assertEquals(0, v2.snapshot().count, "a non-finite component drops the whole vector")
        assertTrue(v2.snapshot().correlationXY.isNaN())
    }
}
