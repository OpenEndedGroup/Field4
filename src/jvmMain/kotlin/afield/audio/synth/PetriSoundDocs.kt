@file:afield.runtime.completion.CompletionCategory("Audio")
package afield.audio.synth

import afield.simulation.petri.emits
import afield.simulation.petri.petri
import afield.audio.engine.WavFloatArrayWriter
import java.io.File

/*
FIELD_TEST
id: synth.petri.sound
runner: jvm-main
entrypoint: afield.audio.synth.PetriSoundDocs
tags: [smoke, synth, audio, wav, dirt, docs, capture, cpu, petri]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "petri sound docs rendered"
*/

/**
 * The clips on `petri_nets.md`: a page net's firings played through the pinned Tidal Dirt-Samples.
 *
 * Each scene is the source of one page fence, parsed with [petri] exactly as the page parses it,
 * and its [emits] pattern (one token per firing that carries a sound) rendered by
 * [patRenderStereoDirt] with `strict = true`. There is no synth voice: a missing library, or a token
 * the pinned subset does not hold, is an exception naming `./gradlew fetchDocsDirtSamples`.
 *
 * As the `synth.petri.sound` FIELD_TEST every scene renders to a temporary directory and is checked
 * (frame count, level, and that every scheduled firing was mixed from a real sample); nothing
 * committed is written. Pass an output directory as `args[0]` to regenerate the page's `.wav`s.
 */
object PetriSoundDocs {

    /** One page net as sound: its source, and how many cycles to play at what tempo. */
    class Scene(val name: String, val listenFor: String, val source: String, val cycles: Int, val cps: Double)

    val SCENES = listOf(
        Scene(
            name = "two-place-loop",
            listenFor = "kick and snare taking turns, an eighth apart: the token going round",
            source = """
                #petri
                !grid 1/8

                kick : 1
                kick -[ hit  @1/8 : bd ]-> hats
                hats -[ back @1/8 : sd ]-> kick
            """.trimIndent(),
            cycles = 4,
            cps = 0.5,
        ),
    )

    private val byName = SCENES.associateBy { it.name }

    /** Looks a scene up by name, with a message that lists the alternatives. */
    fun scene(name: String): Scene =
        byName[name] ?: error("unknown PetriSoundDocs scene '$name'; known: ${byName.keys.sorted()}")

    /** Renders [name] through the pinned Dirt-Samples, strictly. */
    fun render(name: String): DirtRender.DirtRenderResult {
        val scene = scene(name)
        return patRenderStereoDirt(
            petri(scene.source).emits(),
            cycles = scene.cycles,
            cps = scene.cps,
            library = DirtRender.requirePinnedLibrary(),
            strict = true,
        )
    }

    /**
     * Offline-renders scene [name] to a stereo 16-bit WAV at [outPath]: the `renderWav(name, path)`
     * contract the docs runner's `audio="…"` fence drives.
     */
    fun renderWav(name: String, outPath: String) {
        val audio = render(name).audio
        require(audio.frames > 0) { "scene '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "petri-sound").path,
        )
        outDir.mkdirs()
        SCENES.forEach { scene ->
            val result = render(scene.name)
            val audio = result.audio
            val expectedFrames = Math.round(scene.cycles / scene.cps * PatSoundDocs.SR).toInt()
            check(audio.frames == expectedFrames) { "${scene.name}: expected $expectedFrames frames, got ${audio.frames}" }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) { "${scene.name}: non-finite samples" }
            val peak = PatSoundDocs.peak(audio.mono)
            val rms = PatSoundDocs.rms(audio.mono)
            check(peak > 0.02) { "${scene.name}: effectively silent (peak=$peak)" }
            check(peak <= 8.0) { "${scene.name}: runaway level (peak=$peak)" }
            check(rms > 0.002) { "${scene.name}: RMS too low to be music (rms=$rms)" }
            check(result.usedSamples && result.unmatched.isEmpty()) { "${scene.name}: ${result.describe()}" }
            check(result.renderedEvents > 0) { "${scene.name}: no firing was mixed" }
            println("scene ${scene.name}: %.2fs, %d firings mixed, peak=%.3f rms=%.4f; %s"
                .format(audio.frames.toDouble() / PatSoundDocs.SR, result.renderedEvents, peak, rms, result.describe()))
            println("  listen for: ${scene.listenFor}")
            val file = File(outDir, "${scene.name}.wav")
            WavFloatArrayWriter.writeStereo16(file, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
            println("  wrote ${file.absolutePath} (${file.length()} bytes)")
        }
        println("petri sound docs rendered: ${SCENES.size} scene(s)")
    }
}
