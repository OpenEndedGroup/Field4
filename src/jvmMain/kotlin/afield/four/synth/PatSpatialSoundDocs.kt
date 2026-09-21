@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import java.io.File
import java.nio.file.Paths
import kotlin.math.abs

/*
FIELD_TEST
id: synth.pat-spatial.sound
runner: jvm-main
entrypoint: afield.four.synth.PatSpatialSoundDocs
tags: [smoke, synth, pattern, audio, wav, docs, wasm, dough, cpu]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "pat spatial sound docs rendered"
*/

/**
 * The published clips on `pat_spatial.md`: the **mono source**, which is as far as a figure reaches.
 *
 * [patSpatial]'s output cannot be published, and the reason is structural. Three separate things
 * have to be true for a clip to appear behind an `audio=` fence, and this path fails two of them:
 *
 *  * **The docs runner renders offline**, through `renderWav(name, path)` over the backdoor. There
 *    is no offline half of [patSpatial] to call: [PatSpatialLaunch.advanceOffline] consumes what
 *    [PatSpatialRoom]'s render thread has already produced, in real time, so asking it for four
 *    seconds of audio takes four seconds and no flag makes it faster.
 *  * **[DocsAudioProvenance] re-renders every fence on every build** and fails when a render
 *    fails. The HRTF needs Steam Audio's `libphonon`, which is *not committed*;
 *    `./gradlew fetchSteamAudioNatives` downloads it. So on a clean clone, on CI, and in `check`
 *    that render does not merely differ, it cannot start.
 *
 * So what is published here is the half of the chain that *is* offline and *is* committed: the
 * pattern through one [DoughEngine], taken as mono. That is not a stand-in for the spatialised
 * output and is not labelled as one. It is the exact buffer [PatSpatialRoom] copies into
 * `source.mono` and hands to [afield.four.audio.steamaudio.BinauralEffect]: the input to the part
 * that has no figure.
 *
 * ## What the two clips are for
 *
 * A single clip of a synth voice makes no claim. These two are the same notation, the same engine
 * and the same gain, differing only in a **tag that is not a spatial tag**: the second adds `lpf`,
 * which [patSpatialEventsForArc] forwards to Dough untouched. So everything audible between them
 * is the forwarding rule, which is the reason to reach for this path over [patSynth], whose voice
 * has no `lpf` to forward to.
 *
 * ## The claims the audit asserts, which the clips cannot show on their own
 *
 * Both published clips are peak-normalised, exactly as every other figure in these docs is, so
 * neither of them can carry a claim about *level*. Three claims about this path are therefore
 * measured on the pre-normalisation buffers and printed rather than left to the reader's ears:
 *
 *  1. **`pan/0` is a lossless mono tap, not a downmix.** Every event leaves
 *     [patSpatialEventsForArc] carrying `pan/0`, which puts all of an engine's energy in its left
 *     channel. The smoke test requires the right channel to peak below [RIGHT_CHANNEL_CEILING],
 *     which is what lets [PatSpatialRoom] `arraycopy` the left channel rather than compute
 *     `0.5*(L+R)`, and why the source signal has full amplitude rather than half of it.
 *  2. **The six spatial tags never reach Dough.** Asserted on the event maps, not on the audio:
 *     `spat`, `az`, `elev`, `dist`, `occl` and `air` are consumed by this path and `lpf` is not.
 *  3. **`az` is not in the signal.** The same pattern rendered with `az = 0` and with
 *     `az = -70` produces a **bit-identical** mono source. That is the cleanest statement of why
 *     there is no figure of the spatialisation: the direction lives entirely in the effect, and the
 *     effect is the part that needs the uncommitted binary.
 *
 * Claim 3 does a second job, and it is the one that makes these two clips publishable at all: it is
 * a **determinism check on [DoughEngine]**. Two engines, separately booted and separately
 * fed, agreeing to the bit is exactly what [DocsAudioProvenance] requires of a producer, and
 * GraalWasm is not obviously the kind of thing that delivers it. If dough ever acquires an unseeded
 * voice, claim 3 fails here — with a message about placement leaking into the signal — rather than
 * showing up as an unexplained correlation drop in the corpus audit.
 */

/**
 * Renders the two `pat_spatial.md` source clips, asserts the three claims about them and writes the
 * WAVs; `main` is the `synth.pat-spatial.sound` entrypoint.
 */
object PatSpatialSoundDocs {

    /** Matching [patSpatial]'s own default, and [FieldAudioEngine.shared]'s rate on this machine. */
    const val SR = 48_000

    /** Cycles per second. Two, the rate `sound_paths.md`'s comparison beat uses, so they line up. */
    const val CPS = 2.0

    /** How many cycles. Four at [CPS] is two seconds of pattern. */
    const val CYCLES = 4

    /** Per-event gain, on top of each event's own `velocity`. */
    const val GAIN = 0.9

    /** Seconds rendered past the last event, so the final note's release is in the clip. */
    const val TAIL_SECONDS = 0.6

    /** The page these are published on. Its prose is checked against [FIGURES], not trusted. */
    const val PAGE = "public/docs/pat_spatial.md"

    /** The wasm module, tracked in this repository — which is the whole reason this half renders. */
    val WASM = Paths.get("./src/jvmMain/resources/dough.wasm")

    /**
     * The tags [patSpatial] consumes rather than forwards.
     *
     * A copy of `PatSpatial.kt`'s own `SPATIAL_TAGS`, which is `private` to that file. Copied rather
     * than opened up, because what this file needs is not that set but the *page's* table of six
     * keys — and if the two ever disagree, [audit]'s forwarding check is what says so.
     */
    val SPATIAL_TAGS = listOf("spat", "az", "elev", "dist", "occl", "air")

    /** One published clip. */
    class Figure(
        val name: String,
        /** The caption sentence [PAGE] must carry verbatim beside the fence. */
        val pageLabel: String,
        /** Extra, non-spatial annotations, which this path forwards to Dough untouched. */
        val forwarded: Map<String, String>,
    )

    /** The two figures, in the order the page presents them. */
    val FIGURES: List<Figure> = listOf(
        Figure(
            name = "spatial-source-plain",
            pageLabel = "**The mono source, as Steam Audio is handed it.** Not the spatialised " +
                "output — this is `source.mono`, one `spat` group's dough engine with every event " +
                "sent `pan/0`, which is the buffer the binaural effect takes as its input.",
            forwarded = emptyMap(),
        ),
        Figure(
            name = "spatial-source-forwarded",
            pageLabel = "**The same notes, with `lpf` added to the pattern.** `lpf` is not one of " +
                "the six spatial tags, so it is forwarded to dough untouched and the engine's own " +
                "filter answers for it. Everything you can hear between these two clips is that " +
                "forwarding rule.",
            forwarded = mapOf("lpf" to "700"),
        ),
    )

    private val byName = FIGURES.associateBy { it.name }

    /** Looks a figure up by name, with a message that lists the alternatives. */
    fun figure(name: String): Figure =
        byName[name] ?: error("unknown PatSpatialSoundDocs figure '$name'; known: ${byName.keys.sorted()}")

    /**
     * The notation the figures play: a low line, and a faster one two octaves up.
     *
     * **Not `bd ~ sd ~, hh*8`**, which is [SoundPathMap.dryBeat]'s notation and would have let a
     * reader hold these clips against the three voices `sound_paths.md` compares on one buffer.
     * That was tried, and the measurement is why it is not what ships: dough's own drum voices are
     * *already dark*. The drum spelling renders at a **678 Hz** spectral centroid, so the second
     * figure's forwarded `lpf` at 700 Hz takes it to 419 Hz — a 0.62x change with the RMS
     * essentially unmoved, 0.0707 to 0.0704. There is nothing there to hear, and a comparison figure
     * whose two halves sound the same is worse than no comparison.
     *
     * A saw at note 72 measures **4677 Hz** instead, and the same `lpf` takes it to 544 Hz: 0.12x,
     * peak 0.284 to 0.198, RMS 0.078 to 0.059. That is a difference a reader can hear, which is the
     * whole job of the pair. [PROBE] keeps the drum spelling in the output as a number, so the
     * choice stays a measurement rather than becoming a preference somebody has to trust.
     *
     * (The first version of this comment claimed dough *could not* play `bd`/`sd`/`hh` at all,
     * because they are sample names in Strudel's vocabulary and this build loads no samples. It
     * synthesises them. [PROBE] is what caught that, and is why it is still rendered.)
     *
     * `az` and `dist` are here deliberately even though no clip can show them: they are what makes
     * this the *spatial* path's event stream rather than a plain dough one, and [audit]'s third
     * claim is that their presence changes nothing about the buffer below.
     */
    fun beat(azimuthDeg: Double = -70.0, extra: Map<String, String> = emptyMap()): PatPattern<String> {
        // param(key, Number) goes through `pure`, not through the mini-notation parser, which is
        // what a negative azimuth needs: `pat("-70")` has a leading `-` to read and this does not.
        var p = (pat(NOTATION) with gain(GAIN))
            .with(param("az", azimuthDeg))
            .with(param("dist", 1.5))
            // Consumed by patSpatialEventsForArc as a multiplier on each event's `duration`, and so
            // not forwarded either — without it a `72*4` event is 62 ms and the figure is a clicking
            // noise rather than a line.
            .with(param("sustain", SUSTAIN))
        for ((k, v) in extra) p = p.with(param(k, v))
        return p
    }

    /** The mini-notation both figures play. See [beat] for the measurement that chose it. */
    const val NOTATION = "36 ~ 48 ~, 72*4"

    /** Multiplier on each event's own span, so a note is longer than its slot. */
    const val SUSTAIN = 2.5

    /**
     * The drum spelling of the same rhythm, kept as a measurement rather than as a figure.
     *
     * [patSpatialEventsForArc] reads a numeric atom as a `note` on the default `saw` and a word atom
     * as a *sound name*, so these two spellings reach dough as entirely different events. [audit]
     * renders this one and prints its peak and centroid: the line that records both why [NOTATION]
     * is notes, and that dough synthesises drums perfectly well.
     */
    const val PROBE = "bd ~ sd ~, hh*8"

    /** What one offline render measured, alongside the samples it produced. */
    class Source(
        /** The left channel: the mono source signal, un-normalised. */
        val mono: FloatArray,
        /** The right channel Dough produced. Silent, under `pan/0`, and that is claim 1. */
        val right: FloatArray,
        /** The Dough events the pattern resolved to, in start order. */
        val events: List<PatSpatialEvent>,
    ) {
        val peak: Double get() = mono.maxOfOrNull { abs(it).toDouble() } ?: 0.0
        val rms: Double get() = Math.sqrt(mono.sumOf { it.toDouble() * it } / mono.size.coerceAtLeast(1))
        val rightPeak: Double get() = right.maxOfOrNull { abs(it).toDouble() } ?: 0.0
        val brightnessHz: Double get() = SoundPathMap.brightnessHz(mono, SR)
        val seconds: Double get() = mono.size.toDouble() / SR
    }

    /**
     * Renders one `spat` group's mono source offline — no device, no thread, no Steam Audio.
     *
     * Deliberately **not** a call into [PatSpatialRoom]: that class owns a thread and a ring by
     * design, and driving it would make this render take as long as the audio it produces. What is
     * shared with the real path is the part that decides the sound — [patSpatialEventsForArc], which
     * builds the very Dough events the render thread hands to the very same [DoughEngine.evaluate].
     * The block loop here is the same shape as [PatSpatialBudgetProbe]'s, minus the effects.
     */
    fun renderSource(pattern: PatPattern<String>): Source {
        val events = patSpatialEventsForArc(
            pattern = pattern,
            arc = PatArc(PatQ.of(0L), PatQ.of(CYCLES.toLong())),
            cps = CPS,
            sampleRate = SR,
            gain = 1.0,
        )
        val keys = events.map { it.sourceKey }.distinct()
        require(keys.size == 1) {
            "the published figures are one source on purpose, so that the clip is one engine's " +
                "mono output; this pattern names ${keys.size} spat group(s): $keys"
        }

        val blocks = ((CYCLES / CPS + TAIL_SECONDS) * SR / DoughEngine.BLOCK_SIZE).toInt() + 1
        val mono = FloatArray(blocks * DoughEngine.BLOCK_SIZE)
        val right = FloatArray(blocks * DoughEngine.BLOCK_SIZE)
        val blockL = FloatArray(DoughEngine.BLOCK_SIZE)
        val blockR = FloatArray(DoughEngine.BLOCK_SIZE)

        val engine = DoughEngine(wasmPath = WASM, sampleRate = SR)
        try {
            var next = 0
            for (b in 0 until blocks) {
                val blockEnd = (b + 1).toLong() * DoughEngine.BLOCK_SIZE
                // Events due in this block, applied before it is rendered. Dough takes an event
                // through evaluate() and applies it at the next dsp(), so an onset quantises to a
                // block here exactly as it does on the render thread — 2.67 ms at this rate.
                while (next < events.size && events[next].startFrame < blockEnd) {
                    engine.evaluate(events[next].doughEvent)
                    next++
                }
                engine.renderBlock(blockL, blockR)
                System.arraycopy(blockL, 0, mono, b * DoughEngine.BLOCK_SIZE, DoughEngine.BLOCK_SIZE)
                System.arraycopy(blockR, 0, right, b * DoughEngine.BLOCK_SIZE, DoughEngine.BLOCK_SIZE)
            }
            check(next == events.size) {
                "$next of ${events.size} events were applied; the render is shorter than the pattern"
            }
        } finally {
            engine.close()
        }
        return Source(mono, right, events)
    }

    /** Renders figure [name]'s mono source. */
    fun render(name: String): Source = renderSource(beat(extra = figure(name).forwarded))

    /**
     * Offline-renders figure [name] to a mono 16-bit WAV at [outPath].
     *
     * The `renderWav(name, path)` contract the docs runner's `audio="…"` fence drives over the
     * backdoor, identical to [FaustPolySoundDocs.renderWav] and [SoundPathMap.renderWav], so
     * [DocsAudioProvenance] re-derives these two clips exactly like the other forty-nine. Mono
     * because the thing being published *is* a mono signal: a stereo file would be a silent right
     * channel and twice the bytes.
     */
    fun renderWav(name: String, outPath: String) {
        val source = render(name)
        require(source.mono.isNotEmpty()) { "figure '$name' rendered no samples" }
        require(source.mono.any { it != 0f }) { "figure '$name' rendered silence" }
        WavFloatArrayWriter.writeMono16(outPath, source.mono, SR, normalizePeak = 0.9f)
    }

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    /**
     * Renders both figures, asserts the three claims, and writes them.
     *
     * Writes NO committed assets; pass an output dir as `args[0]` (or `PAT_SPATIAL_SOUND_DIR`) to
     * regenerate the published ones.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("PAT_SPATIAL_SOUND_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "pat-spatial-sound").path
        )
        outDir.mkdirs()
        audit(outDir)
        println("pat spatial sound docs rendered: ${FIGURES.size} figure(s)")
    }

    private fun audit(outDir: File) {
        val rendered = FIGURES.associate { it.name to render(it.name) }

        // Every measurement first, then every assertion. A check whose failure message carries only
        // the one number it tripped on makes you run the whole thing again to see the rest, and one
        // run of this is four Dough boots.
        println("  notation `$NOTATION`, $CYCLES cycles at $CPS cps, sustain $SUSTAIN, gain $GAIN")
        rendered.forEach { (name, source) ->
            println(
                "  %-26s peak=%.4f rms=%.5f rightPeak=%.3e centroid=%6.0f Hz %.2fs".format(
                    name, source.peak, source.rms, source.rightPeak, source.brightnessHz,
                    source.seconds,
                )
            )
        }

        // The drum spelling, printed and never asserted. Two things live on this one line: that
        // dough synthesises `bd`/`sd`/`hh` itself, and that those voices are dark enough that a
        // forwarded `lpf` barely moves them — which is why the figures are notes. See [beat].
        val drums = runCatching { renderSource(pat(PROBE) with gain(GAIN)) }
        drums.fold(
            onSuccess = {
                println(
                    "  drum-spelling probe `$PROBE`: peak=%.4f rms=%.5f centroid=%.0f Hz".format(
                        it.peak, it.rms, it.brightnessHz,
                    )
                )
            },
            onFailure = { println("  drum-spelling probe `$PROBE`: did not render — ${it.message}") },
        )
        println()

        // ── claim 2: the six spatial tags are consumed, and a seventh key is not ──────────
        val plain = rendered.getValue("spatial-source-plain")
        val tagsThatLeaked = SPATIAL_TAGS.filter { tag -> plain.events.any { tag in it.doughEvent } }
        check(tagsThatLeaked.isEmpty()) {
            "$tagsThatLeaked reached a Dough event. The six tags on $PAGE's table are this path's " +
                "own parameters; a tag that is forwarded instead is a tag Dough will try to " +
                "interpret, and the page's table is then wrong."
        }
        val forwarded = rendered.getValue("spatial-source-forwarded")
        check(forwarded.events.all { it.doughEvent["lpf"] == "700" }) {
            "`lpf` did not reach Dough untouched, so the forwarding rule the second figure is " +
                "about no longer holds: ${forwarded.events.firstOrNull()?.doughEvent}"
        }
        check(plain.events.all { it.doughEvent["pan"] == "0" }) {
            "an event left patSpatialEventsForArc without `pan/0`, so the left channel is no " +
                "longer the whole signal and claim 1 below is measuring a downmix"
        }
        println("  the six spatial tags ${SPATIAL_TAGS} are consumed; `lpf` is forwarded as 700")
        println("  ${plain.events.size} events over $CYCLES cycles at $CPS cps, every one carrying pan/0")
        println()

        // ── claim 3: az is not in the signal ─────────────────────────────────────────────
        // `plain` is already beat(azimuthDeg = -70.0), so only the az = 0 render is new: booting a
        // DoughEngine costs a few hundred milliseconds and this claim needs two renders, not four.
        val ahead = renderSource(beat(azimuthDeg = 0.0))
        val hardLeft = plain
        val differingSamples = ahead.mono.indices.count { ahead.mono[it] != hardLeft.mono[it] }
        check(differingSamples == 0) {
            "the mono source differs in $differingSamples sample(s) between az=0 and az=-70. " +
                "Something about placement has leaked into the source signal, and the claim that " +
                "the direction lives entirely in the Steam Audio effect is no longer true."
        }
        println(
            "  az=0 and az=-70 render a bit-identical mono source: " +
                "${ahead.mono.size} samples, 0 differ"
        )
        println("  so no offline figure can show this path's placement — only the effect applies it")
        println()

        // ── claim 1, and the figures themselves ──────────────────────────────────────────
        FIGURES.forEach { f ->
            val source = rendered.getValue(f.name)
            check(source.mono.all { it.isFinite() }) { "figure '${f.name}': non-finite samples" }
            check(source.peak > 0.02) { "figure '${f.name}': effectively silent (peak ${source.peak})" }
            check(source.peak <= 4.0) { "figure '${f.name}': runaway level (peak ${source.peak})" }

            // pan/0 is a tap, not a downmix: nothing is in the right channel to lose.
            check(source.rightPeak < RIGHT_CHANNEL_CEILING) {
                "figure '${f.name}': the right channel peaks at ${source.rightPeak}, above the " +
                    "$RIGHT_CHANNEL_CEILING ceiling. pan/0 is supposed to put the whole signal in " +
                    "the left channel, which is what lets PatSpatialRoom.spatialiseSource " +
                    "arraycopy it instead of summing — if energy is arriving on the right, the " +
                    "source signal is being silently halved."
            }

            val file = File(outDir, "${f.name}.wav")
            WavFloatArrayWriter.writeMono16(file, source.mono, SR, normalizePeak = 0.9f)
            val bytes = file.readBytes()
            check(bytes.size == 44 + source.mono.size * 2) {
                "figure '${f.name}': WAV data size mismatch: ${bytes.size} vs ${44 + source.mono.size * 2}"
            }
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "figure '${f.name}': missing RIFF header" }
            println("      wrote ${file.absolutePath} (${bytes.size} bytes)")
        }
        println()

        // The comparison the second figure exists to make, as a number.
        val dark = forwarded.brightnessHz
        val bright = plain.brightnessHz
        check(dark < bright * FORWARDING_CENTROID_CEILING) {
            ("the forwarded `lpf` took the centroid from %.0f Hz to %.0f Hz, which is not the " +
                "%.2fx the page claims. Either Dough's filter stopped answering to `lpf`, or the " +
                "tag is no longer reaching it.").format(bright, dark, FORWARDING_CENTROID_CEILING)
        }
        println(
            "  `lpf` forwarded: centroid %.0f Hz -> %.0f Hz (%.2fx)".format(bright, dark, dark / bright)
        )
        println()

        // And the page has to carry what the figures claim, so the prose cannot drift off the audio.
        val page = File(PAGE)
        if (page.isFile) {
            val text = page.readText()
            val missing = FIGURES.filterNot { text.contains(it.pageLabel) }
            check(missing.isEmpty()) {
                "$PAGE does not carry the caption(s) ${missing.map { it.name }} verbatim, so the " +
                    "prose and the published clip can drift apart"
            }
            FIGURES.forEach { f ->
                check(text.contains("audio-spec=\"${f.name}\"")) {
                    "$PAGE has no audio fence for figure '${f.name}'"
                }
            }
            println("  $PAGE carries both fences and both captions verbatim")
        } else {
            println("  $PAGE does not exist yet, so its captions were not checked")
        }
        println()
    }

    /**
     * How much of the signal may appear in the right channel before `pan/0` is not a tap.
     *
     * **Measured, it is exactly `0.000e+00`** — both figures, every sample. So `pan/0` is not a gain
     * law that happens to land very near zero; dough's right channel receives nothing at all, and
     * the `arraycopy` in [PatSpatialRoom.spatialiseSource] is lossless in the strict sense rather
     * than the approximate one.
     *
     * The ceiling is nevertheless a small number rather than `0.0`, and that is a deliberate choice
     * about what this guard is for. Pinning exact zero would fail on a dough build that computed
     * the same intent through a `cos`/`sin` pan law — a change with no audible consequence — while
     * `1e-4` still fails a genuine half-and-half downmix by nearly four orders of magnitude, which
     * is the failure worth catching. `rightPeak` is printed on every run, so the exact figure stays
     * visible instead of being hidden behind the tolerance.
     */
    const val RIGHT_CHANNEL_CEILING = 1e-4

    /** How far below the plain figure's centroid the `lpf` figure's has to sit to be the filter. */
    const val FORWARDING_CENTROID_CEILING = 0.6
}
