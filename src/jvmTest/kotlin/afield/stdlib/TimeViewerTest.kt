package afield.stdlib

import afield.Box
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.*

/**
 * The one time-axis viewer's server query and embedded payload. `SignalViewerTest` covers the
 * range query's sampling budget; this pins what each migrated caller puts on the wire.
 */
class TimeViewerTest {
    private val mapper = jacksonObjectMapper()

    private fun config(html: String): Map<String, Any?> {
        val text = Regex("""<script type="application/json" class="fsv-config">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: fail("no .fsv-config in the viewer HTML")
        return mapper.readValue(text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun embeddedValues(cfg: Map<String, Any?>): List<Double?> {
        val window = cfg["window"] as Map<String, Any?>
        val lane = (window["lanes"] as List<Map<String, Any?>>).single()
        return ((lane["current"] as Map<String, Any?>)["low"] as List<Number?>).map { it?.toDouble() }
    }

    @Test fun fnPlotEmbedsItsOwnSamplesAndNamesItsGapsExactly() {
        // A function with holes of both kinds: non-finite values near 0, and a throw inside 0.7..0.8.
        val body = { x: Double -> if (x in 0.7..0.8) error("hole") else kotlin.math.ln(x - 0.05) }
        val fn = Functions.fn(0.0..1.0) { x -> body(x) }
        val cfg = config(with(Functions) { fn.plotHtml(samples = 101) })
        assertEquals("embedded", cfg["source"])
        assertNull(cfg["url"], "bare plotHtml has no server source, so it cannot depend on one")
        val values = embeddedValues(cfg)
        assertEquals(101, values.size)
        val grid = List(101) { 0.0 + (1.0 / 100) * it }
        val expectedGaps = grid.indices.filter { runCatching { body(grid[it]) }.getOrNull()?.isFinite() != true }.toSet()
        // Premise: the fixture really has both kinds of gap.
        assertTrue(expectedGaps.any { grid[it] < 0.1 } && expectedGaps.any { grid[it] > 0.69 })
        assertEquals(expectedGaps, values.indices.filter { values[it] == null }.toSet())
        for (i in grid.indices) if (i !in expectedGaps) assertEquals(body(grid[i]), values[i]!!, 1e-12)
        assertEquals("${101 - expectedGaps.size}/101 samples", cfg["samplesChip"])
    }

    @Test fun boxPlotAddsALiveSourceThatReplacesAndReleasesThePreviousOne() {
        val box = Box("time-viewer-plot")
        val shown = mutableListOf<String>()
        box.output.displayProxy = { _, value -> shown += value as String }
        val first = Functions.sin(3.0)
        with(Functions) { box.plot(first, "plot", Functions.Domain.of(0.0, 2.0)) }
        val firstId = (config(shown.last())["url"] as String).substringAfterLast('/')
        val live = assertNotNull(SignalViewer.find(firstId))
        // The live source samples the same function over the same domain, with gaps allowed.
        assertTrue(live.gaps)
        assertEquals(0.0, live.start); assertEquals(2.0, live.end)
        val zoom = live.window(0.5, 0.6, 64)
        assertEquals(first.eval(0.5), zoom.lanes.single().current.low.first(), 1e-12)
        with(Functions) { box.plot(Functions.cos(3.0), "plot", Functions.Domain.of(0.0, 2.0)) }
        assertNull(SignalViewer.find(firstId), "replacing the plot must release the previous evaluator")
    }

    @Test fun minMaxBinsKeepASingleSampleSpikeThatDecimationDrops() {
        // One grid sample at t = 0.3 is 1; everything else is 0. 1,000,000-sample grid, 500 bins.
        val step = 1e-6
        val spikeAt = 300_002
        val signal = { t: Double -> if (kotlin.math.abs(t / step - spikeAt) < 0.5) 1.0 else 0.0 }
        SignalViewer(listOf(SignalChannel("spike", signal)), 0.0, 1.0, sampleStep = step * 2).use { viewer ->
            val w = viewer.window(0.0, 1.0, 500)
            assertEquals("envelope", w.mode)
            val grid = (0..500_000).map { it * 2 * step }
            // Premise: the spike sits on the evaluation grid, or the test proves nothing.
            assertTrue(grid.any { signal(it) == 1.0 })
            // Counterfactual floor: decimating that grid to one sample per bin (what a naive
            // plot would draw) misses the spike entirely.
            val decimated = (0 until 500).map { grid[it * 1000] }
            assertEquals(0.0, decimated.maxOf(signal))
            // The envelope keeps it, in exactly one bin.
            val high = w.lanes.single().current.high
            assertEquals(setOf(150), high.indices.filter { high[it] == 1.0 }.toSet())
        }
    }

    @Test fun gapsTravelAsNullOnTheWireAndAreRefusedWithoutGapsMode() {
        val holey = { t: Double -> if (t in 0.4..0.6) Double.NaN else t }
        SignalViewer(listOf(SignalChannel("h", holey)), 0.0, 1.0, sampleStep = 0.1, gaps = true).use { viewer ->
            val json: Map<String, Any?> = mapper.readValue(viewer.window(0.0, 1.0, 16).toJson())
            @Suppress("UNCHECKED_CAST")
            val low = (((json["lanes"] as List<Map<String, Any?>>).single()["current"] as Map<String, Any?>)["low"] as List<Any?>)
            val nullBins = low.indices.filter { low[it] == null }.toSet()
            // Waveform mode, 33 samples over 0..1: exactly the samples inside 0.4..0.6 are gaps.
            val expected = (0..32).filter { (it / 32.0) in 0.4..0.6 }.toSet()
            assertEquals(setOf(13, 14, 15, 16, 17, 18, 19), expected)
            assertEquals(expected, nullBins)
        }
        SignalViewer(listOf(SignalChannel("h", holey)), 0.0, 1.0).use { viewer ->
            assertFailsWith<IllegalArgumentException> { viewer.window(0.0, 1.0, 16) }
        }
    }

    @Test fun audioAndAnalysisCallersReachTheSameViewer() {
        val audio = AudioVisualizer.renderSamplesHtml(floatArrayOf(-1f, 0f, 1f), 8_000,
            AudioVisualizerOptions(syncGroup = "g", syncOffsetSeconds = 2.0, interactive = false))
        val a = config(audio)
        assertEquals("audio", a["source"]); assertEquals("g", a["syncGroup"]); assertEquals(2.0, a["syncOffset"])
        assertEquals(false, a["interactive"])
        assertFalse("data-action=\"in\"" in audio, "a non-interactive figure hides its zoom buttons")
        val preview = afield.stdlib.noir.audioai.analysisPreviewVisualizationHtml(
            afield.stdlib.noir.audioai.AnalysisPreview("analysis.beats", 1.0, emptyList()), syncGroup = "g")
        assertEquals("analysis", config(preview)["source"])
        // One runtime, not three: every caller inlines the same signal-viewer.js.
        val runtime = checkNotNull(javaClass.getResourceAsStream("/signal-viewer.js")).bufferedReader().readText()
        for (html in listOf(audio, preview, with(Functions) { sin(2.0).plotHtml() }, SignalViewer(listOf(SignalChannel("x", { .5 })), 0.0, 1.0).use { it.renderHtml() }))
            assertTrue(runtime in html)
    }
}
