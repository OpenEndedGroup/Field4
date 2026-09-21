@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.pat-spatial.smoke
runner: jvm-main
entrypoint: afield.four.synth.PatSpatialSmokeTest
tags: [smoke, audio, synth, steamaudio, cpu]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "PAT_SPATIAL_SMOKE_PASSED"
*/

/**
 * The audio check on `patSpatial`, run without an audio device: patterns that differ only in a
 * spatial tag, and the samples that came out of each.
 *
 * This file and [HeadTrackedSpatialSmokeTest] both pull [PatSpatialBank] themselves and compare
 * captures against each other rather than against remembered levels, which is what lets them run on
 * a machine with no sound card. [PatSpatialEngineSmoke] covers the half they cannot reach, and
 * [PatSpatialBudgetProbe] measures what a block costs.
 *
 * Of the six spatial tags `patSpatial` reads, `az`, `dist`, `occl` and `spat` each get a case here;
 * `elev` and `air` get none. The remaining cases cover polyphony inside one source, `turnListener`,
 * and whether the ring absorbed the render thread's worst block.
 *
 * Run it with `tools/run_field_tests.py --id synth.pat-spatial.smoke`.
 */

/**
 * Proves the whole route -- pattern language, Dough, Steam Audio HRTF -- on real audio, in realtime.
 *
 * Every assertion here is about the samples that came out of [PatSpatialLaunch], not about whether
 * the thing compiled and ran. The claim worth testing is the one in [patSpatial]'s documentation:
 * that a *tag in the pattern* becomes a spatial parameter. So the test writes patterns that differ
 * only in their `az` or `dist` annotation and requires the audio to differ in the matching way.
 *
 * Each case boots a fresh room and closes it. The captures are not paced by the wall clock: the
 * loop drains the ring as fast as blocks appear, so the chain free-runs, and nothing here asserts
 * a rate.
 */
object PatSpatialSmokeTest {

    private const val SR = 44_100
    private const val BLOCK = 512

    /** Energy in the left and right channels, plus what the room did to produce them. */
    private class Capture(
        val rmsL: Double,
        val rmsR: Double,
        val peak: Double,
        val sources: Int,
        val peakVoices: List<Int>,
        val notes: Long,
        val underruns: Long,
        val minFill: Int,
        val worstBlockMs: Double,
        val lateSourceBoots: Int,
        val ringCapacityFrames: Int,
    ) {
        val ratio: Double get() = rmsL / rmsR.coerceAtLeast(1e-9)
        val rms: Double get() = sqrt((rmsL * rmsL + rmsR * rmsR) / 2.0)
    }

    /**
     * Play [pattern] for [seconds] and measure what came out.
     *
     * [skipSeconds] is discarded first: a source starts at the listener's centre and a placement is
     * approached over a few milliseconds rather than jumped to, so the very start of a capture is
     * deliberately not yet where the tag asked for.
     */
    private fun capture(
        pattern: PatPattern<String>,
        cps: Double = 2.0,
        seconds: Double = 1.2,
        skipSeconds: Double = 0.35,
        maxSources: Int = 4,
    ): Capture {
        val launch = patSpatial(pattern, cps = cps, sampleRate = SR, maxSources = maxSources)
        try {
            check(launch.awaitReady()) { "spatial room never became ready" }
            val ctx = SynthContext(SR.toFloat(), BLOCK)
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            var cycleId = 0L
            var sumL = 0.0
            var sumR = 0.0
            var peak = 0.0
            var counted = 0
            val skipFrames = (skipSeconds * SR).toInt()
            val totalFrames = ((seconds + skipSeconds) * SR).toInt()
            var at = 0
            while (at < totalFrames) {
                // The render thread produces in realtime, so wait for a block to exist rather than
                // spinning on an empty ring and counting it as an underrun.
                while (launch.room.ring.fill < BLOCK) {
                    launch.room.failure?.let { throw IllegalStateException("render thread failed", it) }
                    Thread.sleep(1L)
                }
                java.util.Arrays.fill(l, 0f)
                java.util.Arrays.fill(r, 0f)
                ctx.cycleId = ++cycleId
                ctx.time = at.toFloat() / SR
                launch.bank.render(l, r, BLOCK, ctx)
                if (at >= skipFrames) {
                    for (i in 0 until BLOCK) {
                        sumL += l[i].toDouble() * l[i]
                        sumR += r[i].toDouble() * r[i]
                        val a = Math.abs(l[i].toDouble())
                        val b = Math.abs(r[i].toDouble())
                        if (a > peak) peak = a
                        if (b > peak) peak = b
                        counted++
                    }
                }
                at += BLOCK
            }
            val sources = launch.sources
            return Capture(
                rmsL = sqrt(sumL / counted),
                rmsR = sqrt(sumR / counted),
                peak = peak,
                sources = sources.size,
                peakVoices = sources.map { it.peakVoices },
                notes = sources.sumOf { it.notesPlayed },
                underruns = launch.room.ring.underruns,
                minFill = launch.room.ring.minFill,
                worstBlockMs = launch.room.worstBlockMs,
                lateSourceBoots = launch.room.lateSourceBoots,
                ringCapacityFrames = launch.room.ring.capacityFrames,
            )
        } finally {
            launch.close()
        }
    }

    private fun report(label: String, c: Capture) {
        println(
            "  %-26s rmsL %.5f  rmsR %.5f  L/R %6.2f  peak %.4f  src %d  notes %d  underruns %d"
                .format(label, c.rmsL, c.rmsR, c.ratio, c.peak, c.sources, c.notes, c.underruns),
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("pat -> Dough -> Steam Audio, realtime, $SR Hz")
        println()

        // ── 1. a tag puts the sound on one side, and the other tag puts it on the other ──────────
        // The two patterns are identical apart from the number in `az`. Nothing else in the chain
        // knows what `az` is until patSpatialEventsForArc reads it.
        val notes = pat("60 64 67 71")
        val left = capture(notes.with(param("az", -90)))
        report("az -90 (hard left)", left)
        val right = capture(notes.with(param("az", 90)))
        report("az +90 (hard right)", right)
        val ahead = capture(notes.with(param("az", 0)))
        report("az 0 (ahead)", ahead)

        check(left.rms > 1e-4) { "hard-left capture was silent: rms ${left.rms}" }
        check(left.ratio > 1.6) { "az -90 did not favour the left ear: L/R ${left.ratio}" }
        check(right.ratio < 1.0 / 1.6) { "az +90 did not favour the right ear: L/R ${right.ratio}" }
        check(ahead.ratio in 0.8..1.25) { "az 0 should be near-symmetric, got L/R ${ahead.ratio}" }
        // The asymmetry has to be a property of the tag, not of the synth: same notes, same engine.
        check(left.ratio / right.ratio > 3.0) {
            "az did not swap the ears: ${left.ratio} vs ${right.ratio}"
        }

        // ── 2. `dist` attenuates, through Steam Audio's own inverse-distance curve ───────────────
        val near = capture(notes.with(param("az", 30), param("dist", 1)))
        report("dist 1 m", near)
        val far = capture(notes.with(param("az", 30), param("dist", 12)))
        report("dist 12 m", far)
        check(near.rms > far.rms * 2.0) {
            "distance did not attenuate: near rms ${near.rms} vs far rms ${far.rms}"
        }

        // ── 3. `occl` blocks the direct path ────────────────────────────────────────────────────
        val clear = capture(notes.with(param("az", 30), param("occl", 0)))
        val blocked = capture(notes.with(param("az", 30), param("occl", 0.9)))
        report("occl 0", clear)
        report("occl 0.9", blocked)
        check(clear.rms > blocked.rms * 2.0) {
            "occlusion did not attenuate: ${clear.rms} vs ${blocked.rms}"
        }

        // ── 4. `spat` really splits one pattern across engines, placed independently ─────────────
        // A polyphonic pattern whose two voices are tagged onto two sources at opposite azimuths.
        val split = capture(
            pat("60 64 67 71").with(paramOf("spat", pat("a b a b")))
                .with(paramOf("az", pat("-80 80 -80 80"))),
            seconds = 1.4,
        )
        report("spat a|b, az -80|+80", split)
        check(split.sources == 2) { "two spat tags should make two sources, got ${split.sources}" }
        check(split.notes >= 4) { "expected at least a bar of notes, got ${split.notes}" }
        check(split.rms > 1e-4) { "split capture was silent" }

        // ── 5. polyphony inside one source: a chord, on one engine ──────────────────────────────
        val chord = capture(pat("[60,64,67,71]").with(param("az", 20)), cps = 0.8, seconds = 1.0)
        report("chord [60,64,67,71]", chord)
        check(chord.sources == 1) { "a chord is one source, got ${chord.sources}" }
        val chordVoices = chord.peakVoices.firstOrNull() ?: -1
        check(chordVoices >= 3) {
            "a four-note chord should light at least three Dough voices, got $chordVoices"
        }

        // ── 6. turning the listener's head swaps the ears, with the pattern unchanged ────────────
        val head = patSpatial(notes.with(param("az", -90)), cps = 2.0, sampleRate = SR)
        try {
            check(head.awaitReady()) { "listener-turn launch never became ready" }
            head.turnListener(180.0)
            val ctx = SynthContext(SR.toFloat(), BLOCK)
            val l = FloatArray(BLOCK)
            val r = FloatArray(BLOCK)
            var cycleId = 0L
            var sumL = 0.0
            var sumR = 0.0
            var counted = 0
            var at = 0
            val skip = (0.5 * SR).toInt()
            while (at < (1.4 * SR).toInt()) {
                while (head.room.ring.fill < BLOCK) Thread.sleep(1L)
                java.util.Arrays.fill(l, 0f)
                java.util.Arrays.fill(r, 0f)
                ctx.cycleId = ++cycleId
                head.bank.render(l, r, BLOCK, ctx)
                if (at >= skip) {
                    for (i in 0 until BLOCK) {
                        sumL += l[i].toDouble() * l[i]
                        sumR += r[i].toDouble() * r[i]
                        counted++
                    }
                }
                at += BLOCK
            }
            val turnedRatio = sqrt(sumL / counted) / sqrt(sumR / counted).coerceAtLeast(1e-9)
            println("  %-26s L/R %6.2f (was %.2f facing forward)".format("head turned 180", turnedRatio, left.ratio))
            check(turnedRatio < 1.0) {
                "turning the head 180 degrees should move a left-hand source to the right ear, " +
                    "got L/R $turnedRatio"
            }
        } finally {
            head.close()
        }

        // ── 7. the decoupling did its job ───────────────────────────────────────────────────────
        println()
        println("  worst render block %.2f ms; audio-thread underruns %d; min ring fill %d frames; late boots %d"
            .format(split.worstBlockMs, split.underruns, split.minFill, split.lateSourceBoots))
        check(split.underruns == 0L) {
            "the ring underran ${split.underruns} frames -- the render thread fell behind"
        }
        // Both `spat` tags are visible in the pattern's first cycle, so both engines must have been
        // booted before any audio was due. A late boot here would mean a dropout on a real device:
        // booting Dough takes longer than the ring holds.
        check(split.lateSourceBoots == 0) {
            "a Dough engine booted inside the render loop (${split.lateSourceBoots} times); " +
                "the prescan should have caught both spat tags"
        }
        // With the boots hoisted out, the worst block has to be something the ring can absorb.
        val ringMs = split.ringCapacityFrames * 1000.0 / SR
        check(split.worstBlockMs < ringMs) {
            "worst render block %.1f ms exceeds the ring's %.1f ms of headroom"
                .format(split.worstBlockMs, ringMs)
        }

        println()
        println("PAT_SPATIAL_SMOKE_PASSED")
    }
}
