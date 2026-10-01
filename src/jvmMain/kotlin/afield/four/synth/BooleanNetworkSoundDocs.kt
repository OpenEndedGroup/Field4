@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import afield.stdlib.BoolNode
import afield.stdlib.BooleanNetwork
import afield.stdlib.findAttractors
import afield.stdlib.kauffmanNetwork
import java.io.File
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sin

/*
FIELD_TEST
id: synth.boolean.network.sound
runner: jvm-main
entrypoint: afield.four.synth.BooleanNetworkSoundDocs
tags: [smoke, synth, audio, wav, dirt, docs, capture, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "boolean network sound docs rendered"
*/

/**
 * The clips on `boolean_network_music_examples.md`: each figure's note stream, played through the
 * real Tidal Dirt-Samples.
 *
 * Every scene rebuilds the notes its page fence builds — the same seeds, step durations, trigger
 * modes and labels — so a clip is the piano roll above it, heard. The notes become Dirt events by
 * [notesToDirtPattern]: a note's start is the event onset, its velocity the `gain`, and its label
 * the sample token, which for three of the four fences already *is* a Dirt token (`bd:3`, `sn:1`,
 * `hh:2`, …). The other two need one decision each, made in [Scene.token]:
 *
 *  * the first fence labels its notes with pitch names (`C2` … `C4`). Those play as Tidal plays a
 *    pitched sample, `arpy` with `speed = 2^(semitones/12)`, counting from C3, so the five lanes
 *    are five pitches of one sample rather than five drums;
 *  * the driven fence's forced lane is labelled `clk`, which no Dirt folder is called. It plays as
 *    `click`, the nearest folder that is one.
 *
 * Rendering is [patRenderStereoDirt] with `strict = true` against [DirtRender.requirePinnedLibrary],
 * so there is no synth voice anywhere here: a missing library, or a token the pinned subset does not
 * hold, is an exception that names `./gradlew fetchDocsDirtSamples`.
 *
 * Run as the `synth.boolean.network.sound` FIELD_TEST, every scene renders to a temporary directory
 * and is checked — frame count, level, and that the number of distinct onset times is what the
 * note stream says — and nothing committed is written. Pass an output directory as `args[0]` to
 * regenerate the page's `.wav`s.
 */
object BooleanNetworkSoundDocs {

    /** One page figure as sound: the notes it draws, how long it lasts, and the token per note. */
    class Scene(
        val name: String,
        /** What to listen for, printed by the smoke. */
        val listenFor: String,
        val seconds: Double,
        val notes: () -> List<ScheduledNote>,
        val token: (ScheduledNote) -> String = { requireNotNull(it.label) { "note $it has no label to play" } },
        val speed: (ScheduledNote) -> Double = { 1.0 },
        /** The notes whose onsets the smoke insists are audible as a new hit; see [audibleAt]. */
        val mustHear: (ScheduledNote) -> Boolean = { true },
        /** Why [mustHear] is narrower than every note, when it is. */
        val mustHearWhy: String = "every note",
    )

    // ── the four fences, rebuilt ──────────────────────────────────────────────────────────

    private fun <T> Iterator<T>.collectAll(): List<T> = buildList { while (hasNext()) add(next()) }

    /** The first fence: the first `kauffmanNetwork(5, 2)` seed with an attractor of period 6 or more. */
    fun attractorNotes(): List<ScheduledNote> {
        val (seed, net, attr) = (0..80).firstNotNullOf { seed ->
            val net = kauffmanNetwork(n = 5, k = 2, seed = seed.toLong())
            net.findAttractors().filter { it.period >= 6 }.maxByOrNull { it.period }?.let { Triple(seed, net, it) }
        }
        check(seed == 4 && attr.period == 6) {
            "the page's first figure is headed 'seed 4 · period 6'; the sweep now finds seed $seed, period ${attr.period}"
        }
        val pitches = listOf(36, 43, 48, 55, 60)
        val labels = listOf("C2", "G2", "C3", "G3", "C4")
        return net.scheduledNotes(initialState = attr.cycle.first(), stepDuration = 0.25, steps = 24) { step ->
            step.lanes(BooleanTriggerMode.RISING_EDGE).map { lane ->
                note(
                    pitch = pitches[lane], duration = step.duration * 0.84, start = step.start,
                    velocity = 0.74 + lane * 0.04, label = labels[lane],
                )
            }
        }.collectAll()
    }

    /** The second fence: seed 27's longest attractor, its lanes labelled with Dirt tokens. */
    fun dirtTokenNotes(): List<ScheduledNote> {
        val net = kauffmanNetwork(n = 5, k = 2, seed = 27L)
        val attr = net.findAttractors().maxByOrNull { it.period }!!
        val tokens = listOf("bd:3", "sn:1", "hh:4", "cp:0", "perc:5")
        return net.scheduledNotes(initialState = attr.cycle.first(), stepDuration = 0.25, steps = 32) { step ->
            step.lanes(BooleanTriggerMode.RISING_EDGE).map { lane ->
                note(
                    pitch = 42 + lane * 3, duration = step.duration * 0.8, start = step.start,
                    velocity = if (lane == 0) 1.0 else 0.82, label = tokens[lane],
                )
            }
        }.collectAll()
    }

    /** The third fence: three attractors on three clocks, braided. */
    fun braidNotes(): List<ScheduledNote> {
        class Layer(
            val seed: Long, val stepDuration: Double, val steps: Int, val mode: BooleanTriggerMode,
            val basePitch: Int, val label: String, val velocity: Double,
        )
        val layers = listOf(
            Layer(27L, 0.125, 64, BooleanTriggerMode.RISING_EDGE, 36, "bd", 0.98),
            Layer(11L, 0.1875, 48, BooleanTriggerMode.CHANGE, 50, "sn", 0.76),
            Layer(42L, 0.0625, 64, BooleanTriggerMode.ACTIVE, 74, "hh", 0.42),
        )
        return layers.flatMap { layer ->
            val net = kauffmanNetwork(n = 5, k = 2, seed = layer.seed)
            val attr = net.findAttractors().maxByOrNull { it.period }!!
            net.scheduledNotes(initialState = attr.cycle.first(), stepDuration = layer.stepDuration, steps = layer.steps) { step ->
                val lanes = when (layer.mode) {
                    BooleanTriggerMode.ACTIVE ->
                        step.state.indices.filter { lane -> step.isActive(lane) && (lane + step.stepIndex.toInt()) % 2 == 0 }.take(2)
                    else -> step.lanes(layer.mode)
                }
                lanes.map { lane ->
                    note(
                        pitch = layer.basePitch + lane * 2,
                        duration = if (layer.mode == BooleanTriggerMode.ACTIVE) step.duration * 0.42 else step.duration * 0.78,
                        start = step.start,
                        velocity = (layer.velocity - lane * 0.04).coerceAtLeast(0.2),
                        label = "${layer.label}:$lane",
                    )
                }
            }.collectAll()
        }.sortedWith(compareBy<ScheduledNote> { it.start }.thenBy { it.pitch })
    }

    /** The fourth fence: a hand-built network with node 0 forced by a sine-derived square wave. */
    fun drivenNotes(): List<ScheduledNote> {
        val net = BooleanNetwork(
            listOf(
                BoolNode("clk", "external", emptyList()) { s -> s[0] },
                BoolNode("kick", "clk XOR clap", listOf(0, 3)) { s -> s[0] xor s[3] },
                BoolNode("snare", "kick OR hat", listOf(1, 4)) { s -> s[1] || s[4] },
                BoolNode("clap", "snare AND NOT clk", listOf(2, 0)) { s -> s[2] && !s[0] },
                BoolNode("hat", "kick XOR snare", listOf(1, 2)) { s -> s[1] xor s[2] },
            )
        )
        val driver = { stepIndex: Long, _: Double, _: Double, state: BooleanArray ->
            state[0] = sin(2.0 * PI * stepIndex.toDouble() / 12.0) > 0.0
        }
        val labels = listOf("clk", "bd", "sn", "cp", "hh")
        val pitches = listOf(30, 36, 50, 57, 74)
        return net.scheduledDrivenNotes(
            initialState = BooleanArray(net.size), stepDuration = 0.125, steps = 64, driver = driver,
        ) { step ->
            step.lanes(BooleanTriggerMode.RISING_EDGE).map { lane ->
                note(
                    pitch = pitches[lane],
                    duration = if (lane == 4) step.duration * 0.45 else step.duration * 0.9,
                    start = step.start,
                    velocity = if (lane == 0) 0.4 else 0.78 + lane * 0.04,
                    label = labels[lane],
                )
            }
        }.collectAll()
    }

    val SCENES: List<Scene> = listOf(
        Scene(
            name = "attractor-roll",
            listenFor = "a period-6 figure repeating every 1.5 s, four pitches of one sample",
            seconds = 6.0,
            notes = ::attractorNotes,
            token = { "arpy" },
            // Tidal's pitched-sample convention: speed 2^(semitones/12) from the sample's own pitch,
            // which is taken to be C3 (MIDI 48), the middle of the five.
            speed = { 2.0.pow((it.pitch - 48.0) / 12.0) },
        ),
        Scene(
            name = "dirt-tokens",
            listenFor = "a period-2 attractor: hats and kick on every step, one perc hit at the start",
            seconds = 8.0,
            notes = ::dirtTokenNotes,
        ),
        Scene(
            name = "braid",
            listenFor = "the shimmer stopping dead at 4 s, and the snare only at time zero",
            seconds = 8.0,
            notes = ::braidNotes,
            // The page's shimmer tokens are hh:0 … hh:4, and in Dirt-Samples hh:1 to hh:4 are a
            // crash and three long hits of up to 2.4 s. Sixteen a second of those is a wash, and a
            // quiet sixteenth under it does not start a new transient. The kick and snare do.
            mustHear = { !it.label.orEmpty().startsWith("hh") },
            mustHearWhy = "kick and snare; the hh shimmer is a wash of long crash samples",
        ),
        Scene(
            name = "driven",
            listenFor = "the forced clock lane as a quiet click, the network answering it",
            seconds = 8.0,
            notes = ::drivenNotes,
            token = { if (it.label == "clk") "click" else requireNotNull(it.label) },
        ),
    )

    private val byName = SCENES.associateBy { it.name }

    /** Looks a scene up by name, with a message that lists the alternatives. */
    fun scene(name: String): Scene =
        byName[name] ?: error("unknown BooleanNetworkSoundDocs scene '$name'; known: ${byName.keys.sorted()}")

    // ── notes to Dirt events ──────────────────────────────────────────────────────────────

    /**
     * [notes] as a one-cycle-per-second pattern of Dirt tokens, ready for [patRenderStereoDirt] at
     * `cps = 1`.
     *
     * Each note becomes one event with an onset at its start, valued [token], annotated with its
     * velocity as `gain` and [speed] as `speed`. The event's length is the note's, though a Dirt
     * sample is a one-shot and plays to its end regardless.
     */
    fun notesToDirtPattern(
        notes: List<ScheduledNote>,
        token: (ScheduledNote) -> String,
        speed: (ScheduledNote) -> Double = { 1.0 },
    ): PatPattern<String> {
        val events = notes.map { n ->
            val arc = PatArc(PatQ.fromDouble(n.start), PatQ.fromDouble(n.start + maxOf(n.duration, 1e-3)))
            PatEvent(
                arc = arc,
                value = token(n),
                annotations = mapOf("gain" to n.velocity.toString(), "speed" to speed(n).toString()),
            )
        }
        return PatPattern { arc -> events.filter { it.arc.start >= arc.start && it.arc.start < arc.end } }
    }

    /** One scene rendered: the audio, and the Dirt bookkeeping that says what played. */
    class Rendered(val scene: Scene, val notes: List<ScheduledNote>, val result: DirtRender.DirtRenderResult)

    /** Renders [name] through the pinned Dirt-Samples, strictly. */
    fun render(name: String): Rendered {
        val scene = scene(name)
        val notes = scene.notes()
        check(notes.isNotEmpty()) { "scene '$name' produced no notes" }
        val pattern = notesToDirtPattern(notes, scene.token, scene.speed)
        val result = patRenderStereoDirt(
            pattern,
            cycles = ceil(scene.seconds).toInt(),
            cps = 1.0,
            library = DirtRender.requirePinnedLibrary(),
            strict = true,
        )
        return Rendered(scene, notes, result)
    }

    /**
     * Offline-renders scene [name] to a stereo 16-bit WAV at [outPath].
     *
     * The `renderWav(name, path)` contract the docs runner's `audio="…"` fence drives, and the one
     * `DocsAudioProvenance` re-renders through to check the committed clip.
     */
    fun renderWav(name: String, outPath: String) {
        val audio = render(name).result.audio
        require(audio.frames > 0) { "scene '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
    }

    // ── a listen check that does not need ears ────────────────────────────────────────────

    /**
     * Whether a hit starts at [seconds] in [mono]: the first-difference energy of the 10 ms after it
     * is more than [ratio] times that of the 10 ms before it.
     *
     * First differences rather than plain energy, because a new hit is a burst of high-frequency
     * content even under a kick that is still booming. Asked only at the times the note stream
     * says something starts, so it measures "did each scheduled event become a sound", not a blind
     * onset count that has to guess at thresholds.
     */
    fun audibleAt(mono: FloatArray, seconds: Double, sampleRate: Int = PatSoundDocs.SR, ratio: Double = 1.5): Boolean {
        val t = Math.round(seconds * sampleRate).toInt()
        val w = sampleRate / 100
        fun energy(from: Int, to: Int): Double {
            var e = 0.0
            for (i in maxOf(1, from) until minOf(mono.size, to)) {
                val d = mono[i] - mono[i - 1]
                e += d.toDouble() * d
            }
            return e / w
        }
        return energy(t, t + w) > ratio * energy(t - w, t) + 1e-12
    }

    // ── FIELD_TEST entry point ────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("BOOLEAN_NETWORK_SOUND_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "boolean-network-sound").path,
        )
        outDir.mkdirs()

        val onsetProblems = mutableListOf<String>()
        SCENES.forEach { scene ->
            val rendered = render(scene.name)
            val audio = rendered.result.audio
            check(audio.frames > 0) { "${scene.name}: rendered no samples" }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) { "${scene.name}: non-finite samples" }
            val expectedFrames = (ceil(scene.seconds) * PatSoundDocs.SR).toInt()
            check(audio.frames == expectedFrames) { "${scene.name}: expected $expectedFrames frames, got ${audio.frames}" }
            val peak = PatSoundDocs.peak(audio.mono)
            val rms = PatSoundDocs.rms(audio.mono)
            check(peak > 0.02) { "${scene.name}: effectively silent (peak=$peak)" }
            check(peak <= 8.0) { "${scene.name}: runaway level (peak=$peak)" }
            check(rms > 0.002) { "${scene.name}: RMS too low to be music (rms=$rms)" }
            check(rendered.result.usedSamples && rendered.result.unmatched.isEmpty()) {
                "${scene.name}: ${rendered.result.describe()}"
            }

            val onsetTimes = rendered.notes.map { it.start }.distinct().filter { it < scene.seconds }
            val heard = onsetTimes.count { audibleAt(audio.mono, it) }
            val mustHear = rendered.notes.filter(scene.mustHear).map { it.start }.distinct().filter { it < scene.seconds }
            val missed = mustHear.filterNot { audibleAt(audio.mono, it) }
            println(
                "scene ${scene.name}: %.2fs, %d notes at %d onset times, a hit starts at %d of them, peak=%.3f rms=%.4f; %s"
                    .format(audio.frames.toDouble() / PatSoundDocs.SR, rendered.notes.size, onsetTimes.size, heard, peak, rms,
                        rendered.result.describe())
            )
            println("  must be heard: ${mustHear.size - missed.size} of ${mustHear.size} (${scene.mustHearWhy})")
            println("  listen for: ${scene.listenFor}")
            if (missed.isNotEmpty()) {
                onsetProblems += "${scene.name}: no hit starts at ${missed.size} scheduled onset(s), first at ${missed.take(4)} s"
            }

            val file = File(outDir, "${scene.name}.wav")
            WavFloatArrayWriter.writeStereo16(file, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
            println("  wrote ${file.absolutePath} (${file.length()} bytes)")
        }
        // After every scene is written, so a failure leaves all four clips on disk to listen to.
        check(onsetProblems.isEmpty()) { onsetProblems.joinToString("\n") }
        println("boolean network sound docs rendered: ${SCENES.size} scene(s)")
    }
}
