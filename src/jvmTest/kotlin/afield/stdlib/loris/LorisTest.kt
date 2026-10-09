package afield.stdlib.loris

import afield.four.audio.WavFloatArrayWriter
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.*
import kotlin.test.*

class LorisTest {
    private fun point(t: Double, f: Double = 440.0) = LorisBreakpoint(t, f, .2, 0.0, .1)
    private fun model() = LorisModel(48000, 48000, listOf(LorisPartial(73, 0, listOf(point(.1), point(.9)))))

    /** Exercise the documented futures in every environment, without downloading a native worker. */
    @Test
    fun asynchronousWavAnalysisAndSynthesisCompleteOrReportMissingWorker() {
        val folder = Files.createTempDirectory("field-loris-async-test-")
        try {
            val rate = 48000
            val wav = folder.resolve("source.wav")
            WavFloatArrayWriter.writeMono16(wav.toString(),
                FloatArray(rate) { (.2 * sin(2 * PI * 440 * it / rate)).toFloat() }, rate)
            val client = LorisClient(folder.resolve("cache"))
            val invalidRange = client.analyzeWavAsync(wav, startSeconds = 2.0)
            val rangeError = assertFailsWith<ExecutionException> { invalidRange.get(60, TimeUnit.SECONDS) }
            assertIs<IllegalArgumentException>(rangeError.cause)
            assertTrue(rangeError.cause!!.message!!.contains("outside WAV"))

            val analysis = client.analyzeWavAsync(wav, startSeconds = .1, durationSeconds = .4)
            val synthesis = client.synthesizeAsync(model())
            if (LorisNative.isAvailable) {
                val analyzed = analysis.get(60, TimeUnit.SECONDS)
                assertEquals(19200, analyzed.samples.size)
                assertEquals(rate, analyzed.model.sampleRate)
                assertTrue(analyzed.model.partials.isNotEmpty())
                val synthesized = synthesis.get(60, TimeUnit.SECONDS)
                assertEquals(rate, synthesized.samples.size)
                assertEquals(73, synthesized.model.partials.single().id)
                assertTrue(synthesized.samples.any { abs(it) > .05f })
                assertTrue(client.analyzeWavAsync(wav, startSeconds = .1, durationSeconds = .4)
                    .get(60, TimeUnit.SECONDS).provenance.cacheHit)
                assertTrue(client.synthesizeAsync(model()).get(60, TimeUnit.SECONDS).provenance.cacheHit)
            } else {
                for (future in listOf(analysis, synthesis)) {
                    val failure = assertFailsWith<ExecutionException> { future.get(60, TimeUnit.SECONDS) }
                    assertIs<IllegalStateException>(failure.cause)
                    assertTrue(failure.cause!!.message!!.contains("buildLorisNative"))
                }
                assertFalse(Files.exists(folder.resolve("cache")))
            }
        } finally { Files.walk(folder).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun validatesNativeDataAndIndependentControls() {
        assertFailsWith<IllegalArgumentException> { LorisOptions(hopSeconds = 0.0) }
        assertFailsWith<IllegalArgumentException> { LorisOptions(windowWidthHz = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { point(-.1) }
        assertFailsWith<IllegalArgumentException> { LorisPartial(0, 0, listOf(point(.2), point(.1))) }
        assertFailsWith<IllegalArgumentException> { model().copy(partials = model().partials + model().partials) }
        assertFailsWith<IllegalArgumentException> { model().transformed(frequencyScale = 100.0) }
        assertFailsWith<IllegalArgumentException> { LorisViewOptions(maxPartials = 2000, pointsPerPartial = 10000) }
        assertFailsWith<IllegalArgumentException> { LorisViewOptions(axis = LorisFrequencyAxis.CENTS, minimumHz = 0.0) }
        assertEquals(0.0, LorisViewOptions(axis = LorisFrequencyAxis.ERB).minimumHz)
    }

    @Test fun transformationsRetainIdentityAndNativePhase() {
        val result = model().transformed(timeScale = 2.0, frequencyScale = .5, gain = .3, bandwidthScale = 0.0)
        assertEquals(96000, result.frameCount)
        assertEquals(73, result.partials.single().id)
        with(result.partials.single().points.first()) {
            assertEquals(.2, timeSeconds); assertEquals(220.0, frequencyHz)
            assertEquals(.06, amplitude); assertEquals(0.0, bandwidth); assertEquals(0.0, phaseRadians)
        }
    }

    @Test fun whirlpoolOnlyEditsItsSelectedChunkAndPreservesIdentities() {
        val original = LorisModel(48000, 48000, listOf(LorisPartial(73, 0,
            listOf(point(.1), point(.3), point(.42), point(.56), point(.7), point(.9)))))
        val edited = original.spectralWhirlpool(.3, .4)
        assertEquals(original.frameCount, edited.frameCount)
        assertEquals(73, edited.partials.single().id)
        val before = original.partials.single().points
        val after = edited.partials.single().points
        for (i in before.indices) if (before[i].timeSeconds <= .3 || before[i].timeSeconds >= .7)
            assertEquals(before[i], after[i])
        assertTrue(after[2].bandwidth > before[2].bandwidth)
        assertNotEquals(before[2].frequencyHz, after[2].frequencyHz)
        assertEquals(before.map { it.phaseRadians }, after.map { it.phaseRadians })
    }

    @Test fun viewerIsSelfContainedEscapesLabelsAndKeepsNativeTimes() {
        val html = model().visualizationHtml(LorisViewOptions(label = "</script><bad>", syncGroup = "test"))
        assertTrue(html.contains("\\u003c/script\\u003e"))
        assertTrue(html.contains("field-time-seek")); assertTrue(html.contains("field-time-window"))
        assertTrue(html.contains("[0.1,440.0,0.2,0.1,0.0]"))
        assertFalse(html.contains("<bad>"))
        assertTrue(html.contains("\"maximumHz\":24000.0"))
        assertTrue(html.contains("full band"))
    }

    @Test fun frequencyBalancedDrawingKeepsQuietHighFrequencyNoisyTracks() {
        val low = List(50) { LorisPartial(it, 0, listOf(point(.1, 120.0), point(.9, 120.0))) }
        val noisy = LorisPartial(999, 0, listOf(
            LorisBreakpoint(.2, 8000.0, .001, 0.0, .8), LorisBreakpoint(.8, 8100.0, .001, 0.0, 1.0)))
        val input = LorisModel(48000, 48000, low + noisy)
        assertTrue(input.visualizationHtml(LorisViewOptions(maxPartials = 8)).contains("\"id\":999"))
        assertFalse(input.visualizationHtml(LorisViewOptions(maxPartials = 8,
            selection = LorisTrackSelection.STRONGEST)).contains("\"id\":999"))
    }

    /** No download/build in normal tests. Build explicitly to activate these real native checks. */
    @Test
    fun optionalNativeToneReconstructsCachesAndTransforms() {
        if (!LorisNative.isAvailable) { println("Loris optional native test: not built; no download"); return }
        val folder = Files.createTempDirectory("field-loris-test-")
        try {
            val client = LorisClient(folder)
            val rate = 48000
            val source = FloatArray(rate) { i -> (.2 * sin(2 * PI * 440 * i / rate) *
                min(1.0, min(i / 2400.0, (rate - 1 - i) / 2400.0))).toFloat() }
            val result = client.analyze(source, rate)
            assertFalse(result.provenance.cacheHit)
            assertEquals(rate, result.samples.size)
            val strongest = result.model.partials.maxBy { p -> p.points.sumOf { it.amplitude } }
            val frequencies = strongest.points.filter { it.timeSeconds in .15.. .85 }.map { it.frequencyHz }
            assertTrue(frequencies.size > 30)
            assertTrue(abs(frequencies.average() - 440) < 2.0, "tracked frequency ${frequencies.average()}")
            val mse = (.15 * rate).toInt().until((.85 * rate).toInt()).map { i ->
                (source[i] - result.samples[i]).toDouble().pow(2) }.average()
            assertTrue(mse < .0002, "reconstruction MSE $mse")
            assertTrue(result.peak in .1f.. .4f)
            val cached = client.analyze(source, rate)
            assertTrue(cached.provenance.cacheHit)
            assertContentEquals(result.samples, cached.samples)
            val shifted = client.synthesize(result.model.transformed(frequencyScale = .5, bandwidthScale = 0.0))
            assertEquals(result.model.partials.map { it.id }, shifted.model.partials.map { it.id })
            assertTrue(shifted.samples.any { abs(it) > .05f })
            assertTrue(client.synthesize(shifted.model).provenance.cacheHit)
            assertEquals(73, client.synthesize(model()).model.partials.single().id,
                "resynthesis must preserve non-sequential track identity")
            val quiet = client.analyze(FloatArray(4800), rate)
            assertTrue(quiet.model.partials.isEmpty())
            assertTrue(quiet.samples.all { it == 0f })
            println("Loris real tone: ${result.model.partials.size} partials; MSE=$mse; cache + native resynthesis passed")
        } finally { Files.walk(folder).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
