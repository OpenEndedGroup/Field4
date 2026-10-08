package afield.stdlib.loris

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

class LorisTransformTest {
    private fun bp(t: Double, f: Double, a: Double = .1) = LorisBreakpoint(t, f, a, 0.0, .2)
    private fun partial(id: Int, label: Int, f: Double, t0: Double = .1, t1: Double = .9, a: Double = .1) =
        LorisPartial(id, label, listOf(bp(t0, f, a), bp((t0 + t1) / 2, f, a), bp(t1, f, a)))

    @Test fun envelopesInterpolateAndHoldTheirEnds() {
        val e = LorisEnvelope.ramp(1.0, 3.0)
        assertEquals(0.0, e.valueAt(0.0)); assertEquals(.5, e.valueAt(2.0)); assertEquals(1.0, e.valueAt(9.0))
        assertEquals(.25, LorisEnvelope.constant(.25).valueAt(123.0))
        assertFailsWith<IllegalArgumentException> { LorisEnvelope(listOf(1.0 to 0.0, 1.0 to 1.0)) }
        assertFailsWith<IllegalArgumentException> { LorisReference.Estimated(300.0, 100.0) }
    }

    @Test fun kotlinPartialEditsAreExact() {
        val m = LorisModel(48000, 48000, listOf(partial(5, 1, 1000.0), partial(6, 2, 2000.0, a = .001),
            partial(7, 0, 4000.0, .4, .45)))
        assertEquals(mapOf(0 to 1, 1 to 1, 2 to 1), m.labelCounts())
        assertEquals(listOf(5, 6), m.dropUnlabeled().partials.map { it.id })
        assertEquals(listOf(6), m.keepLabels(listOf(2)).partials.map { it.id })
        assertEquals(listOf(5, 6), m.minimumDuration(.1).partials.map { it.id })
        assertEquals(listOf(5, 7), m.minimumPeak(-40.0).partials.map { it.id })
        val up = m.shiftPitch(1200.0)
        assertEquals(2000.0, up.partials[0].points[0].frequencyHz, 1e-9)
        assertEquals(listOf(5, 6, 7), up.partials.map { it.id })
        assertEquals(2, m.shiftPitch(3600.0).partials.size, "32 kHz breakpoints exceed 0.49 × rate and are removed")
        val tilted = m.tilt(-6.0, pivotHz = 1000.0)
        assertEquals(.1, tilted.partials[0].points[0].amplitude, 1e-12)
        assertEquals(.001 * 10.0.pow(-6.0 / 20), tilted.partials[1].points[0].amplitude, 1e-12)
        val glide = m.shiftPitch(LorisEnvelope.ramp(.1, .9, 0.0, 1200.0)).partials[0].points
        listOf(1000.0, 1000 * sqrt(2.0), 2000.0).zip(glide.map { it.frequencyHz }).forEach { (w, g) -> assertEquals(w, g, 1e-9) }
        assertEquals(96000, m.stretched(2.0).frameCount)
        val cropped = m.crop(.4, .6)
        assertEquals(9600, cropped.frameCount)
        assertTrue(cropped.partials.all { p -> p.points.all { it.timeSeconds in 0.0.. .2 } })
    }

    /** Harmonic tone whose fundamental and harmonic weights are chosen per test. */
    private fun tone(f0: (Double) -> Double, harmonics: List<Pair<Int, Double>>, seconds: Double = 1.0, rate: Int = 48000): FloatArray {
        val out = FloatArray((seconds * rate).toInt()); var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / rate
            phase += 2 * PI * f0(t) / rate
            val fade = min(1.0, min(i / 2400.0, (out.size - 1 - i) / 2400.0))
            out[i] = (fade * harmonics.sumOf { (k, a) -> a * sin(k * phase) }).toFloat()
        }
        return out
    }

    private fun withClient(body: (LorisClient) -> Unit) {
        if (!LorisNative.isAvailable) { println("Loris transform native test: not built; no download"); return }
        val folder: Path = Files.createTempDirectory("field-loris-transform-")
        try { body(LorisClient(folder)) }
        finally { Files.walk(folder).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun LorisModel.labelled(label: Int) = partials.single { it.label == label }
    private fun LorisPartial.at(t: Double) = points.minBy { abs(it.timeSeconds - t) }

    @Test fun distillLabelsEveryHarmonicOnceAgainstAKnownOrEstimatedFundamental() = withClient { client ->
        val rate = 48000
        val harmonics = (1..8).map { it to .3 / it }
        val analyzed = client.analyze(tone({ 220.0 }, harmonics), rate).model
        for (reference in listOf(LorisReference.constant(220.0), LorisReference.Estimated(150.0, 300.0))) {
            val distilled = client.distill(analyzed, reference).model
            val counts = distilled.labelCounts()
            for (k in 1..8) {
                assertEquals(1, counts[k], "harmonic $k with $reference: $counts")
                assertEquals(220.0 * k, distilled.labelled(k).meanFrequencyHz, 220.0 * k * .01)
            }
            assertTrue(distilled.partials.size <= analyzed.partials.size)
        }
        val channelOnly = client.distill(analyzed, LorisReference.constant(220.0), distill = false).model
        assertEquals(analyzed.partials.size, channelOnly.partials.size, "channelize alone relabels, never merges")
        assertTrue((1..8).all { (channelOnly.labelCounts()[it] ?: 0) >= 1 })
        val sifted = client.sift(channelOnly, dropUnlabeled = true).model
        assertTrue(sifted.partials.all { it.label != 0 })
    }

    @Test fun morphEndpointsReproduceTheirSources() = withClient { client ->
        val rate = 48000
        val a = client.distill(client.analyze(tone({ 220.0 }, (1..6).map { it to .3 / it }), rate).model,
            LorisReference.constant(220.0)).model.dropUnlabeled()
        val b = client.distill(client.analyze(tone({ 330.0 }, listOf(1 to .25, 3 to .2, 5 to .15)), rate).model,
            LorisReference.constant(330.0)).model.dropUnlabeled()
        for ((weight, expected) in listOf(0.0 to a, 1.0 to b)) {
            val m = client.morph(a, b, LorisEnvelope.constant(weight)).model
            for (k in listOf(1, 3, 5)) {
                val want = expected.labelled(k).at(.5); val got = m.labelled(k).at(.5)
                assertEquals(want.frequencyHz, got.frequencyHz, want.frequencyHz * 1e-3, "weight $weight label $k frequency")
                assertEquals(want.amplitude, got.amplitude, want.amplitude * .02, "weight $weight label $k amplitude")
            }
            // Harmonic 2 exists only in the 220 Hz source: present at weight 0, silent at weight 1.
            val two = m.partials.filter { it.label == 2 }.flatMap { it.points }.filter { abs(it.timeSeconds - .5) < .05 }
            if (weight == 0.0) assertEquals(a.labelled(2).at(.5).amplitude, two.maxOf { it.amplitude }, .01)
            else assertTrue(two.all { it.amplitude < 1e-6 }, "label 2 at weight 1: ${two.maxOfOrNull { it.amplitude }}")
        }
        val halfway = client.morph(a, b, LorisEnvelope.constant(.5)).model.labelled(1).at(.5).frequencyHz
        assertTrue(halfway > 225 && halfway < 325, "frequency morph midpoint $halfway")
        val glide = client.morph(a, b, LorisEnvelope.ramp(.2, .8))
        assertEquals(rate, glide.samples.size)
        assertTrue(glide.model.labelled(1).at(.15).frequencyHz < 225 && glide.model.labelled(1).at(.85).frequencyHz > 325)
    }

    @Test fun dilatePutsMarkersWhereAsked() = withClient { client ->
        val rate = 48000
        // A step from 220 Hz to 330 Hz at 0.5 s gives the analysis an event to move.
        val analyzed = client.analyze(tone({ if (it < .5) 220.0 else 330.0 }, listOf(1 to .3)), rate).model
        fun onset(m: LorisModel) = m.partials.filter { abs(it.meanFrequencyHz - 330) < 10 && it.durationSeconds > .2 }
            .minOf { it.startSeconds }
        val before = onset(analyzed)
        assertEquals(.5, before, .03)
        val result = client.dilate(analyzed, listOf(before), listOf(.8))
        assertEquals(.8, onset(result.model), 1e-6)
        assertEquals(.8 + (1.0 - before), result.model.durationSeconds, 1e-4, "default timeline keeps the tail after the last marker")
        assertEquals(result.model.frameCount, result.samples.size)
        val twoMarkers = client.dilate(analyzed, listOf(before, .9), listOf(.25, 1.6)).model
        assertEquals(.25, onset(twoMarkers), 1e-6)
        assertTrue(twoMarkers.partials.maxOf { it.endSeconds } > 1.5)
    }
}
