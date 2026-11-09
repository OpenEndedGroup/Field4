package afield.lang.forth

import afield.audio.engine.WavFloatArrayWriter
import afield.audio.synth.PatSoundDocs

/**
 * The sound on `forth_examples.md`'s step-sequencer sections.
 *
 * Each [SCENES] entry is the ForthField source of one box on the page, character for character
 * (`ForthStepsTest` checks the page still contains it). [renderWav] evaluates that source in a fresh
 * [ForthInterpreter] and writes the WAV its `render-steps` put in the `audio` channel, so the player on
 * the page is the box's own output. Every note goes through a [afield.audio.synth.PatSynthBank], the
 * pattern voice, so the published audio is the stand-in synth; [ForthStepsDirtDocs] renders the Dirt
 * scene, which uses samples.
 */
object ForthStepsDocs {

    /** Page box name to its ForthField source. */
    val SCENES: Map<String, String> = linkedMapOf(
        "forth-steps-alternation" to """
            120 bpm
            seq: line  < c4 eb3 g3 > note | | < g3 c4 > note | ;seq
            line 12 run-steps
            line 12 render-steps
        """.trimIndent(),
        "forth-steps-synth-word" to """
            : acid  saw 900 lpf 0.08 dur ;
            130 bpm
            seq: bass  acid c2 note | acid c3 note | | acid < eb2 g2 bb2 > note ;seq
            bass 8 run-steps
            bass 24 render-steps
        """.trimIndent(),
        "forth-steps-chime" to """
            : chime  tri 0.5 dur 0.5 gain ;
            90 bpm
            seq: bells  chime [ c5 e5 g5 b5 ] iter index note | | 0.2 dur [ c3 g3 eb3 ] [ 6 3 1 ] wchoose note | ;seq
            7 seed bells 8 run-steps
            7 seed bells 32 render-steps
        """.trimIndent(),
    )

    /** Dirt-Samples scenes: the same mechanism through `render-dirt`, rendered strictly from the pinned library. */
    val DIRT_SCENES: Map<String, String> = linkedMapOf(
        "forth-steps-dirt" to """
            130 bpm
            seq: kit  s" bd" sound | s" hh" sound | < s" sn" s" hh" s" cp" > sound | s" hh" sound ;seq
            kit 8 run-steps
            kit 24 render-dirt
        """.trimIndent(),
    )

    /** Evaluate scene [name] and return the audio its `render-steps` or `render-dirt` displayed. */
    fun render(name: String): PatSoundDocs.Stereo {
        val source = SCENES[name] ?: DIRT_SCENES[name] ?: error("unknown forth steps scene '$name'; known: ${SCENES.keys}")
        val forth = ForthInterpreter()
        forth.onOutput = {}
        val errors = forth.eval(source)
        check(errors.isEmpty()) { "scene '$name' failed: $errors" }
        return forth.steps.lastAudio ?: error("scene '$name' displayed no audio")
    }

    /** Offline-render scene [name] to a WAV at [outPath] — the docs fence's entry point. */
    fun renderWav(name: String, outPath: String) {
        val audio = render(name)
        require(audio.left.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
    }
}

/** The Dirt-Samples scene of `forth_examples.md`, kept apart so its provenance is the samples. */
object ForthStepsDirtDocs {
    fun renderWav(name: String, outPath: String) {
        require(name in ForthStepsDocs.DIRT_SCENES) { "unknown forth dirt scene '$name'" }
        ForthStepsDocs.renderWav(name, outPath)
    }
}
