@file:afield.runtime.completion.CompletionCategory("Audio")
package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter
import afield.simulation.petri.PetriNet
import afield.simulation.petri.emits
import afield.simulation.petri.petri
import java.io.File
import kotlin.math.abs

/*
FIELD_TEST
id: synth.petri.sparks.sound
runner: jvm-main
entrypoint: afield.audio.synth.PetriSparksDocs
tags: [smoke, synth, petri, audio, wav, dirt, docs, capture, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "petri sparks sound docs rendered"
*/

/**
 * The sound on `chain_petri_sparks.md`: the page's Petri net, played from the pinned Dirt-Samples.
 *
 * [NET] is the same `#petri` source the page's boxes parse. Three token rings of three, four and
 * five places walk round on one eighth-note grid; the four-ring kicks and drops a token into a
 * bounded `charge` place each lap, and emptying `charge` is a clap. Nothing here knows about the
 * particle figure on the same page: that box queries the same net's firings with
 * `PetriRun.firingsOver` and launches a burst for each one.
 *
 * Rendering is [patRenderStereoDirt] with `strict = true` against [DirtRender.requirePinnedLibrary]:
 * `net.emits()` is a `PatPattern<String>` whose values (`bd`, `sn`, `hh`, `cp`) are Dirt folder
 * names, so there is no voice anywhere but the samples. Run as the `synth.petri.sparks.sound`
 * FIELD_TEST, every scene renders to a temporary directory and is checked; pass a directory as
 * `args[0]` to write the `.wav`s there.
 */
object PetriSparksDocs {

    /** Sample rate of every scene, shared with the other docs galleries. */
    const val SR: Int = PatSoundDocs.SR

    /** The page's net. Three rings on one grid and a place that counts kicks. */
    val NET: String = """
        #petri
        !grid 1/8

        // four places round: a kick each lap, and a token into charge
        k0 : 1
        k0 -[ kick : bd ]-> k1 + charge
        k1 -[ k1.go ]-> k2
        k2 -[ k2.go ]-> k3
        k3 -[ k3.go ]-> k0

        // three places round: a snare each lap
        s0 : 1
        s0 -[ snare : sn ]-> s1
        s1 -[ s1.go ]-> s2
        s2 -[ s2.go ]-> s0

        // five places round: two hats each lap
        h0 : 1
        h0 -[ hat : hh ]-> h1
        h1 -[ h1.go ]-> h2
        h2 -[ hat.2 : hh ]-> h3
        h3 -[ h3.go ]-> h4
        h4 -[ h4.go ]-> h0

        // four kicks fill charge, and emptying it is a clap
        charge : 0 ^4
        4*charge -[ clap : cp ]->
    """.trimIndent()

    /** [NET], parsed. */
    fun net(): PetriNet = petri(NET)

    /** One clip: how many cycles of the net, at what tempo. */
    class Scene(val name: String, val listenFor: String, val cycles: Int, val cps: Double)

    val SCENES: List<Scene> = listOf(
        Scene(
            name = "petri-sparks-drums",
            listenFor = "a kick every half cycle, a snare every three eighths, two hats in every five " +
                "eighths, and a clap each time four kicks have filled charge",
            cycles = 8,
            cps = 0.5,
        ),
    )

    private val byName: Map<String, Scene> = SCENES.associateBy { it.name }

    /** Plays [cycles] cycles of [net]'s emitted sounds from the pinned Dirt-Samples, strictly. */
    fun render(net: PetriNet, cycles: Int, cps: Double): PatSoundDocs.Stereo =
        patRenderStereoDirt(
            net.emits(), cycles = cycles, cps = cps,
            library = DirtRender.requirePinnedLibrary(), strict = true,
        ).audio

    /** Offline-render scene [name] and write a stereo 16-bit PCM WAV to [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val scene = byName[name] ?: error("unknown PetriSparksDocs scene '$name'; known: ${byName.keys.sorted()}")
        val audio = render(net(), scene.cycles, scene.cps)
        require(audio.frames > 0) { "scene '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, SR, normalizePeak = 0.9f)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "petri-sparks-docs").path,
        )
        outDir.mkdirs()
        val net = net()
        SCENES.forEach { scene ->
            val audio = render(net, scene.cycles, scene.cps)
            val seconds = scene.cycles / scene.cps
            check(abs(audio.frames - seconds * SR) <= SR / 10) {
                "${scene.name}: expected ~${(seconds * SR).toInt()} frames, got ${audio.frames}"
            }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) { "${scene.name}: non-finite samples" }
            val peak = PatSoundDocs.peak(audio.mono)
            val rms = PatSoundDocs.rms(audio.mono)
            check(peak > 0.02) { "${scene.name}: effectively silent (peak=$peak)" }
            check(rms > 0.002) { "${scene.name}: RMS too low to be music (rms=$rms)" }
            println("scene ${scene.name}: ${"%.2f".format(seconds)}s, peak=${"%.3f".format(peak)}, rms=${"%.4f".format(rms)} — ${scene.listenFor}")
            val file = File(outDir, "${scene.name}.wav")
            WavFloatArrayWriter.writeStereo16(file, audio.left, audio.right, SR, normalizePeak = 0.9f)
            println("  wrote ${file.absolutePath} (${file.length()} bytes)")
        }
        println("petri sparks sound docs rendered to ${outDir.absolutePath} (${SCENES.size} scenes)")
    }
}
