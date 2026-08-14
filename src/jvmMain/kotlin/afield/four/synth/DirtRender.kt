@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.Box
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.math.sqrt

/**
 * Offline rendering of a pattern through the **real** Dirt sample library.
 *
 * [patRenderStereo] renders through [patVoice] — a sine for `bd` and filtered white noise for
 * `sd` and `hh`. That is a deliberate stand-in: it needs no assets, so it works in CI and on a
 * clean checkout. It also sounds like bursts of white noise, because it *is* bursts of white
 * noise.
 *
 * This used to read "and every audio figure in the documentation uses it". [DocsAudioProvenance]
 * swept the published corpus and measured each clip, and it is not true: of the twelve, four are
 * [patVoice] and **six** come from [DoughDspSoundDocs], which never reaches this voice at all.
 * `public/docs/sound.md` has the breakdown and `DocsAudioProvenanceGuardTest` pins the counts.
 *
 * This file is the other half. [DirtSamples] could already play the genuine Tidal sample set, but
 * only *live* — [playDirt] drives a polyphonic player through `respondAhead`, a clock and an audio
 * device, none of which exist inside a docs build or a headless box run. Here the same library is
 * mixed straight into a buffer, so a transcript, a docs capture or a `println`-and-listen box can
 * sound like actual drums without an audio device anywhere.
 *
 * Two fallbacks, at different granularities, and the distinction matters:
 *
 *  * **No library on this machine** — the whole render falls back to [patRenderStereo]. Nothing
 *    breaks, the figures still generate, and [DirtRenderResult.usedSamples] says `false` so a
 *    caller can be honest about which one you are hearing.
 *  * **Library present, token missing from it** — only *that event* falls back to the synth voice.
 *    A pattern of note numbers, or one naming a drum Dirt has never heard of, must not go silent
 *    just because the library loaded.
 */
object DirtRender {

    /** Where the sample set lives, if it lives anywhere. Probed once; see [dirtSampleRoot]. */
    const val ENV_VAR = "FIELD_DIRT_SAMPLES"

    /**
     * The standard install locations, in probe order.
     *
     * Dirt-Samples ships as a SuperCollider *quark*, so anyone who has run Tidal already has it at
     * one of these — which is the whole reason to auto-discover rather than ask. It is ~400MB of
     * third-party audio with mixed per-folder licensing, so it is emphatically not vendored here.
     */
    private fun candidateRoots(): List<Path> = buildList {
        System.getenv(ENV_VAR)?.takeIf { it.isNotBlank() }?.let { add(Paths.get(it)) }
        val home = System.getProperty("user.home") ?: return@buildList
        add(Paths.get(home, "Library", "Application Support", "SuperCollider", "downloaded-quarks", "Dirt-Samples"))
        add(Paths.get(home, ".local", "share", "SuperCollider", "downloaded-quarks", "Dirt-Samples"))
        add(Paths.get(home, "Dirt-Samples"))
    }

    /**
     * The Dirt sample root on this machine, or null.
     *
     * Not cached: the env var is the documented override, and a caller that sets it and then
     * renders in the same session should get what they asked for.
     */
    fun dirtSampleRoot(): Path? = candidateRoots().firstOrNull { it.isDirectory() && Files.exists(it) }

    /** The shared library for [dirtSampleRoot], or null when there is nothing to load. */
    fun libraryOrNull(preload: Boolean = false): DirtSampleLibrary? =
        dirtSampleRoot()?.let { runCatching { DirtSampleLibrary.shared(it, preload = preload) }.getOrNull() }

    /** A rendered buffer, plus what it was actually made of. */
    class DirtRenderResult(
        val audio: PatSoundDocs.Stereo,
        /** False when no library was found and the whole thing came from [patVoice]. */
        val usedSamples: Boolean,
        /** Distinct tokens played from real samples. */
        val matched: Set<String>,
        /** Distinct tokens the library had nothing for, which fell back to the synth voice. */
        val unmatched: Set<String>,
    ) {
        /** One line fit to print in a box, so a demo can say which voice you are hearing. */
        fun describe(): String = when {
            !usedSamples -> "synth voice (no Dirt-Samples found; set ${ENV_VAR} to use real samples)"
            unmatched.isEmpty() -> "Dirt samples: ${matched.sorted().joinToString(" ")}"
            else -> "Dirt samples: ${matched.sorted().joinToString(" ")}" +
                " — synth voice for ${unmatched.sorted().joinToString(" ")}"
        }
    }
}

/**
 * Schedules [pattern] over [cycles] cycles at [cps] and mixes it from real Dirt samples.
 *
 * Samples are one-shots: a hit plays to its natural end rather than being gated by the length of
 * the arc that triggered it, which is what SuperDirt does and the reason a snare sounds like a
 * snare instead of a click. Only events with an onset trigger — a fragment carried over from a
 * previous arc is a continuation, and re-triggering it would flam.
 *
 * Honours the same `gain`, `pan` and `speed` annotations as [patRenderStereo], so the parameter
 * algebra applies unchanged.
 */
fun patRenderStereoDirt(
    pattern: PatPattern<String>,
    cycles: Int,
    cps: Double = 1.0,
    library: DirtSampleLibrary? = DirtRender.libraryOrNull(),
    voice: PatVoiceSpec = patVoice(),
    sampleRate: Int = PatSoundDocs.SR,
): DirtRender.DirtRenderResult {
    require(cycles > 0) { "cycles must be > 0" }
    require(cps > 0.0) { "cps must be > 0" }

    if (library == null) {
        return DirtRender.DirtRenderResult(
            audio = patRenderStereo(pattern, cycles, cps, voice, sampleRate),
            usedSamples = false,
            matched = emptySet(),
            unmatched = emptySet(),
        )
    }

    val secondsPerCycle = 1.0 / cps
    val frames = (cycles * secondsPerCycle * sampleRate).toInt()
    val left = FloatArray(frames)
    val right = FloatArray(frames)

    val matched = linkedSetOf<String>()
    val unmatched = linkedSetOf<String>()
    val leftovers = mutableListOf<PatEvent<String>>()

    val events = pattern.events(PatArc(PatQ.ZERO, PatQ.of(cycles)))
        .filter { it.hasOnset }
        .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))

    for (event in events) {
        val token = event.value.trim()
        val sample = runCatching { library.sample(token) }.getOrNull()
        if (sample == null) {
            unmatched += token
            leftovers += event
            continue
        }
        matched += token

        val startFrame = (event.arc.start.toDouble() * secondsPerCycle * sampleRate).toInt()
        if (startFrame >= frames) continue

        val gain = (event.annotations["gain"]?.toDoubleOrNull() ?: 1.0).coerceIn(0.0, 4.0)
        val pan = (event.annotations["pan"]?.toDoubleOrNull() ?: 0.5).coerceIn(0.0, 1.0)
        val speed = (event.annotations["speed"]?.toDoubleOrNull() ?: 1.0).let {
            if (it == 0.0) 1.0 else it
        }
        val leftGain = (gain * sqrt(1.0 - pan)).toFloat()
        val rightGain = (gain * sqrt(pan)).toFloat()

        // One step through the source per output frame, accounting for both a sample recorded at a
        // different rate and an explicit `speed`.
        val step = (sample.sampleRate / sampleRate) * speed
        var position = 0.0
        var frame = startFrame
        while (frame < frames && position < sample.lengthSamples - 1) {
            left[frame] += sample.readLeft(position) * leftGain
            right[frame] += sample.readRight(position) * rightGain
            position += step
            frame++
        }
    }

    // Whatever the library had no sample for still gets a voice, mixed on top.
    if (leftovers.isNotEmpty()) {
        val rest = patRenderStereo(
            PatPattern { arc -> leftovers.filter { it.arc.start >= arc.start && it.arc.start < arc.end } },
            cycles, cps, voice, sampleRate,
        )
        for (i in 0 until minOf(frames, rest.left.size)) {
            left[i] += rest.left[i]
            right[i] += rest.right[i]
        }
    }

    return DirtRender.DirtRenderResult(
        audio = PatSoundDocs.Stereo(left, right),
        usedSamples = true,
        matched = matched,
        unmatched = unmatched,
    )
}

/**
 * Renders [pattern] through the real Dirt samples and puts an audio player in this box's output.
 *
 * The sample-backed twin of [showPatAudio]: same call shape, same annotations, but it sounds like
 * the drum machine the token names instead of a stand-in for one. Falls back to the synth voice
 * when the library is absent, and returns the result so a box can print [DirtRender.DirtRenderResult.describe]
 * and be honest about which one played.
 */
fun Box.showDirtAudio(
    pattern: PatPattern<String>,
    cycles: Int = 4,
    cps: Double = 2.0,
    library: DirtSampleLibrary? = DirtRender.libraryOrNull(),
    voice: PatVoiceSpec = patVoice(),
    channel: String = "main",
): DirtRender.DirtRenderResult {
    val result = patRenderStereoDirt(pattern, cycles = cycles, cps = cps, library = library, voice = voice)
    display(
        channel,
        afield.four.audio.WavFloatArrayWriter.encodeStereo16(
            result.audio.left, result.audio.right, PatSoundDocs.SR, normalizePeak = 0.9f
        )
    )
    return result
}
